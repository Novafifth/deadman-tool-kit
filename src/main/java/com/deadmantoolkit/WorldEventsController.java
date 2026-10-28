package com.deadmantoolkit;

import com.deadmantoolkit.world.Stars;
import com.deadmantoolkit.world.UnresolvedLog;
import com.deadmantoolkit.world.WorldEventMarkers;
import com.deadmantoolkit.world.WorldEventMessage;
import com.deadmantoolkit.world.WorldEvents;
import com.deadmantoolkit.world.WorldLocations;
import com.deadmantoolkit.world.WorldPlaces;
import com.google.gson.Gson;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.BooleanSupplier;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameObject;
import net.runelite.api.GameState;
import net.runelite.api.Player;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.util.Filepath;
import net.runelite.client.util.Text;

/**
 * World events on world 345: reads the breach and Deadman's Chest broadcasts, works out where they are
 * ({@link WorldLocations}), notices crashed shooting stars near you ({@link Stars}: the object and its tier), keeps the
 * ones going on now ({@link WorldEvents}), marks them on the world map ({@link WorldEventMarkers}), lists them in the
 * panel, and optionally points the hint arrow at one.
 * <p>
 * With the server (connected, "Share world events" on): reports what this client saw, fetches what was broadcast before
 * you logged in, and keeps the place list current (GET v1/world-places; the newest revision of the bundled copy, the
 * saved copy {@value #PLACES_FILE} and the server's wins). Places that can't be mapped go to {@link UnresolvedLog}.
 * <p>
 * Threads: the event state, markers and hint arrow live on the client thread; disk IO and identity reads on the
 * executor; server callbacks come back through {@link ClientThread#invoke}.
 */
@Slf4j
final class WorldEventsController
{
	/** The server's place list as last fetched, for the next start (and offline use). */
	static final String PLACES_FILE = "world-places.json";
	/** Ask the server for events at most this often (late logins get them right away). */
	static final long ACTIVE_POLL_SECONDS = 5 * 60;
	/** Ask for a newer place list at most this often. */
	static final long PLACES_POLL_SECONDS = 60 * 60;
	/**
	 * A star object that vanished counts as gone only if it vanished this close to you: further away it may just have
	 * left the loaded area (walking off, a loading screen), well inside it can't.
	 */
	static final int STAR_GONE_TILES = 15;
	/** Ticks to wait after a star object vanished for the next tier's object to take its place. */
	static final int STAR_REPLACE_TICKS = 2;
	/**
	 * Standing this close to a star (where you mine it), the game's HUD word under the PvP icon names the star's own
	 * Deadman area ("Guarded", "Deadman"); further away it may be the area you are looking in from.
	 */
	static final int STAR_ZONE_TILES = 2;

	/** Where the panel list and the hint arrow go. */
	interface View
	{
		/** EDT not required: implementations hop threads themselves. */
		void show(List<WorldEvents.Event> events, WorldPoint arrowAt);
	}

	private final Client client;
	private final ClientThread clientThread;
	private final ScheduledExecutorService executor;
	private final DeadmanToolKitConfig config;
	private final Gson gson;
	private final WorldEventMarkers markers;
	private final UnresolvedLog unresolved;
	private final WorldEventClient server;
	private final UploadSpool.Dir dir;
	private final BooleanSupplier onTrackedWorld;
	private final View view;

	// Client thread.
	private final WorldEvents events = new WorldEvents();
	private WorldLocations locations;
	/** A panel line was clicked: the hint arrow points at this event's spot until clicked again or it ends. */
	private WorldPoint pinnedArrow;
	/** The events the minimap overlay draws (set by refresh). */
	private List<WorldEvents.Event> minimapEvents = Collections.emptyList();
	/** The plugin was shut down: nothing is drawn any more. */
	private boolean stopped;
	private long lastActivePoll;
	private long lastPlacesPoll;
	/** Crashed star objects in the loaded area, by tile. */
	private final Map<WorldPoint, GameObject> starObjects = new HashMap<>();
	/** The tier last seen on each star tile here, until it is gone or we leave the world. */
	private final Map<WorldPoint, Integer> starTiers = new HashMap<>();
	/** Star tiles whose object vanished, with the tick: gone unless the next tier replaces it. */
	private final Map<WorldPoint, Integer> starVanished = new HashMap<>();
	/** The last star report sent (tile and tier), so walking back into view doesn't send it again. */
	private WorldPoint reportedStarAt;
	private int reportedStarTier;
	/** The star whose area was sent. */
	private WorldPoint zoneReportedAt;
	/** The star tier the markers were last drawn with (they are redrawn as it counts down). */
	private int shownStarTier;
	/** ETag of the server's place list we have; any thread. */
	private volatile String placesEtag;

	WorldEventsController(Client client, ClientThread clientThread, ScheduledExecutorService executor,
		DeadmanToolKitConfig config, Gson gson, WorldEventMarkers markers, WorldEventClient server,
		UploadSpool.Dir dir, BooleanSupplier onTrackedWorld, View view)
	{
		this.client = client;
		this.clientThread = clientThread;
		this.executor = executor;
		this.config = config;
		this.gson = gson;
		this.markers = markers;
		this.server = server;
		this.dir = dir;
		this.onTrackedWorld = onTrackedWorld;
		this.view = view;
		this.unresolved = new UnresolvedLog(dir::get, gson);
	}

	/** Load the places (bundled, then a newer saved copy) off the client thread. Called once from startUp. */
	void start()
	{
		executor.execute(() ->
		{
			WorldLocations bundled = WorldLocations.load(gson);
			WorldPlaces.Table saved = readSavedPlaces();
			WorldLocations use = saved != null && saved.getRevision() > bundled.revision() ? new WorldLocations(saved) : bundled;
			clientThread.invoke(() ->
			{
				if (stopped)
				{
					return;
				}
				// The server's list may have arrived first; keep whichever is newer.
				if (locations == null || use.revision() > locations.revision())
				{
					locations = use;
				}
				events.reresolve(locations);
				refresh();
			});
		});
	}

	/**
	 * Remove markers and our hint arrow for good: a server reply or place-list load that finishes later finds the
	 * controller stopped and draws nothing. Client thread.
	 */
	void stop()
	{
		stopped = true;
		markers.clear();
		pinnedArrow = null;
		minimapEvents = Collections.emptyList();
	}

	/** A game message or broadcast. Client thread. */
	void onMessage(String text)
	{
		if (!onTrackedWorld.getAsBoolean())
		{
			return;
		}
		Optional<WorldEventMessage> m = WorldEventMessage.parse(text);
		if (m.isPresent())
		{
			handle(m.get(), Instant.now(), WorldEvents.Source.SEEN, 0);
		}
	}

	/** Developer tools: as if the game had broadcast {@code gameText} (never reported to the server). Client thread. */
	void inject(String gameText)
	{
		WorldEventMessage.parse(gameText).ifPresent(m -> handle(m, Instant.now(), WorldEvents.Source.TEST, 0));
	}

	/** Developer tools: forget all events. Client thread. */
	void clear()
	{
		events.clear();
		pinnedArrow = null;
		refresh();
	}

	/** Developer tools: a test star of {@code tier} on your tile (never reported to the server). Client thread. */
	void injectStar(int tier)
	{
		Player me = client.getLocalPlayer();
		if (me == null || tier < 1 || tier > Stars.MAX_TIER)
		{
			return;
		}
		WorldPoint at = me.getWorldLocation();
		Instant now = Instant.now();
		events.addStar(at, Stars.goneBy(tier, now), now, describe(at), WorldEvents.Source.TEST, 0, null);
		refresh();
	}

	/** Roughly every few seconds while logged in: end old events, poll the server now and then. Client thread. */
	void tick()
	{
		Instant now = Instant.now();
		boolean changed = events.expire(now);
		// A star's tier counts down on its own: redraw the markers (their labels show the tier) when it does.
		WorldEvents.Event star = events.star();
		int tier = star == null ? 0 : WorldEventMarkers.starTier(star, now);
		if (changed || tier != shownStarTier)
		{
			refresh();
		}
		poll(now.getEpochSecond(), false);
	}

	/* ------------------------------------------------------------------ shooting stars seen here */

	/** A crashed star object appeared in the loaded area (also when you walk into view of one). Client thread. */
	void onStarSpawned(GameObject o)
	{
		int tier = Stars.tierOf(o.getId());
		if (tier == 0 || stopped || !onTrackedWorld.getAsBoolean())
		{
			return;
		}
		WorldPoint at = o.getWorldLocation();
		starObjects.put(at, o);
		starVanished.remove(at);
		Integer before = starTiers.put(at, tier);
		// A lower tier taking the place of the one seen here: its 7 minutes start now, the exact "gone by".
		seenStar(at, tier, before != null && before > tier);
	}

	/** A crashed star object left the loaded area or the game. Client thread. */
	void onStarDespawned(GameObject o)
	{
		if (Stars.tierOf(o.getId()) == 0)
		{
			return;
		}
		WorldPoint at = o.getWorldLocation();
		if (starObjects.get(at) == o)
		{
			starObjects.remove(at);
		}
		starVanished.put(at, client.getTickCount());
	}

	/**
	 * Every game tick: a star object that vanished and wasn't replaced by the next tier is gone; standing at the star,
	 * its Deadman area is read from the HUD. Client thread.
	 */
	void onGameTick()
	{
		checkStarZone();
		if (starVanished.isEmpty())
		{
			return;
		}
		int tick = client.getTickCount();
		Iterator<Map.Entry<WorldPoint, Integer>> it = starVanished.entrySet().iterator();
		while (it.hasNext())
		{
			Map.Entry<WorldPoint, Integer> en = it.next();
			if (tick - en.getValue() < STAR_REPLACE_TICKS)
			{
				continue;
			}
			it.remove();
			WorldPoint at = en.getKey();
			if (starObjects.containsKey(at))
			{
				// The next tier took its place.
				continue;
			}
			starTiers.remove(at);
			Player me = client.getLocalPlayer();
			WorldPoint mine = me == null ? null : me.getWorldLocation();
			if (client.getGameState() == GameState.LOGGED_IN && mine != null && mine.getPlane() == at.getPlane()
				&& mine.distanceTo2D(at) <= STAR_GONE_TILES)
			{
				starGone(at);
			}
		}
	}

	/** Logged out or hopping: the stars seen here belong to the world being left. Client thread. */
	void onLeftWorld()
	{
		starObjects.clear();
		starTiers.clear();
		starVanished.clear();
		reportedStarAt = null;
		reportedStarTier = 0;
		zoneReportedAt = null;
	}

	/** Standing at the star in view whose area isn't known yet: read it from the HUD, show it and send it. */
	private void checkStarZone()
	{
		if (starTiers.isEmpty())
		{
			return;
		}
		WorldEvents.Event star = events.star();
		if (star == null || star.getZone() != null)
		{
			return;
		}
		WorldPoint at = star.getWhere().getPoint();
		Integer tier = starTiers.get(at);
		Player me = client.getLocalPlayer();
		if (tier == null || me == null)
		{
			return;
		}
		WorldPoint mine = me.getWorldLocation();
		if (mine.getPlane() != at.getPlane() || mine.distanceTo2D(at) > STAR_ZONE_TILES)
		{
			return;
		}
		Widget w = client.getWidget(InterfaceID.PvpIcons.WILDERNESSLEVEL);
		String zone = w == null || w.isHidden() || w.getText() == null ? null : Stars.zoneId(Text.removeTags(w.getText()));
		if (zone == null || !events.starZone(at, zone))
		{
			return;
		}
		refresh();
		if (sharesStar(zone) && !at.equals(zoneReportedAt))
		{
			zoneReportedAt = at;
			reportedStarAt = at;
			reportedStarTier = tier;
			int world = client.getWorld();
			long ts = Instant.now().getEpochSecond();
			int seenTier = tier;
			executor.execute(() -> server.reportStar(world, at, seenTier, false, ts, zone));
		}
	}

	private void seenStar(WorldPoint at, int tier, boolean dropped)
	{
		Instant now = Instant.now();
		boolean changed = events.addStar(at, Stars.goneBy(tier, now), now, describe(at), WorldEvents.Source.SEEN, 0, null);
		// Sent only once its area is known (someone stood at it) and that area is one the player shares.
		String zone = starZone(at);
		if (sharesStar(zone) && (tier != reportedStarTier || !at.equals(reportedStarAt)))
		{
			reportedStarAt = at;
			reportedStarTier = tier;
			int world = client.getWorld();
			long ts = now.getEpochSecond();
			executor.execute(() -> server.reportStar(world, at, tier, dropped, ts, zone));
		}
		if (changed)
		{
			refresh();
		}
	}

	private void starGone(WorldPoint at)
	{
		String zone = starZone(at);
		boolean changed = events.starGone(at);
		if (sharesStar(zone))
		{
			int world = client.getWorld();
			long ts = Instant.now().getEpochSecond();
			executor.execute(() -> server.reportStar(world, at, 0, false, ts));
		}
		if (at.equals(reportedStarAt))
		{
			reportedStarAt = null;
			reportedStarTier = 0;
		}
		if (changed)
		{
			refresh();
		}
	}

	/** How a star's spot is named: the nearest landmark, region or its coordinates. */
	private String describe(WorldPoint at)
	{
		WorldLocations loc = locations;
		return loc != null ? loc.describe(at) : "at " + at.getX() + ", " + at.getY();
	}

	/** The area of the star listed at {@code at}, or null when unknown (or another star is listed). */
	private String starZone(WorldPoint at)
	{
		WorldEvents.Event star = events.star();
		return star != null && at.equals(star.getWhere().getPoint()) ? star.getZone() : null;
	}

	/** Connected, and the star's area is known and one the player shares ("Share guarded / unguarded stars"). */
	private boolean sharesStar(String zone)
	{
		return config.connected() && Stars.shared(zone, config.shareStarsGuarded(), config.shareStarsUnguarded());
	}

	/** Logged in (on any world), or hopped: markers follow the world; late logins ask the server. Client thread. */
	void onLoggedIn()
	{
		refresh();
		poll(Instant.now().getEpochSecond(), true);
	}

	/** Settings changed (markers, arrow, sharing). Client thread. */
	void onConfigChanged()
	{
		refresh();
	}

	/** A panel line was clicked: point the hint arrow at it, or stop if it already points there. Any thread. */
	void pointArrow(WorldEvents.Event e)
	{
		clientThread.invoke(() ->
		{
			WorldPoint at = arrowSpot(e);
			pinnedArrow = at == null || at.equals(pinnedArrow) ? null : at;
			refresh();
		});
	}

	private void handle(WorldEventMessage m, Instant at, WorldEvents.Source source, int reporters)
	{
		WorldLocations loc = locations;
		WorldLocations.Resolved where = loc == null ? null : loc.resolve(m.getLocation());
		boolean changed = events.add(m, at, where, source, reporters);
		// Not placed at all, or only by a near spelling: noted, so the place list can learn the game's wording.
		if (loc != null && source != WorldEvents.Source.REPORTED && (!where.known() || where.isFuzzy()))
		{
			long ts = at.getEpochSecond();
			boolean test = source == WorldEvents.Source.TEST;
			String matched = where.isFuzzy() ? where.getPlace() : null;
			executor.execute(() -> unresolved.record(m.getKind(), m.getLocation(), ts, test, matched));
		}
		if (source == WorldEvents.Source.SEEN && sharing())
		{
			int world = client.getWorld();
			WorldLocations.Accuracy accuracy = where == null ? WorldLocations.Accuracy.UNKNOWN : where.reported();
			long ts = at.getEpochSecond();
			executor.execute(() -> server.report(m, ts, world, accuracy));
		}
		if (changed)
		{
			refresh();
		}
	}

	private boolean sharing()
	{
		return config.connected() && config.shareWorldEvents();
	}

	/** Ask the server for current events (and now and then a newer place list). Client thread. */
	private void poll(long now, boolean force)
	{
		// Connected: the stars others found are always fetched (reading them says nothing about you); broadcasts only
		// with "Share world events" on.
		if (!config.connected() || !onTrackedWorld.getAsBoolean() || client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}
		if (force || now - lastActivePoll >= ACTIVE_POLL_SECONDS)
		{
			lastActivePoll = now;
			int world = client.getWorld();
			server.active(world, a -> clientThread.invoke(() -> addReported(a)));
		}
		if (now - lastPlacesPoll >= PLACES_POLL_SECONDS)
		{
			lastPlacesPoll = now;
			server.places(placesEtag, this::onPlaces);
		}
	}

	private void addReported(WorldEventClient.Active a)
	{
		if (stopped)
		{
			return;
		}
		if (a.stars != null)
		{
			for (WorldEventClient.ReportedStar r : a.stars)
			{
				addReportedStar(r);
			}
		}
		if (!sharing())
		{
			events.expire(Instant.now());
			refresh();
			return;
		}
		for (WorldEventClient.Reported r : a.events)
		{
			if (r == null || r.kind == null || r.place == null || r.at == null)
			{
				continue;
			}
			WorldEventMessage.Kind kind;
			try
			{
				kind = WorldEventMessage.Kind.valueOf(r.kind.toUpperCase(Locale.ROOT));
			}
			catch (IllegalArgumentException ex)
			{
				continue;
			}
			WorldEventMessage.Combat combat = "multi".equals(r.combat) ? WorldEventMessage.Combat.MULTI
				: "single".equals(r.combat) ? WorldEventMessage.Combat.SINGLE : null;
			// Other players' reports: plain text only (no colour or line tags in map labels).
			String place = Text.removeTags(r.place).trim();
			if (place.isEmpty() || place.length() > WorldEventMessage.MAX_LOCATION)
			{
				continue;
			}
			handle(new WorldEventMessage(kind, place, combat), Instant.ofEpochSecond(r.at), WorldEvents.Source.REPORTED,
				r.reporters == null ? 0 : r.reporters);
		}
		events.expire(Instant.now());
		refresh();
	}

	/** A star other players found: checked (a tile, a tier, times that make sense), then listed. */
	private void addReportedStar(WorldEventClient.ReportedStar r)
	{
		if (r == null || r.x == null || r.y == null || r.tier == null || r.goneBy == null || r.firstSeen == null
			|| r.tier < 1 || r.tier > Stars.MAX_TIER || r.x < 0 || r.y < 0 || r.x >= 16384 || r.y >= 16384)
		{
			return;
		}
		int plane = r.plane == null ? 0 : r.plane;
		if (plane < 0 || plane > 3)
		{
			return;
		}
		Instant goneBy = Instant.ofEpochSecond(r.goneBy);
		// Never longer than a fresh tier-9 star could last.
		if (goneBy.isAfter(Instant.now().plus(Stars.TIER_TIME.multipliedBy(Stars.MAX_TIER))))
		{
			return;
		}
		WorldPoint at = new WorldPoint(r.x, r.y, plane);
		// Other players' text: only a plain zone id is used.
		String zone = r.zone != null && r.zone.matches("[a-z]{1," + Stars.MAX_ZONE + "}") ? r.zone : null;
		events.addStar(at, goneBy, Instant.ofEpochSecond(r.firstSeen), describe(at), WorldEvents.Source.REPORTED,
			r.reporters == null ? 0 : r.reporters, zone);
	}

	/**
	 * The server's place list arrived (OkHttp thread): checked and indexed here, off the client thread, then used if it
	 * is newer, and saved for the next start. The list is only data (names and map tiles, bounded by
	 * {@link WorldPlaces#parse}); it can't change what the plugin does.
	 */
	private void onPlaces(String json, String etag)
	{
		WorldPlaces.Table table = WorldPlaces.parse(gson, json);
		if (table == null)
		{
			return;
		}
		WorldLocations newer = new WorldLocations(table);
		placesEtag = etag;
		executor.execute(() -> savePlaces(json));
		clientThread.invoke(() ->
		{
			if (!stopped && (locations == null || newer.revision() > locations.revision()))
			{
				log.debug("World places: revision {} from the server", newer.revision());
				locations = newer;
				events.reresolve(locations);
				refresh();
			}
		});
	}

	/** Markers, hint arrow and panel list from the current state. Client thread. */
	private void refresh()
	{
		if (stopped)
		{
			return;
		}
		List<WorldEvents.Event> list = events.active();
		boolean show = config.showWorldEvents();
		boolean here = onTrackedWorld.getAsBoolean() && client.getGameState() == GameState.LOGGED_IN;
		WorldPoint arrow = null;
		// The map settings only filter the world map and minimap; the panel still lists everything.
		List<WorldEvents.Event> onMap = new ArrayList<>();
		for (WorldEvents.Event e : list)
		{
			if (onMap(e.getKind()))
			{
				onMap.add(e);
			}
		}
		WorldEvents.Event star = events.star();
		shownStarTier = star == null ? 0 : WorldEventMarkers.starTier(star, Instant.now());
		if (show && here)
		{
			arrow = arrowTarget(list);
			markers.show(onMap, arrow);
		}
		else
		{
			markers.clear();
		}
		minimapEvents = show && here && config.worldEventMinimap() ? onMap : Collections.emptyList();
		view.show(show ? list : Collections.emptyList(), arrow);
	}

	private boolean onMap(WorldEventMessage.Kind kind)
	{
		switch (kind)
		{
			case CHEST:
				return config.worldEventMapChests();
			case STAR:
				return config.worldEventMapStars();
			default:
				return config.worldEventMapBreaches();
		}
	}

	/** What the minimap overlay shows now (empty when hidden or not on world 345). Client thread. */
	List<WorldEvents.Event> minimapEvents()
	{
		return minimapEvents;
	}

	/** The world map markers (the world map overlay keeps their edge arrows turned). Client thread. */
	WorldEventMarkers markers()
	{
		return markers;
	}

	/** The pinned event's spot while it lasts; else the chest's if the setting is on and its spot is known. */
	private WorldPoint arrowTarget(List<WorldEvents.Event> list)
	{
		if (pinnedArrow != null)
		{
			for (WorldEvents.Event e : list)
			{
				if (pinnedArrow.equals(arrowSpot(e)))
				{
					return pinnedArrow;
				}
			}
			pinnedArrow = null;
		}
		if (!config.worldEventArrow())
		{
			return null;
		}
		for (WorldEvents.Event e : list)
		{
			if (e.getKind() == WorldEventMessage.Kind.CHEST && e.getWhere() != null
				&& e.getWhere().getAccuracy().ordinal() <= WorldLocations.Accuracy.APPROXIMATE.ordinal())
			{
				return arrowSpot(e);
			}
		}
		return null;
	}

	/** Where an arrow to {@code e} points: its spot, or the surface entrance of a place on another map. */
	private static WorldPoint arrowSpot(WorldEvents.Event e)
	{
		WorldLocations.Resolved w = e.getWhere();
		if (w == null || !w.known() || w.getAccuracy() == WorldLocations.Accuracy.REGION)
		{
			return null;
		}
		return w.getEntrance() != null ? w.getEntrance() : w.getPoint();
	}

	/** The saved copy of the server's place list, or null. Executor. */
	private WorldPlaces.Table readSavedPlaces()
	{
		try
		{
			Filepath file = dir.get().joinSegment(PLACES_FILE);
			if (!file.isFile())
			{
				return null;
			}
			StringBuilder sb = new StringBuilder();
			char[] buf = new char[4096];
			try (BufferedReader r = file.openBufferedReader())
			{
				int n;
				while ((n = r.read(buf)) != -1)
				{
					sb.append(buf, 0, n);
				}
			}
			return WorldPlaces.parse(gson, sb.toString());
		}
		catch (IOException | RuntimeException ex)
		{
			log.debug("Couldn't read the saved place list: {}", ex.getClass().getSimpleName());
			return null;
		}
	}

	/** Save the server's place list (temp file, then rename). Executor. */
	private void savePlaces(String json)
	{
		try
		{
			Filepath folder = dir.get();
			if (!folder.exists())
			{
				folder.createDirectories();
			}
			Filepath tmp = folder.joinSegment(PLACES_FILE + ".tmp");
			try (BufferedWriter w = tmp.openBufferedWriter(StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING))
			{
				w.write(json);
			}
			tmp.moveTo(folder.joinSegment(PLACES_FILE), StandardCopyOption.REPLACE_EXISTING);
		}
		catch (IOException ex)
		{
			log.debug("Couldn't save the place list: {}", ex.getClass().getSimpleName());
		}
	}

	/** For tests. */
	List<WorldEvents.Event> active()
	{
		return new ArrayList<>(events.active());
	}
}
