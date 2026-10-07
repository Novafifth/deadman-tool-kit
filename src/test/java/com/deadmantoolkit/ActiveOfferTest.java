package com.deadmantoolkit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import java.util.List;
import org.junit.Test;

public class ActiveOfferTest
{
	@Test
	public void onlyOpenOffersInSlotOrder()
	{
		OfferTracker t = new OfferTracker(() -> "key");
		t.setSlot(2, new OfferTracker.SlotState("k2", 536, false, 1_250_000, 100, 40, 50_000_000, OfferTracker.Status.ACTIVE, 10, 20));
		t.setSlot(0, new OfferTracker.SlotState("k0", 4151, true, 5, 10, 0, 0, OfferTracker.Status.ACTIVE, 11, 21));
		// Done or fully filled: not open.
		t.setSlot(1, new OfferTracker.SlotState("k1", 4151, true, 5, 10, 10, 50, OfferTracker.Status.ACTIVE, 1, 2));
		t.setSlot(3, new OfferTracker.SlotState("k3", 4151, true, 5, 10, 3, 15, OfferTracker.Status.CANCELLED, 1, 2));
		t.setSlot(4, new OfferTracker.SlotState("k4", 4151, true, 5, 10, 10, 50, OfferTracker.Status.COMPLETE, 1, 2));

		List<ActiveOffer> offers = ActiveOffer.fromTracker(t, id -> id == 536 ? "Dragon bones" : "Abyssal whip");
		assertEquals(2, offers.size());
		assertEquals(new ActiveOffer(0, "k0", 4151, "Abyssal whip", true, 5, 10, 0, 11), offers.get(0));
		assertEquals(new ActiveOffer(2, "k2", 536, "Dragon bones", false, 1_250_000, 100, 40, 10), offers.get(1));
		assertTrue(ActiveOffer.fromTracker(new OfferTracker(() -> "x"), id -> "").isEmpty());
	}
}
