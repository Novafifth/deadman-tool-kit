package com.deadmantoolkit;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import lombok.Value;

/**
 * The permanent Deadman breach schedule: every Saturday and Sunday at 02:00, 06:00, 10:00, 14:00, 18:00 and 22:00
 * UTC (12 a week). Bosses spawn for about 15 minutes; any left alive despawn about 30 minutes after that.
 * Pure, all in UTC.
 */
public final class BreachSchedule
{
	static final int[] HOURS = {2, 6, 10, 14, 18, 22};
	public static final Duration SPAWN = Duration.ofMinutes(15);
	public static final Duration DESPAWN = Duration.ofMinutes(30);
	/** From the start of a breach to the end of its despawn phase. */
	static final Duration TOTAL = SPAWN.plus(DESPAWN);
	/** A corrected time this close to a scheduled start is taken to be that start (the game rounds to minutes). */
	static final Duration SNAP = Duration.ofMinutes(2);
	/** More than a week, so a search always finds a start. */
	private static final int SEARCH_DAYS = 8;

	public enum Phase
	{
		/** Bosses are spawning; {@link State#getAt()} is when spawning ends. */
		ACTIVE,
		/** Spawning is over; {@link State#getAt()} is when the remaining bosses despawn. */
		DESPAWN,
		/** {@link State#getAt()} is when the next breach starts. */
		UPCOMING,
	}

	@Value
	public static class State
	{
		Phase phase;
		Instant at;
	}

	private BreachSchedule()
	{
	}

	static boolean isBreachDay(DayOfWeek d)
	{
		return d == DayOfWeek.SATURDAY || d == DayOfWeek.SUNDAY;
	}

	/** The earliest scheduled start strictly after {@code now}. */
	static Instant nextStart(Instant now)
	{
		LocalDate day = now.atZone(ZoneOffset.UTC).toLocalDate();
		for (int i = 0; i <= SEARCH_DAYS; i++, day = day.plusDays(1))
		{
			if (!isBreachDay(day.getDayOfWeek()))
			{
				continue;
			}
			for (int h : HOURS)
			{
				Instant start = day.atTime(h, 0).toInstant(ZoneOffset.UTC);
				if (start.isAfter(now))
				{
					return start;
				}
			}
		}
		throw new IllegalStateException("no breach within a week of " + now);
	}

	/** The latest scheduled start at or before {@code now}. */
	static Instant lastStart(Instant now)
	{
		LocalDate day = now.atZone(ZoneOffset.UTC).toLocalDate();
		for (int i = 0; i <= SEARCH_DAYS; i++, day = day.minusDays(1))
		{
			if (!isBreachDay(day.getDayOfWeek()))
			{
				continue;
			}
			for (int k = HOURS.length - 1; k >= 0; k--)
			{
				Instant start = day.atTime(HOURS[k], 0).toInstant(ZoneOffset.UTC);
				if (!start.isAfter(now))
				{
					return start;
				}
			}
		}
		throw new IllegalStateException("no breach within a week before " + now);
	}

	/**
	 * Where we are in the breach cycle.
	 *
	 * @param correction a breach start reported by the game (see {@link #correct}), or null. It replaces the
	 *                   scheduled next start while in the future, and drives the phases for 45 minutes after it.
	 */
	public static State state(Instant now, Instant correction)
	{
		if (correction != null)
		{
			State s = phaseOf(correction, now);
			if (s != null)
			{
				return s;
			}
		}
		State s = phaseOf(lastStart(now), now);
		if (s != null)
		{
			return s;
		}
		Instant next = correction != null && correction.isAfter(now) ? correction : nextStart(now);
		return new State(Phase.UPCOMING, next);
	}

	/** The active or despawn phase of a breach that started at {@code start}, or null outside them. */
	private static State phaseOf(Instant start, Instant now)
	{
		if (now.isBefore(start))
		{
			return null;
		}
		Instant spawnEnd = start.plus(SPAWN);
		if (now.isBefore(spawnEnd))
		{
			return new State(Phase.ACTIVE, spawnEnd);
		}
		Instant despawnEnd = start.plus(TOTAL);
		if (now.isBefore(despawnEnd))
		{
			return new State(Phase.DESPAWN, despawnEnd);
		}
		return null;
	}

	/**
	 * The next breach start from the game's "next breach will appear in ..." message seen at {@code now}. The game
	 * only gives minutes, so a time within {@link #SNAP} of a scheduled start is that start; anything else (the
	 * schedule changed) is trusted as reported.
	 */
	public static Instant correct(Instant now, Duration until)
	{
		Instant predicted = now.plus(until);
		Instant scheduled = nextStart(predicted.minus(SNAP).minusSeconds(1));
		if (Duration.between(predicted, scheduled).abs().compareTo(SNAP) <= 0)
		{
			return scheduled;
		}
		return predicted;
	}
}
