package com.deadmantoolkit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import com.google.gson.Gson;
import org.junit.Test;

public class MarketDataTest
{
	private final Gson gson = new Gson();

	@Test
	public void missingListsDefaultToEmpty()
	{
		assertNotNull(gson.fromJson("{}", MarketData.Recent.class).getEvents());
		assertTrue(gson.fromJson("{}", MarketData.Recent.class).getEvents().isEmpty());
		assertTrue(gson.fromJson("{}", MarketData.ItemDetail.class).getTrades().isEmpty());
		assertTrue(gson.fromJson("{}", MarketData.Book.class).getBids().isEmpty());
		assertTrue(gson.fromJson("{}", MarketData.Book.class).getAsks().isEmpty());
		assertTrue(gson.fromJson("{}", MarketData.Series.class).getData().isEmpty());
	}

	@Test
	public void parsesRecent()
	{
		MarketData.Recent r = gson.fromJson("{\"stats\":{\"openBids\":1,\"openAsks\":2,\"units24\":3,\"gp24\":4,\"reporters24\":5},"
			+ "\"events\":[{\"id\":7,\"kind\":\"fill\",\"side\":\"sell\",\"itemId\":4151,\"name\":\"Abyssal whip\",\"qty\":2,"
			+ "\"price\":300,\"total\":600,\"ts\":10,\"late\":true,\"receivedAt\":11}]}", MarketData.Recent.class);
		assertEquals(1, r.getStats().getOpenBids());
		assertEquals(2, r.getStats().getOpenAsks());
		assertEquals(3, r.getStats().getUnits24());
		assertEquals(4, r.getStats().getGp24());
		assertEquals(1, r.getEvents().size());
		MarketData.FeedEvent e = r.getEvents().get(0);
		assertEquals("fill", e.getKind());
		assertEquals("sell", e.getSide());
		assertEquals(4151, e.getItemId());
		assertEquals("Abyssal whip", e.getName());
		assertEquals(2, e.getQty());
		assertEquals(300, e.getPrice());
		assertEquals(11, e.getReceivedAt());
	}

	@Test
	public void parsesItem()
	{
		MarketData.ItemDetail d = gson.fromJson("{\"id\":4151,\"name\":\"Abyssal whip\",\"summary\":{\"lastBuy\":10,"
			+ "\"lastBuyTime\":20,\"vol24\":5,\"bestAsk\":12},\"trades\":[{\"kind\":\"history\",\"side\":\"buy\",\"qty\":1,"
			+ "\"price\":10,\"ts\":30,\"late\":true}],\"book\":{\"bids\":[{\"price\":9,\"offers\":2,\"qty\":100}]}}",
			MarketData.ItemDetail.class);
		assertEquals("Abyssal whip", d.getName());
		assertEquals(Long.valueOf(10), d.getSummary().getLastBuy());
		assertEquals(Long.valueOf(20), d.getSummary().getLastBuyTime());
		assertNull(d.getSummary().getLastSell());
		assertEquals(5, d.getSummary().getVol24());
		assertEquals(Long.valueOf(12), d.getSummary().getBestAsk());
		assertEquals(1, d.getTrades().size());
		assertEquals("history", d.getTrades().get(0).getKind());
		assertEquals(30, d.getTrades().get(0).getTs());
		assertEquals(1, d.getBook().getBids().size());
		assertEquals(9, d.getBook().getBids().get(0).getPrice());
		assertEquals(2, d.getBook().getBids().get(0).getOffers());
		assertEquals(100, d.getBook().getBids().get(0).getQty());
		assertTrue(d.getBook().getAsks().isEmpty());
	}

	@Test
	public void parsesSeries()
	{
		MarketData.Series s = gson.fromJson("{\"step\":\"1h\",\"data\":[{\"timestamp\":3600,\"avgBuy\":5,"
			+ "\"buyVolume\":2,\"sellVolume\":0}]}", MarketData.Series.class);
		assertEquals(1, s.getData().size());
		MarketData.Point p = s.getData().get(0);
		assertEquals(3600, p.getTimestamp());
		assertEquals(Long.valueOf(5), p.getAvgBuy());
		assertNull(p.getAvgSell());
		assertEquals(2, p.getBuyVolume());
		assertEquals(0, p.getSellVolume());
	}

	@Test
	public void seriesRangeIsOptional()
	{
		assertNull(gson.fromJson("{\"step\":\"1h\",\"data\":[]}", MarketData.Series.class).getRange());
		assertEquals("7d", gson.fromJson("{\"id\":1,\"step\":\"1h\",\"range\":\"7d\",\"data\":[]}",
			MarketData.Series.class).getRange());
	}

	@Test
	public void equalJsonGivesEqualObjects()
	{
		String item = "{\"id\":4151,\"name\":\"Abyssal whip\",\"summary\":{\"lastBuy\":10,\"vol24\":5},"
			+ "\"trades\":[{\"kind\":\"fill\",\"side\":\"buy\",\"qty\":1,\"price\":10,\"ts\":30}],"
			+ "\"book\":{\"bids\":[{\"price\":9,\"offers\":2,\"qty\":100}]}}";
		MarketData.ItemDetail a = gson.fromJson(item, MarketData.ItemDetail.class);
		MarketData.ItemDetail b = gson.fromJson(item, MarketData.ItemDetail.class);
		assertEquals(a, b);
		assertEquals(a.hashCode(), b.hashCode());
		assertEquals(a.getSummary(), b.getSummary());
		assertEquals(a.getBook(), b.getBook());
		assertNotEquals(a, gson.fromJson(item.replace("\"price\":9", "\"price\":8"), MarketData.ItemDetail.class));

		String series = "{\"step\":\"1h\",\"data\":[{\"timestamp\":3600,\"avgBuy\":5,\"buyVolume\":2}]}";
		assertEquals(gson.fromJson(series, MarketData.Series.class).getData(),
			gson.fromJson(series, MarketData.Series.class).getData());
		assertEquals(gson.fromJson("{}", MarketData.Recent.class), gson.fromJson("{}", MarketData.Recent.class));
	}

	@Test
	public void trustFieldsAreNullOnOlderServers()
	{
		MarketData.Summary s = gson.fromJson("{\"lastBuy\":10,\"vol24\":5}", MarketData.Summary.class);
		assertNull(s.getTrustedPrice());
		assertNull(s.getTrustedVotes());
		assertNull(s.getConfVol24());
		assertNull(s.getLastFillBuy());
		assertNull(s.getTrustedBestBid());
		MarketData.ItemSummary i = gson.fromJson("{\"id\":1,\"vol24\":5}", MarketData.ItemSummary.class);
		assertNull(i.getTrustedPrice());
		assertNull(i.getTrustedBestAsk());
		assertNull(gson.fromJson("{\"kind\":\"fill\"}", MarketData.Trade.class).getConfirmed());
		assertNull(gson.fromJson("{\"kind\":\"fill\"}", MarketData.FeedEvent.class).getConfirmed());
		assertNull(gson.fromJson("{\"timestamp\":1}", MarketData.Point.class).getConfAvgBuy());
	}

	@Test
	public void parsesTrustFields()
	{
		MarketData.ItemDetail d = gson.fromJson("{\"id\":4151,\"summary\":{\"vol24\":12,\"trustedPrice\":1500000,"
			+ "\"trustedVotes\":4,\"trustedPriceTime\":99,\"confVol24\":8,\"unconfVol24\":4,\"trustedBestBid\":1490000,"
			+ "\"trustedBestAsk\":null,\"confAvgBuy24\":1495000,\"confAvgSell24\":1505000,\"lastConfBuy\":1,"
			+ "\"lastConfBuyTime\":2,\"lastConfSell\":3,\"lastConfSellTime\":4,\"lastFillBuy\":5,\"lastFillBuyTime\":6,"
			+ "\"lastFillSell\":7,\"lastFillSellTime\":8},\"trades\":[{\"kind\":\"fill\",\"confirmed\":false},"
			+ "{\"kind\":\"fill\",\"confirmed\":true}]}", MarketData.ItemDetail.class);
		MarketData.Summary s = d.getSummary();
		assertEquals(Long.valueOf(1_500_000), s.getTrustedPrice());
		assertEquals(Integer.valueOf(4), s.getTrustedVotes());
		assertEquals(Long.valueOf(99), s.getTrustedPriceTime());
		assertEquals(Long.valueOf(8), s.getConfVol24());
		assertEquals(Long.valueOf(4), s.getUnconfVol24());
		assertEquals(Long.valueOf(1_490_000), s.getTrustedBestBid());
		assertNull(s.getTrustedBestAsk());
		assertEquals(Long.valueOf(1_495_000), s.getConfAvgBuy24());
		assertEquals(Long.valueOf(1_505_000), s.getConfAvgSell24());
		assertEquals(Long.valueOf(1), s.getLastConfBuy());
		assertEquals(Long.valueOf(4), s.getLastConfSellTime());
		assertEquals(Long.valueOf(5), s.getLastFillBuy());
		assertEquals(Long.valueOf(8), s.getLastFillSellTime());
		assertEquals(Boolean.FALSE, d.getTrades().get(0).getConfirmed());
		assertEquals(Boolean.TRUE, d.getTrades().get(1).getConfirmed());

		MarketData.ItemSummary i = gson.fromJson("{\"id\":1,\"trustedPrice\":5,\"trustedVotes\":3,\"confVol24\":2,"
			+ "\"unconfVol24\":1,\"trustedBestBid\":4,\"trustedBestAsk\":6,\"lastFillBuy\":7}", MarketData.ItemSummary.class);
		assertEquals(Long.valueOf(5), i.getTrustedPrice());
		assertEquals(Long.valueOf(4), i.getTrustedBestBid());
		assertEquals(Long.valueOf(6), i.getTrustedBestAsk());
		assertEquals(Long.valueOf(7), i.getLastFillBuy());

		MarketData.Point p = gson.fromJson("{\"timestamp\":1,\"confAvgBuy\":9,\"confAvgSell\":null,\"confBuyVolume\":3}",
			MarketData.Point.class);
		assertEquals(Long.valueOf(9), p.getConfAvgBuy());
		assertNull(p.getConfAvgSell());
		assertEquals(Long.valueOf(3), p.getConfBuyVolume());

		assertEquals(Boolean.TRUE, gson.fromJson("{\"kind\":\"fill\",\"confirmed\":true}", MarketData.FeedEvent.class)
			.getConfirmed());
	}
}
