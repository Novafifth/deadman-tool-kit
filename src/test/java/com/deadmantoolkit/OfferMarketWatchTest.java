package com.deadmantoolkit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import com.google.gson.Gson;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import org.junit.Before;
import org.junit.Test;

public class OfferMarketWatchTest
{
	private static final long T0 = 1_700_000_000_000L;

	/** A request the fake fetcher received, answered by the test. */
	private static final class Call
	{
		final List<Integer> ids;
		final Consumer<MarketData.ItemsResponse> ok;
		final Consumer<String> err;

		Call(Collection<Integer> ids, Consumer<MarketData.ItemsResponse> ok, Consumer<String> err)
		{
			this.ids = new ArrayList<>(ids);
			this.ok = ok;
			this.err = err;
		}
	}

	private final List<Call> calls = new ArrayList<>();
	private final List<OfferReport> reports = new ArrayList<>();
	private final List<String> alerts = new ArrayList<>();
	private long now = T0;
	private boolean connected = true;
	private boolean notify = true;
	private boolean panelShown;
	private OfferMarketWatch watch;

	@Before
	public void setUp()
	{
		watch = new OfferMarketWatch((ids, fresh, ok, err) -> calls.add(new Call(ids, ok, err)), () -> now, () -> connected,
			() -> notify, () -> panelShown, () -> 6 * 3600L, Collections::emptyList, new OfferMarketWatch.Listener()
		{
			@Override
			public void onReport(OfferReport report)
			{
				reports.add(report);
			}

			@Override
			public void onAlert(String message)
			{
				alerts.add(message);
			}
		});
	}

	private static ActiveOffer sell(int slot, int itemId, long price)
	{
		return new ActiveOffer(slot, "k" + slot, itemId, "Item " + itemId, false, price, 10, 0, T0 / 1000);
	}

	private static MarketData.ItemsResponse items(String json)
	{
		return new Gson().fromJson(json, MarketData.ItemsResponse.class);
	}

	@Test
	public void newItemAsksRightAwayThenAutoWaitsAMinute()
	{
		watch.setOffers(Collections.singletonList(sell(0, 536, 1000)));
		assertEquals(1, calls.size());
		assertEquals(Collections.singletonList(536), calls.get(0).ids);
		calls.get(0).ok.accept(items("{\"items\":[]}"));

		now += 59_000;
		assertFalse(watch.refresh(false));
		now += 1_000;
		assertTrue(watch.refresh(false));
		assertEquals(2, calls.size());
	}

	@Test
	public void onDemandEveryTenSeconds()
	{
		watch.setOffers(Collections.singletonList(sell(0, 536, 1000)));
		calls.get(0).ok.accept(items("{}"));
		now += 5_000;
		assertFalse(watch.refresh(true));
		// The rate-limited request is remembered and made by the next periodic call once allowed.
		now += 5_000;
		assertTrue(watch.refresh(false));
		assertEquals(2, calls.size());
	}

	@Test
	public void noRequestWhileOneIsRunning()
	{
		watch.setOffers(Collections.singletonList(sell(0, 536, 1000)));
		now += 120_000;
		assertFalse(watch.refresh(true));
		assertEquals(1, calls.size());
	}

	@Test
	public void nothingWhenDisconnectedOrNoOffers()
	{
		assertFalse(watch.refresh(true));
		connected = false;
		watch.setOffers(Collections.singletonList(sell(0, 536, 1000)));
		assertFalse(watch.refresh(true));
		assertTrue(calls.isEmpty());
		// Reports carry no market statuses while disconnected.
		assertTrue(reports.get(reports.size() - 1).getStatuses().isEmpty());
	}

	@Test
	public void sameItemsReuseQuotesWithoutFetching()
	{
		watch.setOffers(Collections.singletonList(sell(0, 536, 1000)));
		calls.get(0).ok.accept(items("{\"items\":[{\"id\":536,\"bestAsk\":900}]}"));
		assertEquals(OfferWatch.Flag.UNDERCUT, reports.get(reports.size() - 1).getStatuses().get(0).getFlag());

		// The offer filled a bit: same item, so it is re-evaluated with the quotes held.
		now += 1_000;
		watch.setOffers(Collections.singletonList(new ActiveOffer(0, "k0", 536, "Item 536", false, 1000, 10, 3, T0 / 1000)));
		assertEquals(1, calls.size());
		assertEquals(OfferWatch.Flag.UNDERCUT, reports.get(reports.size() - 1).getStatuses().get(0).getFlag());
	}

	@Test
	public void responseFiltersAndAlertsOnce()
	{
		watch.setOffers(Arrays.asList(sell(0, 536, 1000), sell(1, 4151, 2_000_000)));
		assertEquals(Arrays.asList(536, 4151), calls.get(0).ids);
		// A server that ignores ids sends everything.
		calls.get(0).ok.accept(items("{\"items\":[{\"id\":536,\"bestAsk\":900},{\"id\":4151},{\"id\":11832,\"bestAsk\":1}]}"));
		OfferReport r = reports.get(reports.size() - 1);
		assertEquals(OfferWatch.Flag.UNDERCUT, r.getStatuses().get(0).getFlag());
		assertEquals(OfferWatch.Flag.OK, r.getStatuses().get(1).getFlag());
		assertEquals(T0, r.getCheckedAt());
		assertNull(r.getError());
		assertEquals(Collections.singletonList("Deadman Tool Kit: your Item 536 sell offer (1,000) was undercut at 900."), alerts);

		now += 60_000;
		assertTrue(watch.refresh(false));
		calls.get(1).ok.accept(items("{\"items\":[{\"id\":536,\"bestAsk\":900}]}"));
		assertEquals(1, alerts.size());
	}

	@Test
	public void errorIsReportedAndOldResponsesDroppedAfterReset()
	{
		watch.setOffers(Collections.singletonList(sell(0, 536, 1000)));
		calls.get(0).err.accept("Can't reach the server.");
		assertEquals("Can't reach the server.", reports.get(reports.size() - 1).getError());

		now += 10_000;
		assertTrue(watch.refresh(true));
		watch.reset();
		calls.get(1).ok.accept(items("{\"items\":[{\"id\":536,\"bestAsk\":900}]}"));
		// Dropped: the report after reset has no quote for the item.
		assertEquals(OfferWatch.Flag.OK, reports.get(reports.size() - 1).getStatuses().get(0).getFlag());
		assertTrue(alerts.isEmpty());
		// Reset allows a new request at once.
		assertTrue(watch.refresh(false));
	}

	@Test
	public void automaticChecksOnlyWhenSomethingUsesThem()
	{
		notify = false;
		panelShown = false;
		watch.setOffers(Collections.singletonList(sell(0, 536, 1000)));
		assertEquals("a new item doesn't fetch for a hidden panel", 0, calls.size());
		now += OfferMarketWatch.AUTO_MS;
		assertFalse(watch.refresh(false));
		assertEquals(0, calls.size());

		panelShown = true;
		assertTrue(watch.refresh(false));
		assertEquals(1, calls.size());
		calls.get(0).ok.accept(items("{\"items\":[]}"));

		panelShown = false;
		now += OfferMarketWatch.AUTO_MS;
		assertFalse(watch.refresh(false));
		notify = true;
		assertTrue("notifications use automatic checks", watch.refresh(false));
		assertEquals(2, calls.size());
	}
}
