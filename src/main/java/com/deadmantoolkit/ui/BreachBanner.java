package com.deadmantoolkit.ui;

import com.deadmantoolkit.BreachSchedule;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.border.EmptyBorder;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;

/**
 * One-line banner at the top of the panel counting down to the next weekend breach, or showing the current one.
 * Driven by the panel's 1 s ticker; EDT only.
 */
class BreachBanner
{
	private static final Color ACTIVE_BG = new Color(0x7a2e12);
	private static final int HEIGHT = 22;

	private final JPanel panel = new JPanel(new BorderLayout());
	private final JLabel label = new JLabel();

	/** Next breach start from the in-game message, or null. */
	private Instant correction;
	/** Developer-mode breach preview: added to the real clock (zero normally). */
	private Duration offset = Duration.ZERO;
	private BreachSchedule.Phase shownPhase;

	BreachBanner()
	{
		panel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		panel.setBorder(new EmptyBorder(3, 6, 3, 6));
		panel.setAlignmentX(Component.LEFT_ALIGNMENT);
		panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, HEIGHT));
		panel.setPreferredSize(new Dimension(0, HEIGHT));
		panel.setToolTipText("Weekend breaches at 02/06/10/14/18/22 UTC; corrected from the in-game message when seen.");
		label.setFont(FontManager.getRunescapeSmallFont());
		panel.add(label, BorderLayout.CENTER);
		tick();
	}

	JComponent getComponent()
	{
		return panel;
	}

	/** The game said when the next breach starts. */
	void setCorrection(Instant start)
	{
		correction = start;
		tick();
	}

	/**
	 * Developer-mode breach preview: run the banner on the real clock shifted by {@code offset} (zero = the real
	 * clock), and drop any correction from a game message.
	 */
	void preview(Duration offset)
	{
		this.offset = offset == null ? Duration.ZERO : offset;
		correction = null;
		tick();
	}

	void tick()
	{
		Instant now = Instant.now().plus(offset);
		if (correction != null && now.isAfter(correction.plus(BreachSchedule.SPAWN).plus(BreachSchedule.DESPAWN)))
		{
			correction = null;
		}
		BreachSchedule.State state = BreachSchedule.state(now, correction);
		String text = BreachText.of(state, now, ZoneId.systemDefault());
		if (!text.equals(label.getText()))
		{
			label.setText(text);
		}
		if (state.getPhase() != shownPhase)
		{
			shownPhase = state.getPhase();
			switch (shownPhase)
			{
				case ACTIVE:
					panel.setBackground(ACTIVE_BG);
					label.setForeground(Color.WHITE);
					break;
				case DESPAWN:
					panel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
					label.setForeground(ColorScheme.BRAND_ORANGE);
					break;
				default:
					panel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
					label.setForeground(ColorScheme.TEXT_COLOR);
					break;
			}
		}
	}
}
