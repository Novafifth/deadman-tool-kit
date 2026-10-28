package com.deadmantoolkit.world;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import com.deadmantoolkit.world.WorldEvents.Event;
import com.deadmantoolkit.world.WorldEvents.Source;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.ObjectID;
import org.junit.Test;

/** Shooting stars: tiers from the game objects, the 7-minute countdown, and one star per world in the event list. */
public class StarsTest
{
	private static final Instant T = Instant.parse("2026-10-21T18:03:00Z");
	private static final WorldPoint MINE = new WorldPoint(3258, 3408, 0);
	private static final WorldPoint OTHER = new WorldPoint(2940, 3280, 0);
	private final WorldEvents events = new WorldEvents();

	@Test
	public void tiersFromTheGameObjects()
	{
		int[] ids = {ObjectID.STAR_SIZE_ONE_STAR, ObjectID.STAR_SIZE_TWO_STAR, ObjectID.STAR_SIZE_THREE_STAR,
			ObjectID.STAR_SIZE_FOUR_STAR, ObjectID.STAR_SIZE_FIVE_STAR, ObjectID.STAR_SIZE_SIX_STAR,
			ObjectID.STAR_SIZE_SEVEN_STAR, ObjectID.STAR_SIZE_EIGHT_STAR, ObjectID.STAR_SIZE_NINE_STAR};
		for (int i = 0; i < ids.length; i++)
		{
			assertEquals(i + 1, Stars.tierOf(ids[i]));
		}
		// The wiki's ids, so a renamed constant can't silently change them.
		assertEquals(9, Stars.tierOf(41020));
		assertEquals(1, Stars.tierOf(41229));
		assertEquals(0, Stars.tierOf(ObjectID.STAR_SIZE_ONE_STAR + 1));
		assertEquals(0, Stars.tierOf(0));
	}

	@Test
	public void aTierDropsEverySevenMinutes()
	{
		Instant goneBy = Stars.goneBy(6, T);
		assertEquals(T.plus(Duration.ofMinutes(42)), goneBy);
		assertEquals(6, Stars.tierAt(goneBy, T));
		assertEquals(6, Stars.tierAt(goneBy, T.plus(Duration.ofMinutes(6))));
		assertEquals(5, Stars.tierAt(goneBy, T.plus(Duration.ofMinutes(7))));
		assertEquals(1, Stars.tierAt(goneBy, T.plus(Duration.ofMinutes(41))));
		assertEquals(0, Stars.tierAt(goneBy, goneBy));
		assertEquals(0, Stars.tierAt(goneBy, goneBy.plusSeconds(60)));
	}

	@Test
	public void sightingsOfOneStarKeepTheEarliestGoneByAndFirstSighting()
	{
		assertTrue(events.addStar(MINE, Stars.goneBy(7, T), T, "near Varrock east mine", Source.REPORTED, 1, null));
		// Seen here 3 minutes later, still tier 7: a later bound, so the server's stays; the sighting here wins.
		assertTrue(events.addStar(MINE, Stars.goneBy(7, T.plusSeconds(180)), T.plusSeconds(180), "near Varrock east mine",
			Source.SEEN, 0, null));
		Event star = events.star();
		assertEquals(Stars.goneBy(7, T), star.getUntil());
		assertEquals(T, star.getAt());
		assertEquals(Source.SEEN, star.getSource());
		assertEquals(1, star.getReporters());
		// Its tier drops 5 minutes in: the exact time, earlier than both.
		Instant drop = T.plusSeconds(300);
		assertTrue(events.addStar(MINE, Stars.goneBy(6, drop), drop, "near Varrock east mine", Source.SEEN, 0, null));
		assertEquals(Stars.goneBy(6, drop), events.star().getUntil());
		// The same again changes nothing.
		assertFalse(events.addStar(MINE, Stars.goneBy(6, drop), drop, "near Varrock east mine", Source.SEEN, 0, null));
	}

	@Test
	public void oneStarPerWorld()
	{
		events.addStar(MINE, Stars.goneBy(9, T), T, "near Varrock east mine", Source.SEEN, 0, null);
		// A star found later elsewhere replaced it (another star landing ends the last one).
		assertTrue(events.addStar(OTHER, Stars.goneBy(6, T.plusSeconds(600)), T.plusSeconds(600), "near Rimmington",
			Source.REPORTED, 2, null));
		assertEquals(OTHER, events.star().getWhere().getPoint());
		// A late report of the older star doesn't bring it back.
		assertFalse(events.addStar(MINE, Stars.goneBy(9, T), T, "near Varrock east mine", Source.REPORTED, 3, null));
		assertEquals(OTHER, events.star().getWhere().getPoint());
	}

	@Test
	public void goneWhenSeenDisappearingOrWhenTimeIsUp()
	{
		events.addStar(MINE, Stars.goneBy(2, T), T, "near Varrock east mine", Source.SEEN, 0, null);
		assertFalse("another tile", events.starGone(OTHER));
		assertTrue(events.starGone(MINE));
		assertEquals(Collections.emptyList(), events.active());

		events.addStar(MINE, Stars.goneBy(2, T), T, "near Varrock east mine", Source.SEEN, 0, null);
		assertFalse(events.expire(T.plus(Duration.ofMinutes(13))));
		assertTrue(events.expire(T.plus(Duration.ofMinutes(14))));
		assertEquals(null, events.star());
	}

	@Test
	public void listedAfterTheChestAndBeforeBreaches()
	{
		WorldLocations loc = WorldLocations.load(new com.google.gson.Gson());
		events.add(new WorldEventMessage(WorldEventMessage.Kind.BREACH, "the Bone Yard", null), T, loc.resolve("the Bone Yard"),
			Source.SEEN, 0);
		events.add(new WorldEventMessage(WorldEventMessage.Kind.CHEST, "Zanaris", null), T, loc.resolve("Zanaris"), Source.SEEN, 0);
		events.addStar(MINE, Stars.goneBy(8, T), T, "near Varrock east mine", Source.SEEN, 0, null);
		List<WorldEventMessage.Kind> kinds = Arrays.asList(events.active().get(0).getKind(), events.active().get(1).getKind(),
			events.active().get(2).getKind());
		assertEquals(Arrays.asList(WorldEventMessage.Kind.CHEST, WorldEventMessage.Kind.STAR, WorldEventMessage.Kind.BREACH),
			kinds);
		// The star's spot is exact: its marker goes on the tile.
		Event star = events.star();
		assertEquals(WorldLocations.Accuracy.EXACT, star.getWhere().getAccuracy());
		assertEquals(MINE, star.getWhere().getPoint());
	}

	@Test
	public void theDeadmanAreaFromTheHudWord()
	{
		// What the HUD said in game (::dgt zone): "Guarded" at the star, "Deadman" outside the guarded area.
		assertEquals("guarded", Stars.zoneId("Guarded"));
		assertEquals("unguarded", Stars.zoneId("<col=ff0000>Deadman</col>"));
		assertEquals("safe", Stars.zoneId("Safe"));
		assertEquals(null, Stars.zoneId(""));
		assertEquals(null, Stars.zoneId(null));
		assertEquals("Guarded", Stars.zoneName("guarded"));
		assertEquals(null, Stars.zoneName(null));
	}

	@Test
	public void sharedOnlyOnceTheAreaIsKnownAndOnlyTheChosenAreas()
	{
		assertFalse("area unknown: never sent", Stars.shared(null, true, true));
		assertTrue(Stars.shared("guarded", true, false));
		assertFalse(Stars.shared("unguarded", true, false));
		assertTrue(Stars.shared("unguarded", false, true));
		assertFalse(Stars.shared("guarded", false, true));
		assertTrue("a safe zone counts as guarded", Stars.shared("safe", true, false));
		assertFalse(Stars.shared("guarded", false, false));
	}

	@Test
	public void theFirstKnownAreaStays()
	{
		events.addStar(MINE, Stars.goneBy(7, T), T, "near Rellekka", Source.SEEN, 0, null);
		assertFalse("another tile", events.starZone(OTHER, "guarded"));
		assertTrue(events.starZone(MINE, "guarded"));
		assertFalse(events.starZone(MINE, "unguarded"));
		assertEquals("guarded", events.star().getZone());
		// A later report keeps it, and a report that has one fills it in.
		events.addStar(MINE, Stars.goneBy(7, T), T, "near Rellekka", Source.REPORTED, 2, "unguarded");
		assertEquals("guarded", events.star().getZone());
		WorldEvents fresh = new WorldEvents();
		fresh.addStar(MINE, Stars.goneBy(7, T), T, "near Rellekka", Source.REPORTED, 1, null);
		fresh.addStar(MINE, Stars.goneBy(7, T), T, "near Rellekka", Source.REPORTED, 2, "guarded");
		assertEquals("guarded", fresh.star().getZone());
	}
}
