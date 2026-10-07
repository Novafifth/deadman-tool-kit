package com.deadmantoolkit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import com.google.gson.Gson;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.Test;

public class ProfitBookTest
{
	private static final ZoneId UTC = ZoneOffset.UTC;
	private static final int ITEM = 536;
	/** 2026-03-10T12:00Z */
	private static final long T0 = Instant.parse("2026-03-10T12:00:00Z").getEpochSecond();

	static TradeEvent fill(String side, int qty, long price, long ts)
	{
		return trade(TradeEvent.FILL, ITEM, side, qty, price, ts);
	}

	static TradeEvent trade(String kind, int item, String side, int qty, long price, long ts)
	{
		return TradeEvent.builder().id(kind + side + qty + "@" + price + "/" + ts).kind(kind).side(side).itemId(item)
			.qty(qty).price(price).total(price * qty).ts(ts).world(345).name("Dragon bones").build();
	}

	@Test
	public void buyThenSellRealizesAgainstAverageCost()
	{
		ProfitBook b = new ProfitBook();
		assertTrue(b.apply(fill(TradeEvent.BUY, 10, 100, T0), UTC));
		assertTrue(b.apply(fill(TradeEvent.SELL, 4, 150, T0), UTC));
		ProfitBook.Position p = b.position(ITEM);
		assertEquals(200, p.realized);
		assertEquals(6, p.held);
		assertEquals(600, p.basis);
		assertEquals(200, b.getAllTime());

		ProfitSnapshot s = b.snapshot(LocalDate.of(2026, 3, 10), false, true);
		assertEquals(100, s.item(ITEM).getAvgCost());
		assertEquals(200, s.today(LocalDate.of(2026, 3, 10)));
		assertEquals("Dragon bones", s.item(ITEM).getName());
	}

	@Test
	public void movingAverageAfterAnotherBuy()
	{
		ProfitBook b = new ProfitBook();
		b.apply(fill(TradeEvent.BUY, 10, 100, T0), UTC);
		b.apply(fill(TradeEvent.SELL, 4, 150, T0), UTC);
		b.apply(fill(TradeEvent.BUY, 6, 200, T0), UTC);
		assertEquals(150, b.snapshot(LocalDate.of(2026, 3, 10), false, false).item(ITEM).getAvgCost());
		// Selling everything at 150 breaks even on the moving average.
		b.apply(fill(TradeEvent.SELL, 12, 150, T0), UTC);
		ProfitBook.Position p = b.position(ITEM);
		assertEquals(0, p.held);
		assertEquals(0, p.basis);
		assertEquals(200, p.realized);
	}

	@Test
	public void sellBeyondHeldSplitsOffUnknownBasis()
	{
		ProfitBook b = new ProfitBook();
		b.apply(fill(TradeEvent.BUY, 2, 100, T0), UTC);
		b.apply(fill(TradeEvent.SELL, 5, 120, T0), UTC);
		ProfitBook.Position p = b.position(ITEM);
		assertEquals(40, p.realized);
		assertEquals(0, p.held);
		assertEquals(3, p.unknownQty);
		assertEquals(360, p.unknownProceeds);
		assertEquals(360, b.getUnknownProceeds());
		assertEquals(40, b.getAllTime());
	}

	@Test
	public void sellWithNoBuysIsAllUnknownNotProfit()
	{
		ProfitBook b = new ProfitBook();
		b.apply(fill(TradeEvent.SELL, 5, 1_000, T0), UTC);
		assertEquals(0, b.getAllTime());
		assertEquals(5_000, b.getUnknownProceeds());
		assertTrue(b.daily().isEmpty());
		ProfitSnapshot s = b.snapshot(LocalDate.of(2026, 3, 10), false, false);
		assertTrue(s.hasData());
		assertTrue(s.getTop().isEmpty());
		assertEquals(5_000, s.item(ITEM).getUnknownProceeds());
	}

	@Test
	public void historyRowsCountSeparatelyToo()
	{
		ProfitBook b = new ProfitBook();
		b.apply(trade(TradeEvent.HISTORY, ITEM, TradeEvent.BUY, 10, 100, T0), UTC);
		b.apply(trade(TradeEvent.HISTORY, ITEM, TradeEvent.SELL, 10, 130, T0), UTC);
		b.apply(fill(TradeEvent.BUY, 1, 100, T0), UTC);
		b.apply(fill(TradeEvent.SELL, 1, 110, T0), UTC);
		assertEquals(310, b.getAllTime());
		assertEquals(300, b.getHistoryAllTime());
		assertEquals(300, b.position(ITEM).historyRealized);
		assertEquals(310, b.position(ITEM).realized);
	}

	@Test
	public void placedAndCancelledAreIgnored()
	{
		ProfitBook b = new ProfitBook();
		assertFalse(b.apply(trade(TradeEvent.PLACED, ITEM, TradeEvent.BUY, 10, 100, T0), UTC));
		assertFalse(b.apply(trade(TradeEvent.CANCELLED, ITEM, TradeEvent.SELL, 10, 100, T0), UTC));
		assertTrue(b.isEmpty());
		assertNull(b.position(ITEM));
	}

	@Test
	public void totalMissingFallsBackToPriceTimesQty()
	{
		ProfitBook b = new ProfitBook();
		b.apply(fill(TradeEvent.BUY, 3, 10, T0).toBuilder().total(null).build(), UTC);
		assertEquals(30, b.position(ITEM).basis);
	}

	@Test
	public void dailyBucketsUseTheZoneAndSevenDayWindow()
	{
		ZoneId ny = ZoneId.of("America/New_York");
		ProfitBook b = new ProfitBook();
		b.apply(fill(TradeEvent.BUY, 100, 100, T0), ny);
		// 2026-03-17T03:00Z is still the 16th in New York.
		long lateEvening = Instant.parse("2026-03-17T03:00:00Z").getEpochSecond();
		b.apply(fill(TradeEvent.SELL, 1, 110, lateEvening), ny);
		long sixDaysBefore = Instant.parse("2026-03-10T16:00:00Z").getEpochSecond();
		b.apply(fill(TradeEvent.SELL, 1, 120, sixDaysBefore), ny);
		long sevenDaysBefore = Instant.parse("2026-03-09T16:00:00Z").getEpochSecond();
		b.apply(fill(TradeEvent.SELL, 1, 140, sevenDaysBefore), ny);

		assertEquals(Long.valueOf(10), b.daily().get("2026-03-16"));
		LocalDate today = LocalDate.of(2026, 3, 16);
		ProfitSnapshot s = b.snapshot(today, false, false);
		assertEquals(10, s.today(today));
		assertEquals(30, s.last7(today));
		assertEquals(70, s.getAllTime());
		// The snapshot only keeps what the 7-day window can still need.
		assertFalse(s.getRecentDaily().containsKey("2026-03-09"));
		// A later day: nothing today, the 7-day window has moved on.
		assertEquals(0, s.today(today.plusDays(1)));
		assertEquals(10, s.last7(today.plusDays(1)));
	}

	@Test
	public void dailyIsPruned()
	{
		ProfitBook b = new ProfitBook();
		b.apply(fill(TradeEvent.BUY, 1_000, 1, T0), UTC);
		for (int d = 0; d < ProfitBook.DAILY_DAYS + 50; d++)
		{
			b.apply(fill(TradeEvent.SELL, 1, 2, T0 + d * 86_400L), UTC);
		}
		assertTrue(b.daily().size() <= ProfitBook.DAILY_DAYS + 1);
		assertEquals(LocalDate.of(2026, 3, 10).plusDays(ProfitBook.DAILY_DAYS + 49).toString(),
			b.daily().keySet().stream().reduce((x, y) -> y).get());
		// All time still counts every day.
		assertEquals(ProfitBook.DAILY_DAYS + 50, b.getAllTime());
	}

	@Test
	public void billionsDontOverflow()
	{
		ProfitBook b = new ProfitBook();
		long price = 2_000_000_000L;
		b.apply(fill(TradeEvent.BUY, 1_000, price, T0), UTC);
		b.apply(fill(TradeEvent.BUY, 1_000, price, T0), UTC);
		b.apply(fill(TradeEvent.SELL, 999, price + 1, T0), UTC);
		ProfitBook.Position p = b.position(ITEM);
		assertEquals(999, p.realized);
		assertEquals(1_001, p.held);
		assertEquals(1_001 * price, p.basis);
		assertEquals(price, b.snapshot(LocalDate.of(2026, 3, 10), false, false).item(ITEM).getAvgCost());
	}

	@Test
	public void mulDivRoundsAndSurvivesOverflow()
	{
		assertEquals(3, ProfitBook.mulDiv(10, 1, 3));
		assertEquals(7, ProfitBook.mulDiv(20, 1, 3));
		assertEquals(Long.MAX_VALUE / 2, ProfitBook.mulDiv(Long.MAX_VALUE / 2, 1_000_000, 1_000_000));
		assertEquals(4_611_686_018_427_387_904L, ProfitBook.mulDiv(Long.MAX_VALUE, 3, 6));
	}

	@Test
	public void topItemsBestFirst()
	{
		ProfitBook b = new ProfitBook();
		for (int item = 1; item <= 7; item++)
		{
			b.apply(trade(TradeEvent.FILL, item, TradeEvent.BUY, 1, 100, T0), UTC);
			b.apply(trade(TradeEvent.FILL, item, TradeEvent.SELL, 1, 100 + item * 10, T0), UTC);
		}
		ProfitSnapshot s = b.snapshot(LocalDate.of(2026, 3, 10), false, false);
		assertEquals(ProfitBook.TOP, s.getTop().size());
		assertEquals(7, s.getTop().get(0).getItemId());
		assertEquals(70, s.getTop().get(0).getRealized());
		assertEquals(3, s.getTop().get(4).getItemId());
	}

	@Test
	public void jsonRoundTrip()
	{
		Gson gson = new Gson();
		ProfitBook b = new ProfitBook();
		b.apply(fill(TradeEvent.BUY, 10, 100, T0), UTC);
		b.apply(fill(TradeEvent.SELL, 4, 150, T0), UTC);
		b.apply(trade(TradeEvent.HISTORY, 4151, TradeEvent.SELL, 1, 2_000_000, T0), UTC);
		ProfitBook back = gson.fromJson(gson.toJson(b), ProfitBook.class).sanitized();
		assertTrue(back.sameAs(b));
		assertEquals("Dragon bones", back.position(ITEM).name);

		// A damaged or older file without some fields still loads.
		ProfitBook partial = gson.fromJson("{\"allTime\":5}", ProfitBook.class).sanitized();
		assertEquals(5, partial.getAllTime());
		assertTrue(partial.isEmpty());
		assertTrue(partial.apply(fill(TradeEvent.BUY, 1, 1, T0), UTC));
		ProfitBook nulls = gson.fromJson("{\"items\":null,\"daily\":null}", ProfitBook.class).sanitized();
		assertTrue(nulls.apply(fill(TradeEvent.SELL, 1, 1, T0), UTC));
	}
}
