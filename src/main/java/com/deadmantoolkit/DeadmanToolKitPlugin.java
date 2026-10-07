package com.deadmantoolkit;

import com.deadmantoolkit.ui.DeadmanToolKitPanel;
import com.deadmantoolkit.ui.RuneLiteImportAction;
import com.deadmantoolkit.ui.DevTools;
import com.deadmantoolkit.world.WorldEventMapOverlay;
import com.deadmantoolkit.world.WorldEventMarkers;
import com.deadmantoolkit.world.WorldEventMinimapOverlay;
import com.deadmantoolkit.ui.SharedDataActions;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.inject.Provides;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import javax.inject.Inject;
import javax.inject.Named;
import javax.swing.SwingUtilities;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.ItemComposition;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.ScriptID;
import net.runelite.api.WorldType;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.GrandExchangeOfferChanged;
import net.runelite.api.events.MenuEntryAdded;
import net.runelite.api.events.ScriptCallbackEvent;
import net.runelite.api.events.ScriptPostFired;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.Notifier;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.chat.ChatColorType;
import net.runelite.client.chat.ChatMessageBuilder;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.chat.QueuedMessage;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.config.RuneScapeProfileType;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.events.ClientShutdown;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.events.OverlayMenuClicked;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemStats;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.overlay.OverlayMenuEntry;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.ui.overlay.worldmap.WorldMapPointManager;
import net.runelite.client.util.AsyncBufferedImage;
import net.runelite.client.util.Filepath;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;

@Slf4j
@PluginDescriptor(
	name = "Deadman Tool Kit",
	description = "Tracks your own Grand Exchange trades on Deadman world 345 (offers vs market, profit, buy limits) and marks breach and Deadman's Chest broadcasts on the world map and minimap. Once you connect (off by default), your trades and the broadcasts you see are sent to a 3rd-party server to build a shared world 345 price index (it never stores IP addresses). Only when you ask, it imports the trade history RuneLite's own Grand Exchange plugin keeps for your account.",
	tags = {"deadman", "dmm", "grand exchange", "ge", "prices", "trade", "history", "flipping", "breach", "chest"},
	internalName = "deadman-tool-kit"
)
public class DeadmanToolKitPlugin extends Plugin
{
	/** The permanent Deadman world. Seasonal Deadman worlds have a separate economy and are ignored. */
	private static final int PERMANENT_DEADMAN_WORLD = 345;
	/** Collection server for every normal (Plugin Hub) install (Google Cloud Run, europe-north1). Players can't change it. */
	static final String PRODUCTION_SERVER_URL = "https://deadman-tool-kit-107023686748.europe-north1.run.app";
	/** Server used when RuneLite runs in developer mode (the dev client): the local server from server/ (npm run dev). */
	static final String LOCAL_SERVER_URL = "http://localhost:8787";
	/** Developer-mode only: point the dev client at another server, e.g. -Ddeadmantoolkit.server=https://staging.example */
	static final String SERVER_OVERRIDE_PROPERTY = "deadmantoolkit.server";
	private static final String MENU_OPTION = "DMM prices";
	/** Offer updates within this many ticks of login are the client replaying state, not live trades. */
	private static final int LOGIN_BURST_TICKS = 2;
	/** Re-send the open offers this often (ticks) so the server knows they are still up. */
	private static final int OPEN_OFFERS_INTERVAL_TICKS = 600;
	/** How many ticks to keep trying to read the GE History list after it opens. */
	private static final int HISTORY_READ_TICKS = 10;
	/** How often (ticks) to record that the offers were still unchanged, bounding when a logged-out fill happened. */
	private static final int SEEN_INTERVAL_TICKS = 100;
	/** World events: end old ones and poll the server about every 6 seconds. */
	private static final int WORLD_EVENT_TICKS = 10;
	/** After a fill, upload within about this long instead of waiting for the next scheduled flush. */
	private static final int FLUSH_SOON_SECONDS = 2;
	/** Tries for that upload while another one is still running. */
	private static final int FLUSH_SOON_TRIES = 3;
	/** How often the offer check runs; {@link OfferMarketWatch} decides whether it actually fetches (at most once a minute). */
	private static final int OFFER_CHECK_SECONDS = 10;
	/** RuneLite script callbacks around the GE offer setup's description text (see {@link GePriceText}). */
	static final String GE_BUY_EXAMINE = "geBuyExamineText";
	static final String GE_SELL_EXAMINE = "geSellExamineText";
	static final String CHAT_HINT = "Deadman Tool Kit: open the side panel to connect and import your GE History.";

	@Inject
	private Client client;

	@Inject
	private DeadmanToolKitConfig config;

	@Inject
	private ConfigManager configManager;

	@Inject
	private ItemManager itemManager;

	@Inject
	private Gson gson;

	@Inject
	private OkHttpClient okHttpClient;

	@Inject
	private ScheduledExecutorService executor;

	@Inject
	private ClientThread clientThread;

	@Inject
	private ClientToolbar clientToolbar;

	@Inject
	private TrackerStore store;

	@Inject
	private EventBus eventBus;

	@Inject
	private ChatMessageManager chatMessageManager;

	@Inject
	private Notifier notifier;

	@Inject
	private WorldMapPointManager worldMapPointManager;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	@Named("developerMode")
	private boolean developerMode;

	// Set in startUp and cleared in shutDown (EDT) but read from the client thread, executor and EDT: volatile, and
	// handlers read each one into a local once so a concurrent shutDown can't null it halfway through.
	private volatile OfferTracker tracker;
	private volatile TradeUploader uploader;
	/** Unsent uploads on disk, so closing RuneLite doesn't lose them. */
	private volatile UploadSpool spool;
	/** "Delete my shared data" (panel Privacy menu). */
	private volatile DataDeletion deletion;
	private volatile DeadmanToolKitPanel panel;
	private volatile NavigationButton navButton;
	private ScheduledFuture<?> flushTask;
	private ScheduledFuture<?> offerCheckTask;
	/** Compares your open offers with the market. */
	private volatile OfferMarketWatch offerWatch;
	/** Market summaries for the GE price line. */
	private volatile ItemQuoteCache geQuotes;
	/** Your profit per account; only used on the executor (see {@link ProfitStore}). */
	private volatile ProfitStore profit;
	/** The pending one-shot upload after a fill, if any; see {@link #requestFlushSoon}. */
	private final AtomicReference<ScheduledFuture<?>> soonFlush = new AtomicReference<>();
	/** Your open offers as last sent to the panel. Written on the client thread; shutDown resets it. */
	private volatile List<ActiveOffer> publishedOffers = Collections.emptyList();
	private final MyTrades myTrades = new MyTrades();
	private final LoginDetector loginDetector = new LoginDetector();
	/** "Import RuneLite GE history" is running (from the click until its result line). */
	private final AtomicBoolean importing = new AtomicBoolean();
	/** Developer mode only: the {@code ::breach} preview, registered on the event bus; null otherwise. */
	private volatile BreachCommands breachCommands;
	private volatile DevCommands devCommands;
	/** Breach and chest broadcasts: map markers, the panel list, the hint arrow. */
	private volatile WorldEventsController worldEvents;
	private WorldEventMapOverlay worldMapOverlay;
	private WorldEventMinimapOverlay minimapOverlay;
	/** Developer-mode breach preview: the breach timer's clock offset (zero normally). */
	private volatile Duration breachOffset = Duration.ZERO;
	/** Logged in on world 345, as of the last tick (client thread writes; the EDT reads it for the buy limit timer). */
	private volatile boolean onTrackedWorld;
	/** Whether the GE offer setup being shown is a buy offer (its text gets the buy limit timer). Client thread only. */
	private boolean geTextBuy;

	// Tick state. Written on the client thread; volatile only because shutDown (EDT) also resets it.
	/** Tick of the last real login (or hop / reconnect); not loading screens. */
	private volatile int lastLoginTick = -1;
	private volatile int nextOpenOffersTick = -1;
	private volatile int historyReadTicks;
	private volatile int lastGeItem = -1;
	/** Set on login: the next tick checks the account's onboarding state and the one-time chat hint. */
	private volatile boolean loginCheckPending;
	/** The RS profile whose GE History import state was last sent to the panel. */
	private volatile String announcedProfile;
	// The GE offer setup text, as last seen by the script callback. Client thread only.
	/** Canonical item the description text was last built for, or -1. */
	private int geTextItem = -1;
	/** The fee text the script had then (empty on Deadman, which has no GE tax). */
	private String geTextFee = "";

	// AccountId cache. Client thread only.
	private long cachedAccountHash = -1;
	private String cachedAccountId;

	/**
	 * Normal installs always use the production server. Only the developer-mode client uses the local server, or the
	 * override property if it is a valid URL.
	 */
	static String serverUrl(boolean developerMode, String override)
	{
		if (!developerMode)
		{
			return PRODUCTION_SERVER_URL;
		}
		if (override != null && HttpUrl.parse(override.trim()) != null)
		{
			return override.trim();
		}
		return LOCAL_SERVER_URL;
	}

	@Provides
	DeadmanToolKitConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(DeadmanToolKitConfig.class);
	}

	@Override
	protected void startUp()
	{
		HttpUrl base = HttpUrl.get(serverUrl(developerMode, developerMode ? System.getProperty(SERVER_OVERRIDE_PROPERTY) : null));
		log.debug("Using collection server {}", base);
		tracker = new OfferTracker(() -> UUID.randomUUID().toString());
		// The install id and token live in a file in the plugin's folder (never in the config, which is logged, shared
		// with other plugins and synced); read it now on the executor, off the client thread and the EDT.
		store.useDataDir(this::getPluginDirectory);
		executor.execute(store::loadIdentity);
		// Uploads carry the install token (registering first); nothing, registration included, is sent unless
		// connected and sharing.
		TradeUploader up = new TradeUploader(okHttpClient, gson, base, this::onUploaded, store, this::shareEnabled);
		uploader = up;
		// Uploads left over from the last session (RuneLite closed, server down, imports the server deferred) go back in
		// the queue; they are only sent if connected and sharing, like everything else.
		UploadSpool sp = new UploadSpool(this::getPluginDirectory, gson);
		spool = sp;
		executor.execute(() ->
		{
			List<TradeUploader.Queued> back = sp.load();
			if (!back.isEmpty())
			{
				up.restore(back);
				log.debug("Restored {} pending uploads", back.size());
			}
		});
		deletion = new DataDeletion(okHttpClient, gson, base, store, up,
			() -> configManager.setConfiguration(DeadmanToolKitConfig.GROUP, DeadmanToolKitConfig.KEY_CONNECTED, false));
		store.reset();
		flushTask = executor.scheduleWithFixedDelay(this::scheduledFlush, 15, 30, TimeUnit.SECONDS);
		MarketClient market = new MarketClient(okHttpClient, gson, base, PERMANENT_DEADMAN_WORLD);
		WorldEventsController we = new WorldEventsController(client, clientThread, executor, config, gson,
			new WorldEventMarkers(worldMapPointManager, client), new WorldEventClient(okHttpClient, gson, base, store),
			this::getPluginDirectory, this::isTrackedWorld, (events, arrow) -> SwingUtilities.invokeLater(() ->
			{
				DeadmanToolKitPanel p = panel;
				if (p != null)
				{
					p.setWorldEvents(events, arrow);
				}
			}));
		worldEvents = we;
		we.start();
		// Edge arrows on the world map, and the minimap arrows. Both only read client-thread state while rendering.
		worldMapOverlay = new WorldEventMapOverlay(client, we::markers);
		minimapOverlay = new WorldEventMinimapOverlay(client, we::minimapEvents);
		overlayManager.add(worldMapOverlay);
		overlayManager.add(minimapOverlay);
		geQuotes = new ItemQuoteCache(market::itemAsync);
		offerWatch = new OfferMarketWatch(market::items, System::currentTimeMillis, config::connected,
			() -> config.offerNotification().isEnabled(), this::panelShown, () -> config.staleHours() * 3600L, myTrades::snapshot,
			new OfferMarketWatch.Listener()
			{
				@Override
				public void onReport(OfferReport report)
				{
					SwingUtilities.invokeLater(() ->
					{
						DeadmanToolKitPanel p = panel;
						if (p != null)
						{
							p.onOfferReport(report);
						}
					});
				}

				@Override
				public void onAlert(String message)
				{
					clientThread.invoke(() -> notifier.notify(config.offerNotification(), message));
				}
			});
		offerCheckTask = executor.scheduleWithFixedDelay(this::checkOffers, OFFER_CHECK_SECONDS, OFFER_CHECK_SECONDS,
			TimeUnit.SECONDS);
		profit = new ProfitStore(this::getPluginDirectory, gson, executor, Clock.systemDefaultZone(), this::onProfit);
		clientThread.invoke(() ->
		{
			resetTickState();
			GameState state = client.getGameState();
			// Reset during LOADING makes the next LOGGED_IN count as a login, which schedules the open offers.
			loginDetector.reset(state);
			if (state == GameState.LOGGED_IN)
			{
				// Enabled while already logged in: no login event is coming, so send the open offers soon.
				nextOpenOffersTick = client.getTickCount() + 1;
				loginCheckPending = true;
				// ... and no offer events either, for slots that haven't changed since: compare all 8 now.
				reconcileSlots();
			}
		});

		// RuneLite's config plugin answers this event by opening overlay.getPlugin()'s configuration page. The overlay
		// is never registered or drawn, so there is nothing to remove in shutDown.
		ConfigLinkOverlay overlay = new ConfigLinkOverlay(this);
		Runnable openConfig = () -> eventBus.post(new OverlayMenuClicked(
			new OverlayMenuEntry(MenuAction.RUNELITE_OVERLAY_CONFIG, "Configure", "Deadman Tool Kit"), overlay));
		panel = new DeadmanToolKitPanel(config, configManager, itemManager, market, myTrades::snapshot, base, openConfig,
			() ->
			{
				OfferMarketWatch w = offerWatch;
				if (w != null)
				{
					w.refresh(true);
				}
			}, this::rebuildProfit, sharedDataActions(), this::startRuneLiteImport, this::buyLimitResetForPanel,
			event ->
			{
				WorldEventsController w = worldEvents;
				if (w != null)
				{
					w.pointArrow(event);
				}
			},
			developerMode ? devTools() : null);
		navButton = NavigationButton.builder()
			.tooltip("Deadman Tool Kit")
			.icon(buildIcon())
			.priority(7)
			.panel(panel)
			.build();
		clientToolbar.addNavigation(navButton);
		int session = myTrades.session();
		executor.execute(() -> loadLocalLog(session));
		if (developerMode)
		{
			// The ::breach preview exists only in developer mode; normal installs never register it.
			BreachCommands commands = new BreachCommands(breachTarget(), this::localChat, Instant::now);
			breachCommands = commands;
			eventBus.register(commands);
			// Likewise ::dgt (fresh-install preview, re-arming hints).
			DevCommands dev = new DevCommands(devTarget(), this::localChat);
			devCommands = dev;
			eventBus.register(dev);
		}
	}

	@Override
	protected void shutDown()
	{
		BreachCommands commands = breachCommands;
		breachCommands = null;
		if (commands != null)
		{
			eventBus.unregister(commands);
		}
		DevCommands dev = devCommands;
		devCommands = null;
		if (dev != null)
		{
			eventBus.unregister(dev);
		}
		breachOffset = Duration.ZERO;
		if (worldMapOverlay != null)
		{
			overlayManager.remove(worldMapOverlay);
			worldMapOverlay = null;
		}
		if (minimapOverlay != null)
		{
			overlayManager.remove(minimapOverlay);
			minimapOverlay = null;
		}
		WorldEventsController we = worldEvents;
		worldEvents = null;
		if (we != null)
		{
			// Map markers and our hint arrow go with the plugin.
			clientThread.invoke(we::stop);
		}
		if (flushTask != null)
		{
			flushTask.cancel(false);
			flushTask = null;
		}
		ScheduledFuture<?> soon = soonFlush.getAndSet(null);
		if (soon != null)
		{
			soon.cancel(false);
		}
		if (offerCheckTask != null)
		{
			offerCheckTask.cancel(false);
			offerCheckTask = null;
		}
		offerWatch = null;
		DataDeletion del = deletion;
		deletion = null;
		if (del != null)
		{
			// A running deletion still finishes (and disconnects); it just no longer reports to the panel.
			del.cancel();
		}
		ItemQuoteCache quotes = geQuotes;
		geQuotes = null;
		if (quotes != null)
		{
			quotes.clear();
		}
		// Final profit save, after any log appends and profit updates already queued (the executor runs them in order).
		ProfitStore ps = profit;
		profit = null;
		if (ps != null)
		{
			executor.execute(ps::close);
		}
		// Take the price line out of an open GE offer setup; the game rebuilds the text without it next time anyway.
		clientThread.invoke(this::removeGeText);
		// Stop recording first, so an event handled meanwhile can't go into an uploader nobody flushes again; then send
		// what's queued and spool whatever is still waiting (the flush is asynchronous) for the next start.
		TradeUploader up = uploader;
		UploadSpool sp = spool;
		tracker = null;
		uploader = null;
		spool = null;
		if (up != null)
		{
			boolean share = shareEnabled();
			// On the executor (the flush may read the identity file and encode item names): it runs tasks in order, so
			// the spool is saved after the flush started.
			executor.execute(() ->
			{
				if (share)
				{
					up.flush();
				}
				else
				{
					up.clear();
				}
				if (sp != null)
				{
					sp.save(up.pending());
				}
			});
		}
		clientToolbar.removeNavigation(navButton);
		panel.shutDown();
		panel = null;
		navButton = null;
		tracker = null;
		store.reset();
		resetTickState();
		// Also starts a new session, so a local log load still running is dropped instead of merged.
		myTrades.clear();
	}

	private void resetTickState()
	{
		lastLoginTick = -1;
		nextOpenOffersTick = -1;
		historyReadTicks = 0;
		lastGeItem = -1;
		loginCheckPending = false;
		announcedProfile = null;
		publishedOffers = Collections.emptyList();
		onTrackedWorld = false;
	}

	private boolean shareEnabled()
	{
		return config.connected() && config.shareTrades();
	}

	/** The panel's "Delete my shared data" (EDT). Results come back on the EDT. */
	private SharedDataActions sharedDataActions()
	{
		return new SharedDataActions()
		{
			@Override
			public boolean mayHaveShared()
			{
				return config.connected() || store.hasToken();
			}

			@Override
			public boolean isDeleting()
			{
				DataDeletion d = deletion;
				return d != null && d.isRunning();
			}

			@Override
			public boolean delete(Consumer<String> onDone)
			{
				DataDeletion d = deletion;
				return d != null && d.start(result ->
				{
					log.debug("Data deletion finished: {}", result);
					String message = DataDeletion.message(result);
					SwingUtilities.invokeLater(() -> onDone.accept(message));
				});
			}
		};
	}

	/* ------------------------------------------------------------ events */

	@Subscribe
	public void onGameStateChanged(GameStateChanged e)
	{
		// LOGGED_IN also follows every loading screen; only a real login / hop / reconnect replays the offers.
		if (loginDetector.update(e.getGameState()))
		{
			lastLoginTick = client.getTickCount();
			nextOpenOffersTick = lastLoginTick + LOGIN_BURST_TICKS + 1;
			loginCheckPending = true;
		}
		else if (e.getGameState() == GameState.LOGIN_SCREEN)
		{
			onTrackedWorld = false;
			announceAccount(null);
			WorldEventsController we = worldEvents;
			if (we != null)
			{
				we.onConfigChanged();
			}
		}
	}

	/** The client is closing (shutDown isn't called then): save profit once the queued log writes are done. */
	@Subscribe
	public void onClientShutdown(ClientShutdown e)
	{
		ProfitStore ps = profit;
		if (ps != null)
		{
			e.waitFor(executor.submit(ps::saveNow));
		}
		e.waitFor(executor.submit(this::saveSpool));
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged e)
	{
		// Only the user-facing settings. Our own RS-profile saves (slot state on every offer update and heartbeat,
		// completed, history) carry a profile, and installId isn't a setting; reacting to those rebuilt the panel
		// and hit the server constantly.
		if (!isSettingChange(e.getGroup(), e.getProfile(), e.getKey()) || panel == null)
		{
			return;
		}
		if (DeadmanToolKitConfig.KEY_CONNECTED.equals(e.getKey()) || DeadmanToolKitConfig.KEY_SHARE.equals(e.getKey()))
		{
			// Send the open offers soon after sharing gets switched on. Tick state belongs to the client thread.
			clientThread.invoke(() -> nextOpenOffersTick = client.getTickCount() + 1);
		}
		OfferMarketWatch watch = offerWatch;
		if (watch != null)
		{
			if (DeadmanToolKitConfig.KEY_CONNECTED.equals(e.getKey()))
			{
				// Drop what was fetched under the old setting; when now connected, compare right away.
				watch.reset();
				watch.refresh(true);
			}
			else if (DeadmanToolKitConfig.KEY_STALE_HOURS.equals(e.getKey()))
			{
				watch.reevaluate();
			}
		}
		if (DeadmanToolKitConfig.KEY_CONNECTED.equals(e.getKey()) && !config.connected())
		{
			clientThread.invoke(this::removeGeText);
		}
		WorldEventsController we = worldEvents;
		if (we != null)
		{
			clientThread.invoke(we::onConfigChanged);
		}
		SwingUtilities.invokeLater(() ->
		{
			DeadmanToolKitPanel p = panel;
			if (p != null)
			{
				p.onConfigChanged();
			}
		});
	}

	/** True for a change to one of the settings (not an RS-profile value, the install id or the chat hint flag). */
	static boolean isSettingChange(String group, String profile, String key)
	{
		return DeadmanToolKitConfig.GROUP.equals(group) && profile == null
			&& (DeadmanToolKitConfig.KEY_CONNECTED.equals(key)
			|| DeadmanToolKitConfig.KEY_SHARE.equals(key)
			|| DeadmanToolKitConfig.KEY_LOCAL_LOG.equals(key)
			|| DeadmanToolKitConfig.KEY_AUTO_OPEN.equals(key)
			|| DeadmanToolKitConfig.KEY_BREACH.equals(key)
			|| DeadmanToolKitConfig.KEY_STALE_HOURS.equals(key)
			|| DeadmanToolKitConfig.KEY_OFFER_NOTIFICATION.equals(key)
			|| DeadmanToolKitConfig.KEY_SHARE_WORLD_EVENTS.equals(key)
			|| DeadmanToolKitConfig.KEY_WORLD_EVENTS.equals(key)
			|| DeadmanToolKitConfig.KEY_WORLD_EVENT_ARROW.equals(key)
			|| DeadmanToolKitConfig.KEY_WORLD_EVENT_MINIMAP.equals(key)
			|| DeadmanToolKitConfig.KEY_MAP_CHESTS.equals(key)
			|| DeadmanToolKitConfig.KEY_MAP_BREACHES.equals(key));
	}

	/**
	 * Opening a buy/sell offer (or picking an item from the GE search) fetches the item's Deadman prices for the offer
	 * setup text, and shows the item in the panel if enabled.
	 */
	@Subscribe
	public void onVarbitChanged(VarbitChanged e)
	{
		if (e.getVarpId() != VarPlayerID.TRADINGPOST_SEARCH || !isTrackedWorld())
		{
			return;
		}
		int itemId = client.getVarpValue(VarPlayerID.TRADINGPOST_SEARCH);
		if (itemId <= 0)
		{
			lastGeItem = -1;
			geTextItem = -1;
			return;
		}
		requestGeQuote(itemManager.canonicalize(itemId));
		if (itemId != lastGeItem)
		{
			lastGeItem = itemId;
			if (config.autoOpenFromGe())
			{
				openInPanel(itemId);
			}
		}
	}

	/* ------------------------------------------------- GE offer setup text */

	/**
	 * The game is about to set the GE offer setup's description text (examine text, core's GE lines, then the fee, if
	 * any). Adds the Deadman price line from the cache; runs after core's handler (priority 0) so it never removes or
	 * repeats core's text. Without cached data nothing changes; the fetch it starts re-applies the text when done.
	 */
	@Subscribe(priority = -1)
	public void onScriptCallbackEvent(ScriptCallbackEvent e)
	{
		String name = e.getEventName();
		if (!GE_BUY_EXAMINE.equals(name) && !GE_SELL_EXAMINE.equals(name))
		{
			return;
		}
		if (!config.connected() || !isTrackedWorld())
		{
			geTextItem = -1;
			return;
		}
		int raw = client.getVarpValue(VarPlayerID.TRADINGPOST_SEARCH);
		Object[] stack = client.getObjectStack();
		int size = client.getObjectStackSize();
		if (raw <= 0 || size < 3 || !(stack[size - 1] instanceof String))
		{
			return;
		}
		int itemId = itemManager.canonicalize(raw);
		String fee = stack[size - 2] instanceof String ? (String) stack[size - 2] : "";
		geTextItem = itemId;
		geTextFee = fee;
		geTextBuy = GE_BUY_EXAMINE.equals(name);
		MarketData.ItemDetail detail = requestGeQuote(itemId);
		if (detail != null)
		{
			stack[size - 1] = GePriceText.insert((String) stack[size - 1], fee, geLine(itemId, detail));
		}
	}

	/**
	 * The DMM line for the offer setup: the market summary, plus on a buy offer when your 4-hour buy limit for the
	 * item resets (RuneLite's GE plugin data, only while a reset is still to come). Client thread.
	 */
	private String geLine(int itemId, MarketData.ItemDetail detail)
	{
		// RuneLite's own GE plugin shows the reset on this screen by default; don't spend the line's room on it twice.
		String limit = geTextBuy ? BuyLimitReset.forGeLine(true,
			configManager.getConfiguration(BuyLimitReset.RUNELITE_GROUP, BuyLimitReset.GE_PLUGIN_KEY),
			configManager.getConfiguration(BuyLimitReset.CONFIG_GROUP, BuyLimitReset.CORE_RESET_KEY),
			store.buyLimitReset(itemId), Instant.now()) : null;
		return GePriceText.line(detail.getSummary(), limit);
	}

	/** Safety net for interface rebuilds that set the text without the callback. */
	@Subscribe
	public void onScriptPostFired(ScriptPostFired e)
	{
		if (e.getScriptId() == ScriptID.GE_OFFERS_SETUP_BUILD)
		{
			reapplyGeText();
		}
	}

	/**
	 * The cached summary for the GE price line, fetching a newer one in the background (which then re-applies the
	 * text on the client thread). Null when not connected or nothing is cached yet. Client thread.
	 */
	private MarketData.ItemDetail requestGeQuote(int itemId)
	{
		ItemQuoteCache quotes = geQuotes;
		if (quotes == null || !config.connected())
		{
			return null;
		}
		return quotes.request(itemId, System.currentTimeMillis(), () -> clientThread.invoke(this::reapplyGeText));
	}

	/**
	 * Put the price line into the offer setup's description now (data arrived after the script ran). Only when the
	 * setup still shows the same item, and only without a fee line: with one, the script places an info icon by the
	 * last line, so the text waits for the next script run instead. Idempotent. Client thread.
	 */
	private void reapplyGeText()
	{
		ItemQuoteCache quotes = geQuotes;
		if (quotes == null || geTextItem < 0 || !geTextFee.isEmpty() || !config.connected() || !isTrackedWorld())
		{
			return;
		}
		int raw = client.getVarpValue(VarPlayerID.TRADINGPOST_SEARCH);
		if (raw <= 0 || itemManager.canonicalize(raw) != geTextItem)
		{
			return;
		}
		Widget desc = client.getWidget(InterfaceID.GeOffers.SETUP_DESC);
		MarketData.ItemDetail detail = quotes.peek(geTextItem, System.currentTimeMillis());
		if (desc == null || desc.isHidden() || detail == null)
		{
			return;
		}
		String text = desc.getText();
		if (text == null || text.isEmpty())
		{
			return;
		}
		String next = GePriceText.insert(text, "", geLine(geTextItem, detail));
		if (!next.equals(text))
		{
			desc.setText(next);
		}
	}

	/** Remove the price line from an open offer setup (disconnected, plugin stopped). Client thread. */
	private void removeGeText()
	{
		if (geTextFee.isEmpty())
		{
			Widget desc = client.getWidget(InterfaceID.GeOffers.SETUP_DESC);
			String text = desc == null ? null : desc.getText();
			if (text != null && text.contains(GePriceText.MARKER))
			{
				desc.setText(GePriceText.strip(text));
			}
		}
		geTextItem = -1;
	}

	/* ------------------------------------------------------------- chat */

	/** "The next breach will appear in X hours, Y minutes": corrects the panel's breach countdown. */
	@Subscribe
	public void onChatMessage(ChatMessage e)
	{
		ChatMessageType type = e.getType();
		if ((type != ChatMessageType.GAMEMESSAGE && type != ChatMessageType.BROADCAST) || !isTrackedWorld())
		{
			return;
		}
		applyBreachMessage(e.getMessage());
		WorldEventsController we = worldEvents;
		if (we != null)
		{
			we.onMessage(e.getMessage());
		}
	}

	/** Correct the breach countdown from a "next breach will appear in ..." text; other text is ignored. */
	private void applyBreachMessage(String text)
	{
		Optional<Duration> until = BreachMessage.parse(text);
		if (!until.isPresent())
		{
			return;
		}
		// The breach timer's clock: the real one, unless the developer-mode preview shifted it.
		Instant start = BreachSchedule.correct(Instant.now().plus(breachOffset), until.get());
		log.debug("Breach message: next breach at {}", start);
		SwingUtilities.invokeLater(() ->
		{
			DeadmanToolKitPanel p = panel;
			if (p != null)
			{
				p.setBreachCorrection(start);
			}
		});
	}

	/** The panel's developer-mode "Dev tools" buttons. Only created in developer mode. Called on the EDT. */
	private DevTools devTools()
	{
		return new DevTools()
		{
			@Override
			public void breach(String action)
			{
				clientThread.invoke(() ->
				{
					BreachCommands c = breachCommands;
					if (c != null)
					{
						c.run(new String[]{action});
					}
				});
			}

			@Override
			public void resetHints()
			{
				clientThread.invoke(DeadmanToolKitPlugin.this::resetHints);
			}

			@Override
			public void worldEvents(String action)
			{
				clientThread.invoke(() ->
				{
					DevCommands c = devCommands;
					if (c != null)
					{
						c.run(new String[]{"events", action});
					}
				});
			}
		};
	}

	/** What the developer-mode {@code ::dgt} command drives. */
	private DevCommands.Target devTarget()
	{
		return new DevCommands.Target()
		{
			@Override
			public void freshPreview(boolean on)
			{
				SwingUtilities.invokeLater(() ->
				{
					DeadmanToolKitPanel p = panel;
					if (p != null)
					{
						p.setFreshPreview(on);
					}
				});
			}

			@Override
			public void resetHints()
			{
				DeadmanToolKitPlugin.this.resetHints();
			}

			@Override
			public void worldEvent(String gameText)
			{
				WorldEventsController we = worldEvents;
				if (we != null)
				{
					we.inject(gameText);
				}
			}

			@Override
			public void clearWorldEvents()
			{
				WorldEventsController we = worldEvents;
				if (we != null)
				{
					we.clear();
				}
			}
		};
	}

	/**
	 * Developer mode: forget that the one-time chat hint was shown, and show it now so it can be previewed. On a normal
	 * login it only reappears when there is still something to set up (not connected, or GE History not imported).
	 * Client thread.
	 */
	private void resetHints()
	{
		configManager.unsetConfiguration(DeadmanToolKitConfig.GROUP, DeadmanToolKitConfig.KEY_HINT_SHOWN);
		localChat(CHAT_HINT);
		localChat("Dev: chat hint re-armed. It shows again on your next login on world 345 when there is something to set up.");
	}

	/** What the developer-mode {@code ::breach} command drives: only the breach timer's clock and schedule input. */
	private BreachCommands.Target breachTarget()
	{
		return new BreachCommands.Target()
		{
			@Override
			public Duration offset()
			{
				return breachOffset;
			}

			@Override
			public void preview(Duration offset)
			{
				breachOffset = offset;
				SwingUtilities.invokeLater(() ->
				{
					DeadmanToolKitPanel p = panel;
					if (p != null)
					{
						p.setBreachPreview(offset);
					}
				});
			}

			@Override
			public void gameMessage(String gameText)
			{
				applyBreachMessage(gameText);
			}
		};
	}

	/** A line in this client's chatbox only (never sent to the game). */
	private void localChat(String text)
	{
		chatMessageManager.queue(QueuedMessage.builder()
			.type(ChatMessageType.CONSOLE)
			.runeLiteFormattedMessage(new ChatMessageBuilder()
				.append(ChatColorType.HIGHLIGHT)
				.append(text)
				.build())
			.build());
	}

	/** Adds "DMM prices" to the right-click menu of tradeable items. */
	@Subscribe
	public void onMenuEntryAdded(MenuEntryAdded e)
	{
		MenuEntry entry = e.getMenuEntry();
		if (!"Examine".equals(e.getOption()) || entry.getItemId() <= 0 || !isTrackedWorld())
		{
			return;
		}
		int itemId = itemManager.canonicalize(entry.getItemId());
		ItemComposition comp = itemManager.getItemComposition(itemId);
		if (!comp.isTradeable())
		{
			return;
		}
		client.getMenu().createMenuEntry(1)
			.setOption(MENU_OPTION)
			.setTarget(e.getTarget())
			.setType(MenuAction.RUNELITE)
			.onClick(me -> openInPanel(itemId));
	}

	/** Must be called on the client thread. */
	private void openInPanel(int itemId)
	{
		int id = itemManager.canonicalize(itemId);
		String name = itemManager.getItemComposition(id).getName();
		SwingUtilities.invokeLater(() ->
		{
			DeadmanToolKitPanel p = panel;
			NavigationButton nav = navButton;
			if (p == null || nav == null)
			{
				return;
			}
			p.openItem(id, name);
			clientToolbar.openPanel(nav);
		});
	}

	@Subscribe
	public void onGrandExchangeOfferChanged(GrandExchangeOfferChanged e)
	{
		GrandExchangeOffer offer = e.getOffer();
		// The client sends a burst of EMPTY offers before login completes; they don't mean anything.
		if (offer.getState() == GrandExchangeOfferState.EMPTY && client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}
		OfferTracker t = tracker;
		if (!isTrackedWorld() || !ensureProfileLoaded(t))
		{
			return;
		}

		boolean late = client.getGameState() != GameState.LOGGED_IN
			|| client.getTickCount() <= lastLoginTick + LOGIN_BURST_TICKS;
		int slot = e.getSlot();
		List<TradeEvent> events = t.update(slot, OfferTracker.Snapshot.of(offer), late, now(), client.getWorld());

		store.saveSlot(t, slot);
		if (!events.isEmpty())
		{
			store.saveCompleted(t);
			record(events);
		}
		publishOffers(t);
	}

	@Subscribe
	public void onWidgetLoaded(WidgetLoaded e)
	{
		if (e.getGroupId() == InterfaceID.GE_HISTORY)
		{
			log.debug("GE history interface opened (tracked world: {})", isTrackedWorld());
			if (isTrackedWorld())
			{
				historyReadTicks = HISTORY_READ_TICKS;
			}
		}
	}

	@Subscribe
	public void onGameTick(GameTick e)
	{
		onTrackedWorld = client.getGameState() == GameState.LOGGED_IN && isTrackedWorld();
		if (loginCheckPending)
		{
			loginCheckPending = false;
			afterLogin();
			WorldEventsController we = worldEvents;
			if (we != null)
			{
				we.onLoggedIn();
			}
		}
		if (client.getTickCount() % WORLD_EVENT_TICKS == 0)
		{
			WorldEventsController we = worldEvents;
			if (we != null)
			{
				we.tick();
			}
		}

		if (historyReadTicks > 0)
		{
			historyReadTicks--;
			List<GeHistory.Entry> entries = readHistoryWidget();
			if (entries != null && !entries.isEmpty())
			{
				historyReadTicks = 0;
				processHistory(entries, tracker);
			}
			else if (historyReadTicks == 0)
			{
				if (entries != null)
				{
					// Still shown with no rows after the whole wait: this account has no GE History.
					markEmptyHistoryImported(tracker);
				}
				else
				{
					log.debug("GE history: gave up, no rows parsed");
				}
			}
		}

		OfferTracker t = tracker;
		if (client.getTickCount() % SEEN_INTERVAL_TICKS == 0 && client.getTickCount() > lastLoginTick + LOGIN_BURST_TICKS
			&& isTrackedWorld() && ensureProfileLoaded(t))
		{
			// Offers we're watching live haven't changed as of now. If one fills after we log out, the server knows
			// it happened after this point when pairing it with the counterparty's report.
			for (int slot : t.markSeen(now()))
			{
				store.saveSlot(t, slot);
			}
		}

		if (nextOpenOffersTick >= 0 && client.getTickCount() >= nextOpenOffersTick)
		{
			nextOpenOffersTick = client.getTickCount() + OPEN_OFFERS_INTERVAL_TICKS;
			TradeUploader up = uploader;
			if (up != null && isTrackedWorld() && ensureProfileLoaded(t) && shareEnabled())
			{
				up.setOpenOffers(client.getWorld(), accountId(), buildOpenOffers(t, up));
			}
		}
	}

	/**
	 * First tick after a login (or after starting while logged in): tell the panel whether this account's GE History
	 * was imported, and send the one-time chat hint. Client thread.
	 */
	private void afterLogin()
	{
		if (!isTrackedWorld() || !ensureProfileLoaded(tracker))
		{
			announceAccount(null);
			return;
		}
		Boolean hintShown = configManager.getConfiguration(DeadmanToolKitConfig.GROUP,
			DeadmanToolKitConfig.KEY_HINT_SHOWN, Boolean.class);
		if (Boolean.TRUE.equals(hintShown))
		{
			return;
		}
		if (Onboarding.shouldShowChatHint(config.connected(), store.historyImported()))
		{
			// Local only: shown in this client's chatbox, never sent to the game.
			chatMessageManager.queue(QueuedMessage.builder()
				.type(ChatMessageType.CONSOLE)
				.runeLiteFormattedMessage(new ChatMessageBuilder()
					.append(ChatColorType.HIGHLIGHT)
					.append(CHAT_HINT)
					.build())
				.build());
		}
		// Once per install, whether or not it was needed.
		configManager.setConfiguration(DeadmanToolKitConfig.GROUP, DeadmanToolKitConfig.KEY_HINT_SHOWN, true);
	}

	/**
	 * Send the panel the import state of the account whose profile is loaded, or null when logged out / off world
	 * 345. Client thread.
	 */
	private void announceAccount(String profile)
	{
		if (profile == null)
		{
			publishOffers(null);
		}
		Boolean imported = profile == null ? null : store.historyImported();
		announcedProfile = profile;
		ProfitStore ps = profit;
		String acct = profile == null ? null : accountId();
		if (ps != null && acct != null)
		{
			// Switch to this account's profit file (one small read) and show its numbers.
			executor.execute(() -> ps.load(acct));
		}
		SwingUtilities.invokeLater(() ->
		{
			DeadmanToolKitPanel p = panel;
			if (p != null)
			{
				p.setAccountState(imported, profile);
			}
		});
	}

	/** The hashed id of the logged-in account ({@link AccountId}), or null when unknown. Client thread. */
	private String accountId()
	{
		long hash = client.getAccountHash();
		if (hash == -1)
		{
			return null;
		}
		if (hash != cachedAccountHash || cachedAccountId == null)
		{
			cachedAccountHash = hash;
			cachedAccountId = AccountId.of(hash);
		}
		return cachedAccountId;
	}

	/* ------------------------------------------------------- GE history */

	/**
	 * @return the parsed rows; an empty list when the list is shown but has no rows at all; null when the list is
	 * missing or hidden, or has rows the parser couldn't read (neither may be taken as an empty history)
	 */
	private List<GeHistory.Entry> readHistoryWidget()
	{
		Widget list = client.getWidget(InterfaceID.GeHistory.LIST);
		if (list == null || list.isHidden())
		{
			log.debug("GE history list widget {}", list == null ? "missing" : "hidden");
			return null;
		}
		Widget[] children = list.getDynamicChildren();
		if (children == null || children.length == 0)
		{
			Widget[] statics = list.getStaticChildren();
			Widget[] nested = list.getNestedChildren();
			log.debug("GE history list has no dynamic children (static: {}, nested: {})",
				statics == null ? 0 : statics.length, nested == null ? 0 : nested.length);
			return Collections.emptyList();
		}
		List<GeHistory.Cell> cells = new ArrayList<>(children.length);
		for (Widget w : children)
		{
			int itemId = w.getItemId() > 0 ? itemManager.canonicalize(w.getItemId()) : -1;
			cells.add(new GeHistory.Cell(w.getText(), itemId, w.getItemQuantity()));
		}
		List<GeHistory.Entry> entries = GeHistory.parse(cells);
		log.debug("Read {} GE history entries from {} widgets", entries.size(), children.length);
		if (entries.isEmpty())
		{
			if (log.isDebugEnabled())
			{
				// Dump the layout so the parser can be fixed if the interface differs from what we expect.
				for (int i = 0; i < Math.min(children.length, 36); i++)
				{
					Widget w = children[i];
					log.debug("  history[{}] type={} item={} qty={} text='{}'", i, w.getType(), w.getItemId(), w.getItemQuantity(), w.getText());
				}
			}
			return null;
		}
		return entries;
	}

	/**
	 * The GE History list stayed empty: there is nothing to import, but the account has been through the import, so
	 * the welcome card stops asking for it. Only the first time; a real import later still adds its rows.
	 */
	private void markEmptyHistoryImported(OfferTracker t)
	{
		if (!ensureProfileLoaded(t) || store.historyImported())
		{
			return;
		}
		store.saveHistory(Collections.emptyList());
		log.debug("GE history: empty, marked as imported");
		SwingUtilities.invokeLater(() ->
		{
			DeadmanToolKitPanel p = panel;
			if (p != null)
			{
				p.onHistoryImported(0);
			}
		});
	}

	private void processHistory(List<GeHistory.Entry> entries, OfferTracker t)
	{
		if (!ensureProfileLoaded(t))
		{
			return;
		}
		List<String> current = new ArrayList<>(entries.size());
		entries.forEach(en -> current.add(en.signature()));
		List<String> previous = store.loadHistory();
		boolean wasImported = store.historyImported();
		int fresh = GeHistory.countNew(previous, current);

		long ts = now();
		int world = client.getWorld();
		List<TradeEvent> events = GeHistory.newEvents(entries, fresh, t::consumeCompleted, ts, world,
			() -> "hist:" + UUID.randomUUID());
		// Rows for trades already imported from RuneLite's GE history (each used once).
		List<String> recent = store.loadRuneLiteRecent();
		if (!recent.isEmpty() && events.removeIf(ev -> RuneLiteImport.consumeRecent(recent, ev)))
		{
			store.saveRuneLiteRecent(recent);
		}

		store.saveHistory(current);
		store.saveCompleted(t);
		log.debug("GE history: {} rows, {} new, {} not seen live", entries.size(), fresh, events.size());
		record(events);
		if (!wasImported)
		{
			int added = events.size();
			SwingUtilities.invokeLater(() ->
			{
				DeadmanToolKitPanel p = panel;
				if (p != null)
				{
					p.onHistoryImported(added);
				}
			});
		}
	}

	/* ---------------------------------------------------------- output */

	/** Record events just observed (client thread): My trades, upload, local log and profit. */
	private void record(List<TradeEvent> raw)
	{
		if (raw.isEmpty())
		{
			return;
		}
		List<TradeEvent> events = new ArrayList<>(raw.size());
		for (TradeEvent ev : raw)
		{
			events.add(withName(ev));
		}
		myTrades.addNewest(events);
		notifyMyTradesChanged();
		persist(events, accountId(), false);
	}

	/**
	 * Upload (when connected and sharing), append to the local log (when on; each event to the file of its own month)
	 * and count for profit, in that order on the executor. Client thread.
	 *
	 * @param older the events are older than ones already counted (an import): profit is recounted from the log in
	 *              time order (when they all went into it), so they count as the cost basis of later sells
	 */
	private void persist(List<TradeEvent> events, String acct, boolean older)
	{
		TradeUploader up = uploader;
		if (up != null && shareEnabled())
		{
			boolean fill = false;
			for (TradeEvent ev : events)
			{
				up.add(ev, acct);
				queueItemInfo(up, ev.getItemId());
				fill |= TradeEvent.FILL.equals(ev.getKind());
			}
			if (fill)
			{
				requestFlushSoon();
			}
		}
		Map<YearMonth, List<String>> lines = null;
		if (config.saveLocalLog())
		{
			// Live events of one update share a month; imported ones carry their own (older) times.
			lines = new TreeMap<>();
			for (TradeEvent ev : events)
			{
				lines.computeIfAbsent(LocalTradeLog.monthOf(ev.getTs()), k -> new ArrayList<>())
					.add(gson.toJson(logLine(gson, ev, acct)));
			}
		}
		ProfitStore ps = profit;
		Map<YearMonth, List<String>> logLines = lines;
		executor.execute(() -> appendAndApply(ps, logLines, acct, events, older));
	}

	/**
	 * Executor: append the events to the local log (if it's on), then count them for profit (even when it's off). In
	 * one task, in order, so a profit rebuild reading the log never misses or double counts an event.
	 */
	private void appendAndApply(ProfitStore ps, Map<YearMonth, List<String>> lines, String acct, List<TradeEvent> events,
		boolean older)
	{
		boolean logged = false, allLogged = lines != null;
		if (lines != null)
		{
			for (Map.Entry<YearMonth, List<String>> e : lines.entrySet())
			{
				boolean ok = appendLocalLog(e.getKey(), e.getValue());
				logged |= ok;
				allLogged &= ok;
			}
		}
		if (ps == null)
		{
			return;
		}
		try
		{
			if (!(older && allLogged && acct != null && ps.rebuildFor(acct)))
			{
				ps.apply(acct, events, logged);
			}
		}
		catch (RuntimeException ex)
		{
			log.warn("Couldn't count profit", ex);
		}
	}

	/* ------------------------------------------- RuneLite GE history import */

	/**
	 * The panel's "Import RuneLite GE history" (EDT): the player asked to read the trade history RuneLite's own Grand
	 * Exchange plugin keeps for the logged-in account. Reads that config value on the client thread, works out what
	 * is new on the executor (with the local trade log), then records it on the client thread. {@code onDone} gets
	 * the result line on the EDT. Returns false when an import is already running.
	 */
	private boolean startRuneLiteImport(Consumer<RuneLiteImportAction.Result> onDone)
	{
		if (!importing.compareAndSet(false, true))
		{
			return false;
		}
		Consumer<RuneLiteImportAction.Result> finish = result ->
		{
			importing.set(false);
			SwingUtilities.invokeLater(() -> onDone.accept(result));
		};
		clientThread.invoke(() ->
		{
			try
			{
				readRuneLiteHistory(finish);
			}
			catch (RuntimeException ex)
			{
				log.warn("RuneLite GE history import failed", ex);
				finish.accept(RuneLiteImportAction.Result.problem(RuneLiteImport.FAILED));
			}
		});
		return true;
	}

	/**
	 * Client thread: only logged in on world 345 with RuneLite's DEADMAN profile, and only the current profile's value
	 * (never another account's). Hands the raw text to the executor.
	 */
	private void readRuneLiteHistory(Consumer<RuneLiteImportAction.Result> finish)
	{
		OfferTracker t = tracker;
		boolean onDeadman345 = t != null && client.getGameState() == GameState.LOGGED_IN && isTrackedWorld()
			&& RuneScapeProfileType.getCurrent(client) == RuneScapeProfileType.DEADMAN && ensureProfileLoaded(t);
		String profile = onDeadman345 ? configManager.getRSProfileKey() : null;
		String acct = onDeadman345 ? accountId() : null;
		onDeadman345 &= profile != null && acct != null && profile.equals(store.loadedProfile());
		// The local log is what tells which trades the plugin already has (queued appends run first on the
		// executor); without it, trades recorded in earlier sessions would be imported again.
		String blocked = RuneLiteImport.blocked(onDeadman345, config.saveLocalLog());
		if (blocked != null)
		{
			finish.accept(RuneLiteImportAction.Result.problem(blocked));
			return;
		}
		String json = store.runeLiteTradeHistory();
		long watermark = store.runeLiteImportedThrough();
		int world = client.getWorld();
		executor.execute(() ->
		{
			try
			{
				planRuneLiteImport(json, watermark, profile, acct, world, finish);
			}
			catch (IOException | RuntimeException ex)
			{
				log.warn("RuneLite GE history import failed", ex);
				finish.accept(RuneLiteImportAction.Result.problem(RuneLiteImport.FAILED));
			}
		});
	}

	/** Executor: parse, drop records before the cutoff, compare with what this account already has. */
	private void planRuneLiteImport(String json, long watermark, String profile, String acct, int world,
		Consumer<RuneLiteImportAction.Result> finish) throws IOException
	{
		List<RuneLiteImport.Record> records = RuneLiteImport.parse(gson, json);
		List<RuneLiteImport.Record> candidates = new ArrayList<>();
		for (RuneLiteImport.Record r : records)
		{
			if (!RuneLiteImport.beforeCutoff(r) && r.getTime() > watermark)
			{
				candidates.add(r);
			}
		}
		List<TradeEvent> known = new ArrayList<>();
		if (!candidates.isEmpty())
		{
			Filepath dir = getPluginDirectory();
			for (String name : LogFiles.fromMonth(LocalTradeLog.listNames(dir), RuneLiteImport.firstMonth(candidates)))
			{
				LocalTradeLog.forEachInFile(dir, name, gson, (obj, ev) ->
				{
					if (ProfitStore.belongsTo(obj, acct))
					{
						known.add(ev);
					}
				});
			}
		}
		RuneLiteImport.Plan plan = RuneLiteImport.plan(records, known, watermark, world);
		log.debug("RuneLite GE history: {} records, {} new, {} already recorded, {} before the cutoff", records.size(),
			plan.getAdd().size(), plan.getAlready(), plan.getBeforeCutoff());
		clientThread.invoke(() ->
		{
			try
			{
				applyRuneLiteImport(plan, watermark, profile, acct, finish);
			}
			catch (RuntimeException ex)
			{
				log.warn("RuneLite GE history import failed", ex);
				finish.accept(RuneLiteImportAction.Result.problem(RuneLiteImport.FAILED));
			}
		});
	}

	/** Client thread: record the new trades, if the same account is still logged in, and remember how far we got. */
	private void applyRuneLiteImport(RuneLiteImport.Plan plan, long watermark, String profile, String acct,
		Consumer<RuneLiteImportAction.Result> finish)
	{
		if (tracker == null)
		{
			finish.accept(RuneLiteImportAction.Result.problem(RuneLiteImport.STOPPED));
			return;
		}
		if (!profile.equals(configManager.getRSProfileKey()) || !profile.equals(store.loadedProfile())
			|| !acct.equals(accountId()))
		{
			finish.accept(RuneLiteImportAction.Result.problem(RuneLiteImport.ACCOUNT_CHANGED));
			return;
		}
		if (!plan.getAdd().isEmpty())
		{
			List<TradeEvent> events = new ArrayList<>(plan.getAdd().size());
			for (TradeEvent ev : plan.getAdd())
			{
				events.add(withName(ev));
			}
			myTrades.addByTime(events);
			notifyMyTradesChanged();
			persist(events, acct, true);
			// So a GE History row read later for one of these trades isn't recorded again.
			store.saveRuneLiteRecent(RuneLiteImport.remember(store.loadRuneLiteRecent(), events));
		}
		if (plan.getNewestMs() > watermark)
		{
			store.saveRuneLiteImportedThrough(plan.getNewestMs());
		}
		finish.accept(RuneLiteImportAction.Result.done(RuneLiteImport.message(plan, ZoneId.systemDefault())));
	}

	/**
	 * When your 4-hour GE buy limit for {@code itemId} resets, from RuneLite's GE plugin data for the current profile;
	 * null off world 345 or when no reset is coming. Any thread (a config read).
	 */
	private Instant buyLimitResetForPanel(int itemId)
	{
		if (!onTrackedWorld)
		{
			return null;
		}
		return BuyLimitReset.future(store.buyLimitReset(itemId), Instant.now());
	}

	/** Profit numbers changed (executor). */
	private void onProfit(ProfitSnapshot snapshot)
	{
		SwingUtilities.invokeLater(() ->
		{
			DeadmanToolKitPanel p = panel;
			if (p != null)
			{
				p.onProfit(snapshot);
			}
		});
	}

	/** The panel's Rebuild button (EDT): recount profit from the local log in the background. */
	private void rebuildProfit()
	{
		ProfitStore ps = profit;
		if (ps != null)
		{
			executor.execute(ps::rebuild);
		}
	}

	/**
	 * One local log line: the event plus "acct", the hashed account id (when known), so per-account numbers can be
	 * rebuilt from the log. Reading a line back as a TradeEvent ignores "acct".
	 */
	static JsonObject logLine(Gson gson, TradeEvent ev, String accountId)
	{
		JsonObject o = gson.toJsonTree(ev).getAsJsonObject();
		if (accountId != null)
		{
			o.addProperty("acct", accountId);
		}
		return o;
	}

	/** Must be called on the client thread. */
	private TradeEvent withName(TradeEvent ev)
	{
		return ev.toBuilder().name(itemManager.getItemComposition(ev.getItemId()).getName()).build();
	}

	/** Older log lines have no item name. Must be called on the client thread. */
	private TradeEvent withNameIfMissing(TradeEvent ev)
	{
		return ev.getName() == null ? withName(ev) : ev;
	}

	private void notifyMyTradesChanged()
	{
		SwingUtilities.invokeLater(() ->
		{
			DeadmanToolKitPanel p = panel;
			if (p != null)
			{
				p.onMyTradesChanged();
			}
		});
	}

	/** Client thread only. Captures the name now; the icon is only referenced and gets PNG-encoded during flush. */
	private void queueItemInfo(TradeUploader up, int itemId)
	{
		if (!up.needsItem(itemId))
		{
			return;
		}
		String name = itemManager.getItemComposition(itemId).getName();
		AsyncBufferedImage img = itemManager.getImage(itemId);
		up.addItem(itemId, name, img, geLimit(itemId));
		img.onLoaded(() -> up.setIcon(itemId, img));
	}

	/**
	 * The item's GE buy limit from RuneLite's item stats (an in-memory lookup, no IO), or 0 when unknown. The server
	 * uses it to reject buys over the 4-hour limit; it only accepts the value from trusted sources. Client thread.
	 */
	private int geLimit(int itemId)
	{
		ItemStats stats = itemManager.getItemStats(itemId);
		return stats != null && stats.getGeLimit() > 0 ? stats.getGeLimit() : 0;
	}

	/**
	 * Load the local trade log so "My trades" survives restarts. Runs off the client thread.
	 *
	 * @param session {@link MyTrades#session()} when the plugin started; if the plugin has been stopped since, the
	 *                result is dropped rather than merged into a later session
	 */
	private void loadLocalLog(int session)
	{
		List<TradeEvent> newest;
		try
		{
			// Only the newest shown events, read from the end of the newest files; the rest of the log isn't parsed.
			newest = LocalTradeLog.readNewest(getPluginDirectory(), gson, MyTrades.MAX);
		}
		catch (IOException ex)
		{
			log.debug("Couldn't read local trade log", ex);
			return;
		}
		if (newest.isEmpty())
		{
			return;
		}

		// Item names are filled in on the client thread. Item definitions only exist once the client has loaded the game
		// cache (login screen or later); at startup this runs earlier, so retry on each tick until then.
		clientThread.invokeLater(() ->
		{
			if (client.getGameState().getState() < GameState.LOGIN_SCREEN.getState())
			{
				return false;
			}
			if (myTrades.mergeLoaded(session, newest, this::withNameIfMissing))
			{
				notifyMyTradesChanged();
			}
			else
			{
				log.debug("Dropped a local trade log load from a previous session");
			}
			return true;
		});
	}

	private static BufferedImage buildIcon()
	{
		BufferedImage img = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = img.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setColor(new Color(0xd8a634));
		g.fillOval(0, 0, 16, 16);
		g.setColor(new Color(0x2b2b2b));
		g.setStroke(new BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		g.drawPolyline(new int[]{3, 6, 9, 13}, new int[]{11, 7, 9, 4}, 4);
		g.dispose();
		return img;
	}

	/** @return true if the lines were written */
	private boolean appendLocalLog(YearMonth month, List<String> lines)
	{
		try
		{
			LocalTradeLog.append(getPluginDirectory(), month, lines);
			return true;
		}
		catch (IOException ex)
		{
			log.warn("Couldn't write local trade log", ex);
			return false;
		}
	}

	/**
	 * Your open offers, sent to the panel when they changed so the item view can show them at once. Client thread.
	 *
	 * @param t the tracker, or null when logged out / not on world 345 (no offers)
	 */
	private void publishOffers(OfferTracker t)
	{
		List<ActiveOffer> offers = t == null ? Collections.emptyList()
			: ActiveOffer.fromTracker(t, id -> itemManager.getItemComposition(id).getName());
		if (offers.equals(publishedOffers))
		{
			return;
		}
		publishedOffers = offers;
		OfferMarketWatch watch = offerWatch;
		if (watch != null)
		{
			watch.setOffers(offers);
		}
		SwingUtilities.invokeLater(() ->
		{
			DeadmanToolKitPanel p = panel;
			if (p != null)
			{
				p.onOffersChanged(offers);
			}
		});
	}

	/** The server accepted trades of these items (OkHttp thread): let the item view fetch the new market data. */
	private void onUploaded(Set<Integer> itemIds)
	{
		SwingUtilities.invokeLater(() ->
		{
			DeadmanToolKitPanel p = panel;
			if (p != null)
			{
				p.onUploaded(itemIds);
			}
		});
	}

	/**
	 * Upload in about {@link #FLUSH_SOON_SECONDS} instead of at the next scheduled flush, so a fill shows up in the
	 * market data quickly. Requests while one is pending are merged into it.
	 */
	private void requestFlushSoon()
	{
		ScheduledFuture<?> pending = soonFlush.get();
		if (pending != null && !pending.isDone())
		{
			return;
		}
		ScheduledFuture<?> next = executor.schedule(() -> flushSoon(1), FLUSH_SOON_SECONDS, TimeUnit.SECONDS);
		if (!soonFlush.compareAndSet(pending, next))
		{
			// Another request scheduled one first.
			next.cancel(false);
		}
	}

	/** The one-shot upload; if a request is still running, try again a little later (a few times at most). */
	private void flushSoon(int attempt)
	{
		if (tracker == null)
		{
			// Stopped meanwhile; shutDown already flushed.
			return;
		}
		TradeUploader up = uploader;
		if (up != null && up.isInFlight() && attempt < FLUSH_SOON_TRIES)
		{
			soonFlush.set(executor.schedule(() -> flushSoon(attempt + 1), FLUSH_SOON_SECONDS, TimeUnit.SECONDS));
			return;
		}
		// Clear first, so a fill recorded during this upload schedules its own.
		soonFlush.set(null);
		scheduledFlush();
	}

	/** Whether the side panel is open; any thread. */
	private boolean panelShown()
	{
		DeadmanToolKitPanel p = panel;
		return p != null && p.isPanelActive();
	}

	/** The periodic offer check (executor). Only starts a request when due; catches everything to keep the task alive. */
	private void checkOffers()
	{
		try
		{
			OfferMarketWatch w = offerWatch;
			if (w != null)
			{
				w.refresh(false);
			}
		}
		catch (RuntimeException ex)
		{
			warnOnce("Offer check failed", ex);
		}
	}

	/** Failures of the repeating tasks already logged as a warning; the next ones go to debug. */
	private final Set<String> warned = ConcurrentHashMap.newKeySet();

	/** A scheduled task failed: a warning the first time, debug after that (it runs every few seconds). */
	private void warnOnce(String what, RuntimeException ex)
	{
		if (warned.add(what))
		{
			log.warn(what, ex);
		}
		else
		{
			log.debug("{}: {}", what, ex.toString());
		}
	}

	/** The scheduled upload. Catches everything: an exception would cancel the fixed-delay task for the session. */
	private void scheduledFlush()
	{
		try
		{
			flush();
		}
		catch (RuntimeException ex)
		{
			warnOnce("Trade upload flush failed", ex);
		}
		saveSpool();
	}

	/** Write the uploads still waiting to the spool file (or remove it). Executor thread. */
	private void saveSpool()
	{
		UploadSpool sp = spool;
		TradeUploader up = uploader;
		if (sp != null && up != null)
		{
			try
			{
				sp.save(up.pending());
			}
			catch (RuntimeException ex)
			{
				warnOnce("Couldn't save pending uploads", ex);
			}
		}
	}

	/**
	 * Enabled while logged in on world 345: the game sends no offer events for slots that haven't changed, so compare
	 * all 8 with the saved state now. Whatever differs happened while the plugin wasn't watching, so it is recorded as
	 * late (never as a live trade at this moment). Slots the game shows as empty are cleared. Client thread.
	 */
	private void reconcileSlots()
	{
		OfferTracker t = tracker;
		if (!isTrackedWorld() || !ensureProfileLoaded(t))
		{
			return;
		}
		GrandExchangeOffer[] offers = client.getGrandExchangeOffers();
		if (offers == null)
		{
			return;
		}
		long ts = now();
		int world = client.getWorld();
		for (int slot = 0; slot < offers.length && slot < OfferTracker.SLOTS; slot++)
		{
			GrandExchangeOffer offer = offers[slot];
			if (offer == null)
			{
				continue;
			}
			List<TradeEvent> events = t.update(slot, OfferTracker.Snapshot.of(offer), true, ts, world);
			store.saveSlot(t, slot);
			if (!events.isEmpty())
			{
				store.saveCompleted(t);
				record(events);
			}
		}
		publishOffers(t);
	}

	private void flush()
	{
		TradeUploader up = uploader;
		if (up == null)
		{
			return;
		}
		if (!shareEnabled())
		{
			up.clear();
			return;
		}
		up.flush();
	}

	/** Your offers that are still open, serialized now since the slot state keeps changing. Client thread only. */
	private List<JsonObject> buildOpenOffers(OfferTracker t, TradeUploader up)
	{
		List<JsonObject> offers = new ArrayList<>();
		for (int slot = 0; slot < OfferTracker.SLOTS; slot++)
		{
			OfferTracker.SlotState s = t.getSlot(slot);
			if (s == null || s.getStatus() != OfferTracker.Status.ACTIVE || s.getSold() >= s.getTotalQty())
			{
				continue;
			}
			offers.add(TradeUploader.offerJson(slot, s));
			queueItemInfo(up, s.getItemId());
		}
		return offers;
	}

	/* ---------------------------------------------------- persistence */

	/**
	 * Load slot state for the current account (each account / game mode has its own RuneLite profile).
	 *
	 * @param t the tracker, read once by the caller (null after shutDown)
	 */
	private boolean ensureProfileLoaded(OfferTracker t)
	{
		if (t == null || !store.ensureLoaded(t))
		{
			return false;
		}
		String profile = store.loadedProfile();
		if (profile != null && !profile.equals(announcedProfile))
		{
			announceAccount(profile);
			publishOffers(t);
		}
		return true;
	}

	/* ---------------------------------------------------------- helpers */

	/** Only the permanent Deadman world (345), double-checked against the world type. */
	private boolean isTrackedWorld()
	{
		return client.getWorld() == PERMANENT_DEADMAN_WORLD
			&& client.getWorldType().contains(WorldType.DEADMAN);
	}

	private static long now()
	{
		return Instant.now().getEpochSecond();
	}
}
