package com.deadmantoolkit.ui;

import com.deadmantoolkit.MarketData;
import com.deadmantoolkit.TradeEvent;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.function.BiConsumer;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.SwingConstants;
import javax.swing.border.EmptyBorder;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.util.QuantityFormatter;

/**
 * Small Swing building blocks shared by the panel's views.
 * <p>
 * Rows are single-line labels (no wrapping HTML) with a fixed maximum height, so a BoxLayout column never stretches
 * them; {@link #wrapText} is only for messages.
 */
final class PanelComponents
{
	static final Color BUY = new Color(0x3987e5);
	static final Color SELL = new Color(0xd95926);
	/** Positive / connected. */
	static final Color GOOD = new Color(0x3cb371);
	/** An offer was undercut or outbid. */
	static final Color ALERT = new Color(0xe0503a);
	/** An offer hasn't filled for a long time. */
	static final Color STALE = new Color(0xd8a634);
	/** Price levels shown per side of the order book. */
	private static final int BOOK_DEPTH = 8;

	private PanelComponents()
	{
	}

	static Color sideColor(String side)
	{
		return TradeEvent.SELL.equals(side) ? SELL : BUY;
	}

	/** A clickable item row: icon, name, an optional second line, and an optional time on the right. */
	static JComponent tradeRow(ItemManager itemManager, int itemId, String name, String line2, String right,
		BiConsumer<Integer, String> onOpen)
	{
		JPanel row = new JPanel(new BorderLayout(6, 0));
		row.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		row.setBorder(new EmptyBorder(3, 4, 3, 6));
		JLabel icon = new JLabel();
		icon.setPreferredSize(new Dimension(32, 30));
		itemManager.getImage(itemId).addTo(icon);
		row.add(icon, BorderLayout.WEST);

		String display = name == null || name.isEmpty() ? "Item " + itemId : name;
		JPanel text = new JPanel(new GridLayout(line2 == null ? 1 : 2, 1));
		text.setOpaque(false);
		text.add(smallLabel(display, Color.WHITE));
		if (line2 != null)
		{
			text.add(smallLabel(line2, ColorScheme.TEXT_COLOR));
		}
		row.add(text, BorderLayout.CENTER);
		if (right != null)
		{
			JLabel when = mutedLabel(right);
			when.setVerticalAlignment(SwingConstants.TOP);
			row.add(when, BorderLayout.EAST);
		}
		row.setToolTipText(line2 == null ? display : display + ": " + line2);
		row.setAlignmentX(Component.LEFT_ALIGNMENT);
		// After all children are added, so the preferred height is final.
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
		row.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		row.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				onOpen.accept(itemId, display);
			}

			@Override
			public void mouseEntered(MouseEvent e)
			{
				row.setBackground(ColorScheme.DARKER_GRAY_HOVER_COLOR);
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				row.setBackground(ColorScheme.DARKER_GRAY_COLOR);
			}
		});
		return row;
	}

	/**
	 * A one-line trade with a colored left edge and a time on the right. No spacing of its own: lists add it
	 * ({@link RowList}).
	 */
	static JComponent lineRow(String text, String right, Color accent)
	{
		return lineRow(text, right, accent, ColorScheme.LIGHT_GRAY_COLOR, text);
	}

	/** {@link #lineRow(String, String, Color)} with a colored right-hand text and its own tooltip. */
	static JComponent lineRow(String text, String right, Color accent, Color rightColor, String tooltip)
	{
		JPanel row = new JPanel(new BorderLayout(6, 0));
		row.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		row.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(0, 2, 0, 0, accent), new EmptyBorder(3, 6, 3, 6)));
		row.add(smallLabel(text, ColorScheme.TEXT_COLOR), BorderLayout.CENTER);
		row.add(smallLabel(right, rightColor), BorderLayout.EAST);
		row.setToolTipText(tooltip);
		row.setAlignmentX(Component.LEFT_ALIGNMENT);
		// A two-line right-hand text (an imported trade's "~3d" above "imported") makes the row taller.
		boolean twoLine = right != null && right.startsWith("<html>");
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, twoLine ? row.getPreferredSize().height : 24));
		return row;
	}

	/** A small square icon-only button, e.g. refresh. */
	static JButton iconButton(Icon icon, String tooltip, int size)
	{
		JButton b = new JButton(icon);
		b.setPreferredSize(new Dimension(size, size));
		b.setMaximumSize(new Dimension(size, size));
		b.setFocusPainted(false);
		b.setToolTipText(tooltip);
		return b;
	}

	/** A compact two-line stat cell: label on top, value (and an optional note) below. */
	static JComponent stat(String label, String value, String sub, Color accent)
	{
		JPanel p = new JPanel(new GridLayout(2, 1));
		p.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		p.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0, 2, 0, 0, accent), new EmptyBorder(2, 5, 2, 4)));
		p.add(mutedLabel(label));
		JPanel line = new JPanel(new BorderLayout(4, 0));
		line.setOpaque(false);
		JLabel v = new JLabel(value);
		v.setFont(FontManager.getRunescapeBoldFont());
		v.setForeground(Color.WHITE);
		line.add(v, BorderLayout.CENTER);
		if (sub != null && !sub.isEmpty())
		{
			line.add(mutedLabel(sub), BorderLayout.EAST);
		}
		p.add(line);
		return p;
	}

	/** Views live in a CardLayout that fills the panel; anchoring them at the top stops BoxLayout stretching rows. */
	static JPanel pinTop(JComponent view)
	{
		JPanel p = new JPanel(new BorderLayout());
		p.setOpaque(false);
		p.add(view, BorderLayout.NORTH);
		return p;
	}

	static JComponent bookColumn(String title, List<MarketData.Level> levels, Color accent)
	{
		JPanel col = column();
		col.add(smallLabel(title, accent));
		if (levels == null || levels.isEmpty())
		{
			col.add(mutedLabel("none"));
		}
		else
		{
			for (MarketData.Level lv : levels.subList(0, Math.min(BOOK_DEPTH, levels.size())))
			{
				JLabel l = smallLabel(QuantityFormatter.formatNumber(lv.getPrice()) + " × " + QuantityFormatter.quantityToStackSize(lv.getQty()),
					ColorScheme.TEXT_COLOR);
				l.setToolTipText(lv.getOffers() + " offer" + (lv.getOffers() == 1 ? "" : "s"));
				col.add(l);
			}
		}
		return col;
	}

	static JComponent legend()
	{
		JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
		p.setOpaque(false);
		p.add(smallLabel("■ Bought", BUY));
		p.add(smallLabel("■ Sold", SELL));
		p.add(smallLabel("bars = units traded", ColorScheme.LIGHT_GRAY_COLOR));
		p.setAlignmentX(Component.LEFT_ALIGNMENT);
		p.setMaximumSize(new Dimension(Integer.MAX_VALUE, 18));
		return p;
	}

	/** A vertical BoxLayout column, transparent and left aligned. */
	static JPanel column()
	{
		JPanel p = new JPanel();
		p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
		p.setOpaque(false);
		p.setAlignmentX(Component.LEFT_ALIGNMENT);
		return p;
	}

	static JButton tabButton(String text)
	{
		JButton b = new JButton(text);
		b.setFocusPainted(false);
		return b;
	}

	static void styleTab(JButton b, boolean on)
	{
		b.setForeground(on ? ColorScheme.BRAND_ORANGE : ColorScheme.LIGHT_GRAY_COLOR);
	}

	static JLabel sectionLabel(String text)
	{
		JLabel l = new JLabel(text);
		l.setFont(FontManager.getRunescapeBoldFont());
		l.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		l.setBorder(new EmptyBorder(2, 0, 4, 0));
		l.setAlignmentX(Component.LEFT_ALIGNMENT);
		return l;
	}

	static JLabel smallLabel(String text, Color color)
	{
		JLabel l = new JLabel(text);
		l.setFont(FontManager.getRunescapeSmallFont());
		l.setForeground(color);
		return l;
	}

	static JLabel mutedLabel(String text)
	{
		return smallLabel(text, ColorScheme.LIGHT_GRAY_COLOR);
	}

	/** Word-wrapped text, for messages only (never rows). */
	static JComponent wrapText(String text, Color color)
	{
		JTextArea t = new JTextArea(text);
		t.setLineWrap(true);
		t.setWrapStyleWord(true);
		t.setEditable(false);
		t.setFocusable(false);
		t.setOpaque(false);
		// FlatLaf gives text areas a 2,6,2,6 margin; without it wrapped text lines up with labels.
		t.setMargin(new Insets(0, 0, 0, 0));
		t.setForeground(color);
		t.setFont(FontManager.getRunescapeSmallFont());
		t.setAlignmentX(Component.LEFT_ALIGNMENT);
		return t;
	}

	/** Small clickable text with a hand cursor, e.g. the "Privacy" link. */
	static JLabel linkLabel(String text, Runnable onClick)
	{
		JLabel l = smallLabel(text, ColorScheme.LIGHT_GRAY_COLOR);
		l.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		l.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				onClick.run();
			}

			@Override
			public void mouseEntered(MouseEvent e)
			{
				l.setForeground(ColorScheme.BRAND_ORANGE);
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				l.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			}
		});
		return l;
	}

	/** A one-line offer: side-colored edge, badge colored by what it says. */
	static JComponent offerRow(OfferLine line)
	{
		Color badge = line.getKind() == OfferLine.Badge.ALERT ? ALERT
			: line.getKind() == OfferLine.Badge.STALE ? STALE : ColorScheme.LIGHT_GRAY_COLOR;
		return lineRow(line.getText(), line.getBadge(), line.getOffer().isBuy() ? BUY : SELL, badge, line.getTooltip());
	}
}
