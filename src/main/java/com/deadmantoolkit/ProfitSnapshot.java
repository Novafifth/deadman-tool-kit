package com.deadmantoolkit;

import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import lombok.Value;

/**
 * Immutable profit numbers for the account shown in the panel, made by {@link ProfitBook#snapshot} on the executor
 * and handed to the EDT. Amounts are gp; realized profit includes GE History rows (counted when imported).
 */
@Value
public class ProfitSnapshot
{
	public static final ProfitSnapshot EMPTY = new ProfitSnapshot(0, 0, 0, Collections.unmodifiableSortedMap(new TreeMap<>()),
		Collections.emptyList(), Collections.emptyMap(), false, false);

	long allTime;
	/** The part of {@link #allTime} from GE History rows. */
	long historyAllTime;
	/** Proceeds of sells with no known cost (not counted as profit). */
	long unknownProceeds;
	/** Realized profit per local date (ISO), from 6 days before the snapshot was made. */
	SortedMap<String, Long> recentDaily;
	/** The items with the most realized profit, best first. */
	List<ItemProfit> top;
	Map<Integer, ItemProfit> items;
	/** A rebuild from the local log is running. */
	boolean rebuilding;
	/** The local trade log has files a rebuild could read. */
	boolean logAvailable;

	/** One item's numbers. */
	@Value
	public static class ItemProfit
	{
		int itemId;
		String name;
		long held;
		/** Average cost of the units held; 0 when none are. */
		long avgCost;
		long realized;
		long unknownProceeds;
		long unknownQty;
		long historyRealized;
	}

	/** True once any of your trades was counted. */
	public boolean hasData()
	{
		return !items.isEmpty();
	}

	/** Realized profit on {@code day} (your time zone when the trades were counted). */
	public long today(LocalDate day)
	{
		return between(day, day);
	}

	/** Realized profit over the 7 days ending on {@code day}. */
	public long last7(LocalDate day)
	{
		return between(day.minusDays(6), day);
	}

	private long between(LocalDate from, LocalDate to)
	{
		long sum = 0;
		for (long v : recentDaily.subMap(from.toString(), to.plusDays(1).toString()).values())
		{
			sum += v;
		}
		return sum;
	}

	/** The item's numbers, or null if none of its trades were counted. */
	public ItemProfit item(int itemId)
	{
		return items.get(itemId);
	}
}
