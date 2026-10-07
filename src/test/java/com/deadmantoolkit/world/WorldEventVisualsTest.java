package com.deadmantoolkit.world;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import com.deadmantoolkit.world.WorldEventMessage.Combat;
import com.deadmantoolkit.world.WorldEventMessage.Kind;
import com.deadmantoolkit.world.WorldLocations.Accuracy;
import com.google.gson.Gson;
import java.awt.image.BufferedImage;
import java.time.Instant;
import net.runelite.api.coords.WorldPoint;
import org.junit.Test;

/** Map / minimap icons, marker labels and where the minimap points. */
public class WorldEventVisualsTest
{
	private static final Instant T = Instant.parse("2026-10-03T14:15:40Z");
	private final WorldLocations loc = WorldLocations.load(new Gson());

	private WorldEvents.Event event(Kind kind, String place, Combat combat)
	{
		return new WorldEvents.Event(kind, place, combat, T, T.plusSeconds(3600), loc.resolve(place), WorldEvents.Source.SEEN, 0);
	}

	private static int alpha(BufferedImage img, int x, int y)
	{
		return img.getRGB(x, y) >>> 24;
	}

	@Test
	public void mapIconsAreBigEnoughAndPinsPointDown()
	{
		BufferedImage pin = WorldEventIcons.pin(Kind.BREACH, Accuracy.EXACT);
		assertTrue("visible on the map", pin.getWidth() >= 26 && pin.getHeight() >= 36);
		// The pointer's tip is at the bottom centre (the spot); the bottom corners are empty.
		assertTrue(alpha(pin, pin.getWidth() / 2, pin.getHeight() - 3) > 0);
		assertEquals(0, alpha(pin, 0, pin.getHeight() - 1));
		assertTrue(WorldEventIcons.minimap(Kind.CHEST, Accuracy.EXACT).getWidth() >= 13);
		assertNotSame(WorldEventIcons.pin(Kind.CHEST, Accuracy.EXACT), WorldEventIcons.pin(Kind.CHEST, Accuracy.REGION));
	}

	@Test
	public void edgeArrowsTurnTowardsTheEvent()
	{
		assertEquals(0, WorldEventIcons.direction(0));
		assertEquals(4, WorldEventIcons.direction(Math.PI / 2));
		assertEquals(8, WorldEventIcons.direction(Math.PI));
		assertEquals(12, WorldEventIcons.direction(-Math.PI / 2));
		assertEquals(0, WorldEventIcons.direction(2 * Math.PI - 0.01));
		// Pointing right: the arrow's tip near the right edge, nothing at the same spot on the left.
		BufferedImage right = WorldEventIcons.edge(Kind.CHEST, Accuracy.EXACT, 0);
		int mid = right.getHeight() / 2;
		assertTrue(alpha(right, right.getWidth() - 3, mid) > 0);
		assertEquals(0, alpha(right, 2, mid));
		BufferedImage left = WorldEventIcons.edge(Kind.CHEST, Accuracy.EXACT, Math.PI);
		assertTrue(alpha(left, 2, mid) > 0);
		assertSame(right, WorldEventIcons.edge(Kind.CHEST, Accuracy.EXACT, 0.05));
	}

	@Test
	public void labelsSayWhatAndWhere()
	{
		assertEquals("Breach (Multi): North of Edgeville", WorldEventMarkers.label(event(Kind.BREACH, "North of Edgeville", Combat.MULTI), null));
		assertEquals("Breach (Single): North of Asgarnia (somewhere in Asgarnia)",
			WorldEventMarkers.label(event(Kind.BREACH, "North of Asgarnia", Combat.SINGLE), null));
		assertEquals("Deadman's Chest: North of Edge Monastry (read as North of the Edgeville Monastery)",
			WorldEventMarkers.label(event(Kind.CHEST, "North of Edge Monastry", null), null));
		assertEquals("Breach (Single): Zanaris - enter at Lumbridge Swamp shed",
			WorldEventMarkers.label(event(Kind.BREACH, "Zanaris", Combat.SINGLE), "Lumbridge Swamp shed"));
	}

	@Test
	public void minimapPointsAtTheEntranceOfAnotherMap()
	{
		WorldPoint surface = new WorldPoint(3200, 3200, 0);
		WorldEvents.Event zanaris = event(Kind.BREACH, "Zanaris", null);
		assertEquals(zanaris.getWhere().getEntrance(), WorldEventMinimapOverlay.target(zanaris, surface));
		// Inside Zanaris itself: its spot.
		assertEquals(zanaris.getWhere().getPoint(), WorldEventMinimapOverlay.target(zanaris, new WorldPoint(2410, 4440, 0)));
		// A surface event seen from a dungeon: nothing to point at.
		WorldEvents.Event edge = event(Kind.BREACH, "North of Edgeville", null);
		assertNull(WorldEventMinimapOverlay.target(edge, new WorldPoint(3100, 9900, 0)));
		assertEquals(edge.getWhere().getPoint(), WorldEventMinimapOverlay.target(edge, surface));
		assertNull(WorldEventMinimapOverlay.target(event(Kind.CHEST, "the Whispering Glade", null), surface));
	}
}
