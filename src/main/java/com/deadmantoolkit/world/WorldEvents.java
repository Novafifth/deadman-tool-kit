package com.deadmantoolkit.world;

import com.deadmantoolkit.BreachSchedule;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import lombok.Value;

/**
 * The world events going on now: the current Deadman's Chest and any breaches. Times come from the broadcast itself
 * (when it arrived, or the server's first report), never from a fixed schedule, since the game is up to a minute or so
 * off its :00 / :15 marks.
 * <ul>
 * <li>One chest at a time: a new chest replaces the last one, which otherwise stays listed {@link #CHEST_LIFE}.</li>
 * <li>Breaches come in waves of several; each lasts {@link #BREACH_LIFE} (15 minutes spawning, then up to 30 more until
 * the monsters left despawn).</li>
 * <li>The same event reported again (the broadcast repeated, or the server's copy of one seen here) within
 * {@link #SAME} is one event: the earliest time wins, and a sighting here wins over the server's.</li>
 * </ul>
 * Pure; one thread (the client thread).
 */
public final class WorldEvents
{
	public static final Duration BREACH_LIFE = BreachSchedule.SPAWN.plus(BreachSchedule.DESPAWN);
	/** A chest is soon looted: it is listed this long after its broadcast (or until the next chest). */
	public static final Duration CHEST_LIFE = Duration.ofMinutes(10);
	/** Reports of one event this far apart (or less) are the same event. */
	static final Duration SAME = Duration.ofMinutes(5);
	/** Most breaches kept at once; a wave is far smaller. */
	static final int MAX_BREACHES = 16;

	/** Where an event came from. */
	public enum Source
	{
		/** The broadcast arrived in this client. */
		SEEN,
		/** The server's list (other players saw it; for late logins). */
		REPORTED,
		/** The developer tools. */
		TEST
	}

	@Value
	public static class Event
	{
		WorldEventMessage.Kind kind;
		/** As the game wrote it. */
		String location;
		/** Breaches: Single / Multi when known. */
		WorldEventMessage.Combat combat;
		Instant at;
		Instant until;
		WorldLocations.Resolved where;
		Source source;
		/** Players who reported it to the server (0 when only seen here). */
		int reporters;

		/** Same kind and place (wording compared like {@link WorldLocations#key}). */
		boolean samePlace(WorldEventMessage.Kind k, String text)
		{
			return kind == k && WorldLocations.key(location).equals(WorldLocations.key(text));
		}
	}

	private Event chest;
	private final List<Event> breaches = new ArrayList<>();

	/**
	 * Add an event.
	 *
	 * @return true when the list changed (a new event, or a better copy of one listed)
	 */
	public boolean add(WorldEventMessage m, Instant at, WorldLocations.Resolved where, Source source, int reporters)
	{
		Instant until = at.plus(m.getKind() == WorldEventMessage.Kind.CHEST ? CHEST_LIFE : BREACH_LIFE);
		Event e = new Event(m.getKind(), m.getLocation(), m.getCombat(), at, until, where, source, reporters);
		if (m.getKind() == WorldEventMessage.Kind.CHEST)
		{
			if (chest != null && chest.samePlace(m.getKind(), m.getLocation()) && near(chest.at, at))
			{
				Event merged = merge(chest, e);
				boolean changed = !merged.equals(chest);
				chest = merged;
				return changed;
			}
			if (chest != null && at.isBefore(chest.at))
			{
				// An older chest (a late server report): the newer one stays.
				return false;
			}
			chest = e;
			return true;
		}
		for (int i = 0; i < breaches.size(); i++)
		{
			Event b = breaches.get(i);
			if (b.samePlace(m.getKind(), m.getLocation()) && near(b.at, at))
			{
				Event merged = merge(b, e);
				boolean changed = !merged.equals(b);
				breaches.set(i, merged);
				return changed;
			}
		}
		breaches.add(e);
		breaches.sort(Comparator.comparing(Event::getAt));
		while (breaches.size() > MAX_BREACHES)
		{
			breaches.remove(0);
		}
		return true;
	}

	/** Forget everything (logged out of world 345, or the developer tools' clear). */
	public void clear()
	{
		chest = null;
		breaches.clear();
	}

	/** Drop ended events. @return true when something was dropped */
	public boolean expire(Instant now)
	{
		boolean changed = false;
		if (chest != null && !now.isBefore(chest.until))
		{
			chest = null;
			changed = true;
		}
		changed |= breaches.removeIf(b -> !now.isBefore(b.until));
		return changed;
	}

	/** The chest (if any) first, then breaches, oldest first. Call {@link #expire} first. */
	public List<Event> active()
	{
		List<Event> out = new ArrayList<>();
		if (chest != null)
		{
			out.add(chest);
		}
		out.addAll(breaches);
		return out;
	}

	/** Re-resolve every place (after a newer place list arrived). */
	public void reresolve(WorldLocations locations)
	{
		if (chest != null)
		{
			chest = withWhere(chest, locations.resolve(chest.location));
		}
		breaches.replaceAll(b -> withWhere(b, locations.resolve(b.location)));
	}

	private static Event withWhere(Event e, WorldLocations.Resolved where)
	{
		return new Event(e.kind, e.location, e.combat, e.at, e.until, where, e.source, e.reporters);
	}

	private static boolean near(Instant a, Instant b)
	{
		return Duration.between(a, b).abs().compareTo(SAME) <= 0;
	}

	/** One event from two reports: the earliest time, a sighting here over the server's, the most reporters. */
	private static Event merge(Event a, Event b)
	{
		Event first = b.at.isBefore(a.at) ? b : a;
		Source source = a.source == Source.SEEN || b.source == Source.SEEN ? Source.SEEN : first.source;
		WorldEventMessage.Combat combat = a.combat != null ? a.combat : b.combat;
		WorldLocations.Resolved where = better(a.where, b.where);
		return new Event(first.kind, first.location, combat, first.at, first.until, where, source,
			Math.max(a.reporters, b.reporters));
	}

	private static WorldLocations.Resolved better(WorldLocations.Resolved a, WorldLocations.Resolved b)
	{
		if (a == null)
		{
			return b;
		}
		if (b == null)
		{
			return a;
		}
		return b.getAccuracy().ordinal() < a.getAccuracy().ordinal() ? b : a;
	}
}
