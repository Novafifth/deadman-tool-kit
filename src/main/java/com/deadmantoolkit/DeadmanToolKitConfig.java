package com.deadmantoolkit;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.Notification;
import net.runelite.client.config.Range;

@ConfigGroup(DeadmanToolKitConfig.GROUP)
public interface DeadmanToolKitConfig extends Config
{
	String GROUP = "deadman-tool-kit";
	String KEY_CONNECTED = "connected";
	String KEY_SHARE = "shareTrades";
	String KEY_LOCAL_LOG = "saveLocalLog";
	String KEY_AUTO_OPEN = "autoOpenFromGe";
	String KEY_BREACH = "showBreachTimer";
	String KEY_STALE_HOURS = "staleHours";
	String KEY_OFFER_NOTIFICATION = "offerNotification";
	String KEY_SHARE_WORLD_EVENTS = "shareWorldEvents";
	String KEY_WORLD_EVENTS = "showWorldEvents";
	String KEY_WORLD_EVENT_ARROW = "worldEventArrow";
	String KEY_WORLD_EVENT_MINIMAP = "worldEventMinimap";
	String KEY_MAP_CHESTS = "worldEventMapChests";
	String KEY_MAP_BREACHES = "worldEventMapBreaches";
	/** Not a setting: set once the one-time chat hint has been considered for this install. */
	String KEY_HINT_SHOWN = "chatHintShown";

	String THIRD_PARTY_WARNING = "This feature submits your IP address to a 3rd-party server not controlled or verified by RuneLite developers";

	@ConfigSection(
		name = "Data sharing",
		description = "Connecting to the Deadman Tool Kit server and sharing your trades",
		position = 0
	)
	String SECTION_SHARING = "sharing";

	@ConfigSection(
		name = "Panel",
		description = "Side panel behaviour",
		position = 1
	)
	String SECTION_PANEL = "panel";

	@ConfigSection(
		name = "Offer alerts",
		description = "How your open world 345 offers are compared with the market",
		position = 2
	)
	String SECTION_ALERTS = "alerts";

	@ConfigSection(
		name = "World events",
		description = "Breach and Deadman's Chest broadcasts on world 345",
		position = 3
	)
	String SECTION_WORLD = "world";

	@ConfigSection(
		name = "Local",
		description = "Data kept on this computer",
		position = 4
	)
	String SECTION_LOCAL = "local";

	/**
	 * Opt-in to contacting the Deadman Tool Kit server at all (viewing market data and sharing trades).
	 */
	@ConfigItem(
		keyName = KEY_CONNECTED,
		name = "Connect to Deadman Tool Kit server",
		description = "Show world 345 market data from all plugin users and share your trades. Shared: item, price,"
			+ " quantity, time, world, a hashed account ID and a random install ID. Never your name. IP addresses are"
			+ " never stored.",
		warning = THIRD_PARTY_WARNING,
		position = 0,
		section = SECTION_SHARING
	)
	default boolean connected()
	{
		return false;
	}

	@ConfigItem(
		keyName = KEY_SHARE,
		name = "Share my trades",
		description = "While connected, upload your world 345 trades (never your name)",
		position = 1,
		section = SECTION_SHARING
	)
	default boolean shareTrades()
	{
		return true;
	}

	@ConfigItem(
		keyName = KEY_SHARE_WORLD_EVENTS,
		name = "Share world events",
		description = "While connected, send the breach and Deadman's Chest broadcasts you see (the place and time, never"
			+ " your position) and get the ones broadcast before you logged in",
		position = 2,
		section = SECTION_SHARING
	)
	default boolean shareWorldEvents()
	{
		return true;
	}

	@ConfigItem(
		keyName = KEY_AUTO_OPEN,
		name = "Open item when setting up a GE offer",
		description = "Show the item in the side panel when you set up a buy or sell offer on world 345",
		position = 0,
		section = SECTION_PANEL
	)
	default boolean autoOpenFromGe()
	{
		return true;
	}

	@ConfigItem(
		keyName = KEY_BREACH,
		name = "Show breach timer",
		description = "Show the countdown to the next weekend breach at the top of the side panel",
		position = 1,
		section = SECTION_PANEL
	)
	default boolean showBreachTimer()
	{
		return true;
	}

	@Range(min = 1, max = 72)
	@ConfigItem(
		keyName = KEY_STALE_HOURS,
		name = "Flag offers with no fill for (hours)",
		description = "Mark an open offer as stale when nothing of it has filled for this many hours",
		position = 0,
		section = SECTION_ALERTS
	)
	default int staleHours()
	{
		return 6;
	}

	@ConfigItem(
		keyName = KEY_OFFER_NOTIFICATION,
		name = "Notify when undercut or outbid",
		description = "While connected, notify when someone sells below your sell offer or bids above your buy offer",
		position = 1,
		section = SECTION_ALERTS
	)
	default Notification offerNotification()
	{
		return Notification.OFF;
	}

	@ConfigItem(
		keyName = KEY_WORLD_EVENTS,
		name = "Show breaches and chests",
		description = "Mark breach and Deadman's Chest broadcasts on the world map, like clue and quest markers (click one"
			+ " at the map's edge to go to it), and list them in the side panel",
		position = 0,
		section = SECTION_WORLD
	)
	default boolean showWorldEvents()
	{
		return true;
	}

	@ConfigItem(
		keyName = KEY_MAP_CHESTS,
		name = "Chests on the map",
		description = "Mark the Deadman's Chest on the world map and minimap",
		position = 1,
		section = SECTION_WORLD
	)
	default boolean worldEventMapChests()
	{
		return true;
	}

	@ConfigItem(
		keyName = KEY_MAP_BREACHES,
		name = "Breaches on the map",
		description = "Mark breaches on the world map and minimap",
		position = 2,
		section = SECTION_WORLD
	)
	default boolean worldEventMapBreaches()
	{
		return true;
	}

	@ConfigItem(
		keyName = KEY_WORLD_EVENT_MINIMAP,
		name = "Arrows on the minimap",
		description = "Show breaches and the chest on the minimap: at their spot when close, otherwise as an arrow on the"
			+ " minimap's edge pointing towards them",
		position = 3,
		section = SECTION_WORLD
	)
	default boolean worldEventMinimap()
	{
		return true;
	}

	@ConfigItem(
		keyName = KEY_WORLD_EVENT_ARROW,
		name = "Hint arrow to the chest",
		description = "Point the game's hint arrow at the current Deadman's Chest when its spot is known. Clicking an"
			+ " event in the side panel points the arrow at that event instead",
		position = 4,
		section = SECTION_WORLD
	)
	default boolean worldEventArrow()
	{
		return false;
	}

	@ConfigItem(
		keyName = KEY_LOCAL_LOG,
		name = "Save local trade log",
		description = "Append every recorded trade to trades-YYYY-MM.jsonl (one file per month) in this plugin's data folder (works without connecting)",
		position = 0,
		section = SECTION_LOCAL
	)
	default boolean saveLocalLog()
	{
		return true;
	}
}
