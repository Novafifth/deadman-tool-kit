package com.deadmantoolkit.world;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import com.deadmantoolkit.world.WorldEventMessage.Combat;
import com.deadmantoolkit.world.WorldEventMessage.Kind;
import com.deadmantoolkit.world.WorldEvents.Event;
import com.deadmantoolkit.world.WorldEvents.Source;
import com.google.gson.Gson;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.Test;

public class WorldEventsTest
{
	private static final Instant T = Instant.parse("2026-10-03T14:15:40Z");
	private final WorldLocations loc = WorldLocations.load(new Gson());
	private final WorldEvents events = new WorldEvents();

	private boolean add(Kind kind, String place, Combat combat, Instant at, Source source, int reporters)
	{
		return events.add(new WorldEventMessage(kind, place, combat), at, loc.resolve(place), source, reporters);
	}

	@Test
	public void oneChestAtATime()
	{
		assertTrue(add(Kind.CHEST, "north of the Warriors' Guild", null, T, Source.SEEN, 0));
		assertTrue(add(Kind.CHEST, "Zanaris", null, T.plus(Duration.ofMinutes(61)), Source.SEEN, 0));
		List<Event> a = events.active();
		assertEquals(1, a.size());
		assertEquals("Zanaris", a.get(0).getLocation());
		// A late server report of the older chest doesn't bring it back.
		assertFalse(add(Kind.CHEST, "north of the Warriors' Guild", null, T, Source.REPORTED, 3));
		assertEquals("Zanaris", events.active().get(0).getLocation());
	}

	@Test
	public void chestEndsAfterTenMinutes()
	{
		add(Kind.CHEST, "Lumbridge", null, T, Source.SEEN, 0);
		assertFalse(events.expire(T.plus(Duration.ofMinutes(9))));
		assertTrue(events.expire(T.plus(Duration.ofMinutes(10))));
		assertTrue(events.active().isEmpty());
	}

	@Test
	public void repeatsAndDriftAreOneEvent()
	{
		add(Kind.CHEST, "Lumbridge", null, T, Source.REPORTED, 2);
		// Seen here a minute later (the game is not exactly on :15), worded slightly differently.
		assertTrue(add(Kind.CHEST, "lumbridge", null, T.plus(Duration.ofMinutes(1)), Source.SEEN, 0));
		Event e = events.active().get(0);
		assertEquals(T, e.getAt());
		assertEquals(Source.SEEN, e.getSource());
		assertEquals(2, e.getReporters());
		assertFalse("nothing new", add(Kind.CHEST, "Lumbridge", null, T, Source.SEEN, 0));
	}

	@Test
	public void breachWaves()
	{
		add(Kind.BREACH, "the Bone Yard", Combat.MULTI, T, Source.SEEN, 0);
		add(Kind.BREACH, "North of Asgarnia", Combat.SINGLE, T, Source.SEEN, 0);
		add(Kind.CHEST, "Lumbridge", null, T, Source.SEEN, 0);
		// The server's copy of one, without its combat note, adds nothing.
		assertFalse(add(Kind.BREACH, "The Bone Yard", null, T.plusSeconds(20), Source.SEEN, 0));
		List<Event> a = events.active();
		assertEquals(3, a.size());
		assertEquals(Kind.CHEST, a.get(0).getKind());
		assertEquals(Combat.MULTI, a.get(1).getCombat());
		// The chest goes after 10 minutes, the breaches after 45.
		assertTrue(events.expire(T.plus(WorldEvents.CHEST_LIFE)));
		assertEquals(2, events.active().size());
		assertTrue(events.expire(T.plus(WorldEvents.BREACH_LIFE)));
		assertTrue(events.active().isEmpty());
	}

	@Test
	public void sameBreachPlaceInALaterWaveIsANewBreach()
	{
		add(Kind.BREACH, "Varrock", null, T, Source.SEEN, 0);
		add(Kind.BREACH, "Varrock", null, T.plus(Duration.ofHours(4)), Source.SEEN, 0);
		assertEquals(2, events.active().size());
		events.expire(T.plus(Duration.ofHours(4)));
		assertEquals(1, events.active().size());
	}

	@Test
	public void aNewerPlaceListPlacesListedEvents()
	{
		add(Kind.CHEST, "the old shed", null, T, Source.SEEN, 0);
		assertEquals(WorldLocations.Accuracy.UNKNOWN, events.active().get(0).getWhere().getAccuracy());
		WorldPlaces.Table newer = WorldPlaces.parse(new Gson(),
			"{\"revision\": 9, \"places\": [{\"name\": \"The old shed\", \"x\": 3202, \"y\": 3169}]}");
		events.reresolve(new WorldLocations(newer));
		assertEquals(WorldLocations.Accuracy.EXACT, events.active().get(0).getWhere().getAccuracy());
	}
}
