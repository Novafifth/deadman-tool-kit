package com.deadmantoolkit.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import com.deadmantoolkit.Onboarding;
import java.awt.Component;
import java.awt.Container;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.AbstractButton;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import javax.swing.text.JTextComponent;
import org.junit.Test;

public class WelcomeCardTest
{
	private final AtomicInteger connects = new AtomicInteger();
	private final AtomicInteger dismissed = new AtomicInteger();

	private static Container card(WelcomeCard c)
	{
		return (Container) ((Container) c.getComponent()).getComponent(0);
	}

	private static String allText(Component c)
	{
		StringBuilder sb = new StringBuilder();
		if (c instanceof JLabel)
		{
			sb.append(((JLabel) c).getText()).append('\n');
		}
		else if (c instanceof JTextComponent)
		{
			sb.append(((JTextComponent) c).getText()).append('\n');
		}
		else if (c instanceof AbstractButton)
		{
			sb.append('[').append(((AbstractButton) c).getText()).append("]\n");
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

	private static AbstractButton button(Component c, String text)
	{
		if (c instanceof AbstractButton && text.equals(((AbstractButton) c).getText()))
		{
			return (AbstractButton) c;
		}
		if (c instanceof Container)
		{
			for (Component child : ((Container) c).getComponents())
			{
				AbstractButton b = button(child, text);
				if (b != null)
				{
					return b;
				}
			}
		}
		return null;
	}

	@Test
	public void states() throws Exception
	{
		SwingUtilities.invokeAndWait(() ->
		{
			boolean[] answer = {false};
			int[] asked = {0};
			WelcomeCard c = new WelcomeCard(connects::incrementAndGet, () -> { }, dismissed::incrementAndGet, null, parent ->
			{
				asked[0]++;
				return answer[0];
			});
			assertFalse(c.getComponent().isVisible());

			c.update(Onboarding.Step.CONNECT_AND_IMPORT, true, null);
			assertTrue(c.getComponent().isVisible());
			String text = allText(c.getComponent());
			assertTrue(text, text.contains("[Connect]"));
			assertTrue(text, text.contains(WelcomeCard.SHARED_TEXT));
			// RuneLite's warning is a confirmation on Connect now, not text on the card.
			assertFalse(text, text.contains("submits your IP address to a 3rd-party server"));
			assertFalse(WelcomeCard.SHARED_TEXT, WelcomeCard.SHARED_TEXT.contains("your IP address"));
			assertTrue(WelcomeCard.SHARED_TEXT, WelcomeCard.SHARED_TEXT.contains("IP addresses are never stored"));
			assertTrue(text, text.contains("2. Click the History button."));
			assertTrue(text, text.contains("Log in on world 345 first."));

			// Same state: nothing is rebuilt.
			Component first = card(c).getComponent(0);
			c.update(Onboarding.Step.CONNECT_AND_IMPORT, true, null);
			assertSame(first, card(c).getComponent(0));

			// Declining the warning doesn't connect; accepting does.
			button(c.getComponent(), "Connect").doClick();
			assertEquals(1, asked[0]);
			assertEquals(0, connects.get());
			answer[0] = true;
			button(c.getComponent(), "Connect").doClick();
			assertEquals(2, asked[0]);
			assertEquals(1, connects.get());

			c.update(Onboarding.Step.IMPORT, false, null);
			text = allText(c.getComponent());
			assertFalse(text, text.contains("[Connect]"));
			assertFalse(text, text.contains("Log in on world 345 first."));
			assertTrue(text, text.contains("1. Open the Grand Exchange."));

			c.update(Onboarding.Step.IMPORTED_NOTICE, false, 12);
			text = allText(c.getComponent());
			assertTrue(text, text.contains("Imported 12 trades from GE History"));
			assertFalse(text, text.contains("Get started"));
			button(c.getComponent(), "x").doClick();
			assertEquals(1, dismissed.get());

			c.update(Onboarding.Step.HIDDEN, false, null);
			assertFalse(c.getComponent().isVisible());
		});
	}
}
