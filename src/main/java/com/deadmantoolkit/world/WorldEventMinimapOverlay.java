package com.deadmantoolkit.world;

import java.awt.BasicStroke;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.function.Supplier;
import net.runelite.api.Client;
import net.runelite.api.Perspective;
import net.runelite.api.Player;
import net.runelite.api.Point;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/**
 * World events on the minimap: an event within the minimap's reach is drawn at its spot; one further away gets an
 * arrow on the minimap's rim pointing towards it (the minimap turns with the camera; the arrows turn with it). A place
 * on another map is pointed at through its surface entrance. Region-only events point at the region's middle.
 */
public final class WorldEventMinimapOverlay extends Overlay
{
	/** Distance of the arrow from the minimap's centre, in pixels (the minimap's radius is about 73). */
	static final int RIM = 54;
	/** How far ahead the direction is sampled, in tiles. */
	private static final int STEP_TILES = 4;
	/** Surface world coordinates end about here (underground areas and other maps lie further north). */
	private static final int SURFACE_MAX_Y = 4200;

	private final Client client;
	/** The events to show now (empty when hidden, or not logged in on world 345). Client thread. */
	private final Supplier<List<WorldEvents.Event>> events;

	public WorldEventMinimapOverlay(Client client, Supplier<List<WorldEvents.Event>> events)
	{
		this.client = client;
		this.events = events;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
		setPriority(PRIORITY_HIGH);
	}

	@Override
	public Dimension render(Graphics2D g)
	{
		List<WorldEvents.Event> list = events.get();
		if (list.isEmpty())
		{
			return null;
		}
		Player me = client.getLocalPlayer();
		if (me == null)
		{
			return null;
		}
		LocalPoint here = me.getLocalLocation();
		WorldPoint at = me.getWorldLocation();
		if (here == null || at == null)
		{
			return null;
		}
		Point centre = Perspective.localToMinimap(client, here);
		if (centre == null)
		{
			return null; // minimap hidden
		}
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		for (WorldEvents.Event e : list)
		{
			WorldPoint target = target(e, at);
			if (target == null)
			{
				continue;
			}
			int dx = target.getX() - at.getX();
			int dy = target.getY() - at.getY();
			BufferedImage icon = WorldEventIcons.minimap(e.getKind(), e.getWhere().getAccuracy());
			Point spot = Perspective.localToMinimap(client,
				new LocalPoint(here.getX() + dx * Perspective.LOCAL_TILE_SIZE, here.getY() + dy * Perspective.LOCAL_TILE_SIZE,
					here.getWorldView()));
			if (spot != null && Math.hypot(spot.getX() - centre.getX(), spot.getY() - centre.getY()) <= RIM)
			{
				g.drawImage(icon, spot.getX() - icon.getWidth() / 2, spot.getY() - icon.getHeight() / 2, null);
				continue;
			}
			double len = Math.hypot(dx, dy);
			if (len < 1)
			{
				continue;
			}
			Point step = Perspective.localToMinimap(client, new LocalPoint(
				here.getX() + (int) Math.round(dx / len * STEP_TILES * Perspective.LOCAL_TILE_SIZE),
				here.getY() + (int) Math.round(dy / len * STEP_TILES * Perspective.LOCAL_TILE_SIZE), here.getWorldView()));
			if (step == null)
			{
				continue;
			}
			double vx = step.getX() - centre.getX();
			double vy = step.getY() - centre.getY();
			double vl = Math.hypot(vx, vy);
			if (vl < 0.5)
			{
				continue;
			}
			drawArrow(g, centre, vx / vl, vy / vl, e.getKind(), icon);
		}
		return null;
	}

	/** Where to point for {@code e} from {@code at}: its spot, or the surface entrance of a place on another map. */
	static WorldPoint target(WorldEvents.Event e, WorldPoint at)
	{
		WorldLocations.Resolved w = e.getWhere();
		if (w == null || !w.known())
		{
			return null;
		}
		WorldPoint p = w.getPoint();
		boolean meOnSurface = at.getY() < SURFACE_MAX_Y;
		boolean itOnSurface = p.getY() < SURFACE_MAX_Y;
		if (meOnSurface == itOnSurface)
		{
			// Underground both: only when it's the same area (a few hundred tiles at most).
			return itOnSurface || Math.abs(p.getX() - at.getX()) + Math.abs(p.getY() - at.getY()) < 400 ? p : null;
		}
		return meOnSurface ? w.getEntrance() : null;
	}

	/** An arrow on the rim in direction (ux, uy) with the event's badge just inside it. */
	private static void drawArrow(Graphics2D g, Point centre, double ux, double uy, WorldEventMessage.Kind kind,
		BufferedImage icon)
	{
		double bx = centre.getX() + ux * RIM;
		double by = centre.getY() + uy * RIM;
		// Perpendicular for the arrow's base.
		double px = -uy;
		double py = ux;
		Path2D a = new Path2D.Double();
		a.moveTo(bx + ux * 9, by + uy * 9);
		a.lineTo(bx + px * 6, by + py * 6);
		a.lineTo(bx - px * 6, by - py * 6);
		a.closePath();
		g.setStroke(new BasicStroke(2.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		g.setColor(WorldEventIcons.OUTLINE);
		g.draw(a);
		g.setColor(WorldEventIcons.color(kind));
		g.fill(a);
		double ix = bx - ux * (icon.getWidth() / 2.0 + 1);
		double iy = by - uy * (icon.getHeight() / 2.0 + 1);
		g.drawImage(icon, (int) Math.round(ix - icon.getWidth() / 2.0), (int) Math.round(iy - icon.getHeight() / 2.0), null);
	}
}
