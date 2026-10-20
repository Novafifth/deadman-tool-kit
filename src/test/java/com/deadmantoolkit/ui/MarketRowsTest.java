package com.deadmantoolkit.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import com.deadmantoolkit.MarketData;
import com.google.gson.Gson;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public class MarketRowsTest
{
	private static final Gson GSON = new Gson();

	private static MarketData.FeedEvent fill(long id, int item, String side, int qty, long price, long receivedAt)
	{
		return GSON.fromJson("{\"id\":" + id + ",\"kind\":\"fill\",\"side\":\"" + side + "\",\"itemId\":" + item
			+ ",\"qty\":" + qty + ",\"price\":" + price + ",\"receivedAt\":" + receivedAt + "}", MarketData.FeedEvent.class);
	}

	@Test
	public void oneRowPerItemWithItsLatestTrade()
	{
		List<MarketRows.Row> rows = MarketRows.latestPerItem(Arrays.asList(
			fill(5, 6685, "sell", 3, 5800, 1000),
			fill(4, 561, "buy", 1000, 195, 900),
			fill(3, 6685, "buy", 10, 5600, 800),
			fill(2, 561, "sell", 50, 190, 700)));
		assertEquals(2, rows.size());
		assertEquals(5L, rows.get(0).getEvent().getId());
		assertEquals(4L, rows.get(1).getEvent().getId());
		assertFalse(rows.get(0).isBothSides());
	}

	@Test
	public void bothSidesOfOneTradeAreOneTradedRow()
	{
		// Seen live: two plugin users on either side of one GE trade, reported a second apart.
		List<MarketRows.Row> rows = MarketRows.latestPerItem(Arrays.asList(
			fill(9, 6685, "sell", 10, 5697, 1001),
			fill(8, 6685, "buy", 10, 5697, 1000)));
		assertEquals(1, rows.size());
		assertEquals(9L, rows.get(0).getEvent().getId());
		assertTrue(rows.get(0).isBothSides());
	}

	@Test
	public void differentQuantityPriceOrTimeIsNotTheSameTrade()
	{
		assertFalse(MarketRows.latestPerItem(Arrays.asList(fill(2, 1, "sell", 10, 100, 1000), fill(1, 1, "buy", 9, 100, 1000)))
			.get(0).isBothSides());
		assertFalse(MarketRows.latestPerItem(Arrays.asList(fill(2, 1, "sell", 10, 100, 1000), fill(1, 1, "buy", 10, 101, 1000)))
			.get(0).isBothSides());
		assertFalse(MarketRows.latestPerItem(Arrays.asList(fill(2, 1, "sell", 10, 100, 1000),
			fill(1, 1, "buy", 10, 100, 1000 - MarketRows.SAME_TRADE - 1))).get(0).isBothSides());
		assertFalse(MarketRows.latestPerItem(Arrays.asList(fill(2, 1, "sell", 10, 100, 1000), fill(1, 1, "sell", 10, 100, 1000)))
			.get(0).isBothSides());
	}
}
