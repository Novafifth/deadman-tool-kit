package com.deadmantoolkit;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.util.Filepath;

/**
 * The local trade log in the plugin's data folder: one JSON object per line (a {@link TradeEvent} plus "acct", the
 * hashed account id), in one file per month ({@code trades-YYYY-MM.jsonl}, by the event time in UTC). The legacy
 * {@code trades.jsonl} from older versions is still read. Does disk IO, so only call it from the executor, never the
 * client thread.
 */
@Slf4j
final class LocalTradeLog
{
	private LocalTradeLog()
	{
	}

	/** The file that events at {@code month} (UTC) go to. */
	static String fileName(YearMonth month)
	{
		return String.format("trades-%04d-%02d.jsonl", month.getYear(), month.getMonthValue());
	}

	/** The month (UTC) of a unix-seconds timestamp. */
	static YearMonth monthOf(long ts)
	{
		return YearMonth.from(Instant.ofEpochSecond(ts).atZone(ZoneOffset.UTC));
	}

	/** Append already-serialized lines to {@code month}'s file, creating the folder and file if needed. */
	static void append(Filepath dir, YearMonth month, List<String> lines) throws IOException
	{
		// getPluginDirectory() doesn't create this plugin's own folder.
		if (!dir.exists())
		{
			dir.createDirectories();
		}
		Filepath file = dir.joinSegment(fileName(month));
		try (BufferedWriter w = file.openBufferedWriter(StandardOpenOption.CREATE, StandardOpenOption.APPEND))
		{
			for (String line : lines)
			{
				w.write(line);
				w.newLine();
			}
		}
	}

	/** Names of the log files in {@code dir}, in no particular order (empty if the folder doesn't exist). */
	static List<String> listNames(Filepath dir) throws IOException
	{
		if (!dir.isDirectory())
		{
			return Collections.emptyList();
		}
		try (Stream<Filepath> s = dir.walk(1))
		{
			return s.filter(Filepath::isFile).map(Filepath::getFileName).collect(Collectors.toList());
		}
	}

	/**
	 * The newest {@code maxShown} events that "My trades" shows ({@link MyTrades#isShown}), oldest first. Reads files
	 * newest month first, each from its end, and stops as soon as it has enough, so older months are never opened.
	 * Lines that don't parse, or have no kind, are skipped.
	 */
	static List<TradeEvent> readNewest(Filepath dir, Gson gson, int maxShown) throws IOException
	{
		return readNewest(dir, gson, maxShown, ReverseLineReader.DEFAULT_CHUNK);
	}

	static List<TradeEvent> readNewest(Filepath dir, Gson gson, int maxShown, int chunk) throws IOException
	{
		List<TradeEvent> newestFirst = new ArrayList<>();
		if (maxShown <= 0)
		{
			return newestFirst;
		}
		for (String name : LogFiles.newestFirst(listNames(dir)))
		{
			Filepath file = dir.joinSegment(name);
			try (ReverseLineReader r = new ReverseLineReader(file.openFileChannel(StandardOpenOption.READ), chunk))
			{
				String line;
				while ((line = r.readLine()) != null)
				{
					TradeEvent ev = parse(gson, line);
					if (ev != null && MyTrades.isShown(ev))
					{
						newestFirst.add(ev);
						if (newestFirst.size() >= maxShown)
						{
							Collections.reverse(newestFirst);
							return newestFirst;
						}
					}
				}
			}
		}
		Collections.reverse(newestFirst);
		return newestFirst;
	}

	/**
	 * Stream every log line, oldest file first and each file front to back, as the raw object (for "acct") and the
	 * parsed event. Bad lines are skipped. For rebuilding totals from the whole log; this reads everything.
	 */
	static void forEachOldestFirst(Filepath dir, Gson gson, BiConsumer<JsonObject, TradeEvent> consumer) throws IOException
	{
		for (String name : LogFiles.oldestFirst(listNames(dir)))
		{
			forEachInFile(dir, name, gson, consumer);
		}
	}

	/** Stream one log file front to back; see {@link #forEachOldestFirst}. Does nothing if the file is missing. */
	static void forEachInFile(Filepath dir, String name, Gson gson, BiConsumer<JsonObject, TradeEvent> consumer) throws IOException
	{
		Filepath file = dir.joinSegment(name);
		if (!file.isFile())
		{
			return;
		}
		try (BufferedReader r = file.openBufferedReader())
		{
			String line;
			while ((line = r.readLine()) != null)
			{
				if (line.isEmpty())
				{
					continue;
				}
				try
				{
					JsonObject obj = gson.fromJson(line, JsonObject.class);
					if (obj == null)
					{
						continue;
					}
					TradeEvent ev = gson.fromJson(obj, TradeEvent.class);
					if (ev != null && ev.getKind() != null)
					{
						consumer.accept(obj, ev);
					}
				}
				catch (RuntimeException ex)
				{
					log.debug("Skipping bad trade log line", ex);
				}
			}
		}
	}

	/** The event on one log line, or null if it doesn't parse or has no kind. "acct" is ignored. */
	static TradeEvent parse(Gson gson, String line)
	{
		try
		{
			TradeEvent ev = gson.fromJson(line, TradeEvent.class);
			return ev != null && ev.getKind() != null ? ev : null;
		}
		catch (RuntimeException ex)
		{
			log.debug("Skipping bad trade log line", ex);
			return null;
		}
	}
}
