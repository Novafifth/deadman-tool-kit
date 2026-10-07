package com.deadmantoolkit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import com.deadmantoolkit.GeHistory.Cell;
import com.deadmantoolkit.GeHistory.Entry;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public class GeHistoryTest
{
	private static Cell text(String t)
	{
		return new Cell(t, -1, 0);
	}

	private static Cell item(int id, int qty)
	{
		return new Cell("", id, qty);
	}

	@Test
	public void parsesRows()
	{
		List<Cell> cells = Arrays.asList(
			text(""), text("Bought:"), item(4151, 2), text("Abyssal whip"), text("600,000 coins<br>= 300,000 each"),
			text(""), text("Sold:"), item(385, 500), text("Shark"), text("<col=ffffff>450,000</col> coins")
		);
		List<Entry> rows = GeHistory.parse(cells);
		assertEquals(2, rows.size());
		assertTrue(rows.get(0).isBuy());
		assertEquals(4151, rows.get(0).getItemId());
		assertEquals(2, rows.get(0).getQty());
		assertEquals(600_000, rows.get(0).getTotal());
		assertFalse(rows.get(1).isBuy());
		assertEquals(450_000, rows.get(1).getTotal());
	}

	@Test
	public void toleratesPriceBeforeItem()
	{
		List<Entry> rows = GeHistory.parse(Arrays.asList(text("Sold:"), text("1,000 coins"), item(560, 10)));
		assertEquals(1, rows.size());
		assertEquals(560, rows.get(0).getItemId());
		assertEquals(1000, rows.get(0).getTotal());
	}

	@Test
	public void countNewFirstRead()
	{
		assertEquals(3, GeHistory.countNew(Collections.emptyList(), Arrays.asList("a", "b", "c")));
	}

	@Test
	public void countNewNothingChanged()
	{
		assertEquals(0, GeHistory.countNew(Arrays.asList("a", "b", "c"), Arrays.asList("a", "b", "c")));
	}

	@Test
	public void countNewWithNewRowsOnTop()
	{
		// List is capped, so old rows fall off the bottom as new ones arrive.
		assertEquals(2, GeHistory.countNew(Arrays.asList("a", "b", "c"), Arrays.asList("x", "y", "a")));
	}

	@Test
	public void countNewWithRepeatedIdenticalTrades()
	{
		assertEquals(1, GeHistory.countNew(Arrays.asList("a", "a", "b"), Arrays.asList("a", "a", "a", "b")));
	}

	@Test
	public void countNewWhenEverythingRolledOver()
	{
		List<String> cur = new ArrayList<>(Arrays.asList("p", "q", "r"));
		assertEquals(3, GeHistory.countNew(Arrays.asList("a", "b", "c"), cur));
	}

	@Test
	public void newEventsOldestFirstSkippingLiveRows()
	{
		List<Entry> entries = Arrays.asList(
			new Entry(true, 4151, 3, 1_000_000),
			new Entry(false, 385, 500, 450_000),
			new Entry(true, 560, 10, 1_000),
			new Entry(true, 1, 1, 1)
		);
		AtomicInteger n = new AtomicInteger();
		List<String> asked = new ArrayList<>();
		List<TradeEvent> ev = GeHistory.newEvents(entries, 3, sig ->
		{
			asked.add(sig);
			return sig.equals("sell|385|500|450000");
		}, 777, 345, () -> "hist:" + n.incrementAndGet());

		assertEquals(Arrays.asList("buy|560|10|1000", "sell|385|500|450000", "buy|4151|3|1000000"), asked);
		assertEquals(2, ev.size());

		TradeEvent a = ev.get(0);
		assertEquals("hist:1", a.getId());
		assertEquals(TradeEvent.HISTORY, a.getKind());
		assertEquals("buy", a.getSide());
		assertEquals(560, a.getItemId());
		assertEquals(10, a.getQty());
		assertEquals(100, a.getPrice());
		assertEquals(Long.valueOf(1_000), a.getTotal());
		assertEquals(777, a.getTs());
		assertEquals(345, a.getWorld());
		assertTrue(a.isLate());

		TradeEvent b = ev.get(1);
		assertEquals("hist:2", b.getId());
		assertEquals(4151, b.getItemId());
		// 1,000,000 / 3 rounds to the nearest gp.
		assertEquals(333_333, b.getPrice());
	}

	@Test
	public void newEventsRoundsHalfUp()
	{
		List<TradeEvent> ev = GeHistory.newEvents(Collections.singletonList(new Entry(false, 4151, 2, 5)), 1,
			sig -> false, 1, 345, () -> "hist:x");
		assertEquals("sell", ev.get(0).getSide());
		assertEquals(3, ev.get(0).getPrice());
	}

	@Test
	public void newEventsWithNothingNew()
	{
		assertTrue(GeHistory.newEvents(Collections.singletonList(new Entry(true, 4151, 1, 1)), 0,
			sig -> false, 1, 345, () -> "hist:x").isEmpty());
	}
}
