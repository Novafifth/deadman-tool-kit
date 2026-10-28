package com.deadmantoolkit.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import com.deadmantoolkit.world.Stars;
import com.deadmantoolkit.world.WorldEventMessage;
import com.deadmantoolkit.world.WorldEvents;
import com.deadmantoolkit.world.WorldLocations;
import java.time.Instant;
import net.runelite.api.coords.WorldPoint;
import org.junit.Test;

/** A shooting star's line in the panel: its tier and place, and how long it has left at most. */
public class WorldEventsListTest
{
	@Test
	public void aStarLine()
	{
		Instant now = Instant.now();
		WorldPoint at = new WorldPoint(3254, 3418, 0);
		WorldEvents.Event star = new WorldEvents.Event(WorldEventMessage.Kind.STAR, "near Varrock East Bank", null, now,
			Stars.goneBy(4, now), new WorldLocations.Resolved(WorldLocations.Accuracy.EXACT, at, 0, "near Varrock East Bank", null,
			null, false), WorldEvents.Source.REPORTED, 2);
		assertEquals("T4 · Varrock East Bank", WorldEventsList.label(star));
		assertEquals("~28m left", WorldEventsList.timeText(star, now));
		assertEquals("~0m left", WorldEventsList.timeText(star, now.plusSeconds(28 * 60)));
		String tip = WorldEventsList.tooltip(star, false);
		assertTrue(tip, tip.contains("Tier 4 now") && tip.contains("Found by other players (2 reports)")
			&& tip.contains("Area not known yet") && tip.contains("Click to point the hint arrow here"));

		WorldEvents.Event guarded = new WorldEvents.Event(star.getKind(), star.getLocation(), null, star.getAt(), star.getUntil(),
			star.getWhere(), star.getSource(), star.getReporters(), "guarded");
		assertEquals("T4 · Guarded · Varrock East Bank", WorldEventsList.label(guarded));
		assertTrue(WorldEventsList.tooltip(guarded, false).contains("In a guarded area"));
	}
}
