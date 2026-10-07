package com.deadmantoolkit.ui;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import com.deadmantoolkit.MarketData;
import com.google.gson.Gson;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.Test;

public class ChartRangeTest
{
	private static final Gson GSON = new Gson();

	@Test
	public void labelsAreTheServerRangeNames()
	{
		assertArrayEquals(new String[]{"24h", "7d", "30d", "90d", "1y"},
			Arrays.stream(ChartRange.values()).map(ChartRange::label).toArray(String[]::new));
	}

	@Test
	public void stepsMatchTheTable()
	{
		assertArrayEquals(new String[]{"5m", "1h", "6h", "6h", "24h"},
			Arrays.stream(ChartRange.values()).map(ChartRange::step).toArray(String[]::new));
		assertArrayEquals(new long[]{300, 3600, 21600, 21600, 86400},
			Arrays.stream(ChartRange.values()).mapToLong(ChartRange::stepSeconds).toArray());
		assertArrayEquals(new long[]{86400, 7 * 86400, 30 * 86400, 90 * 86400, 365 * 86400},
			Arrays.stream(ChartRange.values()).mapToLong(ChartRange::seconds).toArray());
		assertArrayEquals(new int[]{6, 3, 2, 3, 3},
			Arrays.stream(ChartRange.values()).mapToInt(ChartRange::maxGapBuckets).toArray());
	}

	@Test
	public void oldServerHistoryCoversEveryRange()
	{
		// The Worker ignores range and returns 365 buckets of the step.
		for (ChartRange r : ChartRange.values())
		{
			assertTrue(r.name(), r.stepSeconds() * 365 >= r.seconds());
		}
	}

	@Test
	public void defaultIsSevenDays()
	{
		assertEquals(ChartRange.D7, ChartRange.DEFAULT);
	}

	@Test
	public void trimDropsBucketsBeforeTheRangeKeepsTheStraddlingOneAndSorts()
	{
		long now = 1_000_000;
		long step = ChartRange.H24.stepSeconds();
		// A bucket ending exactly where the range starts is out; one that starts 1 s later straddles the start.
		List<MarketData.Point> pts = Arrays.asList(point(now - 10), point(now - 86_400 - step),
			point(now - 86_400 - step + 1), point(now - 86_400), point(now - 50));
		List<Long> kept = ChartRange.H24.trim(pts, now).stream().map(MarketData.Point::getTimestamp)
			.collect(Collectors.toList());
		assertEquals(Arrays.asList(now - 86_400 - step + 1, now - 86_400, now - 50, now - 10), kept);
		assertTrue(ChartRange.H24.trim(null, now).isEmpty());
	}

	private static MarketData.Point point(long ts)
	{
		return GSON.fromJson("{\"timestamp\":" + ts + ",\"avgBuy\":5}", MarketData.Point.class);
	}
}
