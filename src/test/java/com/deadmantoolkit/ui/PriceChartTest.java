package com.deadmantoolkit.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import com.deadmantoolkit.MarketData;
import com.google.gson.Gson;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

public class PriceChartTest
{
	private static final Gson GSON = new Gson();
	private static final long NOW = 1_800_000_000L;

	private static MarketData.Point point(long ts, Long buy, Long sell, long vol)
	{
		return GSON.fromJson("{\"timestamp\":" + ts + (buy == null ? "" : ",\"avgBuy\":" + buy)
			+ (sell == null ? "" : ",\"avgSell\":" + sell) + ",\"buyVolume\":" + vol + ",\"sellVolume\":0}", MarketData.Point.class);
	}

	private static void paint(PriceChart c, int w)
	{
		c.setSize(w, c.getPreferredSize().height);
		BufferedImage img = new BufferedImage(w, c.getPreferredSize().height, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = img.createGraphics();
		c.paint(g);
		g.dispose();
	}

	@Test
	public void nearestIndex()
	{
		double[] xs = {10, 20, 40};
		assertEquals(-1, PriceChart.nearestIndex(new double[0], 5));
		assertEquals(0, PriceChart.nearestIndex(xs, -100));
		assertEquals(0, PriceChart.nearestIndex(xs, 14));
		assertEquals(1, PriceChart.nearestIndex(xs, 16));
		assertEquals(1, PriceChart.nearestIndex(xs, 29));
		assertEquals(2, PriceChart.nearestIndex(xs, 31));
		assertEquals(2, PriceChart.nearestIndex(xs, 1000));
	}

	@Test
	public void states()
	{
		PriceChart c = new PriceChart();
		paint(c, 205);
		assertEquals(PriceChart.LOADING, c.shownMessage());

		c.setData(Collections.emptyList(), ChartRange.D7, NOW);
		paint(c, 205);
		assertEquals("No trades in the last 7 days", c.shownMessage());

		// Only data older than the range: also empty.
		c.setData(Collections.singletonList(point(NOW - 8 * 86_400, 5L, null, 1)), ChartRange.D7, NOW);
		paint(c, 205);
		assertEquals("No trades in the last 7 days", c.shownMessage());

		c.setUnavailable(ChartRange.D7);
		paint(c, 205);
		assertEquals(PriceChart.UNAVAILABLE, c.shownMessage());
		assertFalse(c.hasData());

		c.setData(Collections.singletonList(point(NOW - 3600, 1_250_000L, null, 3)), ChartRange.D7, NOW);
		paint(c, 100);
		assertEquals(PriceChart.TOO_NARROW, c.shownMessage());
		paint(c, 205);
		assertNull(c.shownMessage());
		assertTrue(c.hasData());
	}

	@Test
	public void sameDataDoesNotRelayout()
	{
		PriceChart c = new PriceChart();
		List<MarketData.Point> pts = Arrays.asList(point(NOW - 7200, 100L, 110L, 2), point(NOW - 3600, 101L, null, 1));
		c.setData(pts, ChartRange.H24, NOW);
		paint(c, 205);
		assertFalse(c.layoutPending());
		// A refresh 20 s later with equal (separately parsed) data.
		c.setData(new ArrayList<>(Arrays.asList(point(NOW - 7200, 100L, 110L, 2), point(NOW - 3600, 101L, null, 1))),
			ChartRange.H24, NOW + 20 - NOW % 60);
		assertFalse(c.layoutPending());
		c.setData(pts, ChartRange.D7, NOW);
		assertTrue(c.layoutPending());
	}

	@Test
	public void paintsAllShapesOfData()
	{
		for (ChartRange r : ChartRange.values())
		{
			PriceChart c = new PriceChart();
			List<MarketData.Point> pts = new ArrayList<>();
			// Runs, gaps, lone points, nulls, flat values, billions.
			long step = r.stepSeconds();
			pts.add(point(NOW - r.seconds() + step, 2_100_000_000L, 2_150_000_000L, 1));
			pts.add(point(NOW - r.seconds() + 2 * step, 2_100_000_000L, null, 1_000_000));
			pts.add(point(NOW - r.seconds() / 2, null, 2_000_000_000L, 5));
			pts.add(point(NOW - step, 2_100_000_000L, 2_100_000_000L, 0));
			c.setData(pts, r, NOW);
			for (int w : new int[]{120, 205, 400, 1000})
			{
				paint(c, w);
				assertNull(c.shownMessage());
			}
		}
	}

	@Test
	public void singlePointAndFlatData()
	{
		PriceChart c = new PriceChart();
		c.setData(Collections.singletonList(point(NOW - 60, 7L, 7L, 1)), ChartRange.H24, NOW);
		paint(c, 205);
		assertNull(c.shownMessage());
	}

	@Test
	public void gutterDoesNotDependOnVolume()
	{
		int[] widths = new int[3];
		long[] vols = {3, 1_000_000, 2_000_000_000L};
		for (int i = 0; i < vols.length; i++)
		{
			PriceChart c = new PriceChart();
			c.setData(Arrays.asList(point(NOW - 7200, 1_240_000L, 1_300_000L, vols[i]), point(NOW - 3600, 1_260_000L, null, 1)),
				ChartRange.H24, NOW);
			paint(c, 205);
			widths[i] = c.gutter();
		}
		assertEquals(widths[0], widths[1]);
		assertEquals(widths[0], widths[2]);
	}
}
