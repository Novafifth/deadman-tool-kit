package com.deadmantoolkit.world;

import com.deadmantoolkit.BreachSchedule;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import lombok.Value;
import net.runelite.api.coords.WorldPoint;

/**
 * The world events going on now: the current Deadman's Chest and any breaches. Times come from the broadcast itself
 * (when it arrived, or the server's first report), never from a fixed schedule, since the game is up to a minute or so
 * off its :00 / :15 marks.
 * <ul>
 * <li>One chest at a time: a new chest replaces the last one, which otherwise stays listed {@link #CHEST_LIFE}.</li>
 * <li>Breaches come in waves of several; each is listed {@link #BREACH_LIFE}: its 15 minutes of spawning, then
 * {@link #AFTER_SPAWN} more (the monsters left can stay up to 30 minutes, but by then the breach is over).</li>
 * <li>The same event reported again (the broadcast repeated, or the server's copy of one seen here) within
 * {@link #SAME} is one event: the earliest time wins, and a sighting here wins over the server's.</li>
 * <li>One shooting star at a time ({@link Stars}): a star first seen later at another spot replaces the last one. It is
 * listed until it is seen to disappear or its "gone by" time passes (the earliest any sighting gave).</li>
 * </ul>
 * Pure; one thread (the client thread).
 */
public final class WorldEvents
{
	/** How long a breach stays listed after its spawning ends. */
	static final Duration AFTER_SPAWN = Duration.ofMinutes(5);
	public static final Duration BREACH_LIFE = BreachSchedule.SPAWN.plus(AFTER_SPAWN);
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
		/**
		 * Stars: the Deadman area it is in as the game's HUD names it for a player at the star ("guarded",
		 * "unguarded", ...), or null when nobody at it reported one. Null for broadcasts.
		 */
		String zone;

		public Event(WorldEventMessage.Kind kind, String location, WorldEventMessage.Combat combat, Instant at,
			Instant until, WorldLocations.Resolved where, Source source, int reporters)
		{
			this(kind, location, combat, at, until, where, source, reporters, null);
		}

		public Event(WorldEventMessage.Kind kind, String location, WorldEventMessage.Combat combat, Instant at,
			Instant until, WorldLocations.Resolved where, Source source, int reporters, String zone)
		{
			this.kind = kind;
			this.location = location;
			this.combat = combat;
			this.at = at;
			this.until = until;
			this.where = where;
			this.source = source;
			this.reporters = reporters;
			this.zone = zone;
		}

		/** Same kind and place (wording compared like {@link WorldLocations#key}). */
		boolean samePlace(WorldEventMessage.Kind k, String text)
		{
			return kind == k && WorldLocations.key(location).equals(WorldLocations.key(text));
		}
	}

	private Event chest;
	private Event star;
	private final List<Event> breaches = new ArrayList<>();

	/**
	 * A shooting star seen here or reported by the server. The same spot again keeps the earliest "gone by" (every
	 * sighting is an upper bound, a tier drop the exact time) and the first sighting's time.
	 *
	 * @param goneBy    the latest it can still be there ({@link Stars#goneBy})
	 * @param firstSeen when it was first seen
	 * @param place     how its spot is named ({@link WorldLocations#describe})
	 * @param zone      the Deadman area it is in ({@link Event#getZone}), or null when not known; the first known stays
	 * @return true when the list changed
	 */
	public boolean addStar(WorldPoint point, Instant goneBy, Instant firstSeen, String place, Source source, int reporters,
		String zone)
	{
		if (star != null && point.equals(star.where.getPoint()))
		{
			Instant until = goneBy.isBefore(star.until) ? goneBy : star.until;
			Instant at = firstSeen.isBefore(star.at) ? firstSeen : star.at;
			Source src = star.source == Source.SEEN || source == Source.SEEN ? Source.SEEN : star.source;
			Event merged = new Event(WorldEventMessage.Kind.STAR, star.location, null, at, until, star.where, src,
				Math.max(star.reporters, reporters), star.zone != null ? star.zone : zone);
			boolean changed = !merged.equals(star);
			star = merged;
			return changed;
		}
		if (star != null && !firstSeen.isAfter(star.at))
		{
			// An older star (a late report): the newer one stays.
			return false;
		}
		star = new Event(WorldEventMessage.Kind.STAR, place, null, firstSeen, goneBy, starWhere(point, place), source,
			reporters, zone);
		return true;
	}

	/** A player at the star saw which Deadman area it is in. @return true when that is new */
	public boolean starZone(WorldPoint point, String zone)
	{
		if (star == null || zone == null || star.zone != null || !point.equals(star.where.getPoint()))
		{
			return false;
		}
		star = new Event(star.kind, star.location, null, star.at, star.until, star.where, star.source, star.reporters, zone);
		return true;
	}

	/** The star at {@code point} was seen disappearing. @return true when it was listed */
	public boolean starGone(WorldPoint point)
	{
		if (star != null && point.equals(star.where.getPoint()))
		{
			star = null;
			return true;
		}
		return false;
	}

	/** The star, or null. */
	public Event star()
	{
		return star;
	}

	private static WorldLocations.Resolved starWhere(WorldPoint point, String place)
	{
		return new WorldLocations.Resolved(WorldLocations.Accuracy.EXACT, point, 0, place, null, null, false);
	}

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
		star = null;
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
		if (star != null && !now.isBefore(star.until))
		{
			star = null;
			changed = true;
		}
		changed |= breaches.removeIf(b -> !now.isBefore(b.until));
		return changed;
	}

	/** The chest (if any) first, then the star, then breaches, oldest first. Call {@link #expire} first. */
	public List<Event> active()
	{
		List<Event> out = new ArrayList<>();
		if (chest != null)
		{
			out.add(chest);
		}
		if (star != null)
		{
			out.add(star);
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
		if (star != null)
		{
			// The spot is exact; only its name may change.
			String place = locations.describe(star.where.getPoint());
			star = new Event(star.kind, place, null, star.at, star.until, starWhere(star.where.getPoint(), place),
				star.source, star.reporters, star.zone);
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
