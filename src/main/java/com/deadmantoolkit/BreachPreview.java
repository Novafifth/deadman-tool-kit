package com.deadmantoolkit;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import lombok.Value;

/**
 * Developer-mode breach preview (pure): parses {@code ::breach} commands and works out how far to shift the breach
 * timer's clock so the real schedule is at the requested point. Only the breach timer's clock and schedule input
 * change; the banner then runs through its phases on its own. Never registered outside developer mode.
 * <ul>
 * <li>{@code ::breach soon}: the next breach starts in 2 minutes;</li>
 * <li>{@code ::breach active}: a breach started just now;</li>
 * <li>{@code ::breach despawn}: spawning ended just now;</li>
 * <li>{@code ::breach msg <h> <m>}: as if the game said "The next breach will appear in h hour(s), m minute(s)";</li>
 * <li>{@code ::breach off}: the real clock and schedule again.</li>
 * </ul>
 */
public final class BreachPreview
{
	public static final String COMMAND = "breach";
	public static final String USAGE = "Usage: ::breach soon | active | despawn | msg <hours> <minutes> | off";
	/** "soon" starts the next breach this long from now. */
	static final Duration SOON = Duration.ofMinutes(2);

	private BreachPreview()
	{
	}

	public enum Action
	{
		SOON, ACTIVE, DESPAWN, MSG, OFF
	}

	@Value
	public static class Command
	{
		Action action;
		/** msg only. */
		int hours;
		int minutes;
	}

	/** The command for {@code ::breach <args>}, or empty when the arguments aren't one. */
	public static Optional<Command> parse(String[] args)
	{
		if (args == null || args.length == 0 || args[0] == null)
		{
			return Optional.empty();
		}
		String a = args[0].trim().toLowerCase(Locale.ROOT);
		switch (a)
		{
			case "soon":
				return args.length == 1 ? Optional.of(new Command(Action.SOON, 0, 0)) : Optional.empty();
			case "active":
				return args.length == 1 ? Optional.of(new Command(Action.ACTIVE, 0, 0)) : Optional.empty();
			case "despawn":
				return args.length == 1 ? Optional.of(new Command(Action.DESPAWN, 0, 0)) : Optional.empty();
			case "off":
				return args.length == 1 ? Optional.of(new Command(Action.OFF, 0, 0)) : Optional.empty();
			case "msg":
			{
				if (args.length != 3)
				{
					return Optional.empty();
				}
				Integer h = smallInt(args[1]), m = smallInt(args[2]);
				return h == null || m == null ? Optional.empty() : Optional.of(new Command(Action.MSG, h, m));
			}
			default:
				return Optional.empty();
		}
	}

	/** 0..999, or null. */
	private static Integer smallInt(String s)
	{
		if (s == null || !s.trim().matches("\\d{1,3}"))
		{
			return null;
		}
		return Integer.parseInt(s.trim());
	}

	/**
	 * The offset to add to the real clock so that, at {@code realNow}, the real schedule is where the action asks:
	 * 2 minutes before a start (SOON), at a start (ACTIVE) or at the end of its spawn phase (DESPAWN). Zero for OFF; MSG
	 * keeps the current offset (it only feeds the message parser).
	 */
	public static Duration offset(Action action, Instant realNow, Duration current)
	{
		switch (action)
		{
			case SOON:
				return Duration.between(realNow, BreachSchedule.nextStart(realNow).minus(SOON));
			case ACTIVE:
				return Duration.between(realNow, BreachSchedule.nextStart(realNow));
			case DESPAWN:
				return Duration.between(realNow, BreachSchedule.nextStart(realNow).plus(BreachSchedule.SPAWN));
			case OFF:
				return Duration.ZERO;
			default:
				return current == null ? Duration.ZERO : current;
		}
	}

	/** The game message for {@code ::breach msg h m}, in the game's wording. */
	public static String gameMessage(int hours, int minutes)
	{
		return "The next breach will appear in " + hours + (hours == 1 ? " hour" : " hours") + ", " + minutes
			+ (minutes == 1 ? " minute" : " minutes") + ".";
	}

	/** The local chat confirmation for a command. */
	public static String confirmation(Command c)
	{
		switch (c.getAction())
		{
			case SOON:
				return "Breach preview: next breach starts in 2 minutes.";
			case ACTIVE:
				return "Breach preview: a breach started just now.";
			case DESPAWN:
				return "Breach preview: spawning ended just now; bosses despawn in 30 minutes.";
			case MSG:
				return "Breach preview: fed \"" + gameMessage(c.getHours(), c.getMinutes()) + "\"";
			default:
				return "Breach preview off: real clock and schedule.";
		}
	}
}
