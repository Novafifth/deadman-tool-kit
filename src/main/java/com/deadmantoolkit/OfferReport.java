package com.deadmantoolkit;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import lombok.Value;

/** How your open offers compare with the market, for the panel ({@link OfferMarketWatch}). Immutable. */
@Value
public class OfferReport
{
	public static final OfferReport EMPTY = new OfferReport(Collections.emptyMap(), 0, null);

	/** By slot; empty when not connected. Match {@link OfferWatch.OfferStatus#getOfferKey()} to the offer. */
	Map<Integer, OfferWatch.OfferStatus> statuses;
	/** Epoch ms of the last successful market check, or 0. */
	long checkedAt;
	/** Why the last check failed, or null. */
	String error;

	/** The status of the offer in {@code slot}, if it is about that same offer; else null. */
	public OfferWatch.OfferStatus statusOf(ActiveOffer o)
	{
		OfferWatch.OfferStatus s = statuses.get(o.getSlot());
		return s != null && Objects.equals(s.getOfferKey(), o.getOfferKey()) ? s : null;
	}
}
