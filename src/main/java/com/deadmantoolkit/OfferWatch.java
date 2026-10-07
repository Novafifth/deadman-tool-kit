package com.deadmantoolkit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.Value;
import net.runelite.client.util.QuantityFormatter;

/**
 * Compares your open offers with the market (pure). For each offer it decides whether someone sells below your sell
 * price (undercut), bids above your buy price (outbid), or the offer hasn't filled for a long time (stale).
 * <p>
 * The market's book includes your own offers and can't tell them apart, so comparisons are strict (an order at your
 * own price is never flagged) and prices of your other open offers, and of your own recent fills, on the same item
 * and side are ignored.
 */
public final class OfferWatch
{
	private OfferWatch()
	{
	}

	public enum Flag
	{
		OK, UNDERCUT, OUTBID, STALE
	}

	/**
	 * The market numbers one comparison needs. Any of them may be unknown (null). The last buy / sell must be live
	 * fills only: GE History rows are uploaded with the time they were imported, so a weeks-old trade imported by
	 * someone else would look like a trade made "since you listed".
	 */
	@Value
	public static class Quote
	{
		Long bestBid;
		Long bestAsk;
		Long lastBuy;
		Long lastBuyTime;
		Long lastSell;
		Long lastSellTime;

		/**
		 * From the batch summary: the order book only. Its last buy / sell include GE History rows (timestamped at
		 * import), which can't be told apart, so they are left out. The best bid / ask from trusted sources are
		 * preferred when the server sends them, so fake book entries can't trigger undercut / outbid alerts.
		 */
		public static Quote of(MarketData.ItemSummary s)
		{
			return new Quote(prefer(s.getTrustedBestBid(), s.getBestBid()), prefer(s.getTrustedBestAsk(), s.getBestAsk()),
				null, null, null, null);
		}

		/**
		 * From the item view's detail, which is usually fresher than the batch summary: its order book, plus the
		 * newest live (not late, not GE History) fill per side from its recent trades.
		 */
		public static Quote of(MarketData.Summary s, List<MarketData.Trade> trades)
		{
			MarketData.Trade buy = null, sell = null;
			for (MarketData.Trade t : trades == null ? Collections.<MarketData.Trade>emptyList() : trades)
			{
				if (t == null || !TradeEvent.FILL.equals(t.getKind()) || t.isLate())
				{
					continue;
				}
				if (TradeEvent.BUY.equals(t.getSide()) && (buy == null || t.getTs() > buy.getTs()))
				{
					buy = t;
				}
				else if (TradeEvent.SELL.equals(t.getSide()) && (sell == null || t.getTs() > sell.getTs()))
				{
					sell = t;
				}
			}
			return new Quote(prefer(s.getTrustedBestBid(), s.getBestBid()), prefer(s.getTrustedBestAsk(), s.getBestAsk()),
				buy == null ? null : buy.getPrice(), buy == null ? null : buy.getTs(),
				sell == null ? null : sell.getPrice(), sell == null ? null : sell.getTs());
		}
	}

	/** The trusted value when the server sent one, else the raw one (older servers, or no trusted offers yet). */
	private static Long prefer(Long trusted, Long raw)
	{
		return trusted != null ? trusted : raw;
	}

	/** The verdict for one offer. */
	@Value
	public static class OfferStatus
	{
		/** The offer this is about; a new offer in the same slot gets its own status. */
		String offerKey;
		Flag flag;
		/** The competing price for UNDERCUT / OUTBID, else null. */
		Long competing;
		/** One line for a tooltip, e.g. "Lowest ask 1,240,000". */
		String detail;
		/** Seconds since the last fill (or since placing), or -1 when unknown. */
		long idleSecs;
	}

	/**
	 * Evaluate every offer.
	 *
	 * @param quotes    market quotes by item id; an offer without one is OK ("no market data")
	 * @param myTrades  your recent trades (any order): last fills per offer, and own fill prices to ignore
	 * @param now       unix seconds
	 * @param staleSecs an offer with no fill for longer than this is STALE (when nothing worse applies)
	 * @return statuses by slot, in slot order
	 */
	public static Map<Integer, OfferStatus> evaluate(List<ActiveOffer> offers, Map<Integer, Quote> quotes,
		List<TradeEvent> myTrades, long now, long staleSecs)
	{
		Map<String, Long> lastFill = new HashMap<>();
		Map<String, List<TradeEvent>> ownFills = new HashMap<>();
		for (TradeEvent ev : myTrades)
		{
			if (!TradeEvent.FILL.equals(ev.getKind()))
			{
				continue;
			}
			if (ev.getOfferKey() != null)
			{
				lastFill.merge(ev.getOfferKey(), ev.getTs(), Math::max);
			}
			ownFills.computeIfAbsent(sideKey(ev.getItemId(), TradeEvent.BUY.equals(ev.getSide())), k -> new ArrayList<>())
				.add(ev);
		}

		Map<Integer, OfferStatus> out = new LinkedHashMap<>();
		for (ActiveOffer o : offers)
		{
			// Your fills of this item and side since this offer was placed may be what the market saw last.
			Set<Long> own = new HashSet<>();
			for (TradeEvent f : ownFills.getOrDefault(sideKey(o.getItemId(), o.isBuy()), Collections.emptyList()))
			{
				if (f.getTs() >= o.getPlacedAt())
				{
					own.add(f.getPrice());
				}
			}
			for (ActiveOffer other : offers)
			{
				if (other != o && other.getItemId() == o.getItemId() && other.isBuy() == o.isBuy())
				{
					own.add(other.getPrice());
				}
			}
			out.put(o.getSlot(), evaluate(o, quotes.get(o.getItemId()), own, lastFill.get(o.getOfferKey()), now, staleSecs));
		}
		return out;
	}

	/**
	 * One offer.
	 *
	 * @param ownPrices prices of your other offers and fills on the same item and side, which aren't competition
	 * @param lastFill  when this offer last filled (unix s), or null
	 */
	static OfferStatus evaluate(ActiveOffer o, Quote q, Set<Long> ownPrices, Long lastFill, long now, long staleSecs)
	{
		long since = Math.max(o.getPlacedAt(), lastFill == null ? 0 : lastFill);
		long idle = since > 0 ? Math.max(0, now - since) : -1;
		if (q == null)
		{
			return new OfferStatus(o.getOfferKey(), Flag.OK, null, "No market data", idle);
		}
		long p = o.getPrice();
		long placed = o.getPlacedAt();
		if (!o.isBuy())
		{
			Long ask = q.getBestAsk();
			if (ask != null && ask < p && !ownPrices.contains(ask))
			{
				return new OfferStatus(o.getOfferKey(), Flag.UNDERCUT, ask, "Lowest ask " + gp(ask), idle);
			}
			Long sold = q.getLastSell();
			if (sold != null && sold < p && after(q.getLastSellTime(), placed) && !ownPrices.contains(sold))
			{
				return new OfferStatus(o.getOfferKey(), Flag.UNDERCUT, sold, "Sold for " + gp(sold) + " since you listed", idle);
			}
		}
		else
		{
			Long bid = q.getBestBid();
			if (bid != null && bid > p && !ownPrices.contains(bid))
			{
				return new OfferStatus(o.getOfferKey(), Flag.OUTBID, bid, "Highest bid " + gp(bid), idle);
			}
			Long bought = q.getLastBuy();
			if (bought != null && bought > p && after(q.getLastBuyTime(), placed) && !ownPrices.contains(bought))
			{
				return new OfferStatus(o.getOfferKey(), Flag.OUTBID, bought, "Bought for " + gp(bought) + " since you bid", idle);
			}
		}
		if (idle >= 0 && idle > staleSecs)
		{
			return new OfferStatus(o.getOfferKey(), Flag.STALE, null, "No fill for " + hours(idle), idle);
		}
		return new OfferStatus(o.getOfferKey(), Flag.OK, null, "No better offer seen", idle);
	}

	/** A trade time strictly after the offer was placed; false when either is unknown. */
	private static boolean after(Long ts, long placedAt)
	{
		return ts != null && placedAt > 0 && ts > placedAt;
	}

	private static String sideKey(int itemId, boolean buy)
	{
		return itemId + (buy ? "|b" : "|s");
	}

	/** "7h", or "45m" under an hour. */
	public static String hours(long secs)
	{
		return secs >= 3600 ? (secs / 3600) + "h" : Math.max(0, secs / 60) + "m";
	}

	/** Exact below 10M gp, stack format (e.g. 12.5M) above. */
	public static String gp(long v)
	{
		return v < 10_000_000 ? QuantityFormatter.formatNumber(v) : QuantityFormatter.quantityToStackSize(v);
	}

	/**
	 * The notification text, e.g. "Deadman Tool Kit: your Dragon bones sell offer (1,250,000) was undercut at
	 * 1,240,000."; null for flags that don't notify.
	 */
	public static String alertMessage(ActiveOffer o, OfferStatus s)
	{
		if (s.getCompeting() == null || (s.getFlag() != Flag.UNDERCUT && s.getFlag() != Flag.OUTBID))
		{
			return null;
		}
		String name = o.getName() == null ? "item " + o.getItemId() : o.getName();
		return "Deadman Tool Kit: your " + name + (o.isBuy() ? " buy" : " sell") + " offer (" + gp(o.getPrice()) + ") was "
			+ (s.getFlag() == Flag.UNDERCUT ? "undercut" : "outbid") + " at " + gp(s.getCompeting()) + ".";
	}
}
