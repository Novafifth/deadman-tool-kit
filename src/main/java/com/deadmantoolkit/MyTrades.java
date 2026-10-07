package com.deadmantoolkit;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.UnaryOperator;

/**
 * Your recent trades (newest first), from this session plus the local log. Thread safe.
 */
class MyTrades
{
	static final int MAX = 300;

	private final Deque<TradeEvent> trades = new ArrayDeque<>();
	/** Bumped by {@link #clear}, so a log load started before a plugin restart can't merge into the next session. */
	private int session;

	/**
	 * Fills, GE History rows and cancellations are shown. Offer placements are not trades (users misread
	 * "Buy offer 1,000 x 200" as a purchase), so the list never shows them. They are still logged and uploaded.
	 */
	static boolean isShown(TradeEvent ev)
	{
		return !TradeEvent.PLACED.equals(ev.getKind());
	}

	/** Add events that were just recorded, in the order given. */
	synchronized void addNewest(List<TradeEvent> events)
	{
		for (TradeEvent ev : events)
		{
			if (isShown(ev))
			{
				trades.addFirst(ev);
			}
		}
		while (trades.size() > MAX)
		{
			trades.removeLast();
		}
	}

	/**
	 * Add events with their own (older) times, e.g. imported RuneLite trades: merged into the list by time, newest
	 * first; those older than the newest {@link #MAX} drop off.
	 */
	synchronized void addByTime(List<TradeEvent> events)
	{
		List<TradeEvent> all = new ArrayList<>(trades);
		for (TradeEvent ev : events)
		{
			if (isShown(ev))
			{
				all.add(ev);
			}
		}
		// Stable: on equal times what was there stays ahead.
		all.sort((a, b) -> Long.compare(b.getTs(), a.getTs()));
		trades.clear();
		trades.addAll(all.subList(0, Math.min(MAX, all.size())));
	}

	/** The current session token; capture it when starting a log load and pass it to {@link #mergeLoaded}. */
	synchronized int session()
	{
		return session;
	}

	/**
	 * Merge in events from {@link LocalTradeLog#readNewest} (oldest first, shown events only), each passed through {@code nameFill}. Live trades
	 * recorded before the log finished loading are merged in by time.
	 *
	 * @param session the {@link #session()} captured when the load started; if the list has been cleared since
	 *                (plugin stopped, maybe restarted), the load is stale and is dropped
	 * @return false if the load was stale and nothing was merged
	 */
	synchronized boolean mergeLoaded(int session, List<TradeEvent> loaded, UnaryOperator<TradeEvent> nameFill)
	{
		if (session != this.session)
		{
			return false;
		}
		for (int i = Math.max(0, loaded.size() - MAX); i < loaded.size(); i++)
		{
			trades.addLast(nameFill.apply(loaded.get(i)));
		}
		// Newest first. The sort is stable, so on equal times live trades stay ahead of loaded ones.
		List<TradeEvent> all = new ArrayList<>(trades);
		all.sort((a, b) -> Long.compare(b.getTs(), a.getTs()));
		trades.clear();
		trades.addAll(all.subList(0, Math.min(MAX, all.size())));
		return true;
	}

	/** Copy of the list, newest first. */
	synchronized List<TradeEvent> snapshot()
	{
		return new ArrayList<>(trades);
	}

	/** Empty the list and start a new session, so in-flight log loads from the old one are dropped. */
	synchronized void clear()
	{
		trades.clear();
		session++;
	}
}
