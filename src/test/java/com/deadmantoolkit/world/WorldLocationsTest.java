package com.deadmantoolkit.world;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import com.deadmantoolkit.world.WorldLocations.Accuracy;
import com.deadmantoolkit.world.WorldLocations.Resolved;
import com.google.gson.Gson;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import net.runelite.api.coords.WorldPoint;
import org.junit.Test;

public class WorldLocationsTest
{
	private final WorldLocations loc = WorldLocations.load(new Gson());

	private static final WorldPoint WARRIORS = new WorldPoint(2864, 3544, 0);

	@Test
	public void knownPlaceIsExactWhateverTheWording()
	{
		for (String s : new String[]{"Warriors' Guild", "the Warriors' Guild", "WARRIORS GUILD", "Warriors’ Guild",
			"at the warriors guild", "Surrounding the Warriors' Guild"})
		{
			Resolved r = loc.resolve(s);
			assertEquals(s, Accuracy.EXACT, r.getAccuracy());
			assertEquals(s, WARRIORS, r.getPoint());
			assertEquals("Warriors' Guild", r.getPlace());
		}
	}

	@Test
	public void directionMovesTheMarker()
	{
		// (Wording the place file doesn't list itself; "north of the Warriors' Guild" is a chest spot of its own.)
		Resolved r = loc.resolve("south of the Warriors' Guild");
		assertEquals(Accuracy.APPROXIMATE, r.getAccuracy());
		assertEquals(new WorldPoint(2864, 3544 - WorldLocations.NEAR, 0), r.getPoint());
		assertEquals(WorldLocations.NEAR, r.getRadius());
		assertEquals(Accuracy.EXACT, loc.resolve("north of the Warriors' Guild").getAccuracy());

		r = loc.resolve("South-West of Lumbridge");
		assertTrue(r.getPoint().getX() < 3222 && r.getPoint().getY() < 3218);
		r = loc.resolve("east of the GE");
		assertEquals(3165 + WorldLocations.NEAR, r.getPoint().getX());
		// A direction inside longer wording.
		r = loc.resolve("the old ruins west of the Bone Yard");
		assertEquals(Accuracy.APPROXIMATE, r.getAccuracy());
		assertEquals(3236 - WorldLocations.NEAR, r.getPoint().getX());
	}

	@Test
	public void regionsAndKingdoms()
	{
		Resolved center = loc.resolve("Kingdom Of Asgarnia");
		assertEquals(Accuracy.REGION, center.getAccuracy());
		assertEquals("Asgarnia", center.getPlace());
		assertTrue(center.getRadius() > 50);

		Resolved north = loc.resolve("North of Asgarnia");
		assertEquals(Accuracy.REGION, north.getAccuracy());
		assertTrue(north.getPoint().getY() > center.getPoint().getY());
		assertEquals(center.getPoint().getX(), north.getPoint().getX());

		// A part of a kingdom the file doesn't list (the southern one is a regional breach of its own).
		Resolved east = loc.resolve("The eastern Asgarnian kingdom");
		assertEquals("Asgarnia", east.getPlace());
		assertTrue(east.getPoint().getX() > center.getPoint().getX());
		assertEquals("The southern Asgarnian Kingdom", loc.resolve("the southern asgarnian kingdom").getPlace());

		assertEquals("Misthalin", loc.resolve("Throughout Misthalin").getPlace());
		assertEquals("Wilderness", loc.resolve("Within the Wilderness").getPlace());
		assertEquals("Kandarin", loc.resolve("northern Kandarin").getPlace());
		assertTrue(loc.resolve("northern Kandarin").getPoint().getY() > loc.resolve("Kandarin").getPoint().getY());
	}

	@Test
	public void leadWordsAreDropped()
	{
		// Wording the file doesn't list as a spot of its own.
		assertEquals("White Wolf Mountain", loc.resolve("Surrounding White Wolf Mountain").getPlace());
		assertEquals("Barrows", loc.resolve("Within the Barrows").getPlace());
		assertEquals(Accuracy.EXACT, loc.resolve("Within the Barrows").getAccuracy());
		assertEquals("Lumbridge", loc.resolve("near Lumbridge").getPlace());
		// Listed wording is its own spot.
		assertEquals("On top of White Wolf Mountain", loc.resolve("On top of White Wolf Mountain").getPlace());
	}

	@Test
	public void otherMapsHaveASurfaceEntrance()
	{
		Resolved r = loc.resolve("Zanaris");
		assertEquals(Accuracy.EXACT, r.getAccuracy());
		assertNotNull(r.getEntrance());
		assertEquals("Lumbridge Swamp shed", r.getEntranceName());
		assertTrue("Zanaris is off the surface map", r.getPoint().getY() > 4000);
		assertNotNull(loc.resolve("the Mole Hole").getEntrance());
		assertNull(loc.resolve("Lumbridge").getEntrance());
	}

	@Test
	public void unknownPlaces()
	{
		for (String s : new String[]{"Nowhere-in-particular", "", "the", "north of nowhere", "ge"})
		{
			Resolved r = loc.resolve(s);
			if ("ge".equals(s))
			{
				// A short alias on its own is fine...
				assertEquals("Grand Exchange", r.getPlace());
				continue;
			}
			assertEquals(s, Accuracy.UNKNOWN, r.getAccuracy());
			assertFalse(r.known());
		}
		// ...but never matched inside other words.
		assertEquals(Accuracy.UNKNOWN, loc.resolve("the edge of the gem rocks").getAccuracy());
	}

	@Test
	public void aDownloadedPlaceListIsBounded()
	{
		String json = "{\"revision\": 5, \"places\": ["
			+ "{\"name\": \"<col=ff0000>Shed</col>\", \"x\": 3202, \"y\": 3169},"
			+ "{\"name\": \"Off the map\", \"x\": 99999, \"y\": 3169},"
			+ "{\"name\": \"Bad plane\", \"x\": 3000, \"y\": 3000, \"plane\": 9},"
			+ "{\"name\": \"" + new String(new char[200]).replace('\0', 'x') + "\", \"x\": 3000, \"y\": 3000},"
			+ "{\"name\": \"Huge\", \"x\": 3000, \"y\": 3000, \"radius\": 100000}"
			+ "]}";
		WorldPlaces.Table t = WorldPlaces.parse(new Gson(), json);
		assertEquals(2, t.getPlaces().size());
		assertEquals("Shed", t.getPlaces().get(0).getName());
		assertEquals(WorldPlaces.MAX_RADIUS, t.getPlaces().get(1).getRadius());
		assertNull(WorldPlaces.parse(new Gson(), "not json"));
	}

	@Test
	public void everyNameAndAliasResolvesToItsOwnPlace()
	{
		for (WorldPlaces.Place p : loc.places())
		{
			assertEquals(p.getName(), p.getName(), loc.resolve(p.getName()).getPlace());
			for (String a : p.getAliases())
			{
				assertEquals(a, p.getName(), loc.resolve(a).getPlace());
			}
		}
	}

	@Test
	public void broadcastWordingOfKnownSpotsIsExact()
	{
		// A chest spot named after the bank is the chest's tile, not the bank's.
		Resolved r = loc.resolve("Outside the Al-Kharid bank");
		assertEquals(Accuracy.EXACT, r.getAccuracy());
		assertEquals(new WorldPoint(3273, 3160, 0), r.getPoint());
		assertEquals(new WorldPoint(3229, 3650, 0), loc.resolve("North of the Chaos Temple").getPoint());
		// A regional breach: its own spawn area, not a guess from "southern".
		r = loc.resolve("The southern Asgarnian Kingdom");
		assertEquals(Accuracy.REGION, r.getAccuracy());
		assertEquals(new WorldPoint(2984, 3249, 0), r.getPoint());
		// Underground spots keep their surface entrance.
		assertNotNull(loc.resolve("North in the Dwarven Mine").getEntrance());
	}

	@Test
	public void theCompassSpellingAndAbbreviationsDontMatter()
	{
		// Without "the": still the chest spot by the monastery (not 24 tiles north of Edgeville).
		Resolved r = loc.resolve("North of Edgeville Monastry");
		assertEquals("North of the Edgeville Monastery", r.getPlace());
		assertEquals(new WorldPoint(3045, 3512, 0), r.getPoint());
		assertFalse(r.isFuzzy());
		// One-word compass spellings.
		assertEquals(loc.resolve("North-west of the Al Kharid mine").getPlace(),
			loc.resolve("Northwest of the Al-Kharid Mine").getPlace());
		assertFalse(loc.resolve("Northwest of the Al-Kharid Mine").isFuzzy());
	}

	@Test
	public void nearSpellingsOfAWholeName()
	{
		// "Edge" for Edgeville, and "Monastry": read as the chest spot, flagged, reported as approximate.
		Resolved r = loc.resolve("North of Edge Monastry");
		assertEquals("North of the Edgeville Monastery", r.getPlace());
		assertEquals(new WorldPoint(3045, 3512, 0), r.getPoint());
		assertEquals(Accuracy.EXACT, r.getAccuracy());
		assertTrue(r.isFuzzy());
		assertEquals(Accuracy.APPROXIMATE, r.reported());
		// A direction from a misspelled place: that place, moved.
		r = loc.resolve("south of edge monastery");
		assertEquals("Edgeville Monastery", r.getPlace());
		assertEquals(Accuracy.APPROXIMATE, r.getAccuracy());
		assertTrue(r.isFuzzy());
		assertEquals(3490 - WorldLocations.NEAR, r.getPoint().getY());
		assertEquals("Lumbridge", loc.resolve("Lumbrdige").getPlace());
		// Compass words are never guessed: south is not north.
		assertEquals(3544 - WorldLocations.NEAR, loc.resolve("south of the Warriors Guild").getPoint().getY());
		// A lone short word isn't enough to guess from.
		assertFalse(loc.resolve("edge").known());
	}

	@Test
	public void wordScores()
	{
		assertEquals(3, WorldLocations.wordScore("guild", "guild"));
		assertEquals(2, WorldLocations.wordScore("edge", "edgeville"));
		assertEquals(1, WorldLocations.wordScore("monastry", "monastery"));
		assertEquals(1, WorldLocations.wordScore("lumbrdige", "lumbridge"));
		assertEquals(0, WorldLocations.wordScore("north", "south"));
		assertEquals(0, WorldLocations.wordScore("east", "west"));
		assertEquals(0, WorldLocations.wordScore("mine", "mind"));
		assertEquals(0, WorldLocations.wordScore("ge", "gem"));
		assertEquals(0, WorldLocations.wordScore("level", "level2"));
	}

	@Test
	public void twoEqualGuessesAreNoGuess()
	{
		WorldPlaces.Table t = new WorldPlaces.Table(1, Arrays.asList(
			place("Grove Hill", 3000, 3000), place("Grave Hill", 3100, 3100),
			place("Alpha Fort", 3200, 3200), place("Alpha Fortress", 3300, 3300)),
			Collections.emptyList(), Collections.emptyList());
		WorldLocations l = new WorldLocations(t);
		// One letter off both: no guess.
		assertFalse(l.resolve("Grive Hill").known());
		assertEquals("Grove Hill", l.resolve("Grovee Hill").getPlace());
		// A shortening beats a name with letters added.
		assertEquals("Alpha Fortress", l.resolve("Alpha Fortres").getPlace());
	}

	private static WorldPlaces.Place place(String name, int x, int y)
	{
		return new WorldPlaces.Place(name, Collections.emptyList(), new WorldPoint(x, y, 0), false, 8, null, null);
	}

	@Test
	public void tableHasNoClashingNames()
	{
		Set<String> seen = new HashSet<>();
		for (WorldPlaces.Place p : loc.places())
		{
			// Compared the way the resolver matches (no "the", compass words split).
			assertTrue("duplicate name " + p.getName(), seen.add(WorldLocations.matchKey(p.getName())));
			for (String a : p.getAliases())
			{
				assertTrue("alias clashes: " + a, seen.add(WorldLocations.matchKey(a)));
			}
			WorldPoint w = p.getPoint();
			// The game world, surface (Sailing islands reach y 2200) and underground (y up to about 10400).
			assertTrue(p.getName(), w.getX() > 1000 && w.getX() < 4200 && w.getY() > 2000 && w.getY() < 12800);
		}
	}
}
