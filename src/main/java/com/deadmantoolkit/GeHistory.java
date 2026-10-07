package com.deadmantoolkit;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.Value;

/**
 * Parses the in-game Grand Exchange History tab and works out which rows are new since we last read it.
 * The tab lists completed trades newest-first with no timestamps.
 */
public final class GeHistory
{
	private static final Pattern NUMBER = Pattern.compile("\\d[\\d,]*");
	private static final Pattern TAGS = Pattern.compile("<[^>]*>");

	private GeHistory()
	{
	}

	/** The bits of a history-list widget we care about. */
	@Value
	public static class Cell
	{
		String text;
		int itemId;
		int itemQty;
	}

	@Value
	public static class Entry
	{
		boolean buy;
		int itemId;
		int qty;
		long total;

		String signature()
		{
			return OfferTracker.signature(buy, itemId, qty, total);
		}
	}

	/**
	 * Walk the list's children in order, collecting a side label ("Bought"/"Sold"), an item, and a coin total into
	 * each entry. Order-tolerant, so small layout changes in the interface don't break it.
	 */
	public static List<Entry> parse(List<Cell> cells)
	{
		List<Entry> out = new ArrayList<>();
		Boolean buy = null;
		int itemId = -1, qty = 0;
		long total = -1;

		for (Cell c : cells)
		{
			String text = c.text == null ? "" : TAGS.matcher(c.text.replace("<br>", " ")).replaceAll("").trim();
			if (c.itemId > 0)
			{
				itemId = c.itemId;
				qty = c.itemQty;
			}
			else if (text.startsWith("Bought"))
			{
				buy = true;
			}
			else if (text.startsWith("Sold"))
			{
				buy = false;
			}
			else if (text.toLowerCase(Locale.ROOT).contains("coin"))
			{
				Matcher m = NUMBER.matcher(text);
				if (m.find())
				{
					try
					{
						total = Long.parseLong(m.group().replace(",", ""));
					}
					catch (NumberFormatException e)
					{
						total = -1;
					}
				}
			}

			if (buy != null && itemId > 0 && qty > 0 && total > 0)
			{
				out.add(new Entry(buy, itemId, qty, total));
				buy = null;
				itemId = -1;
				qty = 0;
				total = -1;
			}
		}
		return out;
	}

	/**
	 * Given the previous and current list of row signatures (both newest-first), return how many rows at the top of
	 * {@code current} are new. Finds the smallest offset at which the rest of {@code current} lines up with the
	 * start of {@code previous}.
	 */
	public static int countNew(List<String> previous, List<String> current)
	{
		if (previous == null || previous.isEmpty())
		{
			return current.size();
		}
		for (int k = 0; k < current.size(); k++)
		{
			int overlap = Math.min(current.size() - k, previous.size());
			boolean match = true;
			for (int j = 0; j < overlap; j++)
			{
				if (!current.get(k + j).equals(previous.get(j)))
				{
					match = false;
					break;
				}
			}
			if (match)
			{
				return k;
			}
		}
		return current.size();
	}

	/**
	 * Build events for the {@code fresh} new rows at the top of {@code entries}, oldest first so matching against
	 * offers tracked live happens in trade order. Rows {@code seenLive} accepts were already recorded live and are
	 * skipped.
	 */
	public static List<TradeEvent> newEvents(List<Entry> entries, int fresh, Predicate<String> seenLive, long ts, int world,
		Supplier<String> idGen)
	{
		List<TradeEvent> events = new ArrayList<>();
		for (int i = fresh - 1; i >= 0; i--)
		{
			Entry en = entries.get(i);
			if (seenLive.test(en.signature()))
			{
				continue;
			}
			events.add(TradeEvent.builder()
				.id(idGen.get())
				.kind(TradeEvent.HISTORY)
				.side(TradeEvent.sideOf(en.isBuy()))
				.itemId(en.getItemId())
				.qty(en.getQty())
				.price(TradeEvent.avgPrice(en.getTotal(), en.getQty()))
				.total(en.getTotal())
				.ts(ts)
				.late(true)
				.world(world)
				.build());
		}
		return events;
	}
}
