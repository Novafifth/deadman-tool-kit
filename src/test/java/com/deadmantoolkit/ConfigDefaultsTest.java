package com.deadmantoolkit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import net.runelite.client.config.Notification;
import org.junit.Test;

/** Defaults the Plugin Hub cares about: connecting to the server is opt-in; everything local is on. */
public class ConfigDefaultsTest
{
	private final DeadmanToolKitConfig config = new DeadmanToolKitConfig()
	{
	};

	@Test
	public void defaults()
	{
		assertFalse(config.connected());
		assertTrue(config.shareTrades());
		assertTrue(config.saveLocalLog());
		assertTrue(config.autoOpenFromGe());
		assertTrue(config.showBreachTimer());
		assertEquals(6, config.staleHours());
		assertSame(Notification.OFF, config.offerNotification());
		assertTrue(config.shareWorldEvents());
		assertTrue(config.showWorldEvents());
		assertFalse("the hint arrow is opt-in", config.worldEventArrow());
		assertTrue(config.worldEventMinimap());
		assertTrue(config.worldEventMapChests());
		assertTrue(config.worldEventMapBreaches());
	}

	@Test
	public void keysNeverChange()
	{
		assertEquals("deadman-tool-kit", DeadmanToolKitConfig.GROUP);
		assertEquals("connected", DeadmanToolKitConfig.KEY_CONNECTED);
		assertEquals("shareTrades", DeadmanToolKitConfig.KEY_SHARE);
		assertEquals("saveLocalLog", DeadmanToolKitConfig.KEY_LOCAL_LOG);
		assertEquals("autoOpenFromGe", DeadmanToolKitConfig.KEY_AUTO_OPEN);
		assertEquals("showBreachTimer", DeadmanToolKitConfig.KEY_BREACH);
		assertEquals("chatHintShown", DeadmanToolKitConfig.KEY_HINT_SHOWN);
		assertEquals("staleHours", DeadmanToolKitConfig.KEY_STALE_HOURS);
		assertEquals("offerNotification", DeadmanToolKitConfig.KEY_OFFER_NOTIFICATION);
		assertEquals("shareWorldEvents", DeadmanToolKitConfig.KEY_SHARE_WORLD_EVENTS);
		assertEquals("showWorldEvents", DeadmanToolKitConfig.KEY_WORLD_EVENTS);
		assertEquals("worldEventArrow", DeadmanToolKitConfig.KEY_WORLD_EVENT_ARROW);
		assertEquals("worldEventMinimap", DeadmanToolKitConfig.KEY_WORLD_EVENT_MINIMAP);
		assertEquals("worldEventMapChests", DeadmanToolKitConfig.KEY_MAP_CHESTS);
		assertEquals("worldEventMapBreaches", DeadmanToolKitConfig.KEY_MAP_BREACHES);
		assertEquals("This feature submits your IP address to a 3rd-party server not controlled or verified by RuneLite developers",
			DeadmanToolKitConfig.THIRD_PARTY_WARNING);
	}
}
