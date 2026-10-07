package com.deadmantoolkit.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import com.deadmantoolkit.ProfitSnapshot;
import java.awt.Component;
import java.awt.Container;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.JButton;
import org.junit.Test;

public class ProfitTextTest
{
	private static ProfitSnapshot.ItemProfit item(int id, long held, long avg, long realized, long unknown, long unknownQty,
		long history)
	{
		return new ProfitSnapshot.ItemProfit(id, "Dragon bones", held, avg, realized, unknown, unknownQty, history);
	}

	private static ProfitSnapshot snapshot(long allTime, long history, long unknown, boolean rebuilding, boolean log,
		ProfitSnapshot.ItemProfit... items)
	{
		Map<Integer, ProfitSnapshot.ItemProfit> byId = new HashMap<>();
		List<ProfitSnapshot.ItemProfit> top = new ArrayList<>();
		for (ProfitSnapshot.ItemProfit p : items)
		{
			byId.put(p.getItemId(), p);
			top.add(p);
		}
		TreeMap<String, Long> daily = new TreeMap<>();
		daily.put(LocalDate.now().toString(), allTime);
		return new ProfitSnapshot(allTime, history, unknown, daily, top, byId, rebuilding, log);
	}

	@Test
	public void signedGp()
	{
		assertEquals("0", Format.signedGp(0));
		assertEquals("+950", Format.signedGp(950));
		assertEquals("-350K", Format.signedGp(-350_000));
		assertEquals("+1.25M", Format.signedGp(1_250_000));
		assertEquals("+2.14B", Format.signedGp(2_147_000_000L));
		assertTrue(Format.signedGp(Long.MIN_VALUE).startsWith("-"));
		assertEquals("1.21M", Format.shortGp(1_210_000));
	}

	@Test
	public void itemLines()
	{
		ProfitSnapshot.ItemProfit p = item(536, 1_200, 1_210_000, 3_400_000, 0, 0, 0);
		assertEquals("Held 1,200 · avg 1.21M · realized +3.4M", ProfitText.itemLine(p));
		assertNull(ProfitText.itemUnknownLine(p));
		assertTrue(ProfitText.hasPosition(p));

		ProfitSnapshot.ItemProfit sold = item(536, 0, 0, -50_000, 3_200_000, 3, 0);
		assertEquals("Realized -50K", ProfitText.itemLine(sold));
		assertEquals("3 sold for 3.2M with no known cost (not counted)", ProfitText.itemUnknownLine(sold));
		assertTrue(ProfitText.itemTooltip(sold).contains("no known cost"));

		assertFalse(ProfitText.hasPosition(null));
		assertFalse(ProfitText.hasPosition(item(536, 0, 0, 0, 0, 0, 0)));
		assertTrue(ProfitText.itemTooltip(item(536, 0, 0, 10, 0, 0, 10)).contains("from GE History"));
		assertEquals("Item 7", ProfitText.name(new ProfitSnapshot.ItemProfit(7, null, 0, 0, 0, 0, 0, 0)));
	}

	@Test
	public void footnotesOnlyWhenNonZero()
	{
		assertNull(ProfitText.historyNote(snapshot(5, 0, 0, false, true)));
		assertNull(ProfitText.unknownNote(snapshot(5, 0, 0, false, true)));
		assertEquals("Incl. +4.1M from GE History (counted at import)",
			ProfitText.historyNote(snapshot(5, 4_100_000, 0, false, true)));
		assertEquals("Sold without known cost: 3.2M (not counted)",
			ProfitText.unknownNote(snapshot(5, 0, 3_200_000, false, true)));
		assertEquals(PanelComponents.GOOD, ProfitText.color(1));
		assertEquals(PanelComponents.ALERT, ProfitText.color(-1));
	}

	@Test
	public void sectionVisibility()
	{
		assertFalse(ProfitSection.visible(null));
		assertFalse(ProfitSection.visible(ProfitSnapshot.EMPTY));
		// No numbers yet, but a log a rebuild could count.
		assertTrue(ProfitSection.visible(snapshot(0, 0, 0, false, true)));
		assertTrue(ProfitSection.visible(snapshot(0, 0, 0, false, false, item(1, 1, 1, 0, 0, 0, 0))));
	}

	private static List<Component> all(Container c)
	{
		List<Component> out = new ArrayList<>();
		for (Component ch : c.getComponents())
		{
			out.add(ch);
			if (ch instanceof Container)
			{
				out.addAll(all((Container) ch));
			}
		}
		return out;
	}

	private static JButton button(ProfitSection s)
	{
		for (Component c : all((Container) s.getComponent()))
		{
			if (c instanceof JButton)
			{
				return (JButton) c;
			}
		}
		throw new AssertionError("no button");
	}

	@Test
	public void sectionRendersOnlyOnChangeAndRebuildButtonStates()
	{
		AtomicInteger rebuilds = new AtomicInteger();
		ProfitSection s = new ProfitSection(rebuilds::incrementAndGet, (id, name) ->
		{
		});
		assertFalse(s.getComponent().isVisible());
		ProfitSnapshot snap = snapshot(1_000, 0, 0, false, true, item(1, 0, 0, 1_000, 0, 0, 0));
		s.setSnapshot(snap);
		assertTrue(s.getComponent().isVisible());
		List<Component> before = all((Container) s.getComponent());
		s.setSnapshot(snapshot(1_000, 0, 0, false, true, item(1, 0, 0, 1_000, 0, 0, 0)));
		s.tick();
		List<Component> after = all((Container) s.getComponent());
		assertEquals(before.size(), after.size());
		for (int i = 0; i < before.size(); i++)
		{
			assertSame(before.get(i), after.get(i));
		}

		JButton b = button(s);
		assertTrue(b.isEnabled());
		b.doClick();
		assertEquals(1, rebuilds.get());
		assertFalse(b.isEnabled());
		assertEquals("Rebuilding…", b.getText());
		s.setSnapshot(snapshot(1_000, 0, 0, true, true, item(1, 0, 0, 1_000, 0, 0, 0)));
		assertFalse(b.isEnabled());
		s.setSnapshot(snap);
		assertTrue(b.isEnabled());
		assertEquals("Rebuild", b.getText());

		s.setLogEnabled(false);
		assertFalse(b.isEnabled());
		assertEquals(ProfitText.REBUILD_NEEDS_LOG, b.getToolTipText());
	}
}
