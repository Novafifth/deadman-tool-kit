package com.deadmantoolkit.ui;

import static com.deadmantoolkit.ui.Format.describe;
import static com.deadmantoolkit.ui.Format.when;
import static com.deadmantoolkit.ui.PanelComponents.column;
import static com.deadmantoolkit.ui.PanelComponents.iconButton;
import static com.deadmantoolkit.ui.PanelComponents.mutedLabel;
import static com.deadmantoolkit.ui.PanelComponents.styleTab;
import static com.deadmantoolkit.ui.PanelComponents.tabButton;
import static com.deadmantoolkit.ui.PanelComponents.tradeRow;
import static com.deadmantoolkit.ui.PanelComponents.wrapText;
import com.deadmantoolkit.MarketClient;
import com.deadmantoolkit.MarketData;
import com.deadmantoolkit.TradeEvent;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import javax.swing.Box;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.text.JTextComponent;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.util.QuantityFormatter;

/**
 * Home card: the "Get started" card (when there is something to set up), your open offers, your profit, Market / My trades tabs, an "updated" line
 * with a refresh button, a stats line and the list. The two lists are separate {@link RowList}s, so switching tabs
 * or refreshing never rebuilds rows that are already there. EDT only.
 */
class HomeView
{
	static final String NOT_CONNECTED = "Connect with the \"Get started\" card above, or in the plugin's settings (gear icon), to see the market.";

	private final JPanel panel = column();
	private final JLabel statsLabel = mutedLabel("");
	private final JLabel updatedLabel = mutedLabel("");
	/** "Updated Xs ago" plus the refresh button; only on the Market tab once it has data. */
	private final JPanel updatedRow = new JPanel(new BorderLayout(4, 0));
	private final JTextComponent message = (JTextComponent) wrapText("", ColorScheme.LIGHT_GRAY_COLOR);
	private final RowList<MarketData.FeedEvent> marketList;
	private final RowList<RowList.Keyed<TradeEvent>> mineList;
	/** Shown on the My trades tab only: the "Import RuneLite GE history" link. */
	private final JComponent mineExtra;
	/** The welcome card shows the same import link, so {@link #mineExtra} stays hidden. */
	private boolean mineExtraHidden;

	private boolean showMine;
	/** Epoch ms of the last successful market load, or 0. */
	private long lastUpdated;
	private boolean hasMarketData;
	/** The Market tab's stats text from its last load, put back when switching to it. */
	private String marketStats = "";
	/** Why the last market refresh failed, while its old data is still shown; null if it didn't. */
	private String failed;

	/**
	 * @param welcome   the "Get started" card, shown above the tabs (it hides itself when not needed)
	 * @param offers    "Your offers", below the welcome card (it hides itself when you have none)
	 * @param profit    "Profit", below your offers (it hides itself until there are numbers)
	 * @param onTab     run when a tab is clicked, to load it
	 * @param onRefresh the refresh button
	 * @param onOpen    opens an item from a row
	 * @param mineExtra shown under the stats line on the My trades tab only (the import link), or null
	 */
	HomeView(ItemManager itemManager, JComponent welcome, JComponent offers, JComponent profit, Runnable onTab, Runnable onRefresh,
		BiConsumer<Integer, String> onOpen, JComponent mineExtra)
	{
		marketList = new RowList<>(MarketData.FeedEvent::getId, e -> when(e.getKind(), feedTime(e)),
			e -> row(itemManager, e.getItemId(), e.getName(), e.getKind(), e.getSide(), e.getQty(), e.getPrice(), feedTime(e), onOpen));
		marketList.setEmptyText("No trades yet.");
		mineList = new RowList<>(k -> k.key, k -> when(k.value.getKind(), k.value.getTs()),
			k -> row(itemManager, k.value.getItemId(), k.value.getName(), k.value.getKind(), k.value.getSide(),
				k.value.getQty(), k.value.getPrice(), k.value.getTs(), onOpen));
		mineList.setEmptyText("No trades recorded yet. Trade on world 345 or open your GE History.");
		this.mineExtra = mineExtra;

		JPanel tabs = new JPanel(new GridLayout(1, 2, 4, 0));
		tabs.setOpaque(false);
		JButton marketTab = tabButton("Market");
		JButton mine = tabButton("My trades");
		Runnable style = () ->
		{
			styleTab(marketTab, !showMine);
			styleTab(mine, showMine);
		};
		marketTab.addActionListener(e -> selectTab(false, style, onTab));
		mine.addActionListener(e -> selectTab(true, style, onTab));
		style.run();
		tabs.add(marketTab);
		tabs.add(mine);
		tabs.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
		tabs.setAlignmentX(Component.LEFT_ALIGNMENT);

		updatedRow.setOpaque(false);
		updatedRow.setVisible(false);
		updatedRow.add(updatedLabel, BorderLayout.CENTER);
		JButton refresh = iconButton(Icons.refresh(), "Refresh now", 22);
		refresh.addActionListener(e -> onRefresh.run());
		updatedRow.add(refresh, BorderLayout.EAST);
		updatedRow.setAlignmentX(Component.LEFT_ALIGNMENT);
		updatedRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 22));
		statsLabel.setAlignmentX(Component.LEFT_ALIGNMENT);

		panel.add(welcome);
		panel.add(offers);
		panel.add(profit);
		panel.add(tabs);
		panel.add(Box.createVerticalStrut(4));
		panel.add(updatedRow);
		panel.add(Box.createVerticalStrut(2));
		panel.add(statsLabel);
		if (mineExtra != null)
		{
			mineExtra.setAlignmentX(Component.LEFT_ALIGNMENT);
			mineExtra.setVisible(false);
			panel.add(Box.createVerticalStrut(2));
			panel.add(mineExtra);
		}
		panel.add(Box.createVerticalStrut(6));
		message.setVisible(false);
		panel.add(message);
		panel.add(marketList.getComponent());
		panel.add(mineList.getComponent());
		mineList.getComponent().setVisible(false);
		// The Market tab is selected first: say it's loading rather than show an empty list.
		showLoading();
	}

	/** The time a feed row shows: arrival, or an imported trade's own approximate time. */
	private static long feedTime(MarketData.FeedEvent e)
	{
		return Format.feedTime(e.getKind(), e.getReceivedAt(), e.getTs());
	}

	/** An item row; imported trades show their approximate age above "imported", and say so in the tooltip. */
	private static JComponent row(ItemManager itemManager, int itemId, String name, String kind, String side, int qty,
		long price, long ts, BiConsumer<Integer, String> onOpen)
	{
		String line2 = describe(kind, side, qty, price);
		JComponent row = tradeRow(itemManager, itemId, name, line2, Format.whenTwoLine(kind, ts), onOpen);
		if (TradeEvent.IMPORTED.equals(kind))
		{
			row.setToolTipText("<html>" + row.getToolTipText() + "<br>" + Format.IMPORTED_TIP + "</html>");
		}
		return row;
	}

	/** Hide the My trades tab's import link (while the welcome card shows its own), or show it again. */
	void setMineExtraHidden(boolean hidden)
	{
		mineExtraHidden = hidden;
		if (mineExtra != null && mineExtra.isVisible() != (showMine && !hidden))
		{
			mineExtra.setVisible(showMine && !hidden);
		}
	}

	private void selectTab(boolean mine, Runnable style, Runnable onTab)
	{
		if (mine != showMine)
		{
			showMine = mine;
			if (mineExtra != null)
			{
				mineExtra.setVisible(mine && !mineExtraHidden);
			}
			// A different tab starts at its first page and doesn't flash rows as new.
			(mine ? mineList : marketList).reset();
			if (!mine)
			{
				mineList.getComponent().setVisible(false);
				if (hasMarketData)
				{
					// Show the feed we have right away; the load that follows only adds what's new.
					setStats(marketStats);
					message.setVisible(false);
					marketList.getComponent().setVisible(true);
				}
				else
				{
					showLoading();
				}
			}
		}
		style.run();
		onTab.run();
	}

	JComponent getComponent()
	{
		return panel;
	}

	/** True when the "My trades" tab is selected. */
	boolean isShowingMine()
	{
		return showMine;
	}

	void showMine(List<TradeEvent> trades)
	{
		setStats("Your world 345 trades");
		message.setVisible(false);
		marketList.getComponent().setVisible(false);
		mineList.getComponent().setVisible(true);
		mineList.setItems(RowList.withOccurrenceKeys(trades, ItemSections::tradeKey));
		tick();
	}

	void showNotConnected()
	{
		setStats("Not connected");
		showMessage(NOT_CONNECTED);
		marketList.getComponent().setVisible(false);
		mineList.getComponent().setVisible(false);
		tick();
	}

	/** Show the merged Market feed ({@link FeedMerge}), newest first. */
	void showMarket(MarketData.Stats s, List<MarketData.FeedEvent> events)
	{
		marketStats = s == null ? "" : "<html>" + s.getOpenBids() + " bids · " + s.getOpenAsks() + " asks open<br>"
			+ QuantityFormatter.quantityToStackSize(s.getUnits24()) + " units / " + QuantityFormatter.quantityToStackSize(s.getGp24())
			+ " gp traded in 24h</html>";
		setStats(marketStats);
		message.setVisible(false);
		mineList.getComponent().setVisible(false);
		marketList.getComponent().setVisible(true);
		marketList.setItems(events);
		hasMarketData = true;
		failed = null;
		lastUpdated = System.currentTimeMillis();
		tick();
	}

	/** A market refresh failed: replace the list only if nothing was loaded yet, otherwise just say so. */
	void showError(String text)
	{
		if (!hasMarketData)
		{
			setStats("");
			showMessage(text);
			marketList.getComponent().setVisible(false);
			mineList.getComponent().setVisible(false);
		}
		else
		{
			failed = text;
		}
		tick();
	}

	/** Forget the loaded market (connection changed); the next load starts fresh. */
	void resetMarket()
	{
		// Empty first, then reset: the first real load must not count as new rows.
		marketList.setItems(new ArrayList<>());
		marketList.reset();
		hasMarketData = false;
		marketStats = "";
		lastUpdated = 0;
		failed = null;
		if (!showMine)
		{
			showLoading();
		}
	}

	/** The Market tab before its first load: no list (not "No trades yet."), just a loading line. */
	private void showLoading()
	{
		setStats("");
		showMessage("Loading…");
		marketList.getComponent().setVisible(false);
		mineList.getComponent().setVisible(false);
		tick();
	}

	/** Once a second while shown: update the "updated Xs ago" text (market tab only). */
	void tick()
	{
		String text;
		Color color = ColorScheme.LIGHT_GRAY_COLOR;
		if (showMine || !hasMarketData)
		{
			text = "";
		}
		else if (failed != null)
		{
			// The reason in a few words; the full sentence is in the tooltip.
			text = failedText(failed);
			color = ColorScheme.PROGRESS_ERROR_COLOR;
		}
		else
		{
			text = "Updated " + Format.updatedAgo(lastUpdated, System.currentTimeMillis());
		}
		boolean rowShown = !showMine && hasMarketData;
		if (updatedRow.isVisible() != rowShown)
		{
			updatedRow.setVisible(rowShown);
		}
		if (!text.equals(updatedLabel.getText()))
		{
			updatedLabel.setText(text);
			updatedLabel.setForeground(color);
			updatedLabel.setToolTipText(failed != null ? failed + " Showing the last data received." : null);
		}
	}

	/** The status line after a failed refresh, e.g. "Update failed: server unreachable". */
	static String failedText(String error)
	{
		return "Update failed: " + MarketClient.shortReason(error);
	}

	void stop()
	{
		marketList.stop();
		mineList.stop();
	}

	private void setStats(String text)
	{
		if (!text.equals(statsLabel.getText()))
		{
			statsLabel.setText(text);
		}
	}

	private void showMessage(String text)
	{
		if (!text.equals(message.getText()))
		{
			message.setText(text);
		}
		message.setVisible(true);
	}
}
