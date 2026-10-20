package com.deadmantoolkit.ui;

import com.deadmantoolkit.MarketData;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.Value;

/**
 * The Market tab's rows: one per item, its latest live trade, newest first. Every trade of an item is in the item's own
 * view. A buy and a sell with the same quantity and price, reported close together, are one trade seen from both sides
 * (two plugin users traded with each other), shown as one "Traded" row. Pure.
 */
final class MarketRows
{
	/** A buy and a sell reported this close (seconds), with the same quantity and price, are one trade. */
	static final long SAME_TRADE = 120;

	@Value
	static class Row
	{
		MarketData.FeedEvent event;
		/** Both sides of this trade were reported. */
		boolean bothSides;
	}

	private MarketRows()
	{
	}

	/** @param newestFirst the merged feed ({@link FeedMerge}), newest first */
	static List<Row> latestPerItem(List<MarketData.FeedEvent> newestFirst)
	{
		Map<Integer, Row> byItem = new LinkedHashMap<>();
		for (MarketData.FeedEvent e : newestFirst)
		{
			// Newest first: the first event seen for an item is its latest trade.
			if (!byItem.containsKey(e.getItemId()))
			{
				byItem.put(e.getItemId(), new Row(e, hasOtherSide(e, newestFirst)));
			}
		}
		return new ArrayList<>(byItem.values());
	}

	private static boolean hasOtherSide(MarketData.FeedEvent e, List<MarketData.FeedEvent> events)
	{
		for (MarketData.FeedEvent o : events)
		{
			if (o != e && o.getItemId() == e.getItemId() && o.getQty() == e.getQty() && o.getPrice() == e.getPrice()
				&& !Objects.equals(o.getSide(), e.getSide()) && Math.abs(o.getReceivedAt() - e.getReceivedAt()) <= SAME_TRADE)
			{
				return true;
			}
		}
		return false;
	}
}
