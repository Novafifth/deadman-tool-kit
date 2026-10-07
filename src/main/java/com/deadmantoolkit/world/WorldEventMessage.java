package com.deadmantoolkit.world;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.Value;
import net.runelite.client.util.Text;

/**
 * A world event broadcast the game sends on Deadman world 345:
 * <ul>
 * <li>"A Deadman's Chest has spawned at north of the Warriors' Guild!" (about once an hour, around :15);</li>
 * <li>"A breach has spawned at &lt;place&gt;! Powerful monsters now roam the area (Single)." (weekend breaches; several
 * at once, each Single or Multi combat).</li>
 * </ul>
 * Pure: colour tags, curly apostrophes, letter case and spacing don't matter. The place is kept as the game wrote it
 * (for display and for the server's list of places); {@link WorldLocations} works out where it is.
 */
@Value
public class WorldEventMessage
{
	public enum Kind
	{
		CHEST, BREACH;

		/** The wire / log name: "chest" or "breach". */
		public String id()
		{
			return name().toLowerCase(Locale.ROOT);
		}
	}

	public enum Combat
	{
		SINGLE, MULTI;

		public String id()
		{
			return name().toLowerCase(Locale.ROOT);
		}
	}

	/** Longest place text kept; the game's are far shorter. */
	public static final int MAX_LOCATION = 80;

	private static final String VERB = "has\\s+(?:spawned|appeared|opened|emerged)\\s+(?:at|in|on|near|inside|by)\\s+";
	private static final Pattern CHEST = Pattern.compile(
		"\\ba\\s+deadman'?s\\s+chest\\s+" + VERB + "(.+?)\\s*[!.]*\\s*$", Pattern.CASE_INSENSITIVE);
	private static final Pattern BREACH = Pattern.compile("\\ba\\s+breach\\s+" + VERB, Pattern.CASE_INSENSITIVE);
	private static final Pattern COMBAT_NOTE = Pattern.compile("\\s*\\((?:single|multi)[^)]*\\)\\s*$", Pattern.CASE_INSENSITIVE);
	/** A place has at least one letter or digit. */
	private static final Pattern HAS_WORD = Pattern.compile("[\\p{L}\\p{N}]");
	private static final Pattern MULTI = Pattern.compile("\\bmulti(?:[- ]?way|[- ]?combat)?\\b", Pattern.CASE_INSENSITIVE);
	private static final Pattern SINGLE = Pattern.compile("\\bsingle(?:[- ]?way|[- ]?combat)?\\b", Pattern.CASE_INSENSITIVE);

	Kind kind;
	/** The place as the game wrote it (tags removed, trimmed). */
	String location;
	/** Breaches only, when the message says; null otherwise. */
	Combat combat;

	/** The event a game message announces, or empty for any other text. */
	public static Optional<WorldEventMessage> parse(String raw)
	{
		if (raw == null || raw.isEmpty())
		{
			return Optional.empty();
		}
		String text = clean(raw);
		Matcher m = CHEST.matcher(text);
		if (m.find())
		{
			return of(Kind.CHEST, m.group(1), null);
		}
		m = BREACH.matcher(text);
		if (m.find())
		{
			// The place ends at the "!" (or, failing that, at the end of the sentence); the rest says Single / Multi.
			String after = text.substring(m.end());
			int cut = after.indexOf('!');
			if (cut < 0)
			{
				cut = after.indexOf(". ");
			}
			String place = cut < 0 ? after : after.substring(0, cut);
			String rest = cut < 0 ? place : after.substring(cut + 1);
			Combat combat = MULTI.matcher(rest).find() ? Combat.MULTI : SINGLE.matcher(rest).find() ? Combat.SINGLE : null;
			place = COMBAT_NOTE.matcher(place.replaceAll("[.!\\s]+$", "")).replaceAll("").replaceAll("[.!\\s]+$", "");
			return of(Kind.BREACH, place, combat);
		}
		return Optional.empty();
	}

	/** The game's wording of an event (the developer tools feed this to {@link #parse}). */
	public static String gameText(Kind kind, String location, Combat combat)
	{
		if (kind == Kind.CHEST)
		{
			return "A Deadman's Chest has spawned at " + location + "!";
		}
		return "A breach has spawned at " + location + "! Powerful monsters now roam the area"
			+ (combat == null ? "." : combat == Combat.MULTI ? " (Multi)." : " (Single).");
	}

	private static Optional<WorldEventMessage> of(Kind kind, String place, Combat combat)
	{
		String p = place.trim();
		if (p.length() > MAX_LOCATION || !HAS_WORD.matcher(p).find())
		{
			return Optional.empty();
		}
		return Optional.of(new WorldEventMessage(kind, p, combat));
	}

	/** Tags removed, curly apostrophes made straight, spaces collapsed. */
	static String clean(String raw)
	{
		return Text.removeTags(raw)
			.replace('’', '\'')
			.replace('‘', '\'')
			.replace(' ', ' ')
			.replaceAll("\\s+", " ")
			.trim();
	}
}
