package com.deadmantoolkit.ui;

import com.deadmantoolkit.world.WorldEventIcons;
import com.deadmantoolkit.world.WorldEventMarkers;
import com.deadmantoolkit.world.WorldEventMessage;
import com.deadmantoolkit.world.WorldEvents;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.ImageIcon;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.border.EmptyBorder;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;

/**
 * The breaches and Deadman's Chest going on now, one line each under the breach banner: icon, place, and how long ago
 * it spawned (chest) or how long it has left (breach). The tooltip says how exact the map marker is; clicking a line
 * points the game's hint arrow at that event (again to stop). Hidden while there's nothing to show. EDT only.
 */
class WorldEventsList
{
	private static final int ROW_HEIGHT = 20;
	private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm");

	private final JPanel panel = PanelComponents.column();
	private final Consumer<WorldEvents.Event> onClick;
	private List<WorldEvents.Event> events = Collections.emptyList();
	private WorldPoint arrowAt;
	/** The time labels, in event order, updated each second. */
	private final List<JLabel> times = new ArrayList<>();

	/** @param onClick a line was clicked (point the hint arrow there, or stop) */
	WorldEventsList(Consumer<WorldEvents.Event> onClick)
	{
		this.onClick = onClick;
		panel.setVisible(false);
	}

	JComponent getComponent()
	{
		return panel;
	}

	/** Show these events; {@code arrowAt} is where the hint arrow points (that line is highlighted), or null. */
	void set(List<WorldEvents.Event> events, WorldPoint arrowAt)
	{
		if (events.equals(this.events) && Objects.equals(arrowAt, this.arrowAt))
		{
			return;
		}
		this.events = new ArrayList<>(events);
		this.arrowAt = arrowAt;
		panel.removeAll();
		times.clear();
		Instant now = Instant.now();
		for (WorldEvents.Event e : this.events)
		{
			panel.add(row(e, now));
			panel.add(Box.createVerticalStrut(2));
		}
		if (!this.events.isEmpty())
		{
			panel.add(Box.createVerticalStrut(4));
		}
		panel.setVisible(!this.events.isEmpty());
		panel.revalidate();
		panel.repaint();
	}

	/** Once a second: the times move on. */
	void tick()
	{
		Instant now = Instant.now();
		for (int i = 0; i < times.size() && i < events.size(); i++)
		{
			String t = timeText(events.get(i), now);
			if (!t.equals(times.get(i).getText()))
			{
				times.get(i).setText(t);
			}
		}
	}

	private JComponent row(WorldEvents.Event e, Instant now)
	{
		boolean arrowed = e.getWhere() != null && e.getWhere().getPoint() != null
			&& e.getWhere().getPoint().equals(arrowAt);
		JPanel row = new JPanel(new BorderLayout(5, 0));
		row.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		Color edge = arrowed ? ColorScheme.BRAND_ORANGE
			: WorldEventIcons.color(e.getKind());
		row.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0, 2, 0, 0, edge),
			new EmptyBorder(1, 4, 1, 5)));
		row.setAlignmentX(Component.LEFT_ALIGNMENT);
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, ROW_HEIGHT));
		row.setPreferredSize(new Dimension(0, ROW_HEIGHT));

		JLabel icon = new JLabel(new ImageIcon(WorldEventIcons.of(e.getKind(),
			e.getWhere() == null ? null : e.getWhere().getAccuracy())));
		row.add(icon, BorderLayout.WEST);

		JLabel text = new JLabel(label(e));
		text.setFont(FontManager.getRunescapeSmallFont());
		text.setForeground(ColorScheme.TEXT_COLOR);
		row.add(text, BorderLayout.CENTER);

		JLabel time = new JLabel(timeText(e, now));
		time.setFont(FontManager.getRunescapeSmallFont());
		time.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		row.add(time, BorderLayout.EAST);
		times.add(time);

		String tip = tooltip(e, arrowed);
		row.setToolTipText(tip);
		text.setToolTipText(tip);
		if (e.getWhere() != null && e.getWhere().known())
		{
			row.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
			row.addMouseListener(new MouseAdapter()
			{
				@Override
				public void mouseClicked(MouseEvent ev)
				{
					onClick.accept(e);
				}
			});
		}
		return row;
	}

	/** "Chest · north of the Warriors' Guild", "Breach (Multi) · the Bone Yard". */
	static String label(WorldEvents.Event e)
	{
		String kind = e.getKind() == WorldEventMessage.Kind.CHEST ? "Chest"
			: e.getCombat() == WorldEventMessage.Combat.MULTI ? "Breach (Multi)"
			: e.getCombat() == WorldEventMessage.Combat.SINGLE ? "Breach (Single)" : "Breach";
		return kind + " · " + e.getLocation();
	}

	/** A chest: how long since it spawned ("12m"); a breach: how long it has left ("31m left"). */
	static String timeText(WorldEvents.Event e, Instant now)
	{
		if (e.getKind() == WorldEventMessage.Kind.CHEST)
		{
			long m = Math.max(0, Duration.between(e.getAt(), now).toMinutes());
			return m < 1 ? "now" : m + "m";
		}
		long left = Math.max(0, Duration.between(now, e.getUntil()).toMinutes());
		return left + "m left";
	}

	static String tooltip(WorldEvents.Event e, boolean arrowed)
	{
		StringBuilder sb = new StringBuilder("<html>");
		sb.append(e.getKind() == WorldEventMessage.Kind.CHEST ? "Deadman's Chest: " : "Breach: ")
			.append(escape(e.getLocation()));
		String note = WorldEventMarkers.accuracyNote(e.getWhere());
		sb.append("<br>").append(note == null ? "Marked on the world map" : "On the map: " + escape(note));
		if (e.getWhere() != null && e.getWhere().getEntranceName() != null)
		{
			sb.append("<br>Enter at ").append(escape(e.getWhere().getEntranceName()));
		}
		ZoneId zone = ZoneId.systemDefault();
		sb.append("<br>Spawned ").append(CLOCK.format(e.getAt().atZone(zone)));
		if (e.getKind() == WorldEventMessage.Kind.BREACH)
		{
			sb.append(", gone by ").append(CLOCK.format(e.getUntil().atZone(zone)));
		}
		if (e.getSource() == WorldEvents.Source.REPORTED)
		{
			sb.append("<br>Broadcast before you logged in (").append(e.getReporters())
				.append(e.getReporters() == 1 ? " report)" : " reports)");
		}
		else if (e.getSource() == WorldEvents.Source.TEST)
		{
			sb.append("<br>Test event (developer tools)");
		}
		if (e.getWhere() != null && e.getWhere().known())
		{
			sb.append("<br>").append(arrowed ? "Click to remove the hint arrow" : "Click to point the hint arrow here");
		}
		return sb.append("</html>").toString();
	}

	private static String escape(String s)
	{
		return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}
}
