package com.deadmantoolkit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.IntFunction;
import lombok.Value;

/**
 * One of the player's own GE offers that is still open (active and not fully filled), as an immutable snapshot for
 * the panel. Built on the client thread from {@link OfferTracker}'s slots.
 */
@Value
public class ActiveOffer
{
	int slot;
	String offerKey;
	int itemId;
	String name;
	boolean buy;
	/** Offer price per item. */
	long price;
	int totalQty;
	int filled;
	/** When the offer was placed (unix s). */
	long placedAt;

	/**
	 * The open offers in slot order.
	 *
	 * @param names item names by id (on the client thread, the item manager)
	 */
	static List<ActiveOffer> fromTracker(OfferTracker t, IntFunction<String> names)
	{
		List<ActiveOffer> out = new ArrayList<>();
		for (int slot = 0; slot < OfferTracker.SLOTS; slot++)
		{
			OfferTracker.SlotState s = t.getSlot(slot);
			if (s == null || s.getStatus() != OfferTracker.Status.ACTIVE || s.getSold() >= s.getTotalQty())
			{
				continue;
			}
			out.add(new ActiveOffer(slot, s.getOfferKey(), s.getItemId(), names.apply(s.getItemId()), s.isBuy(),
				s.getPrice(), s.getTotalQty(), s.getSold(), s.getPlacedAt()));
		}
		return Collections.unmodifiableList(out);
	}
}
