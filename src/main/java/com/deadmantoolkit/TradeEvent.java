package com.deadmantoolkit;

import lombok.Builder;
import lombok.Value;

/**
 * One observed Grand Exchange event. Field names match the server's JSON contract.
 */
@Value
@Builder(toBuilder = true)
public class TradeEvent
{
	public static final String PLACED = "placed";
	public static final String FILL = "fill";
	public static final String CANCELLED = "cancelled";
	public static final String HISTORY = "history";
	/**
	 * A completed offer read (with the player's consent) from the trade history RuneLite's own Grand Exchange plugin
	 * keeps; see {@link RuneLiteImport}. Its time is only approximate: when RuneLite saw the offer complete.
	 */
	public static final String IMPORTED = "imported";

	public static final String BUY = "buy";
	public static final String SELL = "sell";

	/**
	 * Unique, stable id for this event (derived from the offer key, "hist:" plus a random UUID, or for imported trades
	 * "rl:" plus the RuneLite record's own fields), so retried uploads are de-duplicated server side.
	 */
	String id;
	String kind;
	/** {@link #BUY} or {@link #SELL}. */
	String side;
	int itemId;
	/** placed: total quantity; fill/history/imported: units traded; cancelled: units left unfilled. */
	int qty;
	/**
	 * Per-item price: offer price for placed/cancelled, actual average price for fill/history, and for imported the
	 * floor of the average (all RuneLite keeps).
	 */
	long price;
	/** Total gp that changed hands (fill/history; imported: price * qty, as RuneLite keeps no exact total). */
	Long total;
	Integer slot;
	String offerKey;
	/** Unix seconds when the event was observed. */
	long ts;
	/** True when the event happened while logged out (or before install) and ts is only when we noticed it. */
	boolean late;
	/** fill only: cumulative units filled on the offer after this event. */
	Integer filled;
	/** Late fills only: last time we saw this offer before the fill, so the server can pair it with the counterparty. */
	Long since;
	int world;
	/** Item name, for display in the panel and the local log. */
	String name;

	/**
	 * True for a completed trade (a live fill, a GE History row or an imported RuneLite record), as opposed to an offer
	 * placed or cancelled.
	 */
	public static boolean isTrade(String kind)
	{
		return FILL.equals(kind) || HISTORY.equals(kind) || IMPORTED.equals(kind);
	}

	static String sideOf(boolean buy)
	{
		return buy ? BUY : SELL;
	}

	/** Average per-item price of {@code qty} units that cost {@code total} gp altogether. */
	static long avgPrice(long total, int qty)
	{
		return Math.round((double) total / qty);
	}
}
