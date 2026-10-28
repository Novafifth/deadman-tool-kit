package com.deadmantoolkit.world;

import java.time.Duration;
import java.time.Instant;
import net.runelite.api.gameval.ObjectID;
import net.runelite.client.util.Text;

/**
 * Crashed shooting stars. A star loses a tier every {@link #TIER_TIME}, mined or not, and disappears that long after
 * reaching tier 1 (or when another star lands on the same world: one star per world). So a star seen at tier N at time
 * t is gone by t + N * 7 minutes at the latest; seeing its tier drop gives the exact time. Pure.
 */
public final class Stars
{
	public static final Duration TIER_TIME = Duration.ofMinutes(7);
	public static final int MAX_TIER = 9;

	private Stars()
	{
	}

	/** The tier of a crashed star object, or 0 for any other object. */
	public static int tierOf(int objectId)
	{
		switch (objectId)
		{
			case ObjectID.STAR_SIZE_ONE_STAR:
				return 1;
			case ObjectID.STAR_SIZE_TWO_STAR:
				return 2;
			case ObjectID.STAR_SIZE_THREE_STAR:
				return 3;
			case ObjectID.STAR_SIZE_FOUR_STAR:
				return 4;
			case ObjectID.STAR_SIZE_FIVE_STAR:
				return 5;
			case ObjectID.STAR_SIZE_SIX_STAR:
				return 6;
			case ObjectID.STAR_SIZE_SEVEN_STAR:
				return 7;
			case ObjectID.STAR_SIZE_EIGHT_STAR:
				return 8;
			case ObjectID.STAR_SIZE_NINE_STAR:
				return 9;
			default:
				return 0;
		}
	}

	/** Longest zone id kept (the game's words are short). */
	public static final int MAX_ZONE = 16;

	/**
	 * The Deadman area as an id from the game's HUD word under the PvP icon ("Guarded", "Deadman" for unguarded
	 * areas): lower case letters only, "deadman" read as "unguarded"; null when there is no word.
	 */
	public static String zoneId(String hudText)
	{
		if (hudText == null)
		{
			return null;
		}
		String w = Text.removeTags(hudText).toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z]", "");
		if (w.isEmpty() || w.length() > MAX_ZONE)
		{
			return null;
		}
		return w.equals("deadman") ? "unguarded" : w;
	}

	/**
	 * Whether a star in {@code zoneId} may be sent: only once its area is known (someone stood at it), and only for the
	 * areas the player shares. "unguarded" is the unguarded setting; guarded and any other area (a safe zone) the
	 * guarded one.
	 */
	public static boolean shared(String zoneId, boolean guarded, boolean unguarded)
	{
		if (zoneId == null)
		{
			return false;
		}
		return zoneId.equals("unguarded") ? unguarded : guarded;
	}

	/** A zone id for display: "Guarded", "Unguarded"; null for none. */
	public static String zoneName(String zoneId)
	{
		if (zoneId == null || zoneId.isEmpty())
		{
			return null;
		}
		return Character.toUpperCase(zoneId.charAt(0)) + zoneId.substring(1);
	}

	/** The latest a star seen at {@code tier} at {@code seen} can still be there. */
	public static Instant goneBy(int tier, Instant seen)
	{
		return seen.plus(TIER_TIME.multipliedBy(tier));
	}

	/** The tier a star gone by {@code until} has at {@code now}, at most; 0 once it is gone. */
	public static int tierAt(Instant until, Instant now)
	{
		long left = Duration.between(now, until).getSeconds();
		if (left <= 0)
		{
			return 0;
		}
		long step = TIER_TIME.getSeconds();
		return (int) Math.min(MAX_TIER, (left + step - 1) / step);
	}
}
