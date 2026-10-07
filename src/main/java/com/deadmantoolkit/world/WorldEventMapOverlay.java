package com.deadmantoolkit.world;

import java.awt.Dimension;
import java.awt.Graphics2D;
import java.util.function.Supplier;
import net.runelite.api.Client;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/**
 * Draws nothing itself: while the world map is open it turns the arrows of edge-pinned world event markers towards
 * their events (RuneLite's world map overlay draws the markers). One cheap pass over a handful of markers per frame.
 */
public final class WorldEventMapOverlay extends Overlay
{
	private final Client client;
	private final Supplier<WorldEventMarkers> markers;

	public WorldEventMapOverlay(Client client, Supplier<WorldEventMarkers> markers)
	{
		this.client = client;
		this.markers = markers;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.MANUAL);
		drawAfterInterface(InterfaceID.WORLDMAP);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		WorldEventMarkers m = markers.get();
		if (m != null)
		{
			m.updateEdges(client.getWorldMap());
		}
		return null;
	}
}
