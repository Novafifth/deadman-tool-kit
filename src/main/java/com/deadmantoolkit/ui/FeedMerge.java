package com.deadmantoolkit.ui;

import com.deadmantoolkit.MarketData;
import com.deadmantoolkit.TradeEvent;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Merges an incremental Market feed response ({@code /v1/recent?after=<id>}) into what the panel already shows. Pure.
 * <p>
 * Works with servers that honour {@code after} and with older ones that ignore it (they return the newest rows, which
 * overlap what we have and are de-duplicated by id), whatever order the rows come in.
 */
final class FeedMerge
{
	/** Most events kept for the feed. */
	static final int CAP = 200;

	private FeedMerge()
	{
	}

	/**
	 * @param current  what the feed holds now, newest first
	 * @param incoming the response's events (any order, may include non-trades from servers that ignore kinds)
	 * @param afterId  the id the request asked for events after, or 0 for a full load
	 * @param limit    the request's limit; a full page of only newer events may mean events were skipped
	 * @return completed trades only, de-duplicated by id, newest (highest id) first, at most {@code cap}
	 */
	static List<MarketData.FeedEvent> merge(List<MarketData.FeedEvent> current, List<MarketData.FeedEvent> incoming,
		long afterId, int limit, int cap)
	{
		Map<Long, MarketData.FeedEvent> byId = new LinkedHashMap<>();
		if (!possibleGap(incoming, afterId, limit))
		{
			addAll(byId, current);
		}
		// Incoming wins on equal ids: it is the server's latest view of that event.
		addAll(byId, incoming);
		List<MarketData.FeedEvent> out = completedTrades(byId.values());
		out.sort((a, b) -> Long.compare(b.getId(), a.getId()));
		return out.size() > cap ? new ArrayList<>(out.subList(0, cap)) : out;
	}

	static List<MarketData.FeedEvent> merge(List<MarketData.FeedEvent> current, List<MarketData.FeedEvent> incoming,
		long afterId, int limit)
	{
		return merge(current, incoming, afterId, limit, CAP);
	}

	/**
	 * True when an incremental response is a full page of events all newer than {@code afterId}: there may be more
	 * between them and what we have, so the old events are dropped rather than shown with a hole in between.
	 */
	static boolean possibleGap(List<MarketData.FeedEvent> incoming, long afterId, int limit)
	{
		if (afterId <= 0 || incoming.isEmpty() || incoming.size() < limit)
		{
			return false;
		}
		for (MarketData.FeedEvent e : incoming)
		{
			if (e.getId() <= afterId)
			{
				return false;
			}
		}
		return true;
	}

	/**
	 * Only completed trades (fills and GE History rows). Offers placed or cancelled aren't trades and were misread as
	 * purchases. The request already asks the server for these kinds; older servers ignore that, so filter here too.
	 */
	static List<MarketData.FeedEvent> completedTrades(Collection<MarketData.FeedEvent> events)
	{
		List<MarketData.FeedEvent> trades = new ArrayList<>();
		for (MarketData.FeedEvent e : events)
		{
			if (TradeEvent.isTrade(e.getKind()))
			{
				trades.add(e);
			}
		}
		return trades;
	}

	/**
	 * True when a full (newest page) response shows that the server's ids are lower than the ones we have: a different
	 * or reset server. An empty newest page means the server has no events at all. The old feed must then be dropped.
	 */
	static boolean idsWentBack(List<MarketData.FeedEvent> newestPage, long previousMaxId)
	{
		return previousMaxId > 0 && maxId(newestPage, 0) < previousMaxId;
	}

	/** The highest id in {@code events}, or {@code previous} if that is higher. */
	static long maxId(Collection<MarketData.FeedEvent> events, long previous)
	{
		long max = previous;
		for (MarketData.FeedEvent e : events)
		{
			max = Math.max(max, e.getId());
		}
		return max;
	}

	private static void addAll(Map<Long, MarketData.FeedEvent> byId, List<MarketData.FeedEvent> events)
	{
		for (MarketData.FeedEvent e : events)
		{
			byId.put(e.getId(), e);
		}
	}
}
