package com.deadmantoolkit.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import com.deadmantoolkit.MarketData;
import com.google.gson.Gson;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.Test;

public class FeedMergeTest
{
	private static final Gson GSON = new Gson();

	private static MarketData.FeedEvent ev(long id)
	{
		return ev(id, "fill");
	}

	private static MarketData.FeedEvent ev(long id, String kind)
	{
		return GSON.fromJson("{\"id\":" + id + ",\"kind\":\"" + kind + "\",\"side\":\"buy\",\"itemId\":4151,\"qty\":1}",
			MarketData.FeedEvent.class);
	}

	private static List<MarketData.FeedEvent> evs(long... ids)
	{
		List<MarketData.FeedEvent> out = new ArrayList<>();
		for (long id : ids)
		{
			out.add(ev(id));
		}
		return out;
	}

	private static List<Long> ids(List<MarketData.FeedEvent> events)
	{
		return events.stream().map(MarketData.FeedEvent::getId).collect(Collectors.toList());
	}

	@Test
	public void firstLoadIsSortedNewestFirst()
	{
		assertEquals(Arrays.asList(5L, 3L, 1L), ids(FeedMerge.merge(Collections.emptyList(), evs(3, 5, 1), 0, 60)));
	}

	@Test
	public void incrementalLoadPrependsNewer()
	{
		List<MarketData.FeedEvent> current = evs(5, 4, 3);
		assertEquals(Arrays.asList(7L, 6L, 5L, 4L, 3L), ids(FeedMerge.merge(current, evs(7, 6), 5, 60)));
	}

	@Test
	public void serverIgnoringAfterOverlapIsDeduplicated()
	{
		// An old server returns the newest page whatever "after" says.
		List<MarketData.FeedEvent> current = evs(5, 4, 3);
		assertEquals(Arrays.asList(7L, 6L, 5L, 4L, 3L), ids(FeedMerge.merge(current, evs(7, 6, 5, 4, 3), 5, 60)));
	}

	@Test
	public void ascendingIncomingIsFine()
	{
		assertEquals(Arrays.asList(8L, 7L, 6L, 5L), ids(FeedMerge.merge(evs(5), evs(6, 7, 8), 5, 60)));
	}

	@Test
	public void fullPageOfNewerEventsDropsTheOldOnesToAvoidAHole()
	{
		List<MarketData.FeedEvent> current = evs(5, 4);
		List<MarketData.FeedEvent> incoming = evs(100, 99, 98);
		assertTrue(FeedMerge.possibleGap(incoming, 5, 3));
		assertEquals(Arrays.asList(100L, 99L, 98L), ids(FeedMerge.merge(current, incoming, 5, 3)));
		// Not a full page: nothing was skipped.
		assertFalse(FeedMerge.possibleGap(incoming, 5, 4));
		assertEquals(Arrays.asList(100L, 99L, 98L, 5L, 4L), ids(FeedMerge.merge(current, incoming, 5, 4)));
		// Full page overlapping what we have: no gap.
		assertFalse(FeedMerge.possibleGap(evs(7, 6, 5), 5, 3));
		// A full load (after=0) never counts as a gap.
		assertFalse(FeedMerge.possibleGap(incoming, 0, 3));
	}

	@Test
	public void cappedAtTheNewest()
	{
		List<MarketData.FeedEvent> current = new ArrayList<>();
		for (long id = 250; id > 0; id--)
		{
			current.add(ev(id));
		}
		List<MarketData.FeedEvent> out = FeedMerge.merge(current, evs(251), 250, 60);
		assertEquals(FeedMerge.CAP, out.size());
		assertEquals(251L, out.get(0).getId());
		assertEquals(52L, out.get(out.size() - 1).getId());
	}

	@Test
	public void nonTradesAreDropped()
	{
		List<MarketData.FeedEvent> incoming = Arrays.asList(ev(4, "placed"), ev(3, "history"), ev(2, "cancelled"), ev(1));
		assertEquals(Arrays.asList(3L, 1L), ids(FeedMerge.merge(Collections.emptyList(), incoming, 0, 60)));
	}

	@Test
	public void maxIdIncludesNonTrades()
	{
		// "after" must move past placements too, or an old server's page would be asked for again.
		assertEquals(9, FeedMerge.maxId(Arrays.asList(ev(9, "placed"), ev(3)), 4));
		assertEquals(12, FeedMerge.maxId(Collections.emptyList(), 12));
	}
}
