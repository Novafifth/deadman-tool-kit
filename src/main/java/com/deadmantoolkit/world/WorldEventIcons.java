package com.deadmantoolkit.world;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;

/**
 * World event icons, drawn in code (the plugin ships no image files):
 * <ul>
 * <li>{@link #of}: the small panel icon;</li>
 * <li>{@link #pin}: the world map marker, a round badge on a pointer whose tip is the spot (like clue markers);</li>
 * <li>{@link #edge}: the badge with an arrow on its rim, for a marker pinned to the edge of the world map, turned
 * towards the event ({@value #DIRECTIONS} directions);</li>
 * <li>{@link #minimap}: a small badge for the minimap.</li>
 * </ul>
 * Region-only markers carry a "?". All images are made once and never changed afterwards, so any thread may use them.
 */
public final class WorldEventIcons
{
	public static final int SIZE = 15;
	/** World map badge diameter. */
	static final int BADGE = 26;
	/** Pin: badge plus pointer. */
	static final int PIN_W = BADGE + 4;
	static final int PIN_H = BADGE + 16;
	/** Edge marker: badge plus the arrow around it. */
	static final int EDGE = BADGE + 18;
	static final int MINIMAP = 15;
	static final int DIRECTIONS = 16;

	static final Color CHEST_GOLD = new Color(0xf2c230);
	static final Color CHEST_WOOD = new Color(0x8a5524);
	static final Color CHEST_BG = new Color(0x3a2410);
	static final Color BREACH_PURPLE = new Color(0xb45cff);
	static final Color BREACH_BG = new Color(0x2a0a40);
	static final Color OUTLINE = new Color(0x111111);

	private static final int CHEST = 0;
	private static final int BREACH = 1;

	/** [kind][rough] */
	private static final BufferedImage[][] PANEL = new BufferedImage[2][2];
	private static final BufferedImage[][] PIN = new BufferedImage[2][2];
	private static final BufferedImage[][] MINI = new BufferedImage[2][2];
	/** [kind][rough][direction] */
	private static final BufferedImage[][][] EDGES = new BufferedImage[2][2][DIRECTIONS];

	static
	{
		for (int k = 0; k < 2; k++)
		{
			for (int r = 0; r < 2; r++)
			{
				boolean rough = r == 1;
				PANEL[k][r] = panel(k, rough);
				PIN[k][r] = pin(k, rough);
				MINI[k][r] = badgeImage(k, rough, MINIMAP);
				for (int d = 0; d < DIRECTIONS; d++)
				{
					EDGES[k][r][d] = edge(k, rough, d * 2 * Math.PI / DIRECTIONS);
				}
			}
		}
	}

	private WorldEventIcons()
	{
	}

	private static int kind(WorldEventMessage.Kind kind)
	{
		return kind == WorldEventMessage.Kind.CHEST ? CHEST : BREACH;
	}

	private static int rough(WorldLocations.Accuracy accuracy)
	{
		return accuracy == WorldLocations.Accuracy.REGION ? 1 : 0;
	}

	/** The panel icon ({@value #SIZE} px); "rough" (a "?") when only the region is known. */
	public static BufferedImage of(WorldEventMessage.Kind kind, WorldLocations.Accuracy accuracy)
	{
		return PANEL[kind(kind)][rough(accuracy)];
	}

	/** The world map marker; its pointer tip (bottom centre) is the spot. */
	public static BufferedImage pin(WorldEventMessage.Kind kind, WorldLocations.Accuracy accuracy)
	{
		return PIN[kind(kind)][rough(accuracy)];
	}

	/** The marker at the edge of the world map, its arrow pointing at {@code angle} (radians, screen: 0 = right, y down). */
	public static BufferedImage edge(WorldEventMessage.Kind kind, WorldLocations.Accuracy accuracy, double angle)
	{
		return EDGES[kind(kind)][rough(accuracy)][direction(angle)];
	}

	public static BufferedImage minimap(WorldEventMessage.Kind kind, WorldLocations.Accuracy accuracy)
	{
		return MINI[kind(kind)][rough(accuracy)];
	}

	/** The nearest of the {@value #DIRECTIONS} directions to {@code angle}. */
	static int direction(double angle)
	{
		double turn = angle / (2 * Math.PI);
		int d = (int) Math.round((turn - Math.floor(turn)) * DIRECTIONS);
		return d % DIRECTIONS;
	}

	/** The colour of a kind (pointers, arrows, the panel's edge). */
	public static Color color(WorldEventMessage.Kind kind)
	{
		return kind == WorldEventMessage.Kind.CHEST ? CHEST_GOLD : BREACH_PURPLE;
	}

	/* ---------------------------------------------------------------- drawing */

	private static Graphics2D start(BufferedImage img)
	{
		Graphics2D g = img.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
		g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
		return g;
	}

	/** The 15 px panel icon: the glyph alone. */
	private static BufferedImage panel(int kind, boolean rough)
	{
		BufferedImage img = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = start(img);
		glyph(g, kind);
		if (rough)
		{
			question(g, 8, 8, 7);
		}
		g.dispose();
		return img;
	}

	/** A round badge of diameter {@code d} with the glyph in it. */
	private static BufferedImage badgeImage(int kind, boolean rough, int d)
	{
		BufferedImage img = new BufferedImage(d, d, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = start(img);
		badge(g, kind, rough, 0, 0, d);
		g.dispose();
		return img;
	}

	private static BufferedImage pin(int kind, boolean rough)
	{
		BufferedImage img = new BufferedImage(PIN_W, PIN_H, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = start(img);
		double cx = PIN_W / 2.0;
		// Pointer from under the badge to the spot.
		Path2D p = new Path2D.Double();
		p.moveTo(cx - 7, BADGE - 4);
		p.lineTo(cx + 7, BADGE - 4);
		p.lineTo(cx, PIN_H - 1);
		p.closePath();
		g.setColor(OUTLINE);
		g.setStroke(new BasicStroke(3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		g.draw(p);
		g.setColor(colorOf(kind));
		g.fill(p);
		badge(g, kind, rough, (PIN_W - BADGE) / 2.0, 1, BADGE);
		g.dispose();
		return img;
	}

	private static BufferedImage edge(int kind, boolean rough, double angle)
	{
		BufferedImage img = new BufferedImage(EDGE, EDGE, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = start(img);
		double c = EDGE / 2.0;
		// The arrow: its tip near the image's edge, its base under the badge.
		Path2D a = new Path2D.Double();
		a.moveTo(c + (EDGE / 2.0 - 1), c);
		a.lineTo(c + BADGE / 2.0 - 3, c - 8);
		a.lineTo(c + BADGE / 2.0 - 3, c + 8);
		a.closePath();
		Shape arrow = AffineTransform.getRotateInstance(angle, c, c).createTransformedShape(a);
		g.setColor(OUTLINE);
		g.setStroke(new BasicStroke(3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		g.draw(arrow);
		g.setColor(colorOf(kind));
		g.fill(arrow);
		badge(g, kind, rough, c - BADGE / 2.0, c - BADGE / 2.0, BADGE);
		g.dispose();
		return img;
	}

	private static Color colorOf(int kind)
	{
		return kind == CHEST ? CHEST_GOLD : BREACH_PURPLE;
	}

	/** Dark outline, white ring, dark disc, the glyph scaled into it. */
	private static void badge(Graphics2D g, int kind, boolean rough, double x, double y, double d)
	{
		g.setColor(OUTLINE);
		g.fill(new Ellipse2D.Double(x, y, d, d));
		g.setColor(Color.WHITE);
		g.fill(new Ellipse2D.Double(x + 1.5, y + 1.5, d - 3, d - 3));
		g.setColor(kind == CHEST ? CHEST_BG : BREACH_BG);
		double inner = d - 6;
		g.fill(new Ellipse2D.Double(x + 3, y + 3, inner, inner));
		AffineTransform saved = g.getTransform();
		double glyph = inner * 0.86;
		g.translate(x + (d - glyph) / 2, y + (d - glyph) / 2);
		g.scale(glyph / SIZE, glyph / SIZE);
		glyph(g, kind);
		g.setTransform(saved);
		if (rough)
		{
			double q = Math.max(7, d * 0.42);
			question(g, x + d - q, y + d - q, q);
		}
	}

	/** The chest or breach drawing in a 15 x 15 box. */
	private static void glyph(Graphics2D g, int kind)
	{
		g.setStroke(new BasicStroke(1f));
		if (kind == CHEST)
		{
			g.setColor(CHEST_WOOD);
			g.fill(new RoundRectangle2D.Double(1.5, 6, 12, 7.5, 2, 2));
			g.fill(new RoundRectangle2D.Double(1.5, 2.5, 12, 4.5, 4, 4));
			g.setColor(OUTLINE);
			g.draw(new RoundRectangle2D.Double(1.5, 6, 12, 7.5, 2, 2));
			g.draw(new RoundRectangle2D.Double(1.5, 2.5, 12, 4.5, 4, 4));
			g.setColor(CHEST_GOLD);
			g.fill(new java.awt.geom.Rectangle2D.Double(4, 3, 1.2, 10));
			g.fill(new java.awt.geom.Rectangle2D.Double(9.8, 3, 1.2, 10));
			g.fill(new java.awt.geom.Rectangle2D.Double(6, 6, 3, 3));
		}
		else
		{
			g.setColor(OUTLINE);
			g.fill(new Ellipse2D.Double(0.5, 0.5, 14, 14));
			g.setColor(BREACH_PURPLE);
			g.fill(new Ellipse2D.Double(1.5, 1.5, 12, 12));
			g.setColor(new Color(0x1a0530));
			g.fill(new Ellipse2D.Double(4, 4, 7, 7));
			g.setColor(new Color(0xf0d8ff));
			g.setStroke(new BasicStroke(1.3f));
			g.drawArc(3, 3, 9, 9, 30, 200);
		}
	}

	/** A "?" badge of diameter {@code d} at ({@code x}, {@code y}): only the region is known. */
	private static void question(Graphics2D g, double x, double y, double d)
	{
		g.setColor(OUTLINE);
		g.fill(new Ellipse2D.Double(x, y, d, d));
		g.setColor(Color.WHITE);
		g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, (int) Math.round(d * 0.95)));
		java.awt.FontMetrics fm = g.getFontMetrics();
		g.drawString("?", (float) (x + (d - fm.stringWidth("?")) / 2), (float) (y + d / 2 + fm.getAscent() * 0.36));
	}
}
