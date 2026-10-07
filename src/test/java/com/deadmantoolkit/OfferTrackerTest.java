package com.deadmantoolkit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import com.deadmantoolkit.OfferTracker.Snapshot;
import com.deadmantoolkit.OfferTracker.Status;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import org.junit.Before;
import org.junit.Test;

public class OfferTrackerTest
{
	private static final int WHIP = 4151;
	private static final int WORLD = 345;

	private OfferTracker tracker;

	@Before
	public void setUp()
	{
		AtomicInteger n = new AtomicInteger();
		tracker = new OfferTracker(() -> "offer" + n.incrementAndGet());
	}

	private static Snapshot buy(int total, int sold, long spent, Status status)
	{
		return new Snapshot(WHIP, true, 300_000, total, sold, spent, status);
	}

	@Test
	public void newOfferEmitsPlaced()
	{
		List<TradeEvent> ev = tracker.update(0, buy(10, 0, 0, Status.ACTIVE), false, 100, WORLD);
		assertEquals(1, ev.size());
		TradeEvent e = ev.get(0);
		assertEquals(TradeEvent.PLACED, e.getKind());
		assertEquals("buy", e.getSide());
		assertEquals(10, e.getQty());
		assertEquals(300_000, e.getPrice());
		assertEquals("offer1:placed", e.getId());
		assertFalse(e.isLate());
	}

	@Test
	public void partialFillsReportDeltasAtActualPrice()
	{
		tracker.update(0, buy(10, 0, 0, Status.ACTIVE), false, 100, WORLD);
		List<TradeEvent> ev = tracker.update(0, buy(10, 3, 870_000, Status.ACTIVE), false, 110, WORLD);
		assertEquals(1, ev.size());
		assertEquals(TradeEvent.FILL, ev.get(0).getKind());
		assertEquals(3, ev.get(0).getQty());
		assertEquals(290_000, ev.get(0).getPrice());
		assertEquals(Long.valueOf(870_000), ev.get(0).getTotal());
		assertEquals(Integer.valueOf(3), ev.get(0).getFilled());

		ev = tracker.update(0, buy(10, 10, 2_970_000, Status.COMPLETE), false, 120, WORLD);
		assertEquals(1, ev.size());
		assertEquals(7, ev.get(0).getQty());
		assertEquals(300_000, ev.get(0).getPrice());
		assertEquals("offer1:fill:10", ev.get(0).getId());
	}

	@Test
	public void loginReplayOfUnchangedOfferEmitsNothing()
	{
		tracker.update(0, buy(10, 3, 870_000, Status.ACTIVE), false, 100, WORLD);
		List<TradeEvent> ev = tracker.update(0, buy(10, 3, 870_000, Status.ACTIVE), true, 200, WORLD);
		assertTrue(ev.isEmpty());
	}

	@Test
	public void fillsWhileLoggedOutAreMarkedLate()
	{
		tracker.update(0, buy(10, 0, 0, Status.ACTIVE), false, 100, WORLD);
		List<TradeEvent> ev = tracker.update(0, buy(10, 10, 3_000_000, Status.COMPLETE), true, 5000, WORLD);
		assertEquals(1, ev.size());
		assertTrue(ev.get(0).isLate());
		assertEquals(10, ev.get(0).getQty());
		assertEquals(Long.valueOf(100), ev.get(0).getSince());
	}

	@Test
	public void liveFillsHaveNoSince()
	{
		tracker.update(0, buy(10, 0, 0, Status.ACTIVE), false, 100, WORLD);
		List<TradeEvent> ev = tracker.update(0, buy(10, 2, 600_000, Status.ACTIVE), false, 150, WORLD);
		assertNull(ev.get(0).getSince());
		// The next late fill is bounded by the last time we saw the offer (150), not when it was placed.
		ev = tracker.update(0, buy(10, 5, 1_500_000, Status.ACTIVE), true, 9000, WORLD);
		assertEquals(Long.valueOf(150), ev.get(0).getSince());
	}

	@Test
	public void cancelReportsUnfilledRemainder()
	{
		tracker.update(0, buy(10, 0, 0, Status.ACTIVE), false, 100, WORLD);
		tracker.update(0, buy(10, 4, 1_200_000, Status.ACTIVE), false, 110, WORLD);
		List<TradeEvent> ev = tracker.update(0, buy(10, 4, 1_200_000, Status.CANCELLED), false, 120, WORLD);
		assertEquals(1, ev.size());
		assertEquals(TradeEvent.CANCELLED, ev.get(0).getKind());
		assertEquals(6, ev.get(0).getQty());
		// A repeat of the cancelled state (e.g. on relog) doesn't re-report it.
		assertTrue(tracker.update(0, buy(10, 4, 1_200_000, Status.CANCELLED), true, 130, WORLD).isEmpty());
	}

	@Test
	public void collectingClearsSlotAndNextOfferIsNew()
	{
		tracker.update(0, buy(10, 10, 3_000_000, Status.COMPLETE), false, 100, WORLD);
		tracker.update(0, new Snapshot(0, false, 0, 0, 0, 0, Status.EMPTY), false, 110, WORLD);
		assertNull(tracker.getSlot(0));
		List<TradeEvent> ev = tracker.update(0, buy(10, 0, 0, Status.ACTIVE), false, 120, WORLD);
		assertEquals(TradeEvent.PLACED, ev.get(0).getKind());
		assertEquals("offer2:placed", ev.get(0).getId());
	}

	@Test
	public void identicalOfferPlacedAfterAMissedCollectionIsNew()
	{
		// Cancelled with nothing filled, collected while we weren't watching, then the same offer placed again.
		tracker.update(0, buy(10, 0, 0, Status.ACTIVE), false, 100, WORLD);
		tracker.update(0, buy(10, 0, 0, Status.CANCELLED), false, 110, WORLD);
		List<TradeEvent> ev = tracker.update(0, buy(10, 0, 0, Status.ACTIVE), true, 200, WORLD);
		assertEquals(1, ev.size());
		assertEquals(TradeEvent.PLACED, ev.get(0).getKind());
		assertEquals("offer2:placed", ev.get(0).getId());
		// Its fills belong to the new offer.
		ev = tracker.update(0, buy(10, 10, 3_000_000, Status.COMPLETE), false, 210, WORLD);
		assertEquals("offer2:fill:10", ev.get(0).getId());
	}

	@Test
	public void sameItemDifferentPriceIsANewOffer()
	{
		tracker.update(0, buy(10, 0, 0, Status.ACTIVE), false, 100, WORLD);
		List<TradeEvent> ev = tracker.update(0, new Snapshot(WHIP, true, 310_000, 10, 0, 0, Status.ACTIVE), false, 110, WORLD);
		assertEquals(TradeEvent.PLACED, ev.get(0).getKind());
		assertEquals(310_000, ev.get(0).getPrice());
	}

	@Test
	public void completedOffersAreMatchedAgainstHistoryOnce()
	{
		tracker.update(0, buy(10, 0, 0, Status.ACTIVE), false, 100, WORLD);
		tracker.update(0, buy(10, 10, 2_950_000, Status.COMPLETE), false, 110, WORLD);
		String sig = OfferTracker.signature(true, WHIP, 10, 2_950_000);
		assertTrue(tracker.consumeCompleted(sig));
		assertFalse(tracker.consumeCompleted(sig));
	}

	@Test
	public void sellSide()
	{
		tracker.update(3, new Snapshot(WHIP, false, 280_000, 2, 0, 0, Status.ACTIVE), false, 100, WORLD);
		List<TradeEvent> ev = tracker.update(3, new Snapshot(WHIP, false, 280_000, 2, 2, 600_000, Status.COMPLETE), false, 110, WORLD);
		assertEquals("sell", ev.get(0).getSide());
		assertEquals(300_000, ev.get(0).getPrice());
		assertEquals(Integer.valueOf(3), ev.get(0).getSlot());
	}

	private static GrandExchangeOffer offer(GrandExchangeOfferState state)
	{
		return new GrandExchangeOffer()
		{
			@Override
			public int getQuantitySold()
			{
				return 4;
			}

			@Override
			public int getItemId()
			{
				return WHIP;
			}

			@Override
			public int getTotalQuantity()
			{
				return 10;
			}

			@Override
			public long getPrice()
			{
				return 300_000;
			}

			@Override
			public long getSpent()
			{
				return 1_150_000;
			}

			@Override
			public GrandExchangeOfferState getState()
			{
				return state;
			}
		};
	}

	@Test
	public void snapshotOfMapsEveryState()
	{
		assertEquals(new Snapshot(WHIP, true, 300_000, 10, 4, 1_150_000, Status.ACTIVE), Snapshot.of(offer(GrandExchangeOfferState.BUYING)));
		assertEquals(new Snapshot(WHIP, true, 300_000, 10, 4, 1_150_000, Status.COMPLETE), Snapshot.of(offer(GrandExchangeOfferState.BOUGHT)));
		assertEquals(new Snapshot(WHIP, true, 300_000, 10, 4, 1_150_000, Status.CANCELLED), Snapshot.of(offer(GrandExchangeOfferState.CANCELLED_BUY)));
		assertEquals(new Snapshot(WHIP, false, 300_000, 10, 4, 1_150_000, Status.ACTIVE), Snapshot.of(offer(GrandExchangeOfferState.SELLING)));
		assertEquals(new Snapshot(WHIP, false, 300_000, 10, 4, 1_150_000, Status.COMPLETE), Snapshot.of(offer(GrandExchangeOfferState.SOLD)));
		assertEquals(new Snapshot(WHIP, false, 300_000, 10, 4, 1_150_000, Status.CANCELLED), Snapshot.of(offer(GrandExchangeOfferState.CANCELLED_SELL)));
		assertEquals(new Snapshot(WHIP, false, 300_000, 10, 4, 1_150_000, Status.EMPTY), Snapshot.of(offer(GrandExchangeOfferState.EMPTY)));
		for (GrandExchangeOfferState state : GrandExchangeOfferState.values())
		{
			assertEquals(state.name(), state == GrandExchangeOfferState.EMPTY,
				Snapshot.of(offer(state)).getStatus() == Status.EMPTY);
		}
	}

	@Test
	public void markSeenUpdatesActiveSlotsOnly()
	{
		tracker.update(1, buy(10, 0, 0, Status.ACTIVE), false, 100, WORLD);
		tracker.update(4, buy(10, 10, 3_000_000, Status.COMPLETE), false, 100, WORLD);
		tracker.update(6, new Snapshot(WHIP, false, 280_000, 2, 0, 0, Status.ACTIVE), false, 100, WORLD);
		assertEquals(Arrays.asList(1, 6), tracker.markSeen(500));
		assertEquals(500, tracker.getSlot(1).getSeenAt());
		assertEquals(100, tracker.getSlot(4).getSeenAt());
		assertEquals(500, tracker.getSlot(6).getSeenAt());
	}

	@Test
	public void markSeenWithNoOffers()
	{
		assertEquals(Collections.emptyList(), tracker.markSeen(500));
	}
}
