package com.deadmantoolkit.world;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.runelite.api.Client;
import net.runelite.api.Point;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.worldmap.WorldMap;
import net.runelite.client.ui.overlay.worldmap.WorldMapPoint;
import net.runelite.client.ui.overlay.worldmap.WorldMapPointManager;

/**
 * Puts world events on the world map, like clue and quest markers: a pin whose tip is the spot; off screen, a badge
 * pinned to the edge of the map with an arrow turned towards the event (clicking it moves the map there). The label
 * ("Breach (Multi): North of Edgeville") is the marker's name, which RuneLite shows as "Focus on ..." when hovered;
 * there is no separate tooltip to overlap it. A place on another map (Zanaris) also gets a marker at its surface
 * entrance. Optionally sets the game's hint arrow to one event; only clears a hint arrow it set itself.
 * Client thread only.
 */
public final class WorldEventMarkers
{
	/** Our map points, so only ours are ever removed. */
	static final class EventPoint extends WorldMapPoint
	{
		private final WorldEventMessage.Kind kind;
		private final WorldLocations.Accuracy accuracy;

		EventPoint(WorldPoint at, WorldEventMessage.Kind kind, WorldLocations.Accuracy accuracy, String label)
		{
			super(at, WorldEventIcons.pin(kind, accuracy));
			this.kind = kind;
			this.accuracy = accuracy;
			setImagePoint(pinTip());
			setTarget(at);
			setSnapToEdge(true);
			setJumpOnClick(true);
			setName(label);
		}

		private static Point pinTip()
		{
			return new Point(WorldEventIcons.PIN_W / 2, WorldEventIcons.PIN_H);
		}

		@Override
		public void onEdgeSnap()
		{
			// Centred badge; the arrow is turned towards the event on every frame (updateEdges).
			setImagePoint(null);
		}

		@Override
		public void onEdgeUnsnap()
		{
			setImage(WorldEventIcons.pin(kind, accuracy));
			setImagePoint(pinTip());
		}

		/** Turn the edge arrow towards the event, seen from the middle of the shown map. */
		void pointFrom(Point mapCentre)
		{
			WorldPoint at = getWorldPoint();
			// Screen angle: x to the right, y down (the map's north is up).
			double angle = Math.atan2(-(at.getY() - mapCentre.getY()), at.getX() - mapCentre.getX());
			BufferedImage img = WorldEventIcons.edge(kind, accuracy, angle);
			if (img != getImage())
			{
				setImage(img);
			}
		}
	}

	private final WorldMapPointManager manager;
	private final Client client;
	private final List<EventPoint> shown = new ArrayList<>();
	/** The hint arrow target this class set, or null. */
	private WorldPoint arrow;

	public WorldEventMarkers(WorldMapPointManager manager, Client client)
	{
		this.manager = manager;
		this.client = client;
	}

	/**
	 * Show exactly these events.
	 *
	 * @param arrowTo where the hint arrow should point (an event's spot), or null for none
	 */
	public void show(List<WorldEvents.Event> events, WorldPoint arrowTo)
	{
		removePoints();
		for (WorldEvents.Event e : events)
		{
			WorldLocations.Resolved where = e.getWhere();
			if (where == null || !where.known())
			{
				continue;
			}
			add(new EventPoint(where.getPoint(), e.getKind(), where.getAccuracy(), label(e, null)));
			if (where.getEntrance() != null)
			{
				add(new EventPoint(where.getEntrance(), e.getKind(), where.getAccuracy(), label(e, where.getEntranceName())));
			}
		}
		setArrow(arrowTo);
	}

	public void clear()
	{
		removePoints();
		setArrow(null);
	}

	/** World map open (overlay, every frame): keep the arrows of edge-pinned markers turned towards their events. */
	public void updateEdges(WorldMap map)
	{
		if (shown.isEmpty() || map == null)
		{
			return;
		}
		Point centre = map.getWorldMapPosition();
		if (centre == null)
		{
			return;
		}
		for (EventPoint p : shown)
		{
			if (p.isCurrentlyEdgeSnapped())
			{
				p.pointFrom(centre);
			}
		}
	}

	/** For tests and the overlay. */
	List<EventPoint> shown()
	{
		return Collections.unmodifiableList(shown);
	}

	private void add(EventPoint p)
	{
		manager.add(p);
		shown.add(p);
	}

	private void removePoints()
	{
		if (!shown.isEmpty())
		{
			manager.removeIf(p -> p instanceof EventPoint);
			shown.clear();
		}
	}

	/**
	 * The marker's label: "Deadman's Chest: north of the Warriors' Guild", "Breach (Multi): North of Edgeville",
	 * with how rough it is ("~ somewhere in Asgarnia") or, for an entrance, where you go in.
	 */
	static String label(WorldEvents.Event e, String entranceName)
	{
		StringBuilder sb = new StringBuilder(e.getKind() == WorldEventMessage.Kind.CHEST ? "Deadman's Chest" : "Breach");
		if (e.getCombat() != null)
		{
			sb.append(e.getCombat() == WorldEventMessage.Combat.MULTI ? " (Multi)" : " (Single)");
		}
		sb.append(": ").append(e.getLocation());
		if (entranceName != null)
		{
			sb.append(" - enter at ").append(entranceName);
		}
		else
		{
			String note = accuracyNote(e.getWhere());
			if (note != null)
			{
				sb.append(" (").append(note).append(')');
			}
		}
		return sb.toString();
	}

	/** How rough a marker is, in a few words; null when exact. */
	public static String accuracyNote(WorldLocations.Resolved where)
	{
		if (where == null)
		{
			return "place unknown";
		}
		switch (where.getAccuracy())
		{
			case APPROXIMATE:
				return "roughly, within " + where.getRadius() + " tiles";
			case REGION:
				return "somewhere in " + where.getPlace();
			case UNKNOWN:
				return "place unknown";
			default:
				return where.isFuzzy() ? "read as " + where.getPlace() : null;
		}
	}

	private void setArrow(WorldPoint to)
	{
		if (to != null && to.equals(arrow) && client.hasHintArrow())
		{
			return;
		}
		if (arrow != null && client.hasHintArrow() && arrow.equals(client.getHintArrowPoint()))
		{
			client.clearHintArrow();
		}
		arrow = null;
		if (to != null && !client.hasHintArrow())
		{
			client.setHintArrow(to);
			arrow = to;
		}
	}
}
