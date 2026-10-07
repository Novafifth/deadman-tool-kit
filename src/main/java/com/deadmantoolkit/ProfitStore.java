package com.deadmantoolkit;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.util.Filepath;

/**
 * Keeps the logged-in account's {@link ProfitBook} and saves it to {@code profit-<account id prefix>.json} in the
 * plugin folder, so start-up reads one small file instead of replaying the trade log.
 * <p>
 * Confined to the plugin's single-threaded executor: every method must run there (no locks needed). The log append
 * for an event runs in the same executor before its {@link #apply}, which is what makes {@link #rebuild} race free:
 * the rebuild reads one log file per task, and reads the newest file in the same task that swaps in the new book, so
 * every event is counted exactly once whether it was appended before or after the rebuild started.
 */
@Slf4j
final class ProfitStore
{
	static final int SAVE_DELAY_SECONDS = 5;
	/** Characters of the (hex, hashed) account id used in the file name. */
	static final int FILE_ID_LENGTH = 16;
	private static final Pattern ACCOUNT_ID = Pattern.compile("^[0-9a-f]{" + FILE_ID_LENGTH + ",}$");

	private final Dir dir;
	private final Gson gson;
	private final ScheduledExecutorService executor;
	private final Clock clock;
	private final Consumer<ProfitSnapshot> listener;

	/** The account whose book is loaded, or null before the first login. */
	private String account;
	private ProfitBook book;
	private boolean dirty;
	private ScheduledFuture<?> pendingSave;
	/** Whether the trade log has any files; null when not checked since the last load. */
	private Boolean logAvailable;
	private Rebuild rebuild;
	/** Another rebuild was asked for while one ran (it may have read a file before new lines went in). */
	private boolean rebuildAgain;
	private boolean closed;

	/** The plugin folder; finding it may do IO. */
	interface Dir
	{
		Filepath get() throws IOException;
	}

	/** A rebuild in progress: the account it's for, the log files in order, and the book being built. */
	private static final class Rebuild
	{
		final String account;
		final List<String> files;
		final ProfitBook book = new ProfitBook();
		int next;

		Rebuild(String account, List<String> files)
		{
			this.account = account;
			this.files = new ArrayList<>(files);
		}
	}

	/**
	 * @param dir      the plugin folder (also holds the trade log)
	 * @param clock    gives the time zone and today's date for daily totals
	 * @param listener receives a new snapshot after every change; called on the executor
	 */
	ProfitStore(Dir dir, Gson gson, ScheduledExecutorService executor, Clock clock,
		Consumer<ProfitSnapshot> listener)
	{
		this.dir = dir;
		this.gson = gson;
		this.executor = executor;
		this.clock = clock;
		this.listener = listener;
	}

	/** The profit file for a hashed account id; null if it doesn't look like one (never a name). */
	static String fileName(String accountId)
	{
		if (accountId == null || !ACCOUNT_ID.matcher(accountId).matches())
		{
			return null;
		}
		return "profit-" + accountId.substring(0, FILE_ID_LENGTH) + ".json";
	}

	/** Switch to {@code accountId}'s book (saving the previous one), then publish it. */
	void load(String accountId)
	{
		if (closed || fileName(accountId) == null)
		{
			return;
		}
		if (!accountId.equals(account))
		{
			saveNow();
			account = accountId;
			book = read(accountId);
			dirty = false;
			logAvailable = null;
		}
		publish();
	}

	/**
	 * Count events just recorded (after their log append, if any).
	 *
	 * @param accountId the account they belong to; switches to it if another is loaded
	 * @param logged    the events were also written to the trade log
	 */
	void apply(String accountId, List<TradeEvent> events, boolean logged)
	{
		if (closed)
		{
			return;
		}
		if (accountId != null && !accountId.equals(account))
		{
			load(accountId);
		}
		if (logged)
		{
			logAvailable = true;
		}
		if (book == null)
		{
			return;
		}
		boolean changed = false;
		for (TradeEvent ev : events)
		{
			changed |= book.apply(ev, clock.getZone());
		}
		if (changed && !logged)
		{
			book.markUnlogged();
		}
		if (changed)
		{
			dirty = true;
			scheduleSave();
			publish();
		}
	}

	/**
	 * Recount the loaded account from the whole trade log, one file per executor task. Lines carrying another
	 * account's id are skipped; lines without one (written by older versions) count for this account. Always
	 * publishes, so the panel's button resets even when nothing is done.
	 */
	void rebuild()
	{
		if (closed)
		{
			return;
		}
		if (account == null || rebuild != null)
		{
			publish();
			return;
		}
		List<String> files;
		try
		{
			files = LogFiles.oldestFirst(LocalTradeLog.listNames(dir.get()));
		}
		catch (IOException ex)
		{
			log.warn("Couldn't list the trade log for a profit rebuild", ex);
			publish();
			return;
		}
		rebuild = new Rebuild(account, files);
		publish();
		executor.execute(this::rebuildStep);
	}

	/**
	 * Recount {@code accountId} from the whole trade log, in time order: for events appended out of time order (an
	 * import of older trades), whose cost basis applying them on top would get wrong. If a rebuild is running, another
	 * one follows it. Not when the book counts trades the log doesn't have (it was off for a while): a recount would
	 * silently drop those, so the caller counts the events on top instead (only the player's Rebuild button does that).
	 *
	 * @return false when nothing was started and the events still need counting
	 */
	boolean rebuildFor(String accountId)
	{
		if (closed)
		{
			return true;
		}
		if (accountId != null && !accountId.equals(account))
		{
			load(accountId);
		}
		if (book != null && book.hasUnlogged())
		{
			return false;
		}
		if (rebuild != null)
		{
			rebuildAgain = true;
			return true;
		}
		rebuild();
		return true;
	}

	private void rebuildStep()
	{
		Rebuild r = rebuild;
		if (closed || r == null)
		{
			return;
		}
		try
		{
			if (r.next < r.files.size())
			{
				readInto(r, r.files.get(r.next++));
				if (r.next < r.files.size())
				{
					// Let other executor work (appends, live profit) run between files.
					executor.execute(this::rebuildStep);
					return;
				}
			}
			// Same task as the newest file: a month that began during the rebuild has a file we haven't read.
			for (String name : LogFiles.oldestFirst(LocalTradeLog.listNames(dir.get())))
			{
				if (!r.files.contains(name))
				{
					r.files.add(name);
					readInto(r, name);
				}
			}
			finish(r);
		}
		catch (IOException | RuntimeException ex)
		{
			log.warn("Profit rebuild failed", ex);
			rebuild = null;
			rebuildAgain = false;
			publish();
		}
	}

	/**
	 * Count one log file's events for the rebuild's account, in time order. Lines are appended in time order except
	 * imported RuneLite trades, which are appended when imported into the month of their own (older) time; sorting
	 * (stable, so equal times keep their order) counts them where they happened, for their cost basis.
	 */
	private void readInto(Rebuild r, String file) throws IOException
	{
		List<TradeEvent> events = new ArrayList<>();
		LocalTradeLog.forEachInFile(dir.get(), file, gson, (obj, ev) ->
		{
			if (belongsTo(obj, r.account))
			{
				events.add(ev);
			}
		});
		events.sort((a, b) -> Long.compare(a.getTs(), b.getTs()));
		for (TradeEvent ev : events)
		{
			r.book.apply(ev, clock.getZone());
		}
	}

	/** A log line counts for {@code accountId} if it carries that id, or no id at all (older versions). */
	static boolean belongsTo(JsonObject line, String accountId)
	{
		JsonElement a = line.get("acct");
		if (a == null || a.isJsonNull())
		{
			return true;
		}
		return a.isJsonPrimitive() && accountId.equals(a.getAsString());
	}

	private void finish(Rebuild r)
	{
		rebuild = null;
		logAvailable = !r.files.isEmpty();
		if (r.account.equals(account))
		{
			book = r.book;
			dirty = true;
			saveNow();
		}
		else
		{
			// The account changed meanwhile; the rebuilt book is still complete for the one it was made for.
			try
			{
				write(r.account, r.book);
			}
			catch (IOException | RuntimeException ex)
			{
				log.warn("Couldn't save rebuilt profit", ex);
			}
		}
		log.debug("Profit rebuilt from {} log files", r.files.size());
		publish();
		if (rebuildAgain)
		{
			rebuildAgain = false;
			rebuild();
		}
	}

	/** Final save (plugin stopping); later calls do nothing. */
	void close()
	{
		if (closed)
		{
			return;
		}
		if (pendingSave != null)
		{
			pendingSave.cancel(false);
			pendingSave = null;
		}
		saveNow();
		closed = true;
		rebuild = null;
	}

	private void scheduleSave()
	{
		if (pendingSave != null && !pendingSave.isDone())
		{
			return;
		}
		pendingSave = executor.schedule(() ->
		{
			pendingSave = null;
			saveNow();
		}, SAVE_DELAY_SECONDS, TimeUnit.SECONDS);
	}

	/** Write the loaded book if it changed. */
	void saveNow()
	{
		if (!dirty || account == null || book == null)
		{
			return;
		}
		try
		{
			write(account, book);
			dirty = false;
		}
		catch (IOException | RuntimeException ex)
		{
			log.warn("Couldn't save profit", ex);
		}
	}

	private void write(String accountId, ProfitBook b) throws IOException
	{
		Filepath d = dir.get();
		if (!d.exists())
		{
			d.createDirectories();
		}
		String name = fileName(accountId);
		Filepath file = d.joinSegment(name);
		Filepath tmp = d.joinSegment(name + ".tmp");
		try (BufferedWriter w = tmp.openBufferedWriter(StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
			StandardOpenOption.WRITE))
		{
			gson.toJson(b, w);
		}
		// Write then rename, so a crash mid-write never leaves a half file.
		try
		{
			tmp.moveTo(file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		}
		catch (AtomicMoveNotSupportedException ex)
		{
			tmp.moveTo(file, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	private ProfitBook read(String accountId)
	{
		String name = fileName(accountId);
		try
		{
			Filepath file = dir.get().joinSegment(name);
			if (!file.isFile())
			{
				return new ProfitBook();
			}
			try (BufferedReader r = file.openBufferedReader())
			{
				ProfitBook b = gson.fromJson(r, ProfitBook.class);
				return b == null ? new ProfitBook() : b.sanitized();
			}
		}
		catch (IOException | RuntimeException ex)
		{
			log.warn("Couldn't read profit file {}; starting from zero (Rebuild recounts it from the trade log)", name, ex);
			return new ProfitBook();
		}
	}

	private void publish()
	{
		if (closed)
		{
			return;
		}
		if (logAvailable == null)
		{
			try
			{
				logAvailable = !LogFiles.newestFirst(LocalTradeLog.listNames(dir.get())).isEmpty();
			}
			catch (IOException ex)
			{
				logAvailable = false;
			}
		}
		ProfitBook b = book == null ? new ProfitBook() : book;
		listener.accept(b.snapshot(LocalDate.now(clock), rebuild != null, logAvailable));
	}

	// For tests.

	boolean isRebuilding()
	{
		return rebuild != null;
	}

	ProfitBook book()
	{
		return book;
	}
}
