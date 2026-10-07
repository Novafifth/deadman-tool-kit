package com.deadmantoolkit.ui;

import static com.deadmantoolkit.ui.PanelComponents.column;
import static com.deadmantoolkit.ui.PanelComponents.wrapText;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import javax.swing.Box;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.Timer;
import javax.swing.text.JTextComponent;
import net.runelite.client.ui.ColorScheme;

/**
 * A column of rows that renders a page at a time ({@link #PAGE} rows, then "Show more") and only builds rows it
 * hasn't built before: rows are cached by key and reused, and when the visible rows are unchanged nothing is touched.
 * Rows that appear after the first render are briefly highlighted. EDT only.
 *
 * @param <T> the row data
 */
class RowList<T>
{
	static final int PAGE = 50;
	static final Color HIGHLIGHT = new Color(0x2f4a2f);
	private static final int HIGHLIGHT_MS = 2_500;
	private static final int GAP = 2;

	/** A cached row: the component the factory built, the wrapper holding it and the spacing, and its content key. */
	private static final class Row
	{
		final JComponent row;
		final JComponent wrapper;
		final Object version;

		Row(JComponent row, JComponent wrapper, Object version)
		{
			this.row = row;
			this.wrapper = wrapper;
			this.version = version;
		}
	}

	private final JPanel panel = column();
	private final Function<T, Object> key;
	private final Function<T, Object> version;
	private final Function<T, JComponent> factory;
	private final JButton more = new JButton();
	private final JComponent empty = wrapText("", ColorScheme.LIGHT_GRAY_COLOR);
	private final Timer unhighlight;
	private final List<JComponent> highlighted = new ArrayList<>();

	private Map<Object, Row> cache = new HashMap<>();
	/** Keys of the rows on screen, in order; null before the first render (and after {@link #reset}). */
	private List<Object> shownKeys;
	private boolean shownEmpty;
	private boolean shownMore;
	private List<T> items = Collections.emptyList();
	private int limit = PAGE;

	/**
	 * @param key     identifies a row; a row whose key was already shown is reused, a new key is highlighted
	 * @param version the row's content; when it changes for a known key the row is rebuilt (not highlighted), e.g. a
	 *                time label that went from "5m" to "6m"
	 * @param factory builds a row
	 */
	RowList(Function<T, Object> key, Function<T, Object> version, Function<T, JComponent> factory)
	{
		this.key = key;
		this.version = version;
		this.factory = factory;
		more.setFocusPainted(false);
		more.setAlignmentX(Component.LEFT_ALIGNMENT);
		more.setMaximumSize(new Dimension(Integer.MAX_VALUE, 24));
		more.addActionListener(e -> showMore());
		unhighlight = new Timer(HIGHLIGHT_MS, e -> clearHighlight());
		unhighlight.setRepeats(false);
	}

	RowList(Function<T, Object> key, Function<T, JComponent> factory)
	{
		this(key, t -> null, factory);
	}

	JComponent getComponent()
	{
		return panel;
	}

	/** Text shown when there are no rows. */
	void setEmptyText(String text)
	{
		((JTextComponent) empty).setText(text);
	}

	/** Back to the first page, and the next render counts as a first render (no highlight). Tab or item changes. */
	void reset()
	{
		limit = PAGE;
		shownKeys = null;
	}

	int limit()
	{
		return limit;
	}

	/** Show {@code items} (first {@link #limit} of them). Does nothing if the visible rows are unchanged. */
	void setItems(List<T> items)
	{
		this.items = new ArrayList<>(items);
		render(true);
	}

	void showMore()
	{
		limit += PAGE;
		render(false);
	}

	/** Stop the highlight timer; for the panel's shutdown. */
	void stop()
	{
		unhighlight.stop();
	}

	private void render(boolean highlightNew)
	{
		int n = Math.min(limit, items.size());
		List<Object> keys = new ArrayList<>(n);
		List<Object> versions = new ArrayList<>(n);
		for (int i = 0; i < n; i++)
		{
			keys.add(key.apply(items.get(i)));
			versions.add(version.apply(items.get(i)));
		}
		boolean isEmpty = items.isEmpty();
		boolean hasMore = items.size() > n;
		boolean sameVersions = shownKeys != null && keys.equals(shownKeys);
		for (int i = 0; sameVersions && i < n; i++)
		{
			Row r = cache.get(keys.get(i));
			sameVersions = r != null && Objects.equals(r.version, versions.get(i));
		}
		if (sameVersions && isEmpty == shownEmpty && hasMore == shownMore)
		{
			if (hasMore)
			{
				more.setText(moreText(items.size() - n));
			}
			return;
		}

		// An empty render (reset, logged out, before the first load) is no baseline: the first rows aren't "new".
		Set<Object> fresh = highlightNew && shownKeys != null && !shownKeys.isEmpty()
			? newKeys(shownKeys, keys) : Collections.emptySet();
		Map<Object, Row> next = new LinkedHashMap<>();
		panel.removeAll();
		for (int i = 0; i < n; i++)
		{
			Object k = keys.get(i);
			Row r = cache.get(k);
			if (r == null || !Objects.equals(r.version, versions.get(i)))
			{
				JComponent row = factory.apply(items.get(i));
				JPanel wrapper = column();
				wrapper.add(row);
				wrapper.add(Box.createVerticalStrut(GAP));
				r = new Row(row, wrapper, versions.get(i));
			}
			if (fresh.contains(k))
			{
				r.row.setBackground(HIGHLIGHT);
				highlighted.add(r.row);
			}
			// A key can only appear once on screen; a duplicate keeps its first row.
			if (next.putIfAbsent(k, r) == null)
			{
				panel.add(r.wrapper);
			}
		}
		if (isEmpty)
		{
			panel.add(empty);
		}
		if (hasMore)
		{
			more.setText(moreText(items.size() - n));
			panel.add(more);
		}
		cache = next;
		shownKeys = keys;
		shownEmpty = isEmpty;
		shownMore = hasMore;
		if (!highlighted.isEmpty())
		{
			unhighlight.restart();
		}
		panel.revalidate();
		panel.repaint();
	}

	private void clearHighlight()
	{
		for (JComponent row : highlighted)
		{
			if (HIGHLIGHT.equals(row.getBackground()))
			{
				row.setBackground(ColorScheme.DARKER_GRAY_COLOR);
			}
		}
		highlighted.clear();
	}

	private static String moreText(int remaining)
	{
		return "Show more (" + remaining + " more)";
	}

	/** Keys in {@code now} that weren't in {@code before}: rows added since the last render. */
	static Set<Object> newKeys(List<?> before, List<?> now)
	{
		Set<Object> old = new HashSet<>(before);
		Set<Object> out = new HashSet<>();
		for (Object k : now)
		{
			if (!old.contains(k))
			{
				out.add(k);
			}
		}
		return out;
	}

	/** Items paired with keys that stay unique when items repeat: {@code base + "|" + occurrence}. Pure. */
	static <T> List<Keyed<T>> withOccurrenceKeys(List<T> items, Function<T, String> base)
	{
		Map<String, Integer> seen = new HashMap<>();
		List<Keyed<T>> out = new ArrayList<>(items.size());
		for (T t : items)
		{
			String b = base.apply(t);
			int n = seen.merge(b, 1, Integer::sum);
			out.add(new Keyed<>(b + "|" + n, t));
		}
		return out;
	}

	/** A row's data with its key. */
	static final class Keyed<T>
	{
		final String key;
		final T value;

		Keyed(String key, T value)
		{
			this.key = key;
			this.value = value;
		}
	}
}
