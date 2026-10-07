package com.deadmantoolkit.ui;

import static com.deadmantoolkit.ui.PanelComponents.column;
import static com.deadmantoolkit.ui.PanelComponents.linkLabel;
import static com.deadmantoolkit.ui.PanelComponents.smallLabel;
import static com.deadmantoolkit.ui.PanelComponents.wrapText;
import com.deadmantoolkit.DeadmanToolKitConfig;
import com.deadmantoolkit.Onboarding;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Insets;
import java.util.Objects;
import javax.swing.Box;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.border.EmptyBorder;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;

/**
 * "Get started" card at the top of the home view: connect (what's shared; RuneLite's third-party warning is shown as a
 * confirmation when Connect is clicked) and the GE History import steps, then a one-time "Imported N trades" line.
 * EDT only.
 */
class WelcomeCard
{
	static final String SHARED_TEXT = "Shared: item, price, quantity, time, world, a hashed account ID and a random install ID."
		+ " Never your name. IP addresses are never stored.";

	/** Asks before connecting; returns true to go ahead. Tests replace the dialog. */
	interface ConnectConfirm
	{
		boolean confirm(Component parent);
	}

	/**
	 * RuneLite's required third-party warning, shown when Connect is clicked. The settings page shows the same text
	 * through the config item's warning; the card's Connect button doesn't go through that dialog, so it asks here.
	 */
	static final ConnectConfirm WARNING_DIALOG = parent -> JOptionPane.showConfirmDialog(parent,
		DeadmanToolKitConfig.THIRD_PARTY_WARNING + ".\n\nThe Deadman Tool Kit server never stores your IP address."
			+ "\n\nConnect now?",
		"Deadman Tool Kit", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) == JOptionPane.YES_OPTION;

	private final Runnable onConnect;
	private final ConnectConfirm confirmConnect;
	private final Runnable onPrivacy;
	private final Runnable onDismissNotice;
	/** "Import RuneLite's GE history" next to the GE History steps, or null. */
	private final JComponent importLink;
	/** Outer wrapper: the card plus the gap below it, so a hidden card leaves no gap. */
	private final JPanel wrapper = column();
	private final JPanel card = column();

	private Onboarding.Step shownStep;
	private boolean shownNeedsLogin;
	private Integer shownNotice;

	/**
	 * @param onConnect       sets the connected setting
	 * @param onPrivacy       opens the privacy notice
	 * @param onDismissNotice forgets the import notice (the "x" button)
	 */
	WelcomeCard(Runnable onConnect, Runnable onPrivacy, Runnable onDismissNotice)
	{
		this(onConnect, onPrivacy, onDismissNotice, null);
	}

	/** @param importLink the "Import RuneLite's GE history" action shown with the GE History steps, or null */
	WelcomeCard(Runnable onConnect, Runnable onPrivacy, Runnable onDismissNotice, JComponent importLink)
	{
		this(onConnect, onPrivacy, onDismissNotice, importLink, WARNING_DIALOG);
	}

	/** @param confirmConnect asks before connecting (RuneLite's third-party warning) */
	WelcomeCard(Runnable onConnect, Runnable onPrivacy, Runnable onDismissNotice, JComponent importLink,
		ConnectConfirm confirmConnect)
	{
		this.onConnect = onConnect;
		this.confirmConnect = confirmConnect;
		this.onPrivacy = onPrivacy;
		this.onDismissNotice = onDismissNotice;
		this.importLink = importLink;
		card.setOpaque(true);
		card.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		card.setBorder(new EmptyBorder(8, 8, 8, 8));
		wrapper.add(card);
		wrapper.add(Box.createVerticalStrut(8));
		wrapper.setVisible(false);
	}

	JComponent getComponent()
	{
		return wrapper;
	}

	/** Whether the card shows its import block, with the "Import RuneLite's GE history" link. */
	boolean showsImportLink()
	{
		return importLink != null
			&& (shownStep == Onboarding.Step.IMPORT || shownStep == Onboarding.Step.CONNECT_AND_IMPORT);
	}

	/** Show the given state. Rebuilds only when something changed. */
	void update(Onboarding.Step step, boolean needsLogin, Integer notice)
	{
		if (step == shownStep && needsLogin == shownNeedsLogin && Objects.equals(notice, shownNotice))
		{
			return;
		}
		shownStep = step;
		shownNeedsLogin = needsLogin;
		shownNotice = notice;

		card.removeAll();
		boolean connect = step == Onboarding.Step.CONNECT || step == Onboarding.Step.CONNECT_AND_IMPORT;
		boolean importSteps = step == Onboarding.Step.IMPORT || step == Onboarding.Step.CONNECT_AND_IMPORT;
		boolean showNotice = notice != null && step != Onboarding.Step.HIDDEN;
		if (connect || importSteps)
		{
			JLabel title = new JLabel("Get started");
			title.setFont(FontManager.getRunescapeBoldFont());
			title.setForeground(Color.WHITE);
			title.setAlignmentX(Component.LEFT_ALIGNMENT);
			card.add(title);
			card.add(Box.createVerticalStrut(4));
		}
		if (showNotice)
		{
			card.add(noticeRow(notice));
			if (connect || importSteps)
			{
				card.add(Box.createVerticalStrut(6));
			}
		}
		if (connect)
		{
			addConnectBlock();
		}
		if (importSteps)
		{
			if (connect)
			{
				card.add(Box.createVerticalStrut(10));
			}
			addImportBlock(needsLogin);
		}
		wrapper.setVisible(connect || importSteps || showNotice);
		wrapper.revalidate();
		wrapper.repaint();
	}

	private void addConnectBlock()
	{
		card.add(wrapText("See world 345 prices from every plugin user and share your trades.", ColorScheme.TEXT_COLOR));
		card.add(Box.createVerticalStrut(4));
		card.add(wrapText(SHARED_TEXT, ColorScheme.TEXT_COLOR));
		card.add(Box.createVerticalStrut(6));
		JPanel row = new JPanel(new BorderLayout(8, 0));
		row.setOpaque(false);
		JButton connect = new JButton("Connect");
		connect.setFocusPainted(false);
		connect.addActionListener(e ->
		{
			if (confirmConnect.confirm(card))
			{
				onConnect.run();
			}
		});
		row.add(connect, BorderLayout.WEST);
		JLabel privacy = linkLabel("Privacy", onPrivacy);
		row.add(privacy, BorderLayout.CENTER);
		row.setAlignmentX(Component.LEFT_ALIGNMENT);
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
		card.add(row);
	}

	private void addImportBlock(boolean needsLogin)
	{
		card.add(line("Import your GE History:", Color.WHITE));
		// One component type for all steps, so they line up.
		card.add(wrapText("1. Open the Grand Exchange.", ColorScheme.TEXT_COLOR));
		card.add(wrapText("2. Click the History button.", ColorScheme.TEXT_COLOR));
		card.add(wrapText("3. Done. Your past trades are added.", ColorScheme.TEXT_COLOR));
		if (needsLogin)
		{
			card.add(Box.createVerticalStrut(2));
			card.add(line("Log in on world 345 first.", ColorScheme.LIGHT_GRAY_COLOR));
		}
		if (importLink != null)
		{
			// Opt-in: reads RuneLite's own GE plugin data only when clicked.
			card.add(Box.createVerticalStrut(6));
			card.add(wrapText("Or add the trades RuneLite's own GE plugin recorded for this account (read only when"
				+ " you click):", ColorScheme.TEXT_COLOR));
			card.add(importLink);
		}
	}

	private JComponent noticeRow(int count)
	{
		JPanel row = new JPanel(new BorderLayout(4, 0));
		row.setOpaque(false);
		row.add(smallLabel(noticeText(count), PanelComponents.GOOD), BorderLayout.CENTER);
		JButton close = new JButton("x");
		close.setFont(FontManager.getRunescapeSmallFont());
		close.setMargin(new Insets(0, 4, 0, 4));
		close.setFocusPainted(false);
		close.setToolTipText("Dismiss");
		close.addActionListener(e -> onDismissNotice.run());
		row.add(close, BorderLayout.EAST);
		row.setAlignmentX(Component.LEFT_ALIGNMENT);
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 20));
		return row;
	}

	/** The one-time line after the first GE History import; an empty history still counts as imported. */
	static String noticeText(int count)
	{
		return count == 0 ? "GE History read: no trades to import"
			: "Imported " + count + " trade" + (count == 1 ? "" : "s") + " from GE History";
	}

	private static JComponent line(String text, Color color)
	{
		JLabel l = smallLabel(text, color);
		l.setAlignmentX(Component.LEFT_ALIGNMENT);
		return l;
	}
}
