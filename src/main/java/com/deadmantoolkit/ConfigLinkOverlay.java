package com.deadmantoolkit;

import java.awt.Dimension;
import java.awt.Graphics2D;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.ui.overlay.Overlay;

/**
 * Never registered with the OverlayManager and never drawn. It only carries the plugin in an OverlayMenuClicked
 * event, which RuneLite's config plugin answers by opening this plugin's configuration page (the panel's gear button).
 */
class ConfigLinkOverlay extends Overlay
{
	ConfigLinkOverlay(Plugin plugin)
	{
		super(plugin);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		return null;
	}
}
