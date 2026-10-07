package com.deadmantoolkit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import com.google.gson.Gson;
import java.util.Arrays;
import java.util.Map;
import org.junit.Test;

public class OfferQuotesTest
{
	private static MarketData.ItemsResponse parse(String json)
	{
		return new Gson().fromJson(json, MarketData.ItemsResponse.class);
	}

	@Test
	public void serverIgnoringIdsExtraItemsDropped()
	{
		MarketData.ItemsResponse r = parse("{\"time\":5,\"items\":["
			+ "{\"id\":536,\"bestAsk\":1200,\"bestBid\":1100,\"lastSell\":1190,\"lastSellTime\":4,\"vol24\":10},"
			+ "{\"id\":4151,\"bestAsk\":2000000},"
			+ "{\"id\":11832,\"lastBuy\":30000000,\"lastBuyTime\":3}]}");
		Map<Integer, OfferWatch.Quote> q = OfferQuotes.filter(r, Arrays.asList(536, 11832));
		assertEquals(2, q.size());
		// Book only: the batch's last trades may be GE History rows timestamped at import.
		assertEquals(new OfferWatch.Quote(1100L, 1200L, null, null, null, null), q.get(536));
		assertTrue(q.containsKey(11832));
		assertNull(q.get(11832).getLastBuy());
	}

	@Test
	public void missingIdsAreAbsent()
	{
		Map<Integer, OfferWatch.Quote> q = OfferQuotes.filter(parse("{\"items\":[{\"id\":536}]}"), Arrays.asList(536, 4151));
		assertEquals(1, q.size());
		assertTrue(q.containsKey(536));
	}

	@Test
	public void emptyOrNullResponse()
	{
		assertTrue(OfferQuotes.filter(parse("{}"), Arrays.asList(1)).isEmpty());
		assertTrue(OfferQuotes.filter(null, Arrays.asList(1)).isEmpty());
	}
}
