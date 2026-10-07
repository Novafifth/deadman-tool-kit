package com.deadmantoolkit.ui;

import static com.deadmantoolkit.ui.PanelComponents.column;
import static com.deadmantoolkit.ui.PanelComponents.iconButton;
import static com.deadmantoolkit.ui.PanelComponents.mutedLabel;
import static com.deadmantoolkit.ui.PanelComponents.offerRow;
import static com.deadmantoolkit.ui.PanelComponents.sectionLabel;
import com.deadmantoolkit.ActiveOffer;
import com.deadmantoolkit.OfferReport;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.BiConsumer;
import javax.swing.Box;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import net.runelite.client.ui.ColorScheme;

/**
 * "Your offers" on the home card: each open offer on world 345 with a badge saying whether it was undercut, outbid
 * or is stale (when connected), or how much of it has filled. Hidden while you have no open offers. EDT only.
 */
class OffersSection
{
	static final String NOT_CONNECTED = "Connect to compare with the market";

	private final JPanel panel = column();
	private final JLabel checkedLabel = mutedLabel("");
	private final JLabel hint = mutedLabel(NOT_CONNECTED);
	private final RowList<OfferLine> rows;

	private List<ActiveOffer> offers = Collections.emptyList();
	private OfferReport report = OfferReport.EMPTY;
	private boolean connected;

	/**
	 * @param onRefresh the refresh button: check the market now
	 * @param onOpen    opens an offer's item
	 */
	OffersSection(Runnable onRefresh, BiConsumer<Integer, String> onOpen)
	{
		rows = new RowList<>(OfferLine::key, l -> l.getText() + "|" + l.getBadge() + "|" + l.getTooltip(),
			l -> clickable(offerRow(l), l.getOffer(), onOpen));

		JPanel header = new JPanel(new BorderLayout(4, 0));
		header.setOpaque(false);
		header.add(sectionLabel("Your offers"), BorderLayout.WEST);
		JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
		right.setOpaque(false);
		right.add(checkedLabel);
		JButton refresh = iconButton(Icons.refresh(), "Compare with the market now", 22);
		refresh.addActionListener(e -> onRefresh.run());
		right.add(refresh);
		header.add(right, BorderLayout.EAST);
		header.setAlignmentX(Component.LEFT_ALIGNMENT);
		header.setMaximumSize(new Dimension(Integer.MAX_VALUE, 24));

		hint.setAlignmentX(Component.LEFT_ALIGNMENT);
		panel.add(header);
		panel.add(rows.getComponent());
		panel.add(hint);
		panel.add(Box.createVerticalStrut(8));
		panel.setVisible(false);
	}

	JComponent getComponent()
	{
		return panel;
	}

	void setOffers(List<ActiveOffer> offers)
	{
		this.offers = offers;
		render();
	}

	void setReport(OfferReport report)
	{
		this.report = report == null ? OfferReport.EMPTY : report;
		render();
	}

	void setConnected(boolean connected)
	{
		if (connected != this.connected)
		{
			this.connected = connected;
			render();
		}
	}

	/** The lines shown for these offers (pure): no market status when not connected. */
	static List<OfferLine> lines(List<ActiveOffer> offers, OfferReport report, boolean connected, long now)
	{
		List<OfferLine> out = new ArrayList<>(offers.size());
		for (ActiveOffer o : offers)
		{
			out.add(OfferLine.home(o, connected ? report.statusOf(o) : null, now));
		}
		return out;
	}

	private void render()
	{
		panel.setVisible(!offers.isEmpty());
		if (offers.isEmpty())
		{
			rows.setItems(Collections.emptyList());
			rows.reset();
			return;
		}
		rows.setItems(lines(offers, report, connected, Instant.now().getEpochSecond()));
		hint.setVisible(!connected);
		tick();
	}

	/** Once a second while shown: the "checked Xs ago" text. */
	void tick()
	{
		String text = "";
		Color color = ColorScheme.LIGHT_GRAY_COLOR;
		String tip = null;
		if (connected && report.getError() != null)
		{
			text = "check failed";
			color = PanelComponents.ALERT;
			tip = report.getError();
		}
		else if (connected && report.getCheckedAt() > 0)
		{
			text = "checked " + Format.updatedAgo(report.getCheckedAt(), System.currentTimeMillis());
			tip = "Compared with the market's best bid / ask and recent trades";
		}
		if (!text.equals(checkedLabel.getText()))
		{
			checkedLabel.setText(text);
			checkedLabel.setForeground(color);
			checkedLabel.setToolTipText(tip);
		}
	}

	void stop()
	{
		rows.stop();
	}

	private static JComponent clickable(JComponent row, ActiveOffer o, BiConsumer<Integer, String> onOpen)
	{
		row.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		row.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				onOpen.accept(o.getItemId(), o.getName());
			}
		});
		return row;
	}
}
