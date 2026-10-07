package com.deadmantoolkit;

import java.util.Collections;
import java.util.List;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import net.runelite.client.util.Text;

/**
 * Response bodies of the collection server's read API, parsed by Gson. Field names are the server's JSON contract.
 * Some fields aren't shown in the panel yet; they're kept to document what the server sends.
 * <p>
 * List fields default to empty when the server leaves them out. That only works while each class keeps its implicit
 * no-arg constructor, so don't add constructors or make the fields final.
 * <p>
 * Every class has value equality, so the panel can skip re-rendering when a refresh returns the same data.
 * <p>
 * Fields added for {@code ?include=trust,fill} are boxed and stay null when an older server leaves them out.
 */
public final class MarketData
{
	private MarketData()
	{
	}

	/**
	 * An item name from the server, as plain text: the server keeps what players' clients sent, so it is never trusted
	 * as markup (Swing renders text starting with "&lt;html&gt;" as HTML, which could even load remote images). Tags are
	 * removed and no angle bracket is left.
	 */
	static String plainName(String name)
	{
		if (name == null)
		{
			return null;
		}
		return Text.removeTags(name).replace("<", "").replace(">", "").trim();
	}

	@Getter
	@EqualsAndHashCode
	public static class Recent
	{
		private Stats stats;
		private List<FeedEvent> events = Collections.emptyList();
	}

	@Getter
	@EqualsAndHashCode
	public static class Stats
	{
		private int openBids;
		private int openAsks;
		private long units24;
		private long gp24;
		private int reporters24;
	}

	@Getter
	@EqualsAndHashCode
	public static class FeedEvent
	{
		private long id;
		private String kind;
		private String side;
		private int itemId;
		private String name;
		private int qty;
		private long price;
		private Long total;
		private long ts;
		private boolean late;
		/** When the server received the event (unix s). */
		private long receivedAt;
		/** Fills only, with ?include=trust: whether the fill is confirmed; null otherwise. */
		private Boolean confirmed;

		public String getName()
		{
			return plainName(name);
		}
	}

	@Getter
	@EqualsAndHashCode
	public static class ItemDetail
	{
		private int id;
		private String name;
		private Summary summary;
		private List<Trade> trades = Collections.emptyList();
		private Book book;

		public String getName()
		{
			return plainName(name);
		}
	}

	@Getter
	@EqualsAndHashCode
	public static class Summary
	{
		private Long lastBuy;
		private Long lastBuyTime;
		private Long lastSell;
		private Long lastSellTime;
		private long vol24;
		private Long avgBuy24;
		private Long avgSell24;
		private Long bestBid;
		private Long bestAsk;

		// ?include=trust (newer servers; null on older ones). "Confirmed" fills come from trusted sources at a normal
		// price, or were matched by an independent counterparty.
		/** Median price of recent confirmed fills, one vote per player; null when there aren't enough. */
		private Long trustedPrice;
		/** How many players' fills that median is based on. */
		private Integer trustedVotes;
		private Long trustedPriceTime;
		private Long confVol24;
		private Long unconfVol24;
		/** Best bid / ask among open offers from trusted sources near the trusted price. */
		private Long trustedBestBid;
		private Long trustedBestAsk;
		// ?include=fill: the latest live fills (GE History rows excluded).
		private Long lastFillBuy;
		private Long lastFillBuyTime;
		private Long lastFillSell;
		private Long lastFillSellTime;
		private Long confAvgBuy24;
		private Long confAvgSell24;
		private Long lastConfBuy;
		private Long lastConfBuyTime;
		private Long lastConfSell;
		private Long lastConfSellTime;
	}

	@Getter
	@EqualsAndHashCode
	public static class Trade
	{
		private String kind;
		private String side;
		private int qty;
		private long price;
		private long ts;
		private boolean late;
		/** Fills only, with ?include=trust: whether the fill is confirmed; null otherwise. */
		private Boolean confirmed;
	}

	@Getter
	@EqualsAndHashCode
	public static class Book
	{
		private List<Level> bids = Collections.emptyList();
		private List<Level> asks = Collections.emptyList();
	}

	@Getter
	@EqualsAndHashCode
	public static class Level
	{
		private long price;
		private int offers;
		private long qty;
	}

	@Getter
	@EqualsAndHashCode
	public static class Series
	{
		private String step;
		/** The range asked for (24h, 7d, ...). Older servers leave it out (null) and ignore the range parameter. */
		private String range;
		private List<Point> data = Collections.emptyList();
	}

	@Getter
	@EqualsAndHashCode
	public static class Point
	{
		private long timestamp;
		private Long avgBuy;
		private Long avgSell;
		private long buyVolume;
		private long sellVolume;
		// ?include=trust (null on older servers)
		private Long confAvgBuy;
		private Long confAvgSell;
		private Long confBuyVolume;
		private Long confSellVolume;
	}

	/** GET v1/items: a summary per item. Older servers ignore the ids filter and return every item. */
	@Getter
	@EqualsAndHashCode
	public static class ItemsResponse
	{
		private long time;
		private List<ItemSummary> items = Collections.emptyList();
	}

	@Getter
	@EqualsAndHashCode
	public static class ItemSummary
	{
		private int id;
		private String name;
		private Long lastBuy;
		private Long lastBuyTime;
		private Long lastSell;
		private Long lastSellTime;
		private Long bestBid;
		private Long bestAsk;
		private int bids;
		private int asks;
		private long vol24;

		public String getName()
		{
			return plainName(name);
		}

		// ?include=trust (newer servers; null on older ones). "Confirmed" fills come from trusted sources at a normal
		// price, or were matched by an independent counterparty.
		/** Median price of recent confirmed fills, one vote per player; null when there aren't enough. */
		private Long trustedPrice;
		/** How many players' fills that median is based on. */
		private Integer trustedVotes;
		private Long trustedPriceTime;
		private Long confVol24;
		private Long unconfVol24;
		/** Best bid / ask among open offers from trusted sources near the trusted price. */
		private Long trustedBestBid;
		private Long trustedBestAsk;
		// ?include=fill: the latest live fills (GE History rows excluded).
		private Long lastFillBuy;
		private Long lastFillBuyTime;
		private Long lastFillSell;
		private Long lastFillSellTime;
	}
}
