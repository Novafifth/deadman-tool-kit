package com.deadmantoolkit.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import com.deadmantoolkit.ActiveOffer;
import com.deadmantoolkit.MarketData;
import com.deadmantoolkit.TradeEvent;
import com.google.gson.Gson;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public class ItemSectionsTest
{
	private static ActiveOffer offer(int slot, int itemId, boolean buy, long price, int total, int filled)
	{
		return new ActiveOffer(slot, "k" + slot, itemId, "Dragon bones", buy, price, total, filled, 100);
	}

	@Test
	public void offerText()
	{
		assertEquals("Selling @ 1,250,000 · 40/100", ItemSections.offerText(offer(0, 536, false, 1_250_000, 100, 40)));
		assertEquals("Buying @ 12M · 0/2,000", ItemSections.offerText(offer(0, 536, true, 12_000_000, 2_000, 0)));
		assertEquals("40%", ItemSections.offerRight(offer(0, 536, false, 1, 100, 40)));
		assertEquals("0%", ItemSections.offerRight(offer(0, 536, true, 1, 3, 0)));
		assertEquals("99%", ItemSections.offerRight(offer(0, 536, true, 1, 1000, 999)));
	}

	@Test
	public void filtersByItem()
	{
		List<ActiveOffer> offers = Arrays.asList(offer(0, 536, true, 1, 1, 0), offer(3, 4151, false, 1, 1, 0),
			offer(5, 536, false, 1, 1, 0));
		assertEquals(Arrays.asList(offers.get(0), offers.get(2)), ItemSections.offersFor(offers, 536));

		TradeEvent a = TradeEvent.builder().id("a").kind(TradeEvent.FILL).itemId(536).build();
		TradeEvent b = TradeEvent.builder().id("b").kind(TradeEvent.FILL).itemId(4151).build();
		assertEquals(Arrays.asList(a), ItemSections.tradesFor(Arrays.asList(a, b), 536));
	}

	@Test
	public void tradeKeyFallsBackForOldLines()
	{
		assertEquals("a", ItemSections.tradeKey(TradeEvent.builder().id("a").build()));
		assertEquals("fill|buy|2|5|9", ItemSections.tradeKey(TradeEvent.builder().kind("fill").side("buy").qty(2).price(5).ts(9).build()));
	}

	private final Gson gson = new Gson();

	@Test
	public void typicalConfirmedCellOnlyWithATrustedPrice()
	{
		MarketData.Summary old = gson.fromJson("{\"lastBuy\":10,\"bestBid\":9,\"vol24\":5}", MarketData.Summary.class);
		List<ItemSections.StatCell> cells = ItemSections.statCells(old);
		assertEquals(6, cells.size());
		for (ItemSections.StatCell c : cells)
		{
			assertFalse(ItemSections.TYPICAL_LABEL.equals(c.getLabel()));
		}
		assertEquals("Last bought", cells.get(0).getLabel());
		assertEquals("Best ask", cells.get(5).getLabel());

		MarketData.Summary trusted = gson.fromJson("{\"lastBuy\":10,\"vol24\":5,\"trustedPrice\":1250000,\"trustedVotes\":4}",
			MarketData.Summary.class);
		cells = ItemSections.statCells(trusted);
		assertEquals(7, cells.size());
		ItemSections.StatCell typical = cells.get(6);
		assertEquals("Typical (confirmed)", typical.getLabel());
		assertEquals("1,250,000", typical.getValue());
		assertEquals("4 players", typical.getSub());
		assertEquals(ItemSections.Accent.NEUTRAL, typical.getAccent());
		assertTrue(typical.getTooltip() != null && !typical.getTooltip().isEmpty());

		// With the time of its newest confirmed trade: the age comes first, like "Last bought".
		long now = 1_800_000_000L;
		MarketData.Summary dated = gson.fromJson("{\"trustedPrice\":900,\"trustedVotes\":1,\"trustedPriceTime\":"
			+ (now - 3 * 86400) + "}", MarketData.Summary.class);
		ItemSections.StatCell old3d = ItemSections.statCells(dated, now).get(6);
		assertEquals("3d · 1 player", old3d.getSub());
		assertTrue(old3d.getTooltip().contains("3d ago"));
		MarketData.Summary noVotes = gson.fromJson("{\"trustedPrice\":900,\"trustedPriceTime\":" + (now - 120) + "}",
			MarketData.Summary.class);
		assertEquals("2m", ItemSections.statCells(noVotes, now).get(6).getSub());
	}

	@Test
	public void volumeTooltipOnlyWhenTheServerSaysSo()
	{
		assertNull(ItemSections.volumeTooltip(gson.fromJson("{\"vol24\":5}", MarketData.Summary.class)));
		assertEquals("1,200 confirmed / 34 unconfirmed", ItemSections.volumeTooltip(
			gson.fromJson("{\"vol24\":1234,\"confVol24\":1200,\"unconfVol24\":34}", MarketData.Summary.class)));
	}

	@Test
	public void onlyFillsMarkedUnconfirmedAreMuted()
	{
		assertTrue(ItemSections.unconfirmed(gson.fromJson("{\"kind\":\"fill\",\"confirmed\":false}", MarketData.Trade.class)));
		assertFalse(ItemSections.unconfirmed(gson.fromJson("{\"kind\":\"fill\",\"confirmed\":true}", MarketData.Trade.class)));
		assertFalse("older servers don't say", ItemSections.unconfirmed(gson.fromJson("{\"kind\":\"fill\"}", MarketData.Trade.class)));
	}
}
