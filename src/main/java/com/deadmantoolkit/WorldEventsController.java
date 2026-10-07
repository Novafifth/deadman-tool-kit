package com.deadmantoolkit;

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
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.BooleanSupplier;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.util.Filepath;
import net.runelite.client.util.Text;

/**
 * World events on world 345: reads the breach and Deadman's Chest broadcasts, works out where they are
 * ({@link WorldLocations}), keeps the ones going on now ({@link WorldEvents}), marks them on the world map
 * ({@link WorldEventMarkers}), lists them in the panel, and optionally points the hint arrow at one.
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

	/** Roughly every few seconds while logged in: end old events, poll the server now and then. Client thread. */
	void tick()
	{
		Instant now = Instant.now();
		if (events.expire(now))
		{
			refresh();
		}
		poll(now.getEpochSecond(), false);
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
		if (!sharing() || !onTrackedWorld.getAsBoolean() || client.getGameState() != GameState.LOGGED_IN)
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
			if (e.getKind() == WorldEventMessage.Kind.CHEST ? config.worldEventMapChests() : config.worldEventMapBreaches())
			{
				onMap.add(e);
			}
		}
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
