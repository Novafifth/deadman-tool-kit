package com.deadmantoolkit.ui;

import com.deadmantoolkit.ActiveOffer;
import com.deadmantoolkit.MarketData;
import com.deadmantoolkit.TradeEvent;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.Value;
import net.runelite.client.util.QuantityFormatter;

/**
 * Pure helpers for the item view's sections ("Your offer", "Your trades", the stats grid).
 */
final class ItemSections
{
	private ItemSections()
	{
	}

	/**
	 * e.g. "Selling @ 1,250,000 · 40/100": the price first, then compact filled/total, so at the panel's width the
	 * amounts (also in the tooltip) are cut off rather than the price.
	 */
	static String offerText(ActiveOffer o)
	{
		return (o.isBuy() ? "Buying @ " : "Selling @ ") + Format.gp(o.getPrice()) + " · "
			+ QuantityFormatter.quantityToStackSize(o.getFilled()) + "/" + QuantityFormatter.quantityToStackSize(o.getTotalQty());
	}

	/** The right-hand column of an offer line: how much has filled, e.g. "40%". */
	static String offerRight(ActiveOffer o)
	{
		return o.getTotalQty() <= 0 ? "" : (int) (100L * o.getFilled() / o.getTotalQty()) + "%";
	}

	/** The player's open offers for {@code itemId}, in slot order. */
	static List<ActiveOffer> offersFor(List<ActiveOffer> offers, int itemId)
	{
		List<ActiveOffer> out = new ArrayList<>();
		for (ActiveOffer o : offers)
		{
			if (o.getItemId() == itemId)
			{
				out.add(o);
			}
		}
		return out;
	}

	/** The player's trades of {@code itemId}, newest first as given. */
	static List<TradeEvent> tradesFor(List<TradeEvent> trades, int itemId)
	{
		List<TradeEvent> out = new ArrayList<>();
		for (TradeEvent e : trades)
		{
			if (e.getItemId() == itemId)
			{
				out.add(e);
			}
		}
		return out;
	}

	/** Which colour a stat cell's accent gets. */
	enum Accent
	{
		BUY, SELL, NEUTRAL
	}

	/** One cell of the stats grid. */
	@Value
	static class StatCell
	{
		String label;
		String value;
		/** Small note next to the value (e.g. "5m ago"), or null. */
		String sub;
		Accent accent;
		/** Tooltip, or null. */
		String tooltip;
	}

	static final String TYPICAL_LABEL = "Typical (confirmed)";

	/**
	 * The stats grid: last bought / sold, 24h averages, best bid / ask, plus "Typical (confirmed)" (the trusted
	 * median) when the server sends one. Older servers never do, so their grid is unchanged.
	 */
	static List<StatCell> statCells(MarketData.Summary s)
	{
		return statCells(s, Instant.now().getEpochSecond());
	}

	/**
	 * {@link #statCells(MarketData.Summary)} at {@code now} (unix seconds). The typical price's note says how old its
	 * newest confirmed trade is (the median can span the last 7 days) and how many players it comes from.
	 */
	static List<StatCell> statCells(MarketData.Summary s, long now)
	{
		List<StatCell> cells = new ArrayList<>();
		cells.add(new StatCell("Last bought", Format.gp(s.getLastBuy()), Format.ago(s.getLastBuyTime(), now), Accent.BUY, null));
		cells.add(new StatCell("Last sold", Format.gp(s.getLastSell()), Format.ago(s.getLastSellTime(), now), Accent.SELL, null));
		cells.add(new StatCell("Avg bought 24h", Format.gp(s.getAvgBuy24()), null, Accent.BUY, null));
		cells.add(new StatCell("Avg sold 24h", Format.gp(s.getAvgSell24()), null, Accent.SELL, null));
		cells.add(new StatCell("Best bid", Format.gp(s.getBestBid()), null, Accent.BUY, null));
		cells.add(new StatCell("Best ask", Format.gp(s.getBestAsk()), null, Accent.SELL, null));
		if (s.getTrustedPrice() != null)
		{
			Integer votes = s.getTrustedVotes();
			String players = votes == null ? null : votes + (votes == 1 ? " player" : " players");
			String age = Format.ago(s.getTrustedPriceTime(), now);
			String sub = age.isEmpty() ? players : players == null ? age : age + " · " + players;
			cells.add(new StatCell(TYPICAL_LABEL, Format.gp(s.getTrustedPrice()), sub, Accent.NEUTRAL,
				"Median price of confirmed trades of the last 7 days, one vote per player"
					+ (age.isEmpty() ? "" : "; the newest of them was " + ("now".equals(age) ? "just now" : age + " ago"))
					+ ". Confirmed trades come from established players at a normal price, or were matched by another "
					+ "player's report."));
		}
		return cells;
	}

	/** Tooltip for the 24h volume line: "X confirmed / Y unconfirmed", or null when the server didn't say. */
	static String volumeTooltip(MarketData.Summary s)
	{
		if (s.getConfVol24() == null || s.getUnconfVol24() == null)
		{
			return null;
		}
		return QuantityFormatter.formatNumber(s.getConfVol24()) + " confirmed / "
			+ QuantityFormatter.formatNumber(s.getUnconfVol24()) + " unconfirmed";
	}

	/** True only for a fill the server marked unconfirmed (never for older servers, which don't say). */
	static boolean unconfirmed(MarketData.Trade t)
	{
		return Boolean.FALSE.equals(t.getConfirmed());
	}

	/** Row identity for a trade of the player's: its event id, or its contents for old log lines without one. */
	static String tradeKey(TradeEvent e)
	{
		return e.getId() != null ? e.getId()
			: e.getKind() + "|" + e.getSide() + "|" + e.getQty() + "|" + e.getPrice() + "|" + e.getTs();
	}
}
