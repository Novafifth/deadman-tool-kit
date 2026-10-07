package com.deadmantoolkit.world;

import com.google.gson.Gson;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.Value;
import net.runelite.api.coords.WorldPoint;

/**
 * Where a broadcast's place text is on the map. Handles:
 * <ul>
 * <li>known places and their other names ("Warriors' Guild", "the GE"), whatever the case, punctuation, "the" and
 * compass spelling ("Northwest", "north-west");</li>
 * <li>the game's own wording of a known event spot ("Outside the Al Kharid bank" is a chest tile, not the bank);</li>
 * <li>near spellings and shortenings of a whole name ("North of Edge Monastry"): every word must line up with the
 * name's (the same, a shortening of at least 4 letters, or a letter or two off in a long word), and only one place
 * may fit; such a match is marked {@link Resolved#isFuzzy()};</li>
 * <li>a direction from a place ("north of the Warriors' Guild"): the marker moves about {@value #NEAR} tiles that way,
 * marked approximate;</li>
 * <li>kingdoms and regions ("Kingdom of Asgarnia", "North of Asgarnia", "the eastern Asgarnian kingdom"): a rough
 * marker in that part of the region;</li>
 * <li>wording around the place ("Throughout", "Within", "Surrounding", "On top of", "The mounds at", "near", ...);</li>
 * <li>places on another map (Zanaris, the Mole Hole): their own spot plus their surface entrance.</li>
 * </ul>
 * Anything else resolves to {@link Accuracy#UNKNOWN} and is collected so the place file can grow. Immutable once built
 * (any thread may build one; {@link #resolve} only reads).
 */
public final class WorldLocations
{
	public enum Accuracy
	{
		/** The spot itself (a known place). */
		EXACT,
		/** Near a known place ("north of ..."), or a place mentioned in longer wording. */
		APPROXIMATE,
		/** Only the kingdom or region is known. */
		REGION,
		/** Not a place the plugin knows. */
		UNKNOWN;

		public String id()
		{
			return name().toLowerCase(Locale.ROOT);
		}
	}

	@Value
	public static class Resolved
	{
		Accuracy accuracy;
		/** Where the marker goes; null when unknown. */
		WorldPoint point;
		/** About how far off the marker may be, in tiles (0 when exact). */
		int radius;
		/** The place it was matched to ("Warriors' Guild", "Asgarnia"); null when unknown. */
		String place;
		/** For places on another map: the surface entrance and its name; null otherwise. */
		WorldPoint entrance;
		String entranceName;
		/** Matched by a near spelling or a shortening, not the place's own wording. */
		boolean fuzzy;

		static final Resolved UNKNOWN = new Resolved(Accuracy.UNKNOWN, null, 0, null, null, null, false);

		public boolean known()
		{
			return point != null;
		}

		/** How the plugin rates this match when telling the server (a fuzzy match is never "exact"). */
		public Accuracy reported()
		{
			return fuzzy && accuracy == Accuracy.EXACT ? Accuracy.APPROXIMATE : accuracy;
		}
	}

	/** How far "north of X" is from X, in tiles. */
	static final int NEAR = 24;
	/** A direction within a region moves this share of its radius. */
	private static final double REGION_SHIFT = 0.6;

	private static final String DIR = "north east|north west|south east|south west|north|south|east|west";
	private static final Pattern DIRECTION_OF = Pattern.compile("^(?:(.*?)\\s)??(" + DIR + ")(?:ern)?\\s(?:of|from)\\s(.+)$");
	private static final Pattern DIRECTION_ADJ = Pattern.compile("^(" + DIR + "|central|middle)(?:ern)?\\s(?:part of\\s)?(.+)$");
	/** One-word compass spellings, written as two words in match keys. */
	private static final Map<String, String> COMPASS = new HashMap<>();

	static
	{
		for (String ns : new String[]{"north", "south"})
		{
			for (String ew : new String[]{"east", "west"})
			{
				COMPASS.put(ns + ew, ns + " " + ew);
				COMPASS.put(ns + ew + "ern", ns + " " + ew + "ern");
			}
		}
	}

	/** Used when a place file has no wording of its own. */
	private static final List<String> LEADS = Arrays.asList(
		"the outskirts of", "the area around", "the mounds at", "the mounds of", "the area near", "somewhere in",
		"just outside", "the area of", "the ruins at", "the top of", "the mounds", "surrounding", "throughout",
		"on top of", "the area", "outside", "within", "around", "nearby", "inside", "deep in", "near", "in", "at",
		"on", "by");
	private static final List<String> TAILS = Arrays.asList("kingdom", "region", "area", "lands", "province");

	/** One name or alias, split into words for the near-spelling match. */
	private static final class Name
	{
		final String[] words;
		final WorldPlaces.Place place;

		Name(String key, WorldPlaces.Place place)
		{
			this.words = key.split(" ");
			this.place = place;
		}
	}

	private final WorldPlaces.Table table;
	private final List<WorldPlaces.Place> places;
	private final List<String> leads;
	private final List<String> tails;
	/** Match key of a name or alias -> place. */
	private final Map<String, WorldPlaces.Place> byName = new HashMap<>();
	/** Names longest first, for finding a place inside longer wording. */
	private final List<String> namesLongestFirst;
	private final List<Name> names = new ArrayList<>();

	/** The places bundled with the plugin ({@link WorldPlaces#RESOURCE}). Reads a resource: not on the client thread. */
	public static WorldLocations load(Gson gson)
	{
		return new WorldLocations(WorldPlaces.bundled(gson));
	}

	public WorldLocations(WorldPlaces.Table table)
	{
		this.table = table;
		this.places = table.getPlaces();
		this.leads = table.getLeads().isEmpty() ? words(LEADS) : table.getLeads();
		this.tails = table.getTails().isEmpty() ? words(TAILS) : table.getTails();
		for (WorldPlaces.Place p : places)
		{
			index(p.getName(), p);
			for (String a : p.getAliases())
			{
				index(a, p);
			}
		}
		List<String> all = new ArrayList<>(byName.keySet());
		all.sort((a, b) -> b.length() - a.length());
		namesLongestFirst = Collections.unmodifiableList(all);
	}

	private void index(String name, WorldPlaces.Place p)
	{
		String k = matchKey(name);
		if (!k.isEmpty() && byName.putIfAbsent(k, p) == null)
		{
			names.add(new Name(k, p));
		}
	}

	/** Words in match-key form, longest first, without duplicates or empties. */
	static List<String> words(List<String> in)
	{
		LinkedHashSet<String> out = new LinkedHashSet<>();
		for (String w : in)
		{
			String k = w == null ? "" : matchKey(w);
			if (!k.isEmpty())
			{
				out.add(k);
			}
		}
		List<String> list = new ArrayList<>(out);
		list.sort((a, b) -> b.length() - a.length());
		return Collections.unmodifiableList(list);
	}

	/** The places this resolver knows (for tests and the developer tools). */
	List<WorldPlaces.Place> places()
	{
		return places;
	}

	/** The place file in use. */
	public WorldPlaces.Table table()
	{
		return table;
	}

	/** The revision of the place file in use (higher is newer). */
	public long revision()
	{
		return table.getRevision();
	}

	public Resolved resolve(String text)
	{
		if (text == null)
		{
			return Resolved.UNKNOWN;
		}
		String full = matchKey(text);
		// The broadcast's exact wording is often a place of its own ("Outside the Al Kharid bank" is a chest spot,
		// not the bank), so the full text goes first, before any wording is dropped.
		WorldPlaces.Place exact = byName.get(full);
		if (exact != null)
		{
			return at(exact, null, exact.isRegion() ? Accuracy.REGION : Accuracy.EXACT, false);
		}
		String k = stripLeads(full);
		if (k.isEmpty())
		{
			return Resolved.UNKNOWN;
		}
		// A whole name: "West Ardougne" is a place, not west of Ardougne.
		WorldPlaces.Place whole = lookup(k);
		if (whole != null)
		{
			return at(whole, null, whole.isRegion() ? Accuracy.REGION : Accuracy.EXACT, false);
		}
		// A whole name spelled a little differently or shortened ("North of Edge Monastry").
		WorldPlaces.Place near = nearSpelling(full);
		if (near == null && !k.equals(full))
		{
			near = nearSpelling(k);
		}
		if (near != null)
		{
			return at(near, null, near.isRegion() ? Accuracy.REGION : Accuracy.EXACT, true);
		}

		// "north of X", also inside longer wording ("the ruins west of X").
		Matcher m = DIRECTION_OF.matcher(k);
		if (m.matches())
		{
			Resolved r = shifted(m.group(3), m.group(2));
			if (r != null)
			{
				return r;
			}
		}
		// "northern X", "the southern X kingdom", "north X", "central X".
		m = DIRECTION_ADJ.matcher(k);
		if (m.matches())
		{
			Resolved r = shifted(m.group(2), m.group(1));
			if (r != null)
			{
				return r;
			}
		}
		// A known place somewhere in longer wording ("the old ruins by Lumbridge"): near it.
		WorldPlaces.Place p = mentioned(k);
		if (p != null)
		{
			return at(p, null, p.isRegion() ? Accuracy.REGION : Accuracy.APPROXIMATE, false);
		}
		return Resolved.UNKNOWN;
	}

	/** {@code rest} moved towards {@code direction}, or null when {@code rest} isn't a known place. */
	private Resolved shifted(String rest, String direction)
	{
		String r = stripLeads(rest);
		WorldPlaces.Place p = lookup(r);
		boolean fuzzy = false;
		if (p == null)
		{
			p = nearSpelling(r);
			fuzzy = p != null;
		}
		if (p == null)
		{
			p = mentioned(r);
		}
		if (p == null)
		{
			return null;
		}
		return at(p, direction, p.isRegion() ? Accuracy.REGION : Accuracy.APPROXIMATE, fuzzy);
	}

	private Resolved at(WorldPlaces.Place p, String direction, Accuracy accuracy, boolean fuzzy)
	{
		WorldPoint base = p.getPoint();
		int radius = p.isRegion() ? p.getRadius() : accuracy == Accuracy.EXACT ? 0 : NEAR;
		WorldPoint point = base;
		if (direction != null)
		{
			int distance = p.isRegion() ? (int) Math.round(p.getRadius() * REGION_SHIFT) : NEAR;
			int[] d = unit(direction);
			if (d[0] != 0 && d[1] != 0)
			{
				// Diagonal: the same distance, not further.
				distance = (int) Math.round(distance / Math.sqrt(2));
			}
			point = new WorldPoint(base.getX() + d[0] * distance, base.getY() + d[1] * distance, base.getPlane());
			if (p.isRegion())
			{
				radius = (int) Math.round(p.getRadius() * (1 - REGION_SHIFT));
			}
		}
		return new Resolved(accuracy, point, radius, p.getName(), p.getEntrance(), p.getEntranceName(), fuzzy);
	}

	/** {dx, dy} for a direction word (north is +y, east is +x); {0, 0} for central. */
	private static int[] unit(String direction)
	{
		String d = direction.replace(" ", "");
		int dx = d.contains("east") ? 1 : d.contains("west") ? -1 : 0;
		int dy = d.startsWith("north") ? 1 : d.startsWith("south") ? -1 : 0;
		return new int[]{dx, dy};
	}

	/** A place by its whole name, also with "kingdom" / "region" / ... dropped from either end. */
	private WorldPlaces.Place lookup(String k)
	{
		WorldPlaces.Place p = byName.get(k);
		if (p != null)
		{
			return p;
		}
		String s = k;
		if (s.startsWith("kingdom of "))
		{
			s = s.substring("kingdom of ".length());
		}
		for (String tail : tails)
		{
			if (s.endsWith(" " + tail))
			{
				s = s.substring(0, s.length() - tail.length() - 1);
			}
		}
		s = stripLeads(s);
		return s.equals(k) ? null : byName.get(s);
	}

	/**
	 * The one place whose name (or alias) lines up word for word with {@code k}, allowing shortenings and small
	 * misspellings ({@link #wordScore}); null when none does, or when two different places fit equally well.
	 */
	WorldPlaces.Place nearSpelling(String k)
	{
		String[] q = k.split(" ");
		int best = 0;
		WorldPlaces.Place found = null;
		boolean tie = false;
		for (Name n : names)
		{
			if (n.words.length != q.length)
			{
				continue;
			}
			int score = 0;
			int exactWords = 0;
			for (int i = 0; i < q.length && score >= 0; i++)
			{
				int s = wordScore(q[i], n.words[i]);
				if (s == 0)
				{
					score = -1;
				}
				else
				{
					score += s;
					exactWords += s == 3 ? 1 : 0;
				}
			}
			// A lone short word that isn't spelled out ("edge") is too weak to guess from.
			if (score <= 0 || (q.length == 1 && exactWords == 0 && q[0].length() < 5))
			{
				continue;
			}
			if (score > best)
			{
				best = score;
				found = n.place;
				tie = false;
			}
			else if (score == best && n.place != found)
			{
				tie = true;
			}
		}
		return tie ? null : found;
	}

	/**
	 * How well a word of the broadcast fits a word of a name: 3 the same, 2 a shortening of the name's word (at least 4
	 * letters, "edge" for "edgeville"), 1 the name's word with a letter or two more ("mines" for "mine") or a
	 * misspelling (one letter off in a word of 5+, two in a word of 8+), 0 no fit. Numbers and compass words must be
	 * exact (north and south differ by two letters).
	 */
	static int wordScore(String q, String n)
	{
		if (q.equals(n))
		{
			return 3;
		}
		if (COMPASS_WORDS.contains(q) || COMPASS_WORDS.contains(n) || hasDigit(q) || hasDigit(n))
		{
			return 0;
		}
		int min = Math.min(q.length(), n.length());
		if (min >= 4 && n.startsWith(q))
		{
			return 2;
		}
		if (min >= 4 && q.startsWith(n) && q.length() - n.length() <= 2)
		{
			return 1;
		}
		if (min >= 5)
		{
			int allowed = min >= 8 ? 2 : 1;
			if (Math.abs(q.length() - n.length()) <= allowed && distance(q, n, allowed) <= allowed)
			{
				return 1;
			}
		}
		return 0;
	}

	private static final java.util.Set<String> COMPASS_WORDS = new java.util.HashSet<>(Arrays.asList(
		"north", "south", "east", "west", "northern", "southern", "eastern", "western", "of", "from"));

	private static boolean hasDigit(String s)
	{
		for (int i = 0; i < s.length(); i++)
		{
			if (Character.isDigit(s.charAt(i)))
			{
				return true;
			}
		}
		return false;
	}

	/** Optimal string alignment distance (a swap of two neighbours counts once), stopping early above {@code max}. */
	static int distance(String a, String b, int max)
	{
		int[][] d = new int[a.length() + 1][b.length() + 1];
		for (int i = 0; i <= a.length(); i++)
		{
			d[i][0] = i;
		}
		for (int j = 0; j <= b.length(); j++)
		{
			d[0][j] = j;
		}
		for (int i = 1; i <= a.length(); i++)
		{
			int rowMin = Integer.MAX_VALUE;
			for (int j = 1; j <= b.length(); j++)
			{
				int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
				int v = Math.min(Math.min(d[i - 1][j] + 1, d[i][j - 1] + 1), d[i - 1][j - 1] + cost);
				if (i > 1 && j > 1 && a.charAt(i - 1) == b.charAt(j - 2) && a.charAt(i - 2) == b.charAt(j - 1))
				{
					v = Math.min(v, d[i - 2][j - 2] + 1);
				}
				d[i][j] = v;
				rowMin = Math.min(rowMin, v);
			}
			if (rowMin > max)
			{
				return max + 1;
			}
		}
		return d[a.length()][b.length()];
	}

	/** The longest known name that appears as whole words in {@code k}, or null. */
	private WorldPlaces.Place mentioned(String k)
	{
		String padded = " " + k + " ";
		for (String name : namesLongestFirst)
		{
			// Very short names (an alias like "ge") only count on their own, never inside other wording.
			if (name.length() >= 4 && padded.contains(" " + name + " "))
			{
				return byName.get(name);
			}
		}
		return null;
	}

	private String stripLeads(String k)
	{
		String s = k;
		boolean changed = true;
		while (changed)
		{
			changed = false;
			for (String lead : leads)
			{
				if (s.startsWith(lead + " "))
				{
					s = s.substring(lead.length() + 1);
					changed = true;
				}
			}
		}
		return s;
	}

	/**
	 * The text in a comparable form: lower case, apostrophes dropped ("Warriors' Guild" and "warriors guild" match),
	 * other punctuation as spaces, spaces collapsed. The server groups place names by the same key.
	 */
	public static String key(String text)
	{
		return text.toLowerCase(Locale.ROOT)
			.replace("’", "")
			.replace("'", "")
			.replaceAll("[^a-z0-9]+", " ")
			.trim();
	}

	/**
	 * {@link #key} for matching: also without "the" (the game says "north of the X" and "north of X" alike) and with
	 * one-word compass spellings split ("northwest" -> "north west", "northeastern" -> "north eastern").
	 */
	public static String matchKey(String text)
	{
		String k = key(text);
		if (k.isEmpty())
		{
			return k;
		}
		StringBuilder sb = new StringBuilder(k.length());
		for (String w : k.split(" "))
		{
			if (w.equals("the"))
			{
				continue;
			}
			if (sb.length() > 0)
			{
				sb.append(' ');
			}
			String c = COMPASS.get(w);
			sb.append(c != null ? c : w);
		}
		return sb.toString();
	}
}
