package com.deadmantoolkit.ui;

import static com.deadmantoolkit.ui.PanelComponents.BUY;
import static com.deadmantoolkit.ui.PanelComponents.SELL;
import com.deadmantoolkit.MarketData;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Path2D;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import javax.swing.JComponent;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.util.QuantityFormatter;

/**
 * Average bought/sold price over the chosen range, with a separate volume strip underneath (its own scale, not a
 * second axis on the price plot). Hover shows a crosshair and the values at the nearest bucket.
 * <p>
 * The axis always spans the whole range ending now. Everything that depends on the data or the size (scale, labels,
 * paths, bars) is computed once per change in {@link #layout}; painting only draws the cached result. EDT only.
 */
class PriceChart extends JComponent
{
	private static final Color GRID = new Color(0x3a3a3a);
	private static final Color VOLUME = new Color(0x256abf);
	private static final BasicStroke LINE = new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
	private static final BasicStroke THIN = new BasicStroke(1f);
	private static final BasicStroke DASHED = new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 1f,
		new float[]{3f, 3f}, 0f);
	private static final DateTimeFormatter TIP_TIME = DateTimeFormatter.ofPattern("EEE d MMM HH:mm", Locale.ENGLISH);
	private static final int PRICE_H = 150;
	private static final int VOL_H = 36;
	private static final int GAP = 8;
	private static final int RIGHT = 6;
	private static final int TOP = 6;
	private static final int BOTTOM = 14;
	private static final int HEIGHT = TOP + PRICE_H + GAP + VOL_H + BOTTOM;
	/** Narrower than this and the chart shows a message instead. */
	static final int MIN_WIDTH = 120;
	private static final int Y_TICKS = 4;
	private static final float DOT = 5f;

	static final String UNAVAILABLE = "Chart unavailable";
	static final String LOADING = "Loading…";
	static final String TOO_NARROW = "Too narrow";

	private final Font font = FontManager.getRunescapeSmallFont();

	// Inputs.
	private List<MarketData.Point> points = Collections.emptyList();
	private ChartRange range = ChartRange.DEFAULT;
	private long now;
	/** Message instead of data (loading, unavailable), or null when showing data. */
	private String status = LOADING;

	// Layout, recomputed when dirty or resized.
	private boolean dirty = true;
	private int layoutW = -1;
	private int layoutH = -1;
	private String message;
	private int left;
	private String[] yLabels = new String[0];
	private int[] yLabelY = new int[0];
	private final Path2D.Float buyPath = new Path2D.Float();
	private final Path2D.Float sellPath = new Path2D.Float();
	/** Lone points (no neighbour close enough to join): x, y pairs. */
	private float[] buyDots = new float[0];
	private float[] sellDots = new float[0];
	private int[] barX = new int[0];
	private int[] barH = new int[0];
	private int barW = 1;
	private String volLabel = "";
	private int[] tickX = new int[0];
	private String[] tickLabels = new String[0];
	/** Per point: x, and the y of each price (NaN when there was none). For hover. */
	private double[] xs = new double[0];
	private float[] buyY = new float[0];
	private float[] sellY = new float[0];

	// Hover.
	private int hover = -1;
	/** Where the mouse is over the chart, or -1; a relayout (refresh) re-snaps the hover to it. */
	private int mouseX = -1;
	private String tipTime = "";
	private String tipBuy = "";
	private String tipSell = "";
	private int tipW;

	PriceChart()
	{
		setPreferredSize(new Dimension(0, HEIGHT));
		setMinimumSize(new Dimension(0, HEIGHT));
		setMaximumSize(new Dimension(Integer.MAX_VALUE, HEIGHT));
		setOpaque(false);
		MouseAdapter mouse = new MouseAdapter()
		{
			@Override
			public void mouseMoved(MouseEvent e)
			{
				mouseX = e.getX();
				setHover(nearest(mouseX));
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				mouseX = -1;
				setHover(-1);
			}
		};
		addMouseListener(mouse);
		addMouseMotionListener(mouse);
	}

	/**
	 * Show {@code data} over {@code range} ending at {@code now} (unix s). Does nothing when the trimmed data, the range
	 * and the minute of {@code now} are all unchanged, so a periodic refresh with the same data doesn't repaint.
	 */
	void setData(List<MarketData.Point> data, ChartRange range, long now)
	{
		List<MarketData.Point> trimmed = range.trim(data, now);
		if (status == null && range == this.range && now / 60 == this.now / 60 && trimmed.equals(points))
		{
			return;
		}
		this.points = trimmed;
		this.range = range;
		this.now = now;
		this.status = null;
		invalidateLayout();
	}

	/** Show a loading message for {@code range} (a new item or range), dropping the old data. */
	void setLoading(ChartRange range)
	{
		setStatus(range, LOADING);
	}

	/** The series couldn't be loaded and there's nothing to show for this range. */
	void setUnavailable(ChartRange range)
	{
		setStatus(range, UNAVAILABLE);
	}

	private void setStatus(ChartRange range, String status)
	{
		if (status.equals(this.status) && range == this.range)
		{
			return;
		}
		this.range = range;
		this.status = status;
		this.points = Collections.emptyList();
		invalidateLayout();
	}

	/** True when real data (possibly empty) is shown, rather than a loading or error message. */
	boolean hasData()
	{
		return status == null;
	}

	/** The message shown instead of the chart as of the last paint, or null if it drew data. For tests. */
	String shownMessage()
	{
		return message;
	}

	/** The left gutter (y label) width as of the last layout. For tests. */
	int gutter()
	{
		return left;
	}

	/** True when the next paint recomputes the layout. For tests. */
	boolean layoutPending()
	{
		return dirty;
	}

	private void invalidateLayout()
	{
		dirty = true;
		hover = -1;
		repaint();
	}

	/** The index of the bucket nearest to {@code mx}, or -1. Binary search over the laid-out x positions. */
	private int nearest(int mx)
	{
		double[] x = xs;
		if (dirty || message != null || x.length == 0)
		{
			return -1;
		}
		return nearestIndex(x, mx);
	}

	/** Index of the value in ascending {@code xs} closest to {@code target}; -1 if empty. Pure. */
	static int nearestIndex(double[] xs, double target)
	{
		if (xs.length == 0)
		{
			return -1;
		}
		int lo = 0, hi = xs.length - 1;
		while (lo < hi)
		{
			int mid = (lo + hi) >>> 1;
			if (xs[mid] < target)
			{
				lo = mid + 1;
			}
			else
			{
				hi = mid;
			}
		}
		if (lo > 0 && Math.abs(xs[lo - 1] - target) <= Math.abs(xs[lo] - target))
		{
			return lo - 1;
		}
		return lo;
	}

	/** Change the hovered bucket; the tooltip text is built here, only when it changes, not while painting. */
	private void setHover(int idx)
	{
		if (idx == hover)
		{
			return;
		}
		buildTip(idx, getFontMetrics(font));
		repaint();
	}

	/** Set the hovered bucket and build its tooltip text (no repaint). */
	private void buildTip(int idx, FontMetrics fm)
	{
		hover = idx;
		if (idx >= 0 && idx < points.size())
		{
			MarketData.Point p = points.get(idx);
			tipTime = TIP_TIME.format(Instant.ofEpochSecond(p.getTimestamp()).atZone(ZoneId.systemDefault()));
			tipBuy = "Bought " + Format.gp(p.getAvgBuy()) + " (" + QuantityFormatter.formatNumber(p.getBuyVolume()) + ")";
			tipSell = "Sold " + Format.gp(p.getAvgSell()) + " (" + QuantityFormatter.formatNumber(p.getSellVolume()) + ")";
			tipW = Math.max(fm.stringWidth(tipTime), Math.max(fm.stringWidth(tipBuy), fm.stringWidth(tipSell)) + 12) + 12;
		}
	}

	/* ------------------------------------------------------------ layout */

	private void layout(int w, int h, FontMetrics fm)
	{
		dirty = false;
		layoutW = w;
		layoutH = h;
		message = null;
		xs = new double[0];
		if (w < MIN_WIDTH)
		{
			message = TOO_NARROW;
			return;
		}
		if (status != null)
		{
			message = status;
			return;
		}
		long lo = Long.MAX_VALUE, hi = Long.MIN_VALUE, vmax = 0;
		for (MarketData.Point p : points)
		{
			if (p.getAvgBuy() != null)
			{
				lo = Math.min(lo, p.getAvgBuy());
				hi = Math.max(hi, p.getAvgBuy());
			}
			if (p.getAvgSell() != null)
			{
				lo = Math.min(lo, p.getAvgSell());
				hi = Math.max(hi, p.getAvgSell());
			}
			vmax = Math.max(vmax, p.getBuyVolume() + p.getSellVolume());
		}
		if (lo == Long.MAX_VALUE)
		{
			message = "No trades in the last " + range.longLabel();
			return;
		}

		// Y axis: nice ticks, and a gutter as wide as the widest label.
		NiceScale scale = NiceScale.of(lo, hi, Y_TICKS);
		int n = scale.tickCount();
		yLabels = new String[n];
		yLabelY = new int[n];
		int labelW = 0;
		for (int i = 0; i < n; i++)
		{
			yLabels[i] = Format.compactGp(scale.tick(i), scale.step);
			labelW = Math.max(labelW, fm.stringWidth(yLabels[i]));
		}
		// Compact on its own ("1M", "2B"): it shares the gutter, so a big volume mustn't squeeze the plot.
		volLabel = vmax > 0 ? Format.compactAmount(vmax) : "";
		labelW = Math.max(labelW, fm.stringWidth(volLabel));
		left = labelW + 6;
		int iw = Math.max(1, w - left - RIGHT);
		for (int i = 0; i < n; i++)
		{
			yLabelY[i] = Math.round(y(scale.tick(i), scale));
		}

		// X positions: the middle of each bucket, on an axis spanning the whole range.
		long t1 = now, t0 = now - range.seconds();
		int count = points.size();
		xs = new double[count];
		buyY = new float[count];
		sellY = new float[count];
		for (int i = 0; i < count; i++)
		{
			MarketData.Point p = points.get(i);
			xs[i] = x(p.getTimestamp() + range.stepSeconds() / 2, t0, t1, iw);
			buyY[i] = p.getAvgBuy() == null ? Float.NaN : y(p.getAvgBuy(), scale);
			sellY[i] = p.getAvgSell() == null ? Float.NaN : y(p.getAvgSell(), scale);
		}
		long maxGap = range.maxGapBuckets() * range.stepSeconds();
		buyDots = buildPath(buyPath, buyY, maxGap);
		sellDots = buildPath(sellPath, sellY, maxGap);

		// Volume bars: as wide as a bucket (at most 10px), at least 1px.
		double slot = iw / (double) Math.max(1, range.seconds() / range.stepSeconds());
		barW = (int) Math.max(1, Math.min(10, slot > 4 ? slot - 1 : slot));
		barX = new int[count];
		barH = new int[count];
		for (int i = 0; i < count; i++)
		{
			MarketData.Point p = points.get(i);
			long vol = p.getBuyVolume() + p.getSellVolume();
			barX[i] = (int) Math.round(xs[i] - barW / 2.0);
			barH[i] = vol <= 0 || vmax <= 0 ? 0 : (int) Math.max(1, Math.round((double) vol / vmax * VOL_H));
		}

		// Time labels at round local times, as many as fit.
		int maxLabels = iw / (fm.stringWidth(TimeTicks.sampleLabel(range)) + 8);
		TimeTicks ticks = TimeTicks.of(t0, t1, range, ZoneId.systemDefault(), maxLabels);
		tickLabels = ticks.labels;
		tickX = new int[ticks.ts.length];
		for (int i = 0; i < tickX.length; i++)
		{
			int cx = (int) Math.round(x(ticks.ts[i], t0, t1, iw));
			int lw = fm.stringWidth(tickLabels[i]);
			tickX[i] = Math.max(0, Math.min(w - 1 - lw, cx - lw / 2));
		}

		// A refresh re-lays out under a resting mouse: keep its tooltip, snapped to the new buckets.
		if (mouseX >= 0 && count > 0)
		{
			buildTip(nearestIndex(xs, mouseX), fm);
		}
	}

	/** Fill {@code path} with one line per run of points that are close enough in time; returns the lone points. */
	private float[] buildPath(Path2D.Float path, float[] ys, long maxGap)
	{
		path.reset();
		List<Integer> idx = new ArrayList<>();
		for (int i = 0; i < ys.length; i++)
		{
			if (!Float.isNaN(ys[i]))
			{
				idx.add(i);
			}
		}
		float[] dots = new float[idx.size() * 2];
		int d = 0;
		for (int j = 0; j < idx.size(); j++)
		{
			int i = idx.get(j);
			boolean joinPrev = j > 0 && close(idx.get(j - 1), i, maxGap);
			boolean joinNext = j + 1 < idx.size() && close(i, idx.get(j + 1), maxGap);
			if (joinPrev)
			{
				path.lineTo(xs[i], ys[i]);
			}
			else
			{
				path.moveTo(xs[i], ys[i]);
			}
			if (!joinPrev && !joinNext)
			{
				dots[d++] = (float) xs[i];
				dots[d++] = ys[i];
			}
		}
		float[] out = new float[d];
		System.arraycopy(dots, 0, out, 0, d);
		return out;
	}

	private boolean close(int a, int b, long maxGap)
	{
		return points.get(b).getTimestamp() - points.get(a).getTimestamp() <= maxGap;
	}

	private double x(long ts, long t0, long t1, int iw)
	{
		double f = (double) (ts - t0) / Math.max(1, t1 - t0);
		return left + Math.max(0, Math.min(1, f)) * iw;
	}

	private static float y(long v, NiceScale s)
	{
		return (float) (TOP + PRICE_H - (double) (v - s.min) / Math.max(1, s.max - s.min) * PRICE_H);
	}

	/* ------------------------------------------------------------- paint */

	@Override
	protected void paintComponent(Graphics g0)
	{
		Graphics2D g = (Graphics2D) g0.create();
		try
		{
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
			g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
			g.setFont(font);
			FontMetrics fm = g.getFontMetrics();
			int w = getWidth(), h = getHeight();
			if (dirty || w != layoutW || h != layoutH)
			{
				layout(w, h, fm);
			}
			if (message != null)
			{
				g.setColor(ColorScheme.LIGHT_GRAY_COLOR);
				g.drawString(message, Math.max(0, (w - fm.stringWidth(message)) / 2), TOP + PRICE_H / 2);
				return;
			}
			paintChart(g, fm, w, h);
		}
		finally
		{
			g.dispose();
		}
	}

	private void paintChart(Graphics2D g, FontMetrics fm, int w, int h)
	{
		// Price grid and labels.
		g.setStroke(THIN);
		for (int i = 0; i < yLabels.length; i++)
		{
			g.setColor(GRID);
			g.drawLine(left, yLabelY[i], w - RIGHT, yLabelY[i]);
			g.setColor(ColorScheme.LIGHT_GRAY_COLOR);
			g.drawString(yLabels[i], left - 4 - fm.stringWidth(yLabels[i]), yLabelY[i] + fm.getAscent() / 2 - 1);
		}

		// Volume strip.
		int vBase = TOP + PRICE_H + GAP + VOL_H;
		g.setColor(GRID);
		g.drawLine(left, vBase, w - RIGHT, vBase);
		g.setColor(VOLUME);
		for (int i = 0; i < barX.length; i++)
		{
			if (barH[i] > 0)
			{
				g.fillRect(barX[i], vBase - barH[i], barW, barH[i]);
			}
		}
		g.setColor(ColorScheme.LIGHT_GRAY_COLOR);
		g.drawString(volLabel, left - 4 - fm.stringWidth(volLabel), vBase - VOL_H + fm.getAscent());

		// Price lines (broken across gaps) and lone points.
		g.setStroke(LINE);
		g.setColor(BUY);
		g.draw(buyPath);
		drawDots(g, buyDots);
		g.setColor(SELL);
		g.draw(sellPath);
		drawDots(g, sellDots);

		// Time labels.
		g.setColor(ColorScheme.LIGHT_GRAY_COLOR);
		for (int i = 0; i < tickX.length; i++)
		{
			g.drawString(tickLabels[i], tickX[i], h - 3);
		}

		if (hover >= 0 && hover < xs.length)
		{
			paintHover(g, fm, w, h);
		}
	}

	private static void drawDots(Graphics2D g, float[] dots)
	{
		for (int i = 0; i + 1 < dots.length; i += 2)
		{
			g.fillOval(Math.round(dots[i] - DOT / 2), Math.round(dots[i + 1] - DOT / 2), (int) DOT, (int) DOT);
		}
	}

	private void paintHover(Graphics2D g, FontMetrics fm, int w, int h)
	{
		int px = (int) Math.round(xs[hover]);
		g.setColor(ColorScheme.LIGHT_GRAY_COLOR);
		g.setStroke(DASHED);
		g.drawLine(px, TOP, px, TOP + PRICE_H + GAP + VOL_H);
		marker(g, px, buyY[hover], BUY);
		marker(g, px, sellY[hover], SELL);

		// Tooltip box, beside the crosshair and always inside the component.
		int th = fm.getHeight() * 3 + 8;
		int tw = Math.min(tipW, w - 1);
		int tx = px + 10;
		if (tx + tw > w - 1)
		{
			tx = px - 10 - tw;
		}
		tx = Math.max(0, Math.min(w - 1 - tw, tx));
		int ty = Math.max(0, Math.min(h - 1 - th, TOP + 2));
		g.setColor(ColorScheme.DARKER_GRAY_COLOR);
		g.fillRoundRect(tx, ty, tw, th, 6, 6);
		g.setColor(ColorScheme.MEDIUM_GRAY_COLOR);
		g.setStroke(THIN);
		g.drawRoundRect(tx, ty, tw, th, 6, 6);
		int line = ty + 4 + fm.getAscent();
		g.setColor(ColorScheme.LIGHT_GRAY_COLOR);
		g.drawString(tipTime, tx + 6, line);
		line += fm.getHeight();
		g.setColor(BUY);
		g.fillOval(tx + 6, line - fm.getAscent() / 2 - 3, 6, 6);
		g.setColor(ColorScheme.TEXT_COLOR);
		g.drawString(tipBuy, tx + 18, line);
		line += fm.getHeight();
		g.setColor(SELL);
		g.fillOval(tx + 6, line - fm.getAscent() / 2 - 3, 6, 6);
		g.setColor(ColorScheme.TEXT_COLOR);
		g.drawString(tipSell, tx + 18, line);
	}

	private static void marker(Graphics2D g, int px, float py, Color c)
	{
		if (Float.isNaN(py))
		{
			return;
		}
		int y = Math.round(py);
		g.setColor(ColorScheme.DARKER_GRAY_COLOR);
		g.fillOval(px - 5, y - 5, 10, 10);
		g.setColor(c);
		g.fillOval(px - 4, y - 4, 8, 8);
	}
}
