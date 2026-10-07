package com.deadmantoolkit.ui;

import static com.deadmantoolkit.ui.PanelComponents.column;
import static com.deadmantoolkit.ui.PanelComponents.linkLabel;
import static com.deadmantoolkit.ui.PanelComponents.wrapText;
import java.awt.Color;
import java.awt.Component;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.text.JTextComponent;
import net.runelite.client.ui.ColorScheme;

/**
 * The "Import RuneLite GE history" link and its result line. The panel shows one on the welcome card and one on the
 * My trades tab, both driven by the same state. EDT only.
 */
class ImportLink
{
	static final String WELCOME_TEXT = "Import RuneLite's GE history";
	static final String TEXT = "Import RuneLite GE history";
	static final String RUNNING_TEXT = "Importing RuneLite GE history…";
	static final String TOOLTIP = "<html>Reads the trade history RuneLite's own Grand Exchange plugin keeps for the account"
		+ "<br>you're logged in with on world 345 (trades from March 2026 on), and adds the trades"
		+ "<br>this plugin doesn't have yet, labelled \"imported\". Their times are approximate."
		+ "<br>Nothing is read until you click. Other accounts are never read.</html>";

	private final JPanel panel = column();
	private final JLabel link;
	private final JTextComponent result = (JTextComponent) wrapText("", ColorScheme.LIGHT_GRAY_COLOR);
	private final String text;
	private boolean running;

	/** @param onClick starts the import (ignored while one runs) */
	ImportLink(String text, Runnable onClick)
	{
		this.text = text;
		link = linkLabel(text, () ->
		{
			if (!running)
			{
				onClick.run();
			}
		});
		link.setToolTipText(TOOLTIP);
		link.setAlignmentX(Component.LEFT_ALIGNMENT);
		panel.add(link);
		result.setVisible(false);
		panel.add(result);
	}

	JComponent getComponent()
	{
		return panel;
	}

	/** @param res the last import's result, or null; a problem (blocked or failed) is shown in orange */
	void setState(boolean running, RuneLiteImportAction.Result res)
	{
		String resultLine = res == null ? null : res.getText();
		Color color = res != null && res.isProblem() ? ColorScheme.BRAND_ORANGE : ColorScheme.LIGHT_GRAY_COLOR;
		if (!color.equals(result.getForeground()))
		{
			result.setForeground(color);
		}
		this.running = running;
		String t = running ? RUNNING_TEXT : text;
		if (!t.equals(link.getText()))
		{
			link.setText(t);
		}
		boolean show = !running && resultLine != null;
		if (show && !resultLine.equals(result.getText()))
		{
			result.setText(resultLine);
		}
		if (show != result.isVisible())
		{
			result.setVisible(show);
		}
		panel.revalidate();
		panel.repaint();
	}

	// For tests.

	String linkText()
	{
		return link.getText();
	}

	String resultText()
	{
		return result.isVisible() ? result.getText() : null;
	}

	Color resultColor()
	{
		return result.getForeground();
	}
}
