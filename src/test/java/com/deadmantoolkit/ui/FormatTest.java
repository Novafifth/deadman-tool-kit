package com.deadmantoolkit.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import com.deadmantoolkit.TradeEvent;
import org.junit.Test;

public class FormatTest
{
	private static final long NOW = 1_000_000;

	@Test
	public void gpIsExactBelowTenMillion()
	{
		assertEquals("-", Format.gp(null));
		assertEquals("950", Format.gp(950L));
		assertEquals("9,999,999", Format.gp(9_999_999L));
		assertEquals("10M", Format.gp(10_000_000L));
	}

	@Test
	public void agoBuckets()
	{
		assertEquals("", Format.ago(null, NOW));
		assertEquals("", Format.ago(0L, NOW));
		assertEquals("now", Format.ago(NOW, NOW));
		assertEquals("now", Format.ago(NOW + 30, NOW));
		assertEquals("now", Format.ago(NOW - 59, NOW));
		assertEquals("1m", Format.ago(NOW - 60, NOW));
		assertEquals("59m", Format.ago(NOW - 3599, NOW));
		assertEquals("1h", Format.ago(NOW - 3600, NOW));
		assertEquals("23h", Format.ago(NOW - 86399, NOW));
		assertEquals("1d", Format.ago(NOW - 86400, NOW));
		assertEquals("3d", Format.ago(NOW - 3 * 86400 - 5, NOW));
	}

	@Test
	public void describe()
	{
		assertEquals("Buy offer 10 × 300,000", Format.describe(TradeEvent.PLACED, TradeEvent.BUY, 10, 300_000));
		assertEquals("Sell offer 2 × 5", Format.describe(TradeEvent.PLACED, TradeEvent.SELL, 2, 5));
		assertEquals("Bought 3 × 290,000", Format.describe(TradeEvent.FILL, TradeEvent.BUY, 3, 290_000));
		assertEquals("Sold 3 × 290,000", Format.describe(TradeEvent.FILL, TradeEvent.SELL, 3, 290_000));
		assertEquals("Cancelled buy, 6 left", Format.describe(TradeEvent.CANCELLED, TradeEvent.BUY, 6, 1));
		assertEquals("Cancelled sell, 6 left", Format.describe(TradeEvent.CANCELLED, TradeEvent.SELL, 6, 1));
		assertEquals("Sold 500 × 900", Format.describe(TradeEvent.HISTORY, TradeEvent.SELL, 500, 900));
	}

	@Test
	public void whenMarksHistoryOnly()
	{
		assertEquals(Format.HISTORY_MARK, Format.when(TradeEvent.HISTORY, 5L));
		assertEquals("history", Format.HISTORY_MARK);
		assertEquals("", Format.when(TradeEvent.FILL, 0L));
		assertEquals("", Format.when(TradeEvent.PLACED, null));
	}

	@Test
	public void compactGpDecimalsFollowTheStep()
	{
		assertEquals("2.1B", Format.compactGp(2_147_000_000L, 100_000_000L));
		assertEquals("2B", Format.compactGp(2_000_000_000L, 1_000_000_000L));
		assertEquals("950K", Format.compactGp(950_000, 50_000));
		assertEquals("1.25M", Format.compactGp(1_250_000, 50_000));
		assertEquals("1.30M", Format.compactGp(1_300_000, 50_000));
		assertEquals("1.3M", Format.compactGp(1_300_000, 100_000));
		assertEquals("12M", Format.compactGp(12_000_000, 2_000_000));
		assertEquals("999", Format.compactGp(999, 1));
		assertEquals("0", Format.compactGp(0, 5));
		assertEquals("-350K", Format.compactGp(-350_000, 50_000));
	}

	@Test
	public void compactGpNeverUsesASmallerUnit()
	{
		// Up to 3 decimals in the value's own unit, then the exact number; never "1000.0K" or "12340.5K".
		assertEquals("1.250M", Format.compactGp(1_250_000, 1_000));
		assertEquals("1,000", Format.compactGp(1_000, 2));
		assertEquals("1,002", Format.compactGp(1_002, 2));
		assertEquals("1,000,000", Format.compactGp(1_000_000, 500));
		assertEquals("999.5K", Format.compactGp(999_500, 500));
		assertEquals("12,340,500", Format.compactGp(12_340_500, 500));
		assertEquals("2,100,002,000", Format.compactGp(2_100_002_000L, 2_000));
		java.util.regex.Pattern bare = java.util.regex.Pattern.compile("\\d{4,}");
		for (long[] c : new long[][]{{12_340_000, 500}, {12_341_500, 500}, {2_100_000_000L, 1_000}, {1_000_000, 500}, {5_000_500, 500}})
		{
			String label = Format.compactGp(c[0], c[1]);
			assertFalse(label, bare.matcher(label).find());
		}
	}

	@Test
	public void updatedAgo()
	{
		long now = 1_000_000_000_000L;
		assertEquals("", Format.updatedAgo(0, now));
		assertEquals("just now", Format.updatedAgo(now, now));
		assertEquals("just now", Format.updatedAgo(now - 4_999, now));
		assertEquals("5s ago", Format.updatedAgo(now - 5_000, now));
		assertEquals("59s ago", Format.updatedAgo(now - 59_999, now));
		assertEquals("1m ago", Format.updatedAgo(now - 60_000, now));
		assertEquals("59m ago", Format.updatedAgo(now - 3_599_999, now));
		assertEquals("1h ago", Format.updatedAgo(now - 3_600_000, now));
		assertEquals("1d ago", Format.updatedAgo(now - 86_400_000, now));
		assertEquals("just now", Format.updatedAgo(now + 1_000, now));
	}

	@Test
	public void importedTradesShowAnApproximateAge()
	{
		long now = java.time.Instant.now().getEpochSecond();
		assertEquals("imported ~3d", Format.when(TradeEvent.IMPORTED, now - 3 * 86400 - 5));
		assertEquals("imported ~2h", Format.when(TradeEvent.IMPORTED, now - 2 * 3600 - 5));
		assertEquals("imported", Format.when(TradeEvent.IMPORTED, now));
		assertEquals("imported", Format.when(TradeEvent.IMPORTED, 0L));
		assertEquals("<html>~3d<br>imported</html>", Format.whenTwoLine(TradeEvent.IMPORTED, now - 3 * 86400 - 5));
		assertEquals(Format.when(TradeEvent.FILL, now - 120), Format.whenTwoLine(TradeEvent.FILL, now - 120));
		assertEquals(Format.HISTORY_MARK, Format.whenTwoLine(TradeEvent.HISTORY, 5L));
		// The feed shows an imported trade's own time, everything else when it arrived.
		assertEquals(100, Format.feedTime(TradeEvent.IMPORTED, 500, 100));
		assertEquals(500, Format.feedTime(TradeEvent.FILL, 500, 100));
	}
}
