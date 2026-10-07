package com.deadmantoolkit;

import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Running profit totals for one account, from its own fills and GE History rows. Pure and not thread safe: the plugin
 * keeps it on its single-threaded executor ({@link ProfitStore}). Also the JSON shape of the per-account profit file
 * (read and written with Gson), so applying an event is O(1) and start-up never replays the trade log.
 * <p>
 * Per item it keeps the units held (bought minus sold, within the tracked data), their cost (moving average) and the
 * realized profit on sells. Deadman has no GE tax. A sell of more units than are held (bought before the plugin was
 * installed, or while the log was off) has no known cost: those proceeds are counted as "unknown basis", not profit.
 * <p>
 * Daily totals are keyed by the event's local date. GE History rows have no real time; their ts is when they were
 * imported, so they count on the import day, and are also summed separately so the panel can say so.
 */
final class ProfitBook
{
	/** Daily totals older than this (before the newest day) are dropped. */
	static final int DAILY_DAYS = 400;
	/** Items listed in {@link ProfitSnapshot#getTop}. */
	static final int TOP = 5;
	static final int VERSION = 1;

	/** One item's running position. Fields are the file format; never rename them. */
	static final class Position
	{
		String name;
		/** Units bought and not yet sold. */
		long held;
		/** What {@link #held} cost altogether (moving average: sells take the average cost out). */
		long basis;
		/** Profit on sells with a known cost, including {@link #historyRealized}. */
		long realized;
		/** Proceeds of units sold that weren't held, so have no known cost. Not profit. */
		long unknownProceeds;
		long unknownQty;
		/** The part of {@link #realized} from GE History rows (counted when imported). */
		long historyRealized;

		Position()
		{
		}
	}

	// The file format. Gson fills these in; never rename them.
	private int version = VERSION;
	private Map<Integer, Position> items = new HashMap<>();
	private long allTime;
	private long historyAllTime;
	private long unknownProceeds;
	/** Realized profit per local date (ISO yyyy-MM-dd, which sorts by date). */
	private TreeMap<String, Long> daily = new TreeMap<>();
	/** Counts trades that aren't in the local trade log (it was off), so a recount from the log would lose them. */
	private boolean unlogged;

	ProfitBook()
	{
	}

	/** True when this book counts trades the local trade log doesn't have. */
	boolean hasUnlogged()
	{
		return unlogged;
	}

	void markUnlogged()
	{
		unlogged = true;
	}

	/** After reading the file: replace anything missing (an older or damaged file) with empty values. */
	ProfitBook sanitized()
	{
		if (items == null)
		{
			items = new HashMap<>();
		}
		items.values().removeIf(p -> p == null);
		if (daily == null)
		{
			daily = new TreeMap<>();
		}
		daily.values().removeIf(v -> v == null);
		version = VERSION;
		return this;
	}

	boolean isEmpty()
	{
		return items.isEmpty();
	}

	/**
	 * Count one of your events. Only fills and GE History rows are trades; placements and cancellations are ignored.
	 *
	 * @param zone the time zone whose date the profit counts on
	 * @return true if anything changed
	 */
	boolean apply(TradeEvent ev, ZoneId zone)
	{
		if (ev == null || !TradeEvent.isTrade(ev.getKind()) || ev.getQty() <= 0)
		{
			return false;
		}
		boolean buy = TradeEvent.BUY.equals(ev.getSide());
		if (!buy && !TradeEvent.SELL.equals(ev.getSide()))
		{
			return false;
		}
		long q = ev.getQty();
		// price * qty fits: both are at most about 2^31.
		long total = ev.getTotal() != null ? ev.getTotal() : ev.getPrice() * q;
		if (total < 0)
		{
			return false;
		}
		Position p = items.computeIfAbsent(ev.getItemId(), k -> new Position());
		if (ev.getName() != null && !ev.getName().isEmpty())
		{
			p.name = ev.getName();
		}
		if (buy)
		{
			p.held += q;
			p.basis += total;
			return true;
		}

		long m = Math.min(q, p.held);
		long knownProceeds = m == q ? total : mulDiv(total, m, q);
		if (m > 0)
		{
			long cost = m == p.held ? p.basis : mulDiv(p.basis, m, p.held);
			long gain = knownProceeds - cost;
			p.realized += gain;
			p.basis -= cost;
			p.held -= m;
			allTime += gain;
			if (TradeEvent.HISTORY.equals(ev.getKind()))
			{
				p.historyRealized += gain;
				historyAllTime += gain;
			}
			daily.merge(dayKey(ev.getTs(), zone), gain, Long::sum);
			prune();
		}
		if (m < q)
		{
			long unknown = total - knownProceeds;
			p.unknownQty += q - m;
			p.unknownProceeds += unknown;
			unknownProceeds += unknown;
		}
		return true;
	}

	static String dayKey(long ts, ZoneId zone)
	{
		return Instant.ofEpochSecond(ts).atZone(zone).toLocalDate().toString();
	}

	private void prune()
	{
		if (daily.size() <= DAILY_DAYS)
		{
			return;
		}
		String cutoff = LocalDate.parse(daily.lastKey()).minusDays(DAILY_DAYS).toString();
		daily.headMap(cutoff).clear();
	}

	/**
	 * An immutable copy for the panel: totals, the daily totals it needs from {@code today} on, the top items and every
	 * item's numbers.
	 */
	ProfitSnapshot snapshot(LocalDate today, boolean rebuilding, boolean logAvailable)
	{
		Map<Integer, ProfitSnapshot.ItemProfit> byId = new HashMap<>(items.size() * 2);
		List<ProfitSnapshot.ItemProfit> withProfit = new ArrayList<>();
		for (Map.Entry<Integer, Position> e : items.entrySet())
		{
			Position p = e.getValue();
			ProfitSnapshot.ItemProfit ip = new ProfitSnapshot.ItemProfit(e.getKey(), p.name, p.held,
				p.held > 0 ? mulDiv(p.basis, 1, p.held) : 0, p.realized, p.unknownProceeds, p.unknownQty, p.historyRealized);
			byId.put(e.getKey(), ip);
			if (p.realized != 0)
			{
				withProfit.add(ip);
			}
		}
		withProfit.sort((a, b) ->
		{
			int c = Long.compare(b.getRealized(), a.getRealized());
			return c != 0 ? c : Integer.compare(a.getItemId(), b.getItemId());
		});
		List<ProfitSnapshot.ItemProfit> top = new ArrayList<>(withProfit.subList(0, Math.min(TOP, withProfit.size())));
		TreeMap<String, Long> recent = new TreeMap<>(daily.tailMap(today.minusDays(6).toString(), true));
		return new ProfitSnapshot(allTime, historyAllTime, unknownProceeds, Collections.unmodifiableSortedMap(recent),
			Collections.unmodifiableList(top), Collections.unmodifiableMap(byId), rebuilding, logAvailable);
	}

	// For tests.

	Position position(int itemId)
	{
		return items.get(itemId);
	}

	long getAllTime()
	{
		return allTime;
	}

	long getHistoryAllTime()
	{
		return historyAllTime;
	}

	long getUnknownProceeds()
	{
		return unknownProceeds;
	}

	Map<String, Long> daily()
	{
		return Collections.unmodifiableMap(daily);
	}

	/** Same totals as {@code o} (for comparing a rebuild with live counting). */
	boolean sameAs(ProfitBook o)
	{
		if (allTime != o.allTime || historyAllTime != o.historyAllTime || unknownProceeds != o.unknownProceeds
			|| !daily.equals(o.daily) || !items.keySet().equals(o.items.keySet()))
		{
			return false;
		}
		for (Map.Entry<Integer, Position> e : items.entrySet())
		{
			Position a = e.getValue(), b = o.items.get(e.getKey());
			if (a.held != b.held || a.basis != b.basis || a.realized != b.realized || a.unknownProceeds != b.unknownProceeds
				|| a.unknownQty != b.unknownQty || a.historyRealized != b.historyRealized)
			{
				return false;
			}
		}
		return true;
	}

	/** a * b / c rounded half up, for non-negative a, b and positive c, without overflowing. */
	static long mulDiv(long a, long b, long c)
	{
		try
		{
			long prod = Math.multiplyExact(a, b);
			long q = prod / c, r = prod % c;
			return r >= c - r ? q + 1 : q;
		}
		catch (ArithmeticException ex)
		{
			BigInteger big = BigInteger.valueOf(c);
			BigInteger[] qr = BigInteger.valueOf(a).multiply(BigInteger.valueOf(b)).divideAndRemainder(big);
			long q = qr[0].longValue();
			return qr[1].shiftLeft(1).compareTo(big) >= 0 ? q + 1 : q;
		}
	}
}
