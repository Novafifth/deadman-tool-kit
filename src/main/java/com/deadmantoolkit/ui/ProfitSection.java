package com.deadmantoolkit.ui;

import static com.deadmantoolkit.ui.PanelComponents.column;
import static com.deadmantoolkit.ui.PanelComponents.lineRow;
import static com.deadmantoolkit.ui.PanelComponents.sectionLabel;
import static com.deadmantoolkit.ui.PanelComponents.stat;
import static com.deadmantoolkit.ui.PanelComponents.wrapText;
import com.deadmantoolkit.ProfitSnapshot;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.BiConsumer;
import javax.swing.Box;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;

/**
 * "Profit" on the home card: realized profit today, over 7 days and all time, the best items, and footnotes for GE
 * History rows and sells with no known cost, plus a Rebuild button that recounts from the local trade log. Hidden
 * until an account's numbers arrive and there is something to show (or a log to rebuild from). EDT only.
 */
class ProfitSection
{
	/** Item rows shown under the totals. */
	static final int ROWS = 3;

	private final JPanel panel = column();
	private final JButton rebuild = new JButton("Rebuild");
	private final JPanel body = column();
	private final BiConsumer<Integer, String> onOpen;

	/** The account's numbers; null until an account has loaded. */
	private ProfitSnapshot snapshot;
	private boolean logEnabled = true;
	/** Rebuild was clicked and no snapshot has arrived since. */
	private boolean requested;
	/** What the body shows now, to skip identical re-renders. */
	private List<Object> shown;

	/**
	 * @param onRebuild recount from the trade log (submits the work; must not block)
	 * @param onOpen    opens an item from a row
	 */
	ProfitSection(Runnable onRebuild, BiConsumer<Integer, String> onOpen)
	{
		this.onOpen = onOpen;
		JPanel header = new JPanel(new BorderLayout(4, 0));
		header.setOpaque(false);
		header.add(sectionLabel("Profit"), BorderLayout.WEST);
		rebuild.setFont(FontManager.getRunescapeSmallFont());
		rebuild.setMargin(new Insets(0, 4, 0, 4));
		rebuild.setFocusPainted(false);
		rebuild.addActionListener(e ->
		{
			requested = true;
			styleButton();
			onRebuild.run();
		});
		JPanel east = new JPanel(new BorderLayout());
		east.setOpaque(false);
		east.add(rebuild, BorderLayout.NORTH);
		header.add(east, BorderLayout.EAST);
		header.setAlignmentX(Component.LEFT_ALIGNMENT);
		header.setMaximumSize(new Dimension(Integer.MAX_VALUE, 24));

		panel.add(header);
		panel.add(body);
		panel.add(Box.createVerticalStrut(8));
		panel.setVisible(false);
		styleButton();
	}

	JComponent getComponent()
	{
		return panel;
	}

	/** New numbers from the plugin (after a trade, an account switch or a rebuild). */
	void setSnapshot(ProfitSnapshot s)
	{
		snapshot = s;
		requested = false;
		render();
	}

	/** Whether the local trade log is on; Rebuild needs it. */
	void setLogEnabled(boolean enabled)
	{
		if (enabled != logEnabled)
		{
			logEnabled = enabled;
			render();
		}
	}

	/** Once a second while shown: "Today" moves on at midnight. */
	void tick()
	{
		if (snapshot != null && panel.isVisible())
		{
			render();
		}
	}

	/** Shown once there are numbers, or a log a rebuild could count. */
	static boolean visible(ProfitSnapshot s)
	{
		return s != null && (s.hasData() || s.isLogAvailable());
	}

	private void render()
	{
		styleButton();
		boolean show = visible(snapshot);
		if (show != panel.isVisible())
		{
			panel.setVisible(show);
		}
		if (!show)
		{
			return;
		}
		ProfitSnapshot s = snapshot;
		LocalDate today = LocalDate.now();
		List<ProfitSnapshot.ItemProfit> top = s.getTop().subList(0, Math.min(ROWS, s.getTop().size()));
		List<Object> key = Arrays.asList(s.hasData(), s.today(today), s.last7(today), s.getAllTime(),
			s.getHistoryAllTime(), s.getUnknownProceeds(), new ArrayList<>(top));
		if (key.equals(shown))
		{
			return;
		}
		shown = key;
		body.removeAll();
		if (!s.hasData())
		{
			body.add(wrapText(ProfitText.EMPTY, ColorScheme.LIGHT_GRAY_COLOR));
		}
		else
		{
			JPanel cells = new JPanel(new GridLayout(1, 3, 4, 0));
			cells.setOpaque(false);
			cells.add(cell("Today", s.today(today)));
			cells.add(cell("7 days", s.last7(today)));
			cells.add(cell("All time", s.getAllTime()));
			cells.setAlignmentX(Component.LEFT_ALIGNMENT);
			cells.setMaximumSize(new Dimension(Integer.MAX_VALUE, cells.getPreferredSize().height));
			body.add(cells);
			for (ProfitSnapshot.ItemProfit p : top)
			{
				body.add(Box.createVerticalStrut(2));
				body.add(itemRow(p));
			}
			addNote(ProfitText.historyNote(s));
			addNote(ProfitText.unknownNote(s));
		}
		panel.revalidate();
		panel.repaint();
	}

	private static JComponent cell(String label, long v)
	{
		return stat(label, Format.signedGp(v), null, ProfitText.color(v));
	}

	private JComponent itemRow(ProfitSnapshot.ItemProfit p)
	{
		Color c = ProfitText.color(p.getRealized());
		JComponent row = lineRow(ProfitText.name(p), Format.signedGp(p.getRealized()), c, c, ProfitText.itemTooltip(p));
		row.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		row.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				onOpen.accept(p.getItemId(), ProfitText.name(p));
			}
		});
		return row;
	}

	private void addNote(String text)
	{
		if (text != null)
		{
			body.add(Box.createVerticalStrut(4));
			body.add(wrapText(text, ColorScheme.LIGHT_GRAY_COLOR));
		}
	}

	private void styleButton()
	{
		boolean running = requested || (snapshot != null && snapshot.isRebuilding());
		String text = running ? "Rebuilding…" : "Rebuild";
		boolean enabled = logEnabled && !running && snapshot != null;
		String tip = !logEnabled ? ProfitText.REBUILD_NEEDS_LOG : running ? "Recounting from your local trade log" : ProfitText.REBUILD_TIP;
		if (!text.equals(rebuild.getText()))
		{
			rebuild.setText(text);
		}
		if (enabled != rebuild.isEnabled())
		{
			rebuild.setEnabled(enabled);
		}
		if (!tip.equals(rebuild.getToolTipText()))
		{
			rebuild.setToolTipText(tip);
		}
	}
}
