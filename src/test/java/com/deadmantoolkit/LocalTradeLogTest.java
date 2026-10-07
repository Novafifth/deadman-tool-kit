package com.deadmantoolkit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import com.google.gson.Gson;
import java.time.Instant;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import net.runelite.client.util.Filepath;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class LocalTradeLogTest
{
	private static final YearMonth JAN = YearMonth.of(2026, 1);
	private static final YearMonth FEB = YearMonth.of(2026, 2);
	private static final int[] CHUNKS = {7, ReverseLineReader.DEFAULT_CHUNK};

	@Rule
	public TemporaryFolder tmp = new TemporaryFolder();

	private final Gson gson = new Gson();

	private Filepath dir()
	{
		return Filepath.Unchecked.getRooted(tmp.getRoot().toPath()).joinSegment("deadman-tool-kit");
	}

	private static TradeEvent ev(String id, long ts)
	{
		return ev(id, TradeEvent.FILL, ts);
	}

	private static TradeEvent ev(String id, String kind, long ts)
	{
		return TradeEvent.builder().id(id).kind(kind).side(TradeEvent.BUY).itemId(4151).qty(1).price(1).ts(ts).world(345).build();
	}

	private List<String> lines(TradeEvent... events)
	{
		List<String> out = new ArrayList<>();
		for (TradeEvent e : events)
		{
			out.add(gson.toJson(DeadmanToolKitPlugin.logLine(gson, e, "acct1")));
		}
		return out;
	}

	private static List<String> ids(List<TradeEvent> events)
	{
		return events.stream().map(TradeEvent::getId).collect(Collectors.toList());
	}

	@Test
	public void monthIsUtc()
	{
		long lastSecondOfJan = Instant.parse("2026-01-31T23:59:59Z").getEpochSecond();
		assertEquals(JAN, LocalTradeLog.monthOf(lastSecondOfJan));
		assertEquals(FEB, LocalTradeLog.monthOf(lastSecondOfJan + 1));
		assertEquals("trades-2026-01.jsonl", LocalTradeLog.fileName(JAN));
		assertEquals("trades-0999-12.jsonl", LocalTradeLog.fileName(YearMonth.of(999, 12)));
	}

	@Test
	public void appendCreatesFolderAndGoesToTheMonthsFile() throws Exception
	{
		Filepath dir = dir();
		assertFalse(dir.exists());
		LocalTradeLog.append(dir, JAN, lines(ev("a", 1)));
		LocalTradeLog.append(dir, FEB, lines(ev("b", 2)));
		LocalTradeLog.append(dir, JAN, lines(ev("c", 3)));
		assertTrue(dir.joinSegment("trades-2026-01.jsonl").exists());
		assertTrue(dir.joinSegment("trades-2026-02.jsonl").exists());
		assertFalse(dir.joinSegment(LogFiles.LEGACY_FILE).exists());

		List<String> seen = new ArrayList<>();
		LocalTradeLog.forEachInFile(dir, "trades-2026-01.jsonl", gson, (o, e) -> seen.add(e.getId()));
		assertEquals(Arrays.asList("a", "c"), seen);
	}

	@Test
	public void readNewestWithoutLogIsEmpty() throws Exception
	{
		assertTrue(LocalTradeLog.readNewest(dir(), gson, 300).isEmpty());
	}

	@Test
	public void readNewestReturnsOldestFirstAcrossFiles() throws Exception
	{
		Filepath dir = dir();
		dir.createDirectories();
		dir.joinSegment(LogFiles.LEGACY_FILE).write(gson.toJson(ev("l1", 1)) + "\n" + gson.toJson(ev("l2", 2)) + "\n");
		LocalTradeLog.append(dir, JAN, lines(ev("j1", 10), ev("j2", 11)));
		LocalTradeLog.append(dir, FEB, lines(ev("f1", 20)));
		for (int chunk : CHUNKS)
		{
			assertEquals(Arrays.asList("l1", "l2", "j1", "j2", "f1"), ids(LocalTradeLog.readNewest(dir, gson, 300, chunk)));
			assertEquals(Arrays.asList("j2", "f1"), ids(LocalTradeLog.readNewest(dir, gson, 2, chunk)));
		}
	}

	@Test
	public void legacyOnly() throws Exception
	{
		Filepath dir = dir();
		dir.createDirectories();
		dir.joinSegment(LogFiles.LEGACY_FILE).write(gson.toJson(ev("a", 1)) + "\n" + gson.toJson(ev("b", 2)) + "\n");
		assertEquals(Arrays.asList("a", "b"), ids(LocalTradeLog.readNewest(dir, gson, 300)));
	}

	@Test
	public void readNewestStopsBeforeOlderFiles() throws Exception
	{
		Filepath dir = dir();
		dir.createDirectories();
		// The oldest file starts with a corrupt line; it must not matter (or be reached) when newer files suffice.
		dir.joinSegment(LogFiles.LEGACY_FILE).write("not json {\n" + gson.toJson(ev("old", 1)) + "\n");
		LocalTradeLog.append(dir, JAN, lines(ev("j1", 10), ev("j2", 11)));
		assertEquals(Arrays.asList("j1", "j2"), ids(LocalTradeLog.readNewest(dir, gson, 2)));
		assertEquals(Arrays.asList("old", "j1", "j2"), ids(LocalTradeLog.readNewest(dir, gson, 3)));
	}

	@Test
	public void placedEventsAreSkippedAndNotCounted() throws Exception
	{
		Filepath dir = dir();
		LocalTradeLog.append(dir, JAN, lines(ev("f1", 1), ev("p1", TradeEvent.PLACED, 2), ev("f2", 3),
			ev("c1", TradeEvent.CANCELLED, 4), ev("p2", TradeEvent.PLACED, 5)));
		assertEquals(Arrays.asList("f2", "c1"), ids(LocalTradeLog.readNewest(dir, gson, 2)));
		assertEquals(Arrays.asList("f1", "f2", "c1"), ids(LocalTradeLog.readNewest(dir, gson, 300)));
	}

	@Test
	public void badLinesAndLinesWithoutKindAreSkipped() throws Exception
	{
		Filepath dir = dir();
		List<String> raw = new ArrayList<>(lines(ev("good", 1)));
		raw.addAll(Arrays.asList("not json {", "{}", "", "[1,2]"));
		LocalTradeLog.append(dir, JAN, raw);
		assertEquals(Collections.singletonList("good"), ids(LocalTradeLog.readNewest(dir, gson, 300)));

		List<String> seen = new ArrayList<>();
		LocalTradeLog.forEachOldestFirst(dir, gson, (o, e) -> seen.add(e.getId()));
		assertEquals(Collections.singletonList("good"), seen);
	}

	@Test
	public void acctLinesParseAndExposeTheAccount() throws Exception
	{
		Filepath dir = dir();
		dir.createDirectories();
		dir.joinSegment(LogFiles.LEGACY_FILE).write(gson.toJson(ev("legacy", 1)) + "\n");
		LocalTradeLog.append(dir, FEB, lines(ev("f", 3)));
		LocalTradeLog.append(dir, JAN, lines(ev("j", 2)));
		assertEquals(ev("j", 2), LocalTradeLog.readNewest(dir, gson, 300).get(1));

		List<String> seen = new ArrayList<>();
		LocalTradeLog.forEachOldestFirst(dir, gson, (o, e) ->
			seen.add(e.getId() + "/" + (o.has("acct") ? o.get("acct").getAsString() : "-")));
		assertEquals(Arrays.asList("legacy/-", "j/acct1", "f/acct1"), seen);
	}
}
