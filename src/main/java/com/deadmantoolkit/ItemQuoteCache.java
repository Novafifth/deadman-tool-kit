package com.deadmantoolkit;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Recent market summaries per item for the GE price line. Lookups never block; a missing or old entry starts one
 * background fetch (never two at once for an item) and calls back when it arrives. Thread safe.
 */
class ItemQuoteCache
{
	/** Refetch entries older than this. */
	static final long TTL_MS = 30_000;
	/** Entries older than this aren't shown at all while a newer one is fetched. */
	static final long MAX_AGE_MS = 5 * 60_000;
	static final int MAX_ENTRIES = 64;

	/** Fetches one item; callbacks may run on any thread. */
	interface Fetcher
	{
		void fetch(int itemId, Consumer<MarketData.ItemDetail> ok, Consumer<String> err);
	}

	private static final class Entry
	{
		MarketData.ItemDetail detail;
		long fetchedAt;
		/** When the last fetch was started (also after a failure, so a failing server isn't hammered). */
		long requestedAt = Long.MIN_VALUE;
		boolean inFlight;
		final List<Runnable> waiting = new ArrayList<>();
	}

	private final Fetcher fetcher;
	private final Map<Integer, Entry> entries = new LinkedHashMap<Integer, Entry>(16, 0.75f, true)
	{
		@Override
		protected boolean removeEldestEntry(Map.Entry<Integer, Entry> eldest)
		{
			return size() > MAX_ENTRIES;
		}
	};

	ItemQuoteCache(Fetcher fetcher)
	{
		this.fetcher = fetcher;
	}

	/**
	 * The item's summary if one is held and not older than {@link #MAX_AGE_MS}; fetches a newer one in the background
	 * when it is older than {@link #TTL_MS} (or missing), then runs {@code onReady}.
	 *
	 * @param now    epoch ms
	 * @param onReady run (on the fetching thread) when new data has been stored; may be null
	 */
	MarketData.ItemDetail request(int itemId, long now, Runnable onReady)
	{
		MarketData.ItemDetail shown;
		synchronized (this)
		{
			Entry e = entries.computeIfAbsent(itemId, k -> new Entry());
			boolean has = e.detail != null;
			shown = has && now - e.fetchedAt <= MAX_AGE_MS ? e.detail : null;
			boolean fresh = has && now - e.fetchedAt <= TTL_MS;
			boolean recentlyTried = e.requestedAt != Long.MIN_VALUE && now - e.requestedAt < TTL_MS;
			if (fresh || recentlyTried && !e.inFlight)
			{
				return shown;
			}
			if (onReady != null)
			{
				e.waiting.add(onReady);
			}
			if (e.inFlight)
			{
				return shown;
			}
			e.inFlight = true;
			e.requestedAt = now;
		}
		fetcher.fetch(itemId, d -> done(itemId, d, now), err -> done(itemId, null, now));
		return shown;
	}

	/** The held summary if not older than {@link #MAX_AGE_MS}, without fetching. */
	synchronized MarketData.ItemDetail peek(int itemId, long now)
	{
		Entry e = entries.get(itemId);
		return e != null && e.detail != null && now - e.fetchedAt <= MAX_AGE_MS ? e.detail : null;
	}

	synchronized void clear()
	{
		entries.clear();
	}

	/** @param requestedAt when the fetch started; the data counts as of then */
	private void done(int itemId, MarketData.ItemDetail detail, long requestedAt)
	{
		List<Runnable> run;
		synchronized (this)
		{
			Entry e = entries.get(itemId);
			if (e == null)
			{
				// Cleared or evicted meanwhile.
				return;
			}
			e.inFlight = false;
			run = new ArrayList<>(e.waiting);
			e.waiting.clear();
			if (detail == null)
			{
				return;
			}
			e.detail = detail;
			e.fetchedAt = requestedAt;
		}
		run.forEach(Runnable::run);
	}
}
