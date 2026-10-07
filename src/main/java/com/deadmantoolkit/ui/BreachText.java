package com.deadmantoolkit.ui;

import com.deadmantoolkit.BreachSchedule;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * The breach banner's text. Pure: the time and zone are passed in.
 */
final class BreachText
{
	private static final DateTimeFormatter LOCAL_TIME = DateTimeFormatter.ofPattern("EEE HH:mm", Locale.ENGLISH);

	private BreachText()
	{
	}

	static String of(BreachSchedule.State state, Instant now, ZoneId zone)
	{
		Duration left = Duration.between(now, state.getAt());
		switch (state.getPhase())
		{
			case ACTIVE:
				return "Breach active, spawning ends in " + clock(left);
			case DESPAWN:
				return "Breach bosses despawn in ~" + clock(left);
			default:
				return "Next breach in " + countdown(left) + " (" + LOCAL_TIME.format(state.getAt().atZone(zone)) + ")";
		}
	}

	/** mm:ss, never negative. Minutes aren't capped at 59 (phases are at most 30 minutes anyway). */
	static String clock(Duration d)
	{
		long s = Math.max(0, d.getSeconds());
		return String.format("%02d:%02d", s / 60, s % 60);
	}

	/** "1d 4h 12m", dropping leading zero units ("4h 0m", "12m"), and "<1m" under a minute. */
	static String countdown(Duration d)
	{
		long totalMinutes = Math.max(0, d.getSeconds()) / 60;
		if (totalMinutes == 0)
		{
			return "<1m";
		}
		long days = totalMinutes / (24 * 60);
		long hours = totalMinutes / 60 % 24;
		long minutes = totalMinutes % 60;
		if (days > 0)
		{
			return days + "d " + hours + "h " + minutes + "m";
		}
		if (hours > 0)
		{
			return hours + "h " + minutes + "m";
		}
		return minutes + "m";
	}
}
