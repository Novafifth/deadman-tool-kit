package com.deadmantoolkit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import com.google.gson.Gson;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.Test;

public class ItemQuoteCacheTest
{
	private final Map<Integer, List<Consumer<MarketData.ItemDetail>>> pending = new HashMap<>();
	private final Map<Integer, List<Consumer<String>>> failing = new HashMap<>();
	private int fetches;
	private final ItemQuoteCache cache = new ItemQuoteCache((id, ok, err) ->
	{
		fetches++;
		pending.computeIfAbsent(id, k -> new ArrayList<>()).add(ok);
		failing.computeIfAbsent(id, k -> new ArrayList<>()).add(err);
	});

	private static MarketData.ItemDetail detail(int id)
	{
		return new Gson().fromJson("{\"id\":" + id + ",\"summary\":{\"lastBuy\":5}}", MarketData.ItemDetail.class);
	}

	private void answer(int id)
	{
		List<Consumer<MarketData.ItemDetail>> list = pending.remove(id);
		list.get(list.size() - 1).accept(detail(id));
	}

	@Test
	public void freshHitsDoNotFetch()
	{
		assertNull(cache.request(1, 0, null));
		answer(1);
		assertNotNull(cache.request(1, 1_000, null));
		assertNotNull(cache.request(1, ItemQuoteCache.TTL_MS, null));
		assertEquals(1, fetches);
	}

	@Test
	public void oldEntriesRefetchButStillShow()
	{
		cache.request(1, 0, null);
		answer(1);
		MarketData.ItemDetail first = cache.request(1, ItemQuoteCache.TTL_MS + 1, null);
		// Shown while the refetch runs.
		assertNotNull(first);
		assertEquals(2, fetches);
		// Too old to show at all.
		assertNull(cache.peek(1, ItemQuoteCache.MAX_AGE_MS + 1));
	}

	@Test
	public void concurrentRequestsMakeOneFetchAndAllWaitersRun()
	{
		int[] ready = new int[1];
		cache.request(7, 0, () -> ready[0]++);
		cache.request(7, 10, () -> ready[0]++);
		assertEquals(1, fetches);
		assertEquals(0, ready[0]);
		answer(7);
		assertEquals(2, ready[0]);
		assertNotNull(cache.peek(7, 20));
	}

	@Test
	public void failureBacksOffForTheTtl()
	{
		int[] ready = new int[1];
		cache.request(3, 0, () -> ready[0]++);
		failing.get(3).get(0).accept("down");
		assertEquals(0, ready[0]);
		assertNull(cache.request(3, 1_000, null));
		assertEquals(1, fetches);
		cache.request(3, ItemQuoteCache.TTL_MS, null);
		assertEquals(2, fetches);
	}

	@Test
	public void lruCap()
	{
		for (int id = 0; id <= ItemQuoteCache.MAX_ENTRIES; id++)
		{
			cache.request(id, 0, null);
			answer(id);
		}
		// The first one was evicted; the newest are kept.
		assertNull(cache.peek(0, 1));
		assertNotNull(cache.peek(ItemQuoteCache.MAX_ENTRIES, 1));
	}

	@Test
	public void clearDropsLateResponses()
	{
		int[] ready = new int[1];
		cache.request(9, 0, () -> ready[0]++);
		cache.clear();
		answer(9);
		assertEquals(0, ready[0]);
		assertNull(cache.peek(9, 1));
	}

	@Test
	public void returnsTheStoredDetail()
	{
		cache.request(2, 0, null);
		MarketData.ItemDetail d = detail(2);
		pending.remove(2).get(0).accept(d);
		assertSame(d, cache.peek(2, 1));
	}
}
