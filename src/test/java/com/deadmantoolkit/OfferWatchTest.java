package com.deadmantoolkit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import com.deadmantoolkit.OfferWatch.Flag;
import com.deadmantoolkit.OfferWatch.OfferStatus;
import com.deadmantoolkit.OfferWatch.Quote;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class OfferWatchTest
{
	private static final int BONES = 536;
	private static final long PLACED = 1_000_000;
	private static final long NOW = PLACED + 3600;
	private static final long STALE = 6 * 3600;

	private static ActiveOffer sell(int slot, String key, long price)
	{
		return new ActiveOffer(slot, key, BONES, "Dragon bones", false, price, 100, 40, PLACED);
	}

	private static ActiveOffer buy(int slot, String key, long price)
	{
		return new ActiveOffer(slot, key, BONES, "Dragon bones", true, price, 100, 0, PLACED);
	}

	private static Quote quote(Long bid, Long ask, Long lastBuy, Long lastBuyTime, Long lastSell, Long lastSellTime)
	{
		return new Quote(bid, ask, lastBuy, lastBuyTime, lastSell, lastSellTime);
	}

	private static OfferStatus one(ActiveOffer o, Quote q)
	{
		return one(o, q, Collections.emptyList(), NOW);
	}

	private static OfferStatus one(ActiveOffer o, Quote q, List<TradeEvent> trades, long now)
	{
		Map<Integer, Quote> quotes = q == null ? Collections.emptyMap() : Collections.singletonMap(BONES, q);
		return OfferWatch.evaluate(Collections.singletonList(o), quotes, trades, now, STALE).get(o.getSlot());
	}

	private static TradeEvent fill(String key, boolean buy, long price, long ts)
	{
		return TradeEvent.builder().kind(TradeEvent.FILL).side(buy ? TradeEvent.BUY : TradeEvent.SELL).itemId(BONES)
			.qty(1).price(price).offerKey(key).ts(ts).build();
	}

	@Test
	public void sellUndercutByLowerAsk()
	{
		OfferStatus s = one(sell(0, "a", 1_250_000), quote(null, 1_240_000L, null, null, null, null));
		assertEquals(Flag.UNDERCUT, s.getFlag());
		assertEquals(Long.valueOf(1_240_000), s.getCompeting());
		assertEquals("a", s.getOfferKey());
	}

	@Test
	public void sellUndercutByFillAfterListing()
	{
		OfferStatus s = one(sell(0, "a", 1_250_000), quote(null, null, null, null, 1_200_000L, PLACED + 60));
		assertEquals(Flag.UNDERCUT, s.getFlag());
		assertEquals(Long.valueOf(1_200_000), s.getCompeting());
	}

	@Test
	public void fillBeforeListingIsIgnored()
	{
		assertEquals(Flag.OK, one(sell(0, "a", 1_250_000), quote(null, null, null, null, 1_200_000L, PLACED - 1)).getFlag());
		assertEquals(Flag.OK, one(sell(0, "a", 1_250_000), quote(null, null, null, null, 1_200_000L, PLACED)).getFlag());
	}

	@Test
	public void equalPriceIsNotFlagged()
	{
		assertEquals(Flag.OK, one(sell(0, "a", 1_250_000), quote(null, 1_250_000L, null, null, 1_250_000L, NOW)).getFlag());
		assertEquals(Flag.OK, one(buy(0, "b", 1_250_000), quote(1_250_000L, null, 1_250_000L, NOW, null, null)).getFlag());
	}

	@Test
	public void myOtherOfferAtTheBestPriceIsIgnored()
	{
		ActiveOffer high = sell(0, "a", 1_250_000);
		ActiveOffer low = sell(1, "b", 1_200_000);
		Map<Integer, OfferStatus> out = OfferWatch.evaluate(Arrays.asList(high, low),
			Collections.singletonMap(BONES, quote(null, 1_200_000L, null, null, null, null)), Collections.emptyList(), NOW, STALE);
		assertEquals(Flag.OK, out.get(0).getFlag());
		assertEquals(Flag.OK, out.get(1).getFlag());
	}

	@Test
	public void myOwnFillAfterListingIsIgnored()
	{
		List<TradeEvent> mine = Collections.singletonList(fill("other", false, 1_210_000, PLACED + 30));
		assertEquals(Flag.OK, one(sell(0, "a", 1_250_000), quote(null, null, null, null, 1_210_000L, PLACED + 30), mine, NOW).getFlag());
		// An older fill of mine at that price doesn't hide a newer trade by someone else.
		List<TradeEvent> old = Collections.singletonList(fill("other", false, 1_210_000, PLACED - 100));
		assertEquals(Flag.UNDERCUT, one(sell(0, "a", 1_250_000), quote(null, null, null, null, 1_210_000L, PLACED + 30), old, NOW).getFlag());
	}

	@Test
	public void buyOutbidByHigherBid()
	{
		OfferStatus s = one(buy(0, "b", 1_000), quote(1_100L, null, null, null, null, null));
		assertEquals(Flag.OUTBID, s.getFlag());
		assertEquals(Long.valueOf(1_100), s.getCompeting());
	}

	@Test
	public void buyOutbidByFillAboveAfterBidding()
	{
		assertEquals(Flag.OUTBID, one(buy(0, "b", 1_000), quote(null, null, 1_050L, PLACED + 5, null, null)).getFlag());
		assertEquals(Flag.OK, one(buy(0, "b", 1_000), quote(null, null, 1_050L, PLACED - 5, null, null)).getFlag());
	}

	@Test
	public void staleAfterThreshold()
	{
		ActiveOffer o = sell(0, "a", 1_000);
		Quote q = quote(null, null, null, null, null, null);
		assertEquals(Flag.OK, one(o, q, Collections.emptyList(), PLACED + STALE).getFlag());
		OfferStatus s = one(o, q, Collections.emptyList(), PLACED + STALE + 1);
		assertEquals(Flag.STALE, s.getFlag());
		assertEquals(STALE + 1, s.getIdleSecs());
		// A recent fill of this offer resets the clock.
		assertEquals(Flag.OK, one(o, q, Collections.singletonList(fill("a", false, 1_000, PLACED + STALE)), PLACED + STALE + 1).getFlag());
	}

	@Test
	public void undercutWinsOverStale()
	{
		OfferStatus s = one(sell(0, "a", 1_000), quote(null, 900L, null, null, null, null), Collections.emptyList(), PLACED + STALE * 2);
		assertEquals(Flag.UNDERCUT, s.getFlag());
	}

	@Test
	public void missingQuoteIsOk()
	{
		OfferStatus s = one(sell(0, "a", 1_000), null);
		assertEquals(Flag.OK, s.getFlag());
		assertEquals("No market data", s.getDetail());
		assertNull(s.getCompeting());
	}

	@Test
	public void unknownPlacementNeverStaleNorFillChecked()
	{
		ActiveOffer o = new ActiveOffer(0, "a", BONES, "Dragon bones", false, 1_000, 10, 0, 0);
		OfferStatus s = OfferWatch.evaluate(Collections.singletonList(o),
			Collections.singletonMap(BONES, quote(null, null, null, null, 900L, NOW)), Collections.emptyList(), NOW, STALE).get(0);
		assertEquals(Flag.OK, s.getFlag());
		assertEquals(-1, s.getIdleSecs());
	}

	@Test
	public void alertMessage()
	{
		ActiveOffer o = sell(0, "a", 1_250_000);
		OfferStatus s = one(o, quote(null, 1_240_000L, null, null, null, null));
		assertEquals("Deadman Tool Kit: your Dragon bones sell offer (1,250,000) was undercut at 1,240,000.",
			OfferWatch.alertMessage(o, s));
		OfferStatus b = one(buy(0, "b", 20_000_000), quote(21_000_000L, null, null, null, null, null));
		assertEquals("Deadman Tool Kit: your Dragon bones buy offer (20M) was outbid at 21M.",
			OfferWatch.alertMessage(buy(0, "b", 20_000_000), b));
		assertNull(OfferWatch.alertMessage(o, one(o, null)));
	}

	@Test
	public void gePriceHistoryImportedLaterIsNoUndercut()
	{
		com.google.gson.Gson gson = new com.google.gson.Gson();
		// The batch summary's last sell is a 3-week-old GE History row uploaded (ts = import time) after you listed.
		MarketData.ItemSummary batch = gson.fromJson("{\"id\":536,\"lastSell\":1100000,\"lastSellTime\":" + (PLACED + 1800) + "}",
			MarketData.ItemSummary.class);
		assertEquals(Flag.OK, one(sell(0, "a", 1_250_000), Quote.of(batch)).getFlag());

		// The item detail: only its live fills count.
		MarketData.Summary sum = gson.fromJson("{\"lastSell\":1100000,\"lastSellTime\":" + (PLACED + 1800) + "}",
			MarketData.Summary.class);
		List<MarketData.Trade> history = Arrays.asList(gson.fromJson(
			"{\"kind\":\"history\",\"side\":\"sell\",\"qty\":1,\"price\":1100000,\"ts\":" + (PLACED + 1800) + "}",
			MarketData.Trade.class), gson.fromJson(
			"{\"kind\":\"fill\",\"side\":\"sell\",\"qty\":1,\"price\":1000000,\"ts\":" + (PLACED + 1700) + ",\"late\":true}",
			MarketData.Trade.class));
		assertEquals(Flag.OK, one(sell(0, "a", 1_250_000), Quote.of(sum, history)).getFlag());

		List<MarketData.Trade> live = Arrays.asList(gson.fromJson(
			"{\"kind\":\"fill\",\"side\":\"sell\",\"qty\":1,\"price\":1200000,\"ts\":" + (PLACED + 60) + "}",
			MarketData.Trade.class), history.get(0));
		OfferStatus s = one(sell(0, "a", 1_250_000), Quote.of(sum, live));
		assertEquals(Flag.UNDERCUT, s.getFlag());
		assertEquals(Long.valueOf(1_200_000), s.getCompeting());
	}

	@Test
	public void quotesPreferTheTrustedBook()
	{
		com.google.gson.Gson gson = new com.google.gson.Gson();
		MarketData.ItemSummary both = gson.fromJson("{\"id\":536,\"bestBid\":2000000000,\"bestAsk\":1,"
			+ "\"trustedBestBid\":1240000,\"trustedBestAsk\":1260000}", MarketData.ItemSummary.class);
		Quote q = Quote.of(both);
		assertEquals(Long.valueOf(1_240_000), q.getBestBid());
		assertEquals(Long.valueOf(1_260_000), q.getBestAsk());
		// A fake 1 gp ask doesn't undercut a 1.25M sell offer when the trusted book is known.
		assertEquals(Flag.OK, one(sell(0, "a", 1_250_000), Quote.of(gson.fromJson("{\"id\":536,\"bestAsk\":1,"
			+ "\"trustedBestAsk\":1300000}", MarketData.ItemSummary.class))).getFlag());

		// Older servers (or no trusted offers yet): the raw book.
		Quote raw = Quote.of(gson.fromJson("{\"id\":536,\"bestBid\":5,\"bestAsk\":6}", MarketData.ItemSummary.class));
		assertEquals(Long.valueOf(5), raw.getBestBid());
		assertEquals(Long.valueOf(6), raw.getBestAsk());

		MarketData.Summary s = gson.fromJson("{\"bestBid\":5,\"bestAsk\":6,\"trustedBestAsk\":7}", MarketData.Summary.class);
		Quote detail = Quote.of(s, Collections.emptyList());
		assertEquals(Long.valueOf(5), detail.getBestBid());
		assertEquals(Long.valueOf(7), detail.getBestAsk());
	}
}
