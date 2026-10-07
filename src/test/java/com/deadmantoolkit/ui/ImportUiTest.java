package com.deadmantoolkit.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import com.deadmantoolkit.MarketClient;
import com.deadmantoolkit.Onboarding;
import com.deadmantoolkit.TradeEvent;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Container;
import java.awt.event.MouseEvent;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.text.JTextComponent;
import net.runelite.client.ui.ColorScheme;
import org.junit.Test;

/** The import link, its place on the welcome card, the failed-refresh line and the buy limit line. */
public class ImportUiTest
{
	private static String allText(Component c)
	{
		StringBuilder sb = new StringBuilder();
		if (c instanceof JLabel)
		{
			sb.append(((JLabel) c).getText()).append('\n');
		}
		else if (c instanceof JTextComponent && c.isVisible())
		{
			sb.append(((JTextComponent) c).getText()).append('\n');
		}
		if (c instanceof Container)
		{
			for (Component child : ((Container) c).getComponents())
			{
				sb.append(allText(child));
			}
		}
		return sb.toString();
	}

	private static void click(JLabel l)
	{
		for (java.awt.event.MouseListener ml : l.getMouseListeners())
		{
			ml.mouseClicked(new MouseEvent(l, MouseEvent.MOUSE_CLICKED, 0, 0, 1, 1, 1, false));
		}
	}

	private static JLabel find(Component c, String text)
	{
		if (c instanceof JLabel && text.equals(((JLabel) c).getText()))
		{
			return (JLabel) c;
		}
		if (c instanceof Container)
		{
			for (Component child : ((Container) c).getComponents())
			{
				JLabel l = find(child, text);
				if (l != null)
				{
					return l;
				}
			}
		}
		return null;
	}

	@Test
	public void importLinkStates() throws Exception
	{
		SwingUtilities.invokeAndWait(() ->
		{
			AtomicInteger clicks = new AtomicInteger();
			ImportLink link = new ImportLink(ImportLink.TEXT, clicks::incrementAndGet);
			assertEquals(ImportLink.TEXT, link.linkText());
			assertNull(link.resultText());
			click(find(link.getComponent(), ImportLink.TEXT));
			assertEquals(1, clicks.get());

			link.setState(true, null);
			assertEquals(ImportLink.RUNNING_TEXT, link.linkText());
			// Clicks while running do nothing.
			click(find(link.getComponent(), ImportLink.RUNNING_TEXT));
			assertEquals(1, clicks.get());

			link.setState(false, RuneLiteImportAction.Result.done("Imported 812 trades (Jul 29 - Oct 7) - 210 already recorded"));
			assertEquals(ImportLink.TEXT, link.linkText());
			assertEquals("Imported 812 trades (Jul 29 - Oct 7) - 210 already recorded", link.resultText());
			assertEquals(ColorScheme.LIGHT_GRAY_COLOR, link.resultColor());

			// Blocked or failed: orange, so it doesn't read like a success.
			link.setState(false, RuneLiteImportAction.Result.problem("Log in on world 345 (Deadman) first."));
			assertEquals("Log in on world 345 (Deadman) first.", link.resultText());
			assertEquals(ColorScheme.BRAND_ORANGE, link.resultColor());
			link.setState(false, null);
			assertNull(link.resultText());
		});
	}

	@Test
	public void welcomeCardOffersTheImportWithTheGeHistorySteps() throws Exception
	{
		SwingUtilities.invokeAndWait(() ->
		{
			ImportLink link = new ImportLink(ImportLink.WELCOME_TEXT, () -> { });
			WelcomeCard c = new WelcomeCard(() -> { }, () -> { }, () -> { }, link.getComponent());
			c.update(Onboarding.Step.IMPORT, false, null);
			String text = allText(c.getComponent());
			assertTrue(text, text.contains("2. Click the History button."));
			assertTrue(text, text.contains(ImportLink.WELCOME_TEXT));
			assertTrue(text, text.indexOf(ImportLink.WELCOME_TEXT) > text.indexOf("3. Done."));

			// Not in the connect-only state (no import steps there).
			c.update(Onboarding.Step.CONNECT, false, null);
			assertFalse(allText(c.getComponent()).contains(ImportLink.WELCOME_TEXT));
		});
	}

	@Test
	public void oneImportLinkAtATime() throws Exception
	{
		SwingUtilities.invokeAndWait(() ->
		{
			ImportLink welcomeLink = new ImportLink(ImportLink.WELCOME_TEXT, () -> { });
			WelcomeCard c = new WelcomeCard(() -> { }, () -> { }, () -> { }, welcomeLink.getComponent());
			c.update(Onboarding.Step.IMPORT, false, null);
			assertTrue(c.showsImportLink());
			c.update(Onboarding.Step.CONNECT_AND_IMPORT, false, null);
			assertTrue(c.showsImportLink());
			c.update(Onboarding.Step.CONNECT, false, null);
			assertFalse(c.showsImportLink());
			c.update(Onboarding.Step.HIDDEN, false, null);
			assertFalse(c.showsImportLink());
			assertFalse(new WelcomeCard(() -> { }, () -> { }, () -> { }).showsImportLink());
		});
	}

	/** The CENTER (trade text) label of a {@link PanelComponents#lineRow} laid out at {@code width}. */
	private static JLabel layoutCenter(JComponent row, int width)
	{
		row.setSize(width, row.getPreferredSize().height);
		row.doLayout();
		return (JLabel) ((BorderLayout) ((JPanel) row).getLayout()).getLayoutComponent(BorderLayout.CENTER);
	}

	@Test
	public void importedRowsKeepThePriceVisible() throws Exception
	{
		SwingUtilities.invokeAndWait(() ->
		{
			long ts = System.currentTimeMillis() / 1000 - 123 * 86_400L;
			for (int[] qp : new int[][]{{12_000, 1_234_567}, {5_000, 45_678}, {2, 125_000_000}})
			{
				TradeEvent e = TradeEvent.builder().id("rl:x").kind(TradeEvent.IMPORTED).side(TradeEvent.BUY).itemId(4151)
					.qty(qp[0]).price(qp[1]).total((long) qp[0] * qp[1]).ts(ts).late(true).world(345).build();
				// The item view's rows are 205px wide.
				JLabel center = layoutCenter(ItemView.mineRow(e), 205);
				assertTrue(center.getText() + ": " + center.getWidth() + " < " + center.getPreferredSize().width,
					center.getWidth() >= center.getPreferredSize().width);
			}
		});
	}

	@Test
	public void failedRefreshSaysWhy()
	{
		assertEquals("Update failed: server unreachable", HomeView.failedText(MarketClient.UNREACHABLE));
		assertEquals("Update failed: server error 500", HomeView.failedText("Server error (500)."));
	}

	@Test
	public void buyLimitLine()
	{
		Instant now = Instant.parse("2026-10-07T12:00:00Z");
		assertEquals("Buy limit resets in 2h 14m", ItemView.limitText(now.plus(Duration.ofMinutes(134)), now));
		assertNull(ItemView.limitText(now.minusSeconds(1), now));
		assertNull(ItemView.limitText(null, now));
	}
}
