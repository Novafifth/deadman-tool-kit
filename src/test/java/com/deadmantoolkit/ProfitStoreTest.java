package com.deadmantoolkit;

import static com.deadmantoolkit.ProfitBookTest.trade;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import net.runelite.client.util.Filepath;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class ProfitStoreTest
{
	private static final String A = AccountId.of(1L);
	private static final String B = AccountId.of(2L);
	private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-03-20T12:00:00Z"), ZoneOffset.UTC);
	private static final long JAN = Instant.parse("2026-01-15T12:00:00Z").getEpochSecond();
	private static final long FEB = Instant.parse("2026-02-15T12:00:00Z").getEpochSecond();
	private static final long APR = Instant.parse("2026-04-01T00:00:01Z").getEpochSecond();

	@Rule
	public TemporaryFolder tmp = new TemporaryFolder();

	private final Gson gson = new Gson();
	private final List<ProfitSnapshot> published = new CopyOnWriteArrayList<>();
	private ScheduledExecutorService executor;

	@Before
	public void setUp()
	{
		executor = Executors.newSingleThreadScheduledExecutor();
	}

	@After
	public void tearDown()
	{
		executor.shutdownNow();
	}

	private Filepath dir()
	{
		return Filepath.Unchecked.getRooted(tmp.getRoot().toPath()).joinSegment("deadman-tool-kit");
	}

	private ProfitStore store()
	{
		return new ProfitStore(this::dir, gson, executor, CLOCK, published::add);
	}

	/** Run {@code r} on the executor and wait for it (and everything queued before it). */
	private void on(Runnable r) throws Exception
	{
		executor.submit(r).get(10, TimeUnit.SECONDS);
	}

	private void awaitRebuild(ProfitStore s) throws Exception
	{
		for (int i = 0; i < 1_000; i++)
		{
			if (!executor.submit(s::isRebuilding).get(10, TimeUnit.SECONDS))
			{
				return;
			}
		}
		throw new AssertionError("rebuild never finished");
	}

	private static TradeEvent buy(int qty, long price, long ts)
	{
		return trade(TradeEvent.FILL, 536, TradeEvent.BUY, qty, price, ts);
	}

	private static TradeEvent sell(int qty, long price, long ts)
	{
		return trade(TradeEvent.FILL, 536, TradeEvent.SELL, qty, price, ts);
	}

	private void log(String acct, TradeEvent... events) throws Exception
	{
		List<String> lines = new ArrayList<>();
		for (TradeEvent e : events)
		{
			lines.add(gson.toJson(DeadmanToolKitPlugin.logLine(gson, e, acct)));
		}
		LocalTradeLog.append(dir(), LocalTradeLog.monthOf(events[0].getTs()), lines);
	}

	private void legacy(TradeEvent... events) throws Exception
	{
		StringBuilder sb = new StringBuilder();
		for (TradeEvent e : events)
		{
			sb.append(gson.toJson(e)).append('\n');
		}
		Filepath d = dir();
		d.createDirectories();
		d.joinSegment(LogFiles.LEGACY_FILE).write(sb.toString().getBytes(StandardCharsets.UTF_8));
	}

	private static ProfitBook live(TradeEvent... events)
	{
		ProfitBook b = new ProfitBook();
		for (TradeEvent e : events)
		{
			b.apply(e, ZoneOffset.UTC);
		}
		return b;
	}

	@Test
	public void fileNameIsFromTheHashOnly()
	{
		assertEquals("profit-" + A.substring(0, 16) + ".json", ProfitStore.fileName(A));
		assertNull(ProfitStore.fileName("Zezima"));
		assertNull(ProfitStore.fileName("../../x"));
		assertNull(ProfitStore.fileName(null));
		assertNull(ProfitStore.fileName("abc"));
	}

	@Test
	public void savesAndLoadsPerAccount() throws Exception
	{
		ProfitStore s = store();
		on(() -> s.load(A));
		on(() -> s.apply(A, Arrays.asList(buy(10, 100, JAN), sell(4, 150, JAN)), false));
		assertEquals(200, published.get(published.size() - 1).getAllTime());
		// Switching account saves the old one first.
		on(() -> s.load(B));
		assertTrue(dir().joinSegment(ProfitStore.fileName(A)).isFile());
		assertEquals(0, published.get(published.size() - 1).getAllTime());
		on(() -> s.apply(B, Collections.singletonList(sell(1, 5, JAN)), false));
		on(s::close);
		assertTrue(dir().joinSegment(ProfitStore.fileName(B)).isFile());
		// A closed store ignores late events.
		int count = published.size();
		on(() -> s.apply(B, Collections.singletonList(sell(1, 5, JAN)), false));
		assertEquals(count, published.size());

		ProfitStore again = store();
		on(() -> again.load(A));
		assertTrue(again.book().sameAs(live(buy(10, 100, JAN), sell(4, 150, JAN))));
		on(() -> again.load(B));
		assertEquals(5, again.book().getUnknownProceeds());
	}

	@Test
	public void applyToAnotherAccountSwitchesFirst() throws Exception
	{
		ProfitStore s = store();
		on(() -> s.load(A));
		on(() -> s.apply(B, Collections.singletonList(buy(1, 1, JAN)), false));
		on(() -> s.apply(null, Collections.singletonList(sell(1, 3, JAN)), false));
		on(() -> s.load(A));
		assertTrue(s.book().isEmpty());
		on(() -> s.load(B));
		assertEquals(2, s.book().getAllTime());
	}

	@Test
	public void debouncedSaveWritesTheFile() throws Exception
	{
		ProfitStore s = store();
		on(() -> s.load(A));
		on(() -> s.apply(A, Collections.singletonList(buy(1, 1, JAN)), false));
		assertFalse(dir().joinSegment(ProfitStore.fileName(A)).exists());
		on(s::saveNow);
		assertTrue(dir().joinSegment(ProfitStore.fileName(A)).isFile());
	}

	@Test
	public void damagedFileStartsFromZero() throws Exception
	{
		Filepath d = dir();
		d.createDirectories();
		d.joinSegment(ProfitStore.fileName(A)).write("{not json".getBytes(StandardCharsets.UTF_8));
		ProfitStore s = store();
		on(() -> s.load(A));
		assertTrue(s.book().isEmpty());
		assertFalse(published.isEmpty());
	}

	@Test
	public void rebuildFiltersOtherAccountsAndIncludesLegacy() throws Exception
	{
		TradeEvent l1 = buy(10, 100, JAN - 86_400 * 60);
		legacy(l1);
		TradeEvent a1 = sell(4, 150, JAN), a2 = buy(6, 200, FEB), a3 = sell(12, 160, FEB);
		TradeEvent b1 = sell(100, 1_000, JAN);
		log(A, a1);
		log(B, b1);
		log(A, a2, a3);

		ProfitStore s = store();
		on(() -> s.load(A));
		on(s::rebuild);
		awaitRebuild(s);
		assertTrue(s.book().sameAs(live(l1, a1, a2, a3)));
		assertTrue(dir().joinSegment(ProfitStore.fileName(A)).isFile());
		ProfitSnapshot last = published.get(published.size() - 1);
		assertFalse(last.isRebuilding());
		assertTrue(last.isLogAvailable());
		assertTrue(published.stream().anyMatch(ProfitSnapshot::isRebuilding));
	}

	@Test
	public void rebuildCountsEventsRecordedWhileItRunsOnce() throws Exception
	{
		TradeEvent l1 = buy(10, 100, JAN - 86_400 * 60);
		legacy(l1);
		TradeEvent a1 = sell(2, 150, JAN), a2 = buy(4, 120, FEB);
		log(A, a1);
		log(A, a2);
		ProfitStore s = store();
		on(() -> s.load(A));
		// Count what's already logged live too, so the store starts with the same numbers.
		on(() -> s.apply(A, Arrays.asList(l1, a1, a2), false));

		// Queue the rebuild and, behind it, events recorded meanwhile, the way the plugin does: append, then apply.
		TradeEvent feb2 = sell(3, 200, FEB + 60), apr = sell(1, 300, APR);
		executor.execute(s::rebuild);
		for (TradeEvent e : Arrays.asList(feb2, apr))
		{
			executor.execute(() ->
			{
				try
				{
					log(A, e);
				}
				catch (Exception ex)
				{
					throw new IllegalStateException(ex);
				}
				s.apply(A, Collections.singletonList(e), true);
			});
		}
		awaitRebuild(s);
		// The April file didn't exist when the rebuild listed the log; it is still read, and nothing counts twice.
		assertTrue(s.book().sameAs(live(l1, a1, a2, feb2, apr)));
	}

	@Test
	public void rebuildWithoutAccountOrLogStillPublishes() throws Exception
	{
		ProfitStore s = store();
		on(s::rebuild);
		assertEquals(1, published.size());
		assertFalse(published.get(0).isLogAvailable());
		on(() -> s.load(A));
		on(s::rebuild);
		awaitRebuild(s);
		assertTrue(s.book().isEmpty());
		assertFalse(published.get(published.size() - 1).isRebuilding());
	}

	@Test
	public void belongsTo()
	{
		JsonObject legacy = new JsonObject();
		assertTrue(ProfitStore.belongsTo(legacy, A));
		JsonObject mine = new JsonObject();
		mine.addProperty("acct", A);
		assertTrue(ProfitStore.belongsTo(mine, A));
		assertFalse(ProfitStore.belongsTo(mine, B));
		JsonObject odd = new JsonObject();
		odd.add("acct", new JsonObject());
		assertFalse(ProfitStore.belongsTo(odd, A));
	}

	@Test
	public void importedTradesCountAsTradesAndRebuildInTimeOrder() throws Exception
	{
		// A live sell, then (appended later, into the same month) the imported buy from before it.
		TradeEvent sold = sell(10, 150, FEB + 600);
		TradeEvent bought = trade(TradeEvent.IMPORTED, 536, TradeEvent.BUY, 10, 100, FEB);
		log(A, sold);
		log(A, bought);
		ProfitStore s = store();
		on(() -> s.load(A));
		on(s::rebuild);
		awaitRebuild(s);
		// Counted where it happened: the buy is the sell's cost basis.
		assertTrue(s.book().sameAs(live(bought, sold)));
		assertEquals(500, s.book().getAllTime());
		assertEquals(0, s.book().getUnknownProceeds());
	}

	@Test
	public void importOfOlderTradesRecountsInTimeOrder() throws Exception
	{
		// The sell was counted live (no basis yet); then an import appends the older buy, as the plugin does.
		TradeEvent sold = sell(10, 150, FEB + 600);
		TradeEvent bought = trade(TradeEvent.IMPORTED, 536, TradeEvent.BUY, 10, 100, FEB);
		log(A, sold);
		ProfitStore s = store();
		on(() -> s.load(A));
		on(() -> s.apply(A, Collections.singletonList(sold), true));
		assertEquals(1_500, s.book().getUnknownProceeds());
		on(() ->
		{
			try
			{
				log(A, bought);
			}
			catch (Exception ex)
			{
				throw new IllegalStateException(ex);
			}
			s.rebuildFor(A);
		});
		awaitRebuild(s);
		// Same as a sorted rebuild: the imported buy is the sell's cost basis, nothing is held.
		assertTrue(s.book().sameAs(live(bought, sold)));
		assertEquals(500, s.book().getAllTime());
		assertEquals(0, s.book().getUnknownProceeds());
		// Applying it on top instead would have left phantom held units and unknown proceeds.
		assertFalse(live(sold, bought).sameAs(live(bought, sold)));
	}

	@Test
	public void anImportNeverRecountsAwayTradesTheLogDoesntHave() throws Exception
	{
		// The sell was counted while the local log was off; an import then brings an older buy.
		TradeEvent sold = sell(10, 150, FEB + 600);
		TradeEvent bought = trade(TradeEvent.IMPORTED, 536, TradeEvent.BUY, 10, 100, FEB);
		ProfitStore s = store();
		on(() -> s.load(A));
		on(() -> s.apply(A, Collections.singletonList(sold), false));
		on(s::saveNow);
		ProfitStore again = store();
		on(() -> again.load(A));
		on(() ->
		{
			try
			{
				log(A, bought);
			}
			catch (Exception ex)
			{
				throw new IllegalStateException(ex);
			}
			assertFalse("a recount from the log would drop the unlogged sell", again.rebuildFor(A));
			again.apply(A, Collections.singletonList(bought), true);
		});
		assertFalse(executor.submit(again::isRebuilding).get(10, TimeUnit.SECONDS));
		assertTrue(again.book().sameAs(live(sold, bought)));
		assertEquals(1_500, again.book().getUnknownProceeds());
	}

	@Test
	public void rebuildForWhileARebuildRunsRebuildsAgain() throws Exception
	{
		TradeEvent sold = sell(10, 150, FEB + 600);
		TradeEvent bought = trade(TradeEvent.IMPORTED, 536, TradeEvent.BUY, 10, 100, FEB);
		log(A, sold);
		// A second month, so the first rebuild takes two tasks and the import lands between them.
		TradeEvent apr = sell(1, 300, APR);
		log(A, apr);
		ProfitStore s = store();
		on(() -> s.load(A));
		Runnable importBuy = () ->
		{
			try
			{
				log(A, bought);
			}
			catch (Exception ex)
			{
				throw new IllegalStateException(ex);
			}
			s.rebuildFor(A);
		};
		// Queued right behind the rebuild's first file (February), so it lands after that file was read.
		on(() ->
		{
			s.rebuild();
			executor.execute(importBuy);
		});
		awaitRebuild(s);
		assertTrue(s.book().sameAs(live(bought, sold, apr)));
	}
}
