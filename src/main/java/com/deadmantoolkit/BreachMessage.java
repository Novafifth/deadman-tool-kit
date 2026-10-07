package com.deadmantoolkit;

import java.time.Duration;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.runelite.client.util.Text;

/**
 * Reads the game message "The next breach will appear in X hour(s), Y minute(s)." Accepts singular or plural,
 * a missing hours or minutes part, "and" instead of a comma, colour tags, and "less than a minute".
 */
public final class BreachMessage
{
	private static final Pattern PATTERN = Pattern.compile(
		"next breach will appear in\\s+(?:(\\d+)\\s*hours?)?\\s*,?\\s*(?:and\\s+)?(?:(\\d+)\\s*minutes?)?",
		Pattern.CASE_INSENSITIVE);
	private static final Pattern LESS_THAN_A_MINUTE = Pattern.compile(
		"next breach will appear in\\s+less than a minute", Pattern.CASE_INSENSITIVE);
	/** Breaches are at most a week apart; anything longer isn't a countdown to trust. */
	private static final Duration MAX = Duration.ofDays(8);

	private BreachMessage()
	{
	}

	/** The time until the next breach, or empty when {@code raw} isn't that message. */
	public static Optional<Duration> parse(String raw)
	{
		if (raw == null)
		{
			return Optional.empty();
		}
		String text = Text.removeTags(raw);
		if (LESS_THAN_A_MINUTE.matcher(text).find())
		{
			return Optional.of(Duration.ofMinutes(1));
		}
		Matcher m = PATTERN.matcher(text);
		if (!m.find() || (m.group(1) == null && m.group(2) == null))
		{
			return Optional.empty();
		}
		try
		{
			long hours = m.group(1) == null ? 0 : Long.parseLong(m.group(1));
			long minutes = m.group(2) == null ? 0 : Long.parseLong(m.group(2));
			Duration d = Duration.ofHours(hours).plusMinutes(minutes);
			return d.compareTo(MAX) > 0 ? Optional.empty() : Optional.of(d);
		}
		catch (NumberFormatException | ArithmeticException e)
		{
			// More digits than a long holds: not a real message.
			return Optional.empty();
		}
	}
}
