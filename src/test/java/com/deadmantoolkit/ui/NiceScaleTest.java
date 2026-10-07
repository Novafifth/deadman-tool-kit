package com.deadmantoolkit.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import java.util.HashSet;
import java.util.Set;
import org.junit.Test;

public class NiceScaleTest
{
	/** Ticks ascend, cover [lo, hi], never go below 0, and their labels are all different. */
	private static NiceScale check(long lo, long hi)
	{
		NiceScale s = NiceScale.of(lo, hi, 4);
		assertTrue("min " + s.min, s.min >= 0);
		assertTrue(s.min <= Math.min(lo, hi));
		assertTrue(s.max >= Math.max(lo, hi));
		assertTrue(s.max > s.min);
		assertTrue("tick count " + s.tickCount(), s.tickCount() >= 2 && s.tickCount() <= 7);
		Set<String> labels = new HashSet<>();
		for (int i = 0; i < s.tickCount(); i++)
		{
			if (i > 0)
			{
				assertTrue(s.tick(i) > s.tick(i - 1));
			}
			assertTrue("duplicate label " + Format.compactGp(s.tick(i), s.step),
				labels.add(Format.compactGp(s.tick(i), s.step)));
		}
		assertEquals(s.max, s.tick(s.tickCount() - 1));
		return s;
	}

	@Test
	public void smallRanges()
	{
		check(0, 1);
		check(998, 1003);
		check(1, 2);
		check(5, 5);
	}

	@Test
	public void flatValueGetsRoomAndDistinctLabels()
	{
		NiceScale s = check(1_250_000, 1_250_000);
		assertTrue(s.min < 1_250_000 && s.max > 1_250_000);
	}

	@Test
	public void singleSmallPoint()
	{
		NiceScale s = check(1, 1);
		assertEquals(0, s.min);
		assertTrue(s.max >= 2);
	}

	@Test
	public void nearZeroNeverNegative()
	{
		assertEquals(0, check(3, 1_000).min);
		assertEquals(0, check(0, 0).min);
	}

	@Test
	public void niceSteps()
	{
		NiceScale s = check(1_210_000, 1_340_000);
		assertEquals(50_000, s.step);
		assertEquals(1_200_000, s.min);
		assertEquals(1_350_000, s.max);
	}

	@Test
	public void billions()
	{
		NiceScale s = check(1_200_000_000L, 3_400_000_000L);
		assertTrue(Format.compactGp(s.tick(1), s.step).endsWith("B"));
		check(2_147_000_000L, 2_147_000_001L);
		check(Long.MAX_VALUE / 4, Long.MAX_VALUE / 4 + 1_000);
	}

	@Test
	public void reversedInput()
	{
		NiceScale a = NiceScale.of(10, 500, 4), b = NiceScale.of(500, 10, 4);
		assertEquals(a.min, b.min);
		assertEquals(a.max, b.max);
	}
}
