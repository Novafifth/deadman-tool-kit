package com.deadmantoolkit;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Picks the requested items out of a v1/items response (pure). Servers that ignore {@code ids} return every item.
 */
final class OfferQuotes
{
	private OfferQuotes()
	{
	}

	/** Quotes for the requested ids that the response has; items it leaves out are simply missing. */
	static Map<Integer, OfferWatch.Quote> filter(MarketData.ItemsResponse response, Collection<Integer> ids)
	{
		Map<Integer, OfferWatch.Quote> out = new HashMap<>();
		if (response == null || response.getItems() == null)
		{
			return out;
		}
		Set<Integer> wanted = new HashSet<>(ids);
		for (MarketData.ItemSummary s : response.getItems())
		{
			if (s != null && wanted.contains(s.getId()))
			{
				out.put(s.getId(), OfferWatch.Quote.of(s));
			}
		}
		return out;
	}
}
