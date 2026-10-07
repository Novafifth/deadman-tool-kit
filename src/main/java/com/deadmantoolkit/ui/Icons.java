package com.deadmantoolkit.ui;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.geom.Arc2D;
import java.awt.geom.Area;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import javax.swing.ImageIcon;
import net.runelite.client.ui.ColorScheme;

/**
 * Small icons drawn in code once, so the plugin ships no image files. EDT only (the caches aren't synchronized).
 */
final class Icons
{
	private static final int COG_SIZE = 14;
	private static final int COG_TEETH = 8;
	private static final int DOT_SIZE = 8;
	private static final int REFRESH_SIZE = 12;

	private static ImageIcon cog;
	private static ImageIcon dotOn;
	private static ImageIcon dotOff;
	private static ImageIcon refresh;

	private Icons()
	{
	}

	/** A settings cog. */
	static ImageIcon cog()
	{
		if (cog == null)
		{
			cog = new ImageIcon(drawCog(COG_SIZE, ColorScheme.LIGHT_GRAY_COLOR));
		}
		return cog;
	}

	/** A circular arrow, for refresh buttons. */
	static ImageIcon refresh()
	{
		if (refresh == null)
		{
			refresh = new ImageIcon(drawRefresh(REFRESH_SIZE, ColorScheme.LIGHT_GRAY_COLOR));
		}
		return refresh;
	}

	/** A filled dot (connected) or a ring (not connected) for the status line. */
	static ImageIcon statusDot(boolean on)
	{
		if (on)
		{
			if (dotOn == null)
			{
				dotOn = new ImageIcon(drawDot(DOT_SIZE, PanelComponents.GOOD, true));
			}
			return dotOn;
		}
		if (dotOff == null)
		{
			dotOff = new ImageIcon(drawDot(DOT_SIZE, ColorScheme.LIGHT_GRAY_COLOR, false));
		}
		return dotOff;
	}

	static BufferedImage drawCog(int size, Color color)
	{
		BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = img.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		double c = size / 2.0;
		double body = size * 0.34;
		double hole = size * 0.14;
		double toothW = size * 0.18;
		double toothLen = size * 0.48;
		Area cogArea = new Area(new Ellipse2D.Double(c - body, c - body, body * 2, body * 2));
		Rectangle2D tooth = new Rectangle2D.Double(c - toothW / 2, c - toothLen, toothW, toothLen);
		for (int i = 0; i < COG_TEETH; i++)
		{
			AffineTransform rot = AffineTransform.getRotateInstance(Math.PI * 2 * i / COG_TEETH, c, c);
			cogArea.add(new Area(rot.createTransformedShape(tooth)));
		}
		cogArea.subtract(new Area(new Ellipse2D.Double(c - hole, c - hole, hole * 2, hole * 2)));
		g.setColor(color);
		g.fill(cogArea);
		g.dispose();
		return img;
	}

	static BufferedImage drawRefresh(int size, Color color)
	{
		BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = img.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setColor(color);
		float stroke = Math.max(1.5f, size / 7f);
		g.setStroke(new BasicStroke(stroke, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER));
		double r = size / 2.0 - stroke;
		double c = size / 2.0;
		// Three quarters of a circle, open at the top right, with an arrowhead at the open end.
		g.draw(new Arc2D.Double(c - r, c - r, r * 2, r * 2, 70, 280, Arc2D.OPEN));
		double a = Math.toRadians(70);
		double ex = c + r * Math.cos(a), ey = c - r * Math.sin(a);
		double head = size * 0.32;
		Path2D.Double arrow = new Path2D.Double();
		arrow.moveTo(ex + head * 0.9, ey - head * 0.15);
		arrow.lineTo(ex - head * 0.25, ey - head * 0.75);
		arrow.lineTo(ex - head * 0.1, ey + head * 0.6);
		arrow.closePath();
		g.fill(arrow);
		g.dispose();
		return img;
	}

	private static BufferedImage drawDot(int size, Color color, boolean filled)
	{
		BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = img.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setColor(color);
		if (filled)
		{
			g.fillOval(0, 0, size - 1, size - 1);
		}
		else
		{
			g.drawOval(1, 1, size - 3, size - 3);
		}
		g.dispose();
		return img;
	}
}
