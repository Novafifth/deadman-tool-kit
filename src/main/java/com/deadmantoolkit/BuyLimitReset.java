package com.deadmantoolkit;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;

/**
 * When the 4-hour GE buy limit of an item resets, as RuneLite's own Grand Exchange plugin records it. Pure.
 * <p>
 * That plugin keeps, in the current account's RS-profile config (group {@value #CONFIG_GROUP}, key
 * {@code buylimit_<itemId>}), an Instant (ISO text): set to four hours after a buy when no future reset time exists,
 * so it is when that item's buy limit window resets. Only read for the current profile, on world 345.
 */
public final class BuyLimitReset
{
	public static final String CONFIG_GROUP = "grandexchange";
	static final String KEY_PREFIX = "buylimit_";
	/** RuneLite's own group, where plugins' on/off state lives (key: the plugin class name in lower case). */
	public static final String RUNELITE_GROUP = "runelite";
	public static final String GE_PLUGIN_KEY = "grandexchangeplugin";
	/** The GE plugin's "Enable GE limit reset timer" setting (on by default). */
	public static final String CORE_RESET_KEY = "enableGELimitReset";

	private BuyLimitReset()
	{
	}

	/**
	 * Whether RuneLite's own Grand Exchange plugin already shows the buy limit reset on the offer setup screen ("Buy
	 * limit: 70 (2:14)"): the plugin is on (it is by default) and its reset timer setting is on (also the default).
	 * Then the DMM line leaves the reset out and keeps its room for the market numbers.
	 *
	 * @param gePluginEnabled the {@value #RUNELITE_GROUP}.{@value #GE_PLUGIN_KEY} value, or null (default: on)
	 * @param resetEnabled    the {@value #CONFIG_GROUP}.{@value #CORE_RESET_KEY} value, or null (default: on)
	 */
	public static boolean coreShowsReset(String gePluginEnabled, String resetEnabled)
	{
		return !"false".equalsIgnoreCase(trim(gePluginEnabled)) && !"false".equalsIgnoreCase(trim(resetEnabled));
	}

	/**
	 * The buy limit part of the GE offer setup's DMM line: the time left ({@link #remaining}) on a buy offer, unless
	 * RuneLite's GE plugin already shows it there ({@link #coreShowsReset}); else null.
	 */
	public static String forGeLine(boolean buyOffer, String gePluginEnabled, String resetEnabled, Instant reset,
		Instant now)
	{
		if (!buyOffer || coreShowsReset(gePluginEnabled, resetEnabled))
		{
			return null;
		}
		return remaining(reset, now);
	}

	private static String trim(String s)
	{
		return s == null ? null : s.trim();
	}

	/** The config key for an item's reset time. */
	public static String key(int itemId)
	{
		return KEY_PREFIX + itemId;
	}

	/** The stored value as an Instant (ISO text, or epoch milliseconds), or null when missing or unreadable. */
	public static Instant parse(String value)
	{
		if (value == null)
		{
			return null;
		}
		String v = value.trim();
		if (v.isEmpty())
		{
			return null;
		}
		try
		{
			if (v.chars().allMatch(Character::isDigit))
			{
				return Instant.ofEpochMilli(Long.parseLong(v));
			}
			return Instant.parse(v);
		}
		catch (DateTimeParseException | NumberFormatException | ArithmeticException ex)
		{
			return null;
		}
	}

	/** {@code reset} when it is still in the future at {@code now}, else null (no limit window running). */
	public static Instant future(Instant reset, Instant now)
	{
		return reset != null && reset.isAfter(now) ? reset : null;
	}

	/**
	 * How long until the reset, e.g. "2h 14m", "14m", "<1m"; null when it isn't in the future. Rounded down, like a
	 * countdown.
	 */
	public static String remaining(Instant reset, Instant now)
	{
		if (future(reset, now) == null)
		{
			return null;
		}
		long minutes = Duration.between(now, reset).getSeconds() / 60;
		if (minutes <= 0)
		{
			return "<1m";
		}
		long h = minutes / 60, m = minutes % 60;
		return h > 0 ? h + "h " + m + "m" : m + "m";
	}
}
