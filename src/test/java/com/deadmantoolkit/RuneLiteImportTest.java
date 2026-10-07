package com.deadmantoolkit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import com.google.gson.Gson;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.Test;

/** Reading RuneLite's own GE trade history: parsing, the seasonal cutoff, ids, dedupe and the result line. */
public class RuneLiteImportTest
{
	private final Gson gson = new Gson();
	/** 2026-08-01T12:00:00Z in epoch ms. */
	private static final long AUG1 = Instant.parse("2026-08-01T12:00:00Z").toEpochMilli();
	private static final long MIN = 60_000L;

	private static RuneLiteImport.Record rec(boolean buy, int item, int qty, long price, long timeMs)
	{
		return new RuneLiteImport.Record(buy, item, qty, price, timeMs);
	}

	private static TradeEvent fill(String offerKey, boolean buy, int item, int qty, long total, long ts, int n)
	{
		return TradeEvent.builder().id(offerKey + ":fill:" + n).kind(TradeEvent.FILL).side(TradeEvent.sideOf(buy))
			.itemId(item).qty(qty).price(TradeEvent.avgPrice(total, qty)).total(total).offerKey(offerKey).ts(ts)
			.world(345).build();
	}

	/** A GE History row read on Dec 1 (after every record in these tests). */
	private static TradeEvent history(String id, boolean buy, int item, int qty, long total)
	{
		return history(id, buy, item, qty, total, Instant.parse("2026-12-01T00:00:00Z").getEpochSecond());
	}

	private static TradeEvent history(String id, boolean buy, int item, int qty, long total, long readTs)
	{
		return TradeEvent.builder().id(id).kind(TradeEvent.HISTORY)
			.side(TradeEvent.sideOf(buy)).itemId(item).qty(qty).price(TradeEvent.avgPrice(total, qty)).total(total)
			.ts(readTs).late(true).world(345).build();
	}

	@Test
	public void parsesRuneLiteRecordsLeniently()
	{
		String json = "[{\"b\":true,\"i\":4151,\"q\":2,\"p\":1500000,\"t\":" + AUG1 + "},"
			+ "{\"b\":false,\"i\":11802,\"q\":1,\"p\":30000000,\"t\":" + (AUG1 + 5) + "},"
			// skipped: no time, zero units, price 0, fractional, string, not an object, missing side
			+ "{\"b\":true,\"i\":1,\"q\":1,\"p\":1},{\"b\":true,\"i\":1,\"q\":0,\"p\":1,\"t\":1},"
			+ "{\"b\":true,\"i\":1,\"q\":1,\"p\":0,\"t\":1},{\"b\":true,\"i\":1.5,\"q\":1,\"p\":1,\"t\":1},"
			+ "{\"b\":true,\"i\":\"x\",\"q\":1,\"p\":1,\"t\":1},7,null,{\"i\":1,\"q\":1,\"p\":1,\"t\":1}]";
		List<RuneLiteImport.Record> r = RuneLiteImport.parse(gson, json);
		assertEquals(Arrays.asList(rec(true, 4151, 2, 1_500_000, AUG1), rec(false, 11802, 1, 30_000_000, AUG1 + 5)), r);
		assertTrue(RuneLiteImport.parse(gson, null).isEmpty());
		assertTrue(RuneLiteImport.parse(gson, "").isEmpty());
		assertTrue(RuneLiteImport.parse(gson, "{\"b\":true}").isEmpty());
		assertTrue(RuneLiteImport.parse(gson, "not json [").isEmpty());
	}

	@Test
	public void idAndEvent()
	{
		RuneLiteImport.Record r = rec(true, 4151, 3, 1_500_001, AUG1 + 999);
		assertEquals("rl:" + (AUG1 + 999) + ":4151:3:1500001:true", RuneLiteImport.id(r));
		assertEquals("rl:" + AUG1 + ":4151:3:1500001:false", RuneLiteImport.id(rec(false, 4151, 3, 1_500_001, AUG1)));
		TradeEvent e = RuneLiteImport.toEvent(r, 345);
		assertEquals(RuneLiteImport.id(r), e.getId());
		assertEquals(TradeEvent.IMPORTED, e.getKind());
		assertEquals(TradeEvent.BUY, e.getSide());
		assertEquals(4151, e.getItemId());
		assertEquals(3, e.getQty());
		assertEquals(1_500_001, e.getPrice());
		// Exactly price * qty: the server checks it.
		assertEquals(Long.valueOf(4_500_003L), e.getTotal());
		// Seconds, rounded down.
		assertEquals(AUG1 / 1000, e.getTs());
		assertTrue(e.isLate());
		assertEquals(345, e.getWorld());
		assertNull(e.getSlot());
		assertNull(e.getOfferKey());
		assertNull(e.getFilled());
		assertNull(e.getSince());
		assertTrue(TradeEvent.isTrade(e.getKind()));
	}

	@Test
	public void cutoffIsTheFirstOfMarch2026()
	{
		assertEquals(Instant.parse("2026-03-01T00:00:00Z"), RuneLiteImport.CUTOFF);
		long cut = RuneLiteImport.CUTOFF.toEpochMilli();
		assertTrue(RuneLiteImport.beforeCutoff(rec(true, 1, 1, 1, cut - 1)));
		assertFalse(RuneLiteImport.beforeCutoff(rec(true, 1, 1, 1, cut)));

		// Seasonal Deadman: Annihilation ran 30 Jan - 20 Feb 2026; those records are ignored.
		long seasonal = Instant.parse("2026-02-10T10:00:00Z").toEpochMilli();
		RuneLiteImport.Plan p = RuneLiteImport.plan(Arrays.asList(rec(true, 1, 1, 5, seasonal), rec(true, 2, 1, 5, cut)),
			Collections.emptyList(), 0, 345);
		assertEquals(1, p.getAdd().size());
		assertEquals(2, p.getAdd().get(0).getItemId());
		assertEquals(1, p.getBeforeCutoff());
		assertEquals(0, p.getAlready());
		assertEquals(cut, p.getNewestMs());
	}

	@Test
	public void previouslyImportedRecordsMatchByTheirOwnId()
	{
		RuneLiteImport.Record a = rec(true, 4151, 1, 1_000, AUG1), b = rec(false, 4151, 1, 1_100, AUG1 + MIN);
		RuneLiteImport.Plan first = RuneLiteImport.plan(Arrays.asList(a, b), Collections.emptyList(), 0, 345);
		assertEquals(2, first.getAdd().size());
		// Oldest first.
		assertEquals(RuneLiteImport.id(a), first.getAdd().get(0).getId());

		// Importing again with what the first import added: nothing new.
		RuneLiteImport.Plan again = RuneLiteImport.plan(Arrays.asList(b, a), first.getAdd(), 0, 345);
		assertTrue(again.getAdd().isEmpty());
		assertEquals(2, again.getAlready());

		// A record that appears twice in the list is imported once.
		RuneLiteImport.Plan dup = RuneLiteImport.plan(Arrays.asList(a, a), Collections.emptyList(), 0, 345);
		assertEquals(1, dup.getAdd().size());
		assertEquals(1, dup.getAlready());
	}

	@Test
	public void watermarkSkipsWhatAnEarlierImportLookedAt()
	{
		RuneLiteImport.Record old = rec(true, 1, 1, 5, AUG1), fresh = rec(true, 1, 1, 5, AUG1 + MIN);
		RuneLiteImport.Plan p = RuneLiteImport.plan(Arrays.asList(old, fresh), Collections.emptyList(), AUG1, 345);
		assertEquals(Collections.singletonList(RuneLiteImport.id(fresh)),
			p.getAdd().stream().map(TradeEvent::getId).collect(Collectors.toList()));
		assertEquals(1, p.getAlready());
		assertEquals(AUG1 + MIN, p.getNewestMs());
		// Nothing newer: the watermark stays.
		assertEquals(AUG1 + MIN, RuneLiteImport.plan(Collections.singletonList(old), Collections.emptyList(), AUG1 + MIN, 345)
			.getNewestMs());
	}

	@Test
	public void liveFillsOfOneOfferMatchTheirRecord()
	{
		long ts = AUG1 / 1000;
		// One offer of 10 filled in two parts for 10,005 gp in all: RuneLite records q 10, p 1,000 (10,005 / 10).
		List<TradeEvent> known = Arrays.asList(
			fill("k1", true, 4151, 4, 4_000, ts - 300, 4),
			fill("k1", true, 4151, 6, 6_005, ts - 30, 10));
		RuneLiteImport.Record r = rec(true, 4151, 10, 1_000, AUG1);
		RuneLiteImport.Plan p = RuneLiteImport.plan(Collections.singletonList(r), known, 0, 345);
		assertTrue(p.getAdd().isEmpty());
		assertEquals(1, p.getAlready());

		// Same offer, but the other side, item, units or a total off by more than q gp: not it.
		for (RuneLiteImport.Record other : Arrays.asList(rec(false, 4151, 10, 1_000, AUG1), rec(true, 4152, 10, 1_000, AUG1),
			rec(true, 4151, 9, 1_000, AUG1), rec(true, 4151, 10, 998, AUG1)))
		{
			assertEquals(other.toString(), 1, RuneLiteImport.plan(Collections.singletonList(other), known, 0, 345).getAdd().size());
		}
	}

	@Test
	public void liveOfferMustHaveCompletedWithinTenMinutes()
	{
		long ts = AUG1 / 1000;
		List<TradeEvent> known = Collections.singletonList(fill("k1", false, 560, 100, 25_000, ts - 600, 100));
		assertEquals(0, RuneLiteImport.plan(Collections.singletonList(rec(false, 560, 100, 250, AUG1)), known, 0, 345)
			.getAdd().size());
		assertEquals(1, RuneLiteImport.plan(Collections.singletonList(rec(false, 560, 100, 250, AUG1 + 1000)), known, 0, 345)
			.getAdd().size());
		assertEquals(1, RuneLiteImport.plan(Collections.singletonList(rec(false, 560, 100, 250, AUG1 - 1_201_000)), known, 0, 345)
			.getAdd().size());
	}

	@Test
	public void cancelledOfferCompletesAtItsCancellation()
	{
		long ts = AUG1 / 1000;
		// Filled 3 an hour before it was cancelled; RuneLite records it at the cancellation.
		List<TradeEvent> known = Arrays.asList(
			fill("k2", true, 2, 3, 300, ts - 3600, 3),
			TradeEvent.builder().id("k2:cancelled").kind(TradeEvent.CANCELLED).side(TradeEvent.BUY).itemId(2).qty(7)
				.price(100).offerKey("k2").ts(ts + 20).world(345).build());
		RuneLiteImport.Plan p = RuneLiteImport.plan(Collections.singletonList(rec(true, 2, 3, 100, AUG1)), known, 0, 345);
		assertTrue(p.getAdd().isEmpty());
	}

	@Test
	public void eachOfferOrHistoryRowMatchesOneRecordOnly()
	{
		long ts = AUG1 / 1000;
		// Two identical trades, one recorded live: the other is imported.
		List<TradeEvent> live = Collections.singletonList(fill("k3", true, 7, 5, 500, ts, 5));
		RuneLiteImport.Plan p = RuneLiteImport.plan(Arrays.asList(rec(true, 7, 5, 100, AUG1), rec(true, 7, 5, 100, AUG1 + MIN)),
			live, 0, 345);
		assertEquals(1, p.getAdd().size());
		assertEquals(1, p.getAlready());

		// The closest offer in time is the one taken.
		List<TradeEvent> two = Arrays.asList(fill("a", true, 7, 5, 500, ts - 500, 5), fill("b", true, 7, 5, 500, ts + 400, 5));
		p = RuneLiteImport.plan(Arrays.asList(rec(true, 7, 5, 100, AUG1 + 450_000), rec(true, 7, 5, 100, AUG1 - 450_000)),
			two, 0, 345);
		assertTrue(p.getAdd().isEmpty());

		// GE History rows have no time: matched by side, item, units and total, each once.
		List<TradeEvent> rows = Arrays.asList(history("hist:a", false, 9, 2, 2_001), history("hist:b", false, 9, 2, 2_001));
		p = RuneLiteImport.plan(Arrays.asList(rec(false, 9, 2, 1_000, AUG1), rec(false, 9, 2, 1_000, AUG1 + 99 * MIN),
			rec(false, 9, 2, 1_000, AUG1 + 300 * MIN)), rows, 0, 345);
		assertEquals(1, p.getAdd().size());
		assertEquals(2, p.getAlready());
		// The same history row read twice (log + memory) still counts once.
		p = RuneLiteImport.plan(Arrays.asList(rec(false, 9, 2, 1_000, AUG1), rec(false, 9, 2, 1_000, AUG1 + MIN)),
			Arrays.asList(rows.get(0), rows.get(0)), 0, 345);
		assertEquals(1, p.getAdd().size());
	}

	@Test
	public void historyRowOnlyMatchesARecordFromBeforeItWasRead()
	{
		long aug1 = AUG1 / 1000;
		// 1 whip bought on Aug 1 and read from GE History that day; another identical buy on Sep 10.
		TradeEvent row = history("hist:w", true, 4151, 1, 1_500_000, aug1 + 3600);
		long sep10 = Instant.parse("2026-09-10T12:00:00Z").toEpochMilli();
		RuneLiteImport.Plan p = RuneLiteImport.plan(Arrays.asList(rec(true, 4151, 1, 1_500_000, AUG1),
			rec(true, 4151, 1, 1_500_000, sep10)), Collections.singletonList(row), 0, 345);
		assertEquals(1, p.getAlready());
		assertEquals(1, p.getAdd().size());
		assertEquals(sep10 / 1000, p.getAdd().get(0).getTs());

		// Only the Sep 10 record: the Aug 1 row can't be it.
		p = RuneLiteImport.plan(Collections.singletonList(rec(true, 4151, 1, 1_500_000, sep10)),
			Collections.singletonList(row), 0, 345);
		assertEquals(1, p.getAdd().size());
		assertEquals(0, p.getAlready());

		// Of two rows read after the record, the earliest read is taken, leaving the later one for a later record.
		TradeEvent late = history("hist:late", true, 4151, 1, 1_500_000, sep10 / 1000 + 3600);
		p = RuneLiteImport.plan(Arrays.asList(rec(true, 4151, 1, 1_500_000, AUG1),
			rec(true, 4151, 1, 1_500_000, sep10)), Arrays.asList(late, row), 0, 345);
		assertTrue(p.getAdd().isEmpty());
		assertEquals(2, p.getAlready());
	}

	@Test
	public void geHistoryReadAfterAnImportSkipsTheImportedTrades()
	{
		long aug1 = AUG1 / 1000;
		TradeEvent imported = RuneLiteImport.toEvent(rec(false, 4151, 100, 1_200, AUG1), 345);
		List<String> recent = RuneLiteImport.remember(new ArrayList<>(), Collections.singletonList(imported));
		assertEquals(Collections.singletonList("sell|4151|100|1200|" + aug1), recent);

		// The GE History row for it (total within q gp), read later: skipped, once.
		TradeEvent row = history("hist:1", false, 4151, 100, 120_050, aug1 + 86_400);
		assertFalse(RuneLiteImport.consumeRecent(recent, history("hist:x", true, 4151, 100, 120_050, aug1 + 86_400)));
		assertFalse(RuneLiteImport.consumeRecent(recent, history("hist:x", false, 4151, 100, 120_101, aug1 + 86_400)));
		// A row read before the trade can't be it.
		assertFalse(RuneLiteImport.consumeRecent(recent, history("hist:x", false, 4151, 100, 120_050, aug1 - 3_600)));
		assertTrue(RuneLiteImport.consumeRecent(recent, row));
		assertTrue(recent.isEmpty());
		assertFalse(RuneLiteImport.consumeRecent(recent, row));

		// Bounded, newest kept; malformed entries dropped.
		List<TradeEvent> many = new ArrayList<>();
		for (int i = 0; i < RuneLiteImport.MAX_RECENT + 10; i++)
		{
			many.add(RuneLiteImport.toEvent(rec(true, 1, 1, 1, AUG1 + i * MIN), 345));
		}
		List<String> kept = RuneLiteImport.remember(Arrays.asList("junk", "buy|1|x|1|1"), many);
		assertEquals(RuneLiteImport.MAX_RECENT, kept.size());
		assertEquals(RuneLiteImport.recentEntry(many.get(many.size() - 1)), kept.get(kept.size() - 1));
		assertEquals(RuneLiteImport.recentEntry(many.get(10)), kept.get(0));
	}

	@Test
	public void needsTheLocalLog()
	{
		assertEquals(RuneLiteImport.NEEDS_LOGIN, RuneLiteImport.blocked(false, true));
		assertEquals(RuneLiteImport.NEEDS_LOGIN, RuneLiteImport.blocked(false, false));
		// Without the log, live trades from earlier sessions can't be seen, so they'd be imported again.
		assertEquals(RuneLiteImport.NEEDS_LOCAL_LOG, RuneLiteImport.blocked(true, false));
		assertNull(RuneLiteImport.blocked(true, true));
	}

	@Test
	public void totalWithinQGp()
	{
		RuneLiteImport.Record r = rec(true, 1, 4, 100, AUG1);
		assertTrue(RuneLiteImport.totalMatches(400, r));
		assertTrue(RuneLiteImport.totalMatches(403, r));
		assertTrue(RuneLiteImport.totalMatches(404, r));
		assertFalse(RuneLiteImport.totalMatches(405, r));
		assertTrue(RuneLiteImport.totalMatches(396, r));
		assertFalse(RuneLiteImport.totalMatches(395, r));
	}

	@Test
	public void resultLine()
	{
		ZoneId utc = ZoneOffset.UTC;
		List<TradeEvent> add = new ArrayList<>();
		add.add(RuneLiteImport.toEvent(rec(true, 1, 1, 1, Instant.parse("2026-07-29T10:00:00Z").toEpochMilli()), 345));
		add.add(RuneLiteImport.toEvent(rec(true, 1, 1, 1, Instant.parse("2026-10-07T10:00:00Z").toEpochMilli()), 345));
		assertEquals("Imported 2 trades (Jul 29 - Oct 7) - 210 already recorded",
			RuneLiteImport.message(new RuneLiteImport.Plan(add, 210, 0, 0), utc));
		assertEquals("Imported 1 trade (Jul 29)",
			RuneLiteImport.message(new RuneLiteImport.Plan(add.subList(0, 1), 0, 0, 0), utc));
		assertEquals("No new trades - 5 already recorded", RuneLiteImport.message(new RuneLiteImport.Plan(
			Collections.emptyList(), 5, 0, 0), utc));
		assertEquals("No trades to import - 3 from before Mar 1 2026 ignored", RuneLiteImport.message(new RuneLiteImport.Plan(
			Collections.emptyList(), 0, 3, 0), utc));
		assertEquals("RuneLite's Grand Exchange plugin has no trade history for this account yet.",
			RuneLiteImport.message(new RuneLiteImport.Plan(Collections.emptyList(), 0, 0, 0), utc));
		List<TradeEvent> years = Arrays.asList(add.get(1),
			RuneLiteImport.toEvent(rec(true, 1, 1, 1, Instant.parse("2027-01-02T10:00:00Z").toEpochMilli()), 345));
		assertEquals("Oct 7 2026 - Jan 2 2027", RuneLiteImport.range(years, utc));
	}

	@Test
	public void firstLogMonthToRead()
	{
		RuneLiteImport.Record jul = rec(true, 1, 1, 1, Instant.parse("2026-07-01T00:05:00Z").toEpochMilli());
		RuneLiteImport.Record oct = rec(true, 1, 1, 1, Instant.parse("2026-10-07T00:00:00Z").toEpochMilli());
		assertEquals(YearMonth.of(2026, 6), RuneLiteImport.firstMonth(Arrays.asList(oct, jul)));
		assertNull(RuneLiteImport.firstMonth(Collections.emptyList()));
	}
}
