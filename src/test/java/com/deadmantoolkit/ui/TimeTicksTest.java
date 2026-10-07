package com.deadmantoolkit.ui;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.Test;

public class TimeTicksTest
{
	private static final ZoneId UTC = ZoneOffset.UTC;
	private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");

	private static long ts(String iso)
	{
		return Instant.parse(iso).getEpochSecond();
	}

	private static void ascendingInRange(TimeTicks t, long t0, long t1)
	{
		for (int i = 0; i < t.ts.length; i++)
		{
			assertTrue(t.ts[i] >= t0 && t.ts[i] <= t1);
			if (i > 0)
			{
				assertTrue(t.ts[i] > t.ts[i - 1]);
			}
		}
	}

	@Test
	public void dayRangeUsesHoursAtLeastThreeApart()
	{
		long t1 = ts("2026-10-07T12:30:00Z"), t0 = t1 - 86_400;
		TimeTicks t = TimeTicks.of(t0, t1, ChartRange.H24, UTC, 8);
		assertArrayEquals(new String[]{"15:00", "18:00", "21:00", "00:00", "03:00", "06:00", "09:00", "12:00"}, t.labels);
		ascendingInRange(t, t0, t1);
		for (int i = 1; i < t.ts.length; i++)
		{
			assertTrue(t.ts[i] - t.ts[i - 1] >= 3 * 3600);
		}
		// Less room: every 6 hours.
		assertArrayEquals(new String[]{"18:00", "00:00", "06:00", "12:00"}, TimeTicks.of(t0, t1, ChartRange.H24, UTC, 4).labels);
	}

	@Test
	public void dayRangeInLocalTime()
	{
		long t1 = ts("2026-10-07T12:30:00Z"), t0 = t1 - 86_400;
		TimeTicks t = TimeTicks.of(t0, t1, ChartRange.H24, NEW_YORK, 4);
		// 12:30 UTC is 08:30 in New York (EDT).
		assertArrayEquals(new String[]{"12:00", "18:00", "00:00", "06:00"}, t.labels);
		ascendingInRange(t, t0, t1);
	}

	@Test
	public void weekUsesWeekdays()
	{
		long t1 = ts("2026-10-07T12:00:00Z"), t0 = t1 - 7 * 86_400;
		TimeTicks t = TimeTicks.of(t0, t1, ChartRange.D7, UTC, 10);
		assertArrayEquals(new String[]{"Thu", "Fri", "Sat", "Sun", "Mon", "Tue", "Wed"}, t.labels);
		assertEquals(ts("2026-10-01T00:00:00Z"), t.ts[0]);
		assertTrue(TimeTicks.of(t0, t1, ChartRange.D7, UTC, 4).ts.length <= 4);
	}

	@Test
	public void monthUsesMondays()
	{
		long t1 = ts("2026-10-07T12:00:00Z"), t0 = t1 - 30 * 86_400;
		TimeTicks t = TimeTicks.of(t0, t1, ChartRange.D30, UTC, 6);
		assertArrayEquals(new String[]{"14 Sep", "21 Sep", "28 Sep", "5 Oct"}, t.labels);
	}

	@Test
	public void quarterEveryFewWeeks()
	{
		long t1 = ts("2026-10-07T12:00:00Z"), t0 = t1 - 90 * 86_400;
		TimeTicks t = TimeTicks.of(t0, t1, ChartRange.D90, UTC, 5);
		assertTrue(t.ts.length >= 2 && t.ts.length <= 5);
		for (int i = 1; i < t.ts.length; i++)
		{
			long gap = t.ts[i] - t.ts[i - 1];
			assertTrue(gap >= 14 * 86_400 && gap % (7 * 86_400) == 0);
		}
		ascendingInRange(t, t0, t1);
	}

	@Test
	public void yearUsesMonths()
	{
		long t1 = ts("2026-10-07T12:00:00Z"), t0 = t1 - 365 * 86_400;
		TimeTicks t = TimeTicks.of(t0, t1, ChartRange.Y1, UTC, 12);
		assertArrayEquals(new String[]{"Nov", "Dec", "Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct"},
			t.labels);
		assertEquals(ts("2025-11-01T00:00:00Z"), t.ts[0]);
		assertArrayEquals(new String[]{"Jan", "Apr", "Jul", "Oct"}, TimeTicks.of(t0, t1, ChartRange.Y1, UTC, 4).labels);
	}

	@Test
	public void maxLabelsIsRespected()
	{
		long t1 = ts("2026-10-07T12:00:00Z");
		for (ChartRange r : ChartRange.values())
		{
			for (int max = 1; max <= 12; max++)
			{
				TimeTicks t = TimeTicks.of(t1 - r.seconds(), t1, r, NEW_YORK, max);
				assertTrue(r + " " + max + ": " + t.ts.length, t.ts.length <= max);
				assertEquals(t.ts.length, t.labels.length);
			}
			assertEquals(0, TimeTicks.of(t1 - r.seconds(), t1, r, UTC, 0).ts.length);
		}
	}

	@Test
	public void acrossDaylightSavingChange()
	{
		// New York leaves DST on 1 Nov 2026 at 02:00 local.
		long t1 = ts("2026-11-01T18:00:00Z"), t0 = t1 - 86_400;
		TimeTicks t = TimeTicks.of(t0, t1, ChartRange.H24, NEW_YORK, 8);
		ascendingInRange(t, t0, t1);
		for (String label : t.labels)
		{
			assertTrue(label, Integer.parseInt(label.substring(0, 2)) % 3 == 0 && label.endsWith(":00"));
		}
	}
}
