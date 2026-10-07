package com.deadmantoolkit.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import org.junit.Test;

public class RowListTest
{
	/** How many times the factory built each item. */
	private final Map<String, Integer> built = new HashMap<>();
	private final Map<String, String> versions = new HashMap<>();

	private RowList<String> list()
	{
		return new RowList<>(s -> s, s -> versions.getOrDefault(s, ""), s ->
		{
			built.merge(s, 1, Integer::sum);
			return new JLabel(s);
		});
	}

	private static List<String> items(int n)
	{
		List<String> out = new ArrayList<>();
		for (int i = 0; i < n; i++)
		{
			out.add("r" + i);
		}
		return out;
	}

	/** The row components on screen, in order (each row sits in a wrapper with its spacing). */
	private static List<Component> rows(RowList<?> l)
	{
		List<Component> out = new ArrayList<>();
		for (Component c : l.getComponent().getComponents())
		{
			if (c instanceof Container && ((Container) c).getComponentCount() > 0 && !(c instanceof JButton)
				&& ((Container) c).getComponent(0) instanceof JLabel)
			{
				out.add(((Container) c).getComponent(0));
			}
		}
		return out;
	}

	private static List<String> texts(RowList<?> l)
	{
		return rows(l).stream().map(c -> ((JLabel) c).getText()).collect(Collectors.toList());
	}

	private static JButton moreButton(RowList<?> l)
	{
		for (Component c : l.getComponent().getComponents())
		{
			if (c instanceof JButton)
			{
				return (JButton) c;
			}
		}
		return null;
	}

	@Test
	public void factoryRunsOncePerNewKey()
	{
		RowList<String> l = list();
		l.setItems(Arrays.asList("a", "b"));
		l.setItems(Arrays.asList("c", "a", "b"));
		assertEquals(Arrays.asList("c", "a", "b"), texts(l));
		assertEquals(Integer.valueOf(1), built.get("a"));
		assertEquals(Integer.valueOf(1), built.get("b"));
		assertEquals(Integer.valueOf(1), built.get("c"));
	}

	@Test
	public void identicalInputChangesNothing()
	{
		RowList<String> l = list();
		l.setItems(Arrays.asList("a", "b"));
		Component[] before = l.getComponent().getComponents();
		l.setItems(new ArrayList<>(Arrays.asList("a", "b")));
		Component[] after = l.getComponent().getComponents();
		assertEquals(before.length, after.length);
		for (int i = 0; i < before.length; i++)
		{
			assertSame(before[i], after[i]);
		}
	}

	@Test
	public void changedVersionRebuildsOnlyThatRow()
	{
		RowList<String> l = list();
		l.setItems(Arrays.asList("a", "b"));
		Component a = rows(l).get(0), b = rows(l).get(1);
		versions.put("b", "6m");
		l.setItems(Arrays.asList("a", "b"));
		assertSame(a, rows(l).get(0));
		assertNotSame(b, rows(l).get(1));
		assertEquals(Integer.valueOf(2), built.get("b"));
		// A rebuilt row isn't new: no highlight.
		assertFalse(RowList.HIGHLIGHT.equals(rows(l).get(1).getBackground()));
	}

	@Test
	public void showMoreGrowsByAPage()
	{
		RowList<String> l = list();
		l.setItems(items(120));
		assertEquals(RowList.PAGE, rows(l).size());
		assertEquals("Show more (70 more)", moreButton(l).getText());
		moreButton(l).doClick();
		assertEquals(100, rows(l).size());
		assertEquals("Show more (20 more)", moreButton(l).getText());
		moreButton(l).doClick();
		assertEquals(120, rows(l).size());
		assertEquals(null, moreButton(l));
		// The first rows were built once, never again.
		assertEquals(Integer.valueOf(1), built.get("r0"));

		l.reset();
		l.setItems(items(120));
		assertEquals(RowList.PAGE, rows(l).size());
	}

	@Test
	public void rowsNotVisibleAreNotBuilt()
	{
		RowList<String> l = list();
		l.setItems(items(1000));
		assertEquals(RowList.PAGE, built.size());
	}

	@Test
	public void newRowsAreHighlightedButNotOnTheFirstRender()
	{
		RowList<String> l = list();
		l.setItems(Arrays.asList("a", "b"));
		for (Component c : rows(l))
		{
			assertFalse(RowList.HIGHLIGHT.equals(c.getBackground()));
		}
		l.setItems(Arrays.asList("c", "a", "b"));
		assertEquals(RowList.HIGHLIGHT, rows(l).get(0).getBackground());
		assertFalse(RowList.HIGHLIGHT.equals(rows(l).get(1).getBackground()));

		// After a reset (tab or item change) nothing counts as new.
		l.reset();
		l.setItems(Arrays.asList("d", "c", "a", "b"));
		assertFalse(RowList.HIGHLIGHT.equals(rows(l).get(0).getBackground()));
		l.stop();
	}

	@Test
	public void anEmptyRenderIsNoBaselineForHighlighting()
	{
		RowList<String> l = list();
		// What a reset feed or a logged-out offers list does: empty first, then the first real load.
		l.setItems(Collections.emptyList());
		l.reset();
		l.setItems(Collections.emptyList());
		l.setItems(Arrays.asList("a", "b"));
		for (Component c : rows(l))
		{
			assertFalse(RowList.HIGHLIGHT.equals(c.getBackground()));
		}
		l.stop();
	}

	@Test
	public void emptyShowsTheMessage()
	{
		RowList<String> l = list();
		l.setEmptyText("Nothing here");
		l.setItems(Collections.emptyList());
		assertEquals(1, l.getComponent().getComponentCount());
		assertEquals("Nothing here", ((javax.swing.text.JTextComponent) l.getComponent().getComponent(0)).getText());
		l.setItems(Collections.singletonList("a"));
		assertEquals(Collections.singletonList("a"), texts(l));
		assertEquals(1, l.getComponent().getComponentCount());
	}

	@Test
	public void newKeys()
	{
		assertEquals(new HashSet<>(Arrays.asList("c", "d")),
			RowList.newKeys(Arrays.asList("a", "b"), Arrays.asList("c", "a", "d")));
		assertTrue(RowList.newKeys(Arrays.asList("a"), Arrays.asList("a")).isEmpty());
	}

	@Test
	public void occurrenceKeysStayUnique()
	{
		List<RowList.Keyed<String>> keyed = RowList.withOccurrenceKeys(Arrays.asList("x", "y", "x"), s -> s);
		assertEquals(Arrays.asList("x|1", "y|1", "x|2"), keyed.stream().map(k -> k.key).collect(Collectors.toList()));
		assertEquals("x", keyed.get(2).value);
	}

	@Test
	public void rowsKeepTheirComponentsAcrossReorder()
	{
		RowList<String> l = list();
		l.setItems(Arrays.asList("a", "b", "c"));
		JComponent b = (JComponent) rows(l).get(1);
		l.setItems(Arrays.asList("b", "c"));
		assertSame(b, rows(l).get(0));
	}
}
