package com.deadmantoolkit.ui;

import com.deadmantoolkit.ProfitSnapshot;
import java.awt.Color;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.util.QuantityFormatter;

/**
 * Text for the profit numbers on the home card and in the item view. Pure.
 */
final class ProfitText
{
	static final String REBUILD_TIP = "<html>Recount your profit from the local trade log.<br>"
		+ "Lines written by older versions have no account,<br>so they count for the account you're logged in with.</html>";
	static final String REBUILD_NEEDS_LOG = "Needs Save local trade log (Configuration > Deadman Tool Kit > Local)";
	static final String EMPTY = "No profit counted yet. Rebuild to count the trades already in your local trade log.";

	private ProfitText()
	{
	}

	/** Green for a gain, red for a loss, muted for nothing. */
	static Color color(long v)
	{
		return v > 0 ? PanelComponents.GOOD : v < 0 ? PanelComponents.ALERT : ColorScheme.LIGHT_GRAY_COLOR;
	}

	/** Footnote for profit from GE History rows, or null when there is none. */
	static String historyNote(ProfitSnapshot s)
	{
		return s.getHistoryAllTime() == 0 ? null
			: "Incl. " + Format.signedGp(s.getHistoryAllTime()) + " from GE History (counted at import)";
	}

	/** Footnote for sells with no known cost, or null when there are none. */
	static String unknownNote(ProfitSnapshot s)
	{
		return s.getUnknownProceeds() == 0 ? null
			: "Sold without known cost: " + Format.shortGp(s.getUnknownProceeds()) + " (not counted)";
	}

	/** The name shown for an item. */
	static String name(ProfitSnapshot.ItemProfit p)
	{
		return p.getName() == null || p.getName().isEmpty() ? "Item " + p.getItemId() : p.getName();
	}

	/** True when the item view has anything to say about this item. */
	static boolean hasPosition(ProfitSnapshot.ItemProfit p)
	{
		return p != null && (p.getHeld() > 0 || p.getRealized() != 0 || p.getUnknownProceeds() != 0);
	}

	/** e.g. "Held 1,200 · avg 1.21M · realized +3.4M", or "Realized +3.4M" when none are held. */
	static String itemLine(ProfitSnapshot.ItemProfit p)
	{
		String realized = Format.signedGp(p.getRealized());
		if (p.getHeld() <= 0)
		{
			return "Realized " + realized;
		}
		return "Held " + QuantityFormatter.formatNumber(p.getHeld()) + " · avg " + Format.shortGp(p.getAvgCost())
			+ " · realized " + realized;
	}

	/** e.g. "3 sold for 3.2M with no known cost (not counted)", or null when there were none. */
	static String itemUnknownLine(ProfitSnapshot.ItemProfit p)
	{
		if (p.getUnknownProceeds() == 0)
		{
			return null;
		}
		return QuantityFormatter.formatNumber(p.getUnknownQty()) + " sold for " + Format.shortGp(p.getUnknownProceeds())
			+ " with no known cost (not counted)";
	}

	/** Tooltip for an item row on the home card. */
	static String itemTooltip(ProfitSnapshot.ItemProfit p)
	{
		String tip = name(p) + ": " + itemLine(p);
		if (p.getHistoryRealized() != 0)
		{
			tip += " (" + Format.signedGp(p.getHistoryRealized()) + " from GE History)";
		}
		String unknown = itemUnknownLine(p);
		return unknown == null ? tip : tip + "; " + unknown;
	}
}
