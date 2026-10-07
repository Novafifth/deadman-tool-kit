package com.deadmantoolkit.world;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.util.Text;

/**
 * The places a broadcast can name: towns, guilds, landmarks and Wilderness spots with their map coordinates, and the
 * kingdoms and regions a broadcast sometimes names instead ("North of Asgarnia", "the Kingdom of Kandarin"). Places on
 * another map (Zanaris, the Mole Hole, Mor Ul Rek) give their own coordinates and a surface entrance. The file also
 * lists the wording dropped around a place ("Throughout", "the mounds at", "... kingdom").
 * <p>
 * A copy, {@value #RESOURCE}, ships with the plugin; the server serves the current one (GET v1/world-places), so places
 * can be added or fixed without a plugin release. Whichever copy has the highest {@code revision} is used (the
 * server's is also saved in the plugin folder for the next start). Names the plugin can't place are collected
 * ({@link UnresolvedLog}, and the server's list) so the file can grow.
 */
@Slf4j
public final class WorldPlaces
{
	static final String RESOURCE = "world-places.json";
	/** Radius of a place that doesn't give one, in tiles. */
	static final int SPOT = 8;
	/** Bounds on a place file (any copy, including the server's): only names and map tiles, never more than this. */
	static final int MAX_ENTRIES = 5000;
	static final int MAX_ALIASES = 20;
	static final int MAX_NAME = 80;
	static final int MAX_RADIUS = 600;
	static final int MAX_COORD = 16384;
	static final int MAX_WORDS = 100;

	@Value
	public static class Place
	{
		String name;
		/** Other names, matched like {@link #name} (case, apostrophes and punctuation don't matter). */
		List<String> aliases;
		WorldPoint point;
		/** A kingdom or region, not one spot: markers are rough, and direction words move them further. */
		boolean region;
		/** How far the place spreads, in tiles. */
		int radius;
		/** For places on another map: where you go in from the surface, and what it is; null otherwise. */
		WorldPoint entrance;
		String entranceName;
	}

	/** A parsed copy of the file. */
	@Value
	public static class Table
	{
		/** Higher is newer. */
		long revision;
		List<Place> places;
		/** Words dropped from the front of a place before matching, longest first; empty for the built-in list. */
		List<String> leads;
		/** Words dropped from the end ("kingdom", "region"); empty for the built-in list. */
		List<String> tails;

		static final Table EMPTY = new Table(0, Collections.emptyList(), Collections.emptyList(), Collections.emptyList());
	}

	/** The file's shape (Gson). */
	static final class FileData
	{
		int version;
		long revision;
		Wording wording;
		List<Entry> places;
		List<Entry> regions;
	}

	static final class Wording
	{
		List<String> leads;
		List<String> tails;
	}

	static final class Entry
	{
		String name;
		List<String> aliases;
		Integer x;
		Integer y;
		Integer plane;
		Integer radius;
		Entrance entrance;
	}

	static final class Entrance
	{
		String name;
		Integer x;
		Integer y;
		Integer plane;
	}

	private WorldPlaces()
	{
	}

	/** The copy bundled with the plugin; empty (and a warning logged) if it can't be read. */
	public static Table bundled(Gson gson)
	{
		try (InputStream in = WorldPlaces.class.getResourceAsStream(RESOURCE))
		{
			if (in == null)
			{
				log.warn("{} is missing; world event places won't be shown on the map", RESOURCE);
				return Table.EMPTY;
			}
			try (Reader r = new InputStreamReader(in, StandardCharsets.UTF_8))
			{
				return parse(gson.fromJson(r, FileData.class));
			}
		}
		catch (IOException | JsonParseException ex)
		{
			log.warn("Couldn't read {}", RESOURCE, ex);
			return Table.EMPTY;
		}
	}

	/** A copy from the server or the plugin folder; null when it isn't one (or has no places). */
	public static Table parse(Gson gson, String json)
	{
		try
		{
			Table t = parse(gson.fromJson(json, FileData.class));
			return t.getPlaces().isEmpty() ? null : t;
		}
		catch (JsonParseException ex)
		{
			return null;
		}
	}

	/** The usable entries of {@code data}; entries without a name or coordinates are skipped. */
	static Table parse(FileData data)
	{
		if (data == null)
		{
			return Table.EMPTY;
		}
		List<Place> out = new ArrayList<>();
		add(out, data.places, false);
		add(out, data.regions, true);
		// In match-key form ("the" dropped, so "the mounds at" is "mounds at"), longest first.
		List<String> leads = data.wording == null || data.wording.leads == null ? Collections.emptyList()
			: WorldLocations.words(bounded(data.wording.leads));
		List<String> tails = data.wording == null || data.wording.tails == null ? Collections.emptyList()
			: WorldLocations.words(bounded(data.wording.tails));
		return new Table(data.revision, Collections.unmodifiableList(out), leads, tails);
	}

	private static void add(List<Place> out, List<Entry> entries, boolean region)
	{
		if (entries == null)
		{
			return;
		}
		for (Entry e : entries)
		{
			if (out.size() >= MAX_ENTRIES)
			{
				return;
			}
			String name = text(e == null ? null : e.name);
			if (name == null || !onMap(e.x, e.y, e.plane))
			{
				continue;
			}
			List<String> aliases = new ArrayList<>();
			if (e.aliases != null)
			{
				for (String a : e.aliases)
				{
					String alias = text(a);
					if (alias != null && aliases.size() < MAX_ALIASES)
					{
						aliases.add(alias);
					}
				}
			}
			WorldPoint entrance = null;
			String entranceName = null;
			if (e.entrance != null && onMap(e.entrance.x, e.entrance.y, e.entrance.plane) && text(e.entrance.name) != null)
			{
				entrance = new WorldPoint(e.entrance.x, e.entrance.y, e.entrance.plane == null ? 0 : e.entrance.plane);
				entranceName = text(e.entrance.name);
			}
			int radius = e.radius == null || e.radius < 0 ? SPOT : Math.min(e.radius, MAX_RADIUS);
			out.add(new Place(name, Collections.unmodifiableList(aliases),
				new WorldPoint(e.x, e.y, e.plane == null ? 0 : e.plane), region, radius, entrance, entranceName));
		}
	}

	/** At most {@value #MAX_WORDS} words of at most {@value #MAX_NAME} characters. */
	private static List<String> bounded(List<String> words)
	{
		List<String> out = new ArrayList<>();
		for (String w : words)
		{
			String t = text(w);
			if (t != null && out.size() < MAX_WORDS)
			{
				out.add(t);
			}
		}
		return out;
	}

	/** Plain text (no markup), trimmed, at most {@value #MAX_NAME} characters; null when unusable. */
	private static String text(String s)
	{
		if (s == null)
		{
			return null;
		}
		String t = Text.removeTags(s).trim();
		return t.isEmpty() || t.length() > MAX_NAME ? null : t;
	}

	/** A tile in the game world (coordinates within its bounds, plane 0-3). */
	private static boolean onMap(Integer x, Integer y, Integer plane)
	{
		return x != null && y != null && x >= 0 && x < MAX_COORD && y >= 0 && y < MAX_COORD
			&& (plane == null || (plane >= 0 && plane <= 3));
	}
}
