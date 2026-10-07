package com.deadmantoolkit;

import net.runelite.client.util.QuantityFormatter;
import net.runelite.client.util.Text;

/**
 * The Deadman price line in the GE offer setup's description text (pure).
 * <p>
 * The game builds that text in a script that hands it to RuneLite's "geBuyExamineText" / "geSellExamineText"
 * callbacks before setting it: examine text, then (when there is one) the fee line last, with an info icon placed
 * after the last line. Core's GE plugin appends its own lines before the fee. This adds one line marked
 * {@link #MARKER}, also before the fee, and removes any earlier one first, so applying it twice never duplicates it
 * and nothing else in the text changes.
 */
final class GePriceText
{
	static final String MARKER = "<col=ff981f>DMM</col>";
	private static final String BR = "<br>";
	/** Visible characters a line may have before the bid / ask part is dropped. */
	static final int MAX_VISIBLE = 60;

	private GePriceText()
	{
	}

	/**
	 * {@code out} with {@code line} added: before {@code fee} when the text ends with it (so the fee stays last and its
	 * icon stays in place), otherwise as a new last line. An earlier DMM line is replaced.
	 *
	 * @param out  the text the script will set
	 * @param fee  the fee text the script got, or empty
	 * @param line from {@link #line}
	 */
	static String insert(String out, String fee, String line)
	{
		String base = strip(out == null ? "" : out);
		if (fee != null && !fee.isEmpty() && base.endsWith(fee))
		{
			return base.substring(0, base.length() - fee.length()) + line + BR + fee;
		}
		return base.isEmpty() ? line : base + BR + line;
	}

	/** {@code text} without any DMM line (and the line break that introduced it). */
	static String strip(String text)
	{
		String s = text;
		int at;
		while ((at = s.indexOf(MARKER)) >= 0)
		{
			int end = s.indexOf(BR, at);
			boolean afterBreak = at >= BR.length() && s.startsWith(BR, at - BR.length());
			if (afterBreak)
			{
				// "...<br>DMM ...<br>rest" -> "...<br>rest"; "...<br>DMM ..." -> "..."
				s = s.substring(0, at - BR.length()) + (end < 0 ? "" : s.substring(end));
			}
			else
			{
				// The DMM line is first: drop it and its trailing break.
				s = s.substring(0, at) + (end < 0 ? "" : s.substring(end + BR.length()));
			}
		}
		return s;
	}

	/** The line for an item's market summary, e.g. "DMM last 1.25M/1.24M · bid 1.23M ask 1.26M · 523/24h". */
	static String line(MarketData.Summary s)
	{
		return line(s, null);
	}

	/**
	 * {@link #line(MarketData.Summary)} plus, when {@code limitReset} is set, when your GE buy limit for the item
	 * resets, e.g. "... · limit resets in 2h 14m".
	 *
	 * @param limitReset the time left ({@link BuyLimitReset#remaining}), or null to leave it out
	 */
	static String line(MarketData.Summary s, String limitReset)
	{
		if (s == null)
		{
			return MARKER + " no trades yet" + limitPart(limitReset, false);
		}
		return line(s.getLastBuy(), s.getLastSell(), s.getBestBid(), s.getBestAsk(), s.getVol24(), limitReset);
	}

	static String line(Long lastBuy, Long lastSell, Long bestBid, Long bestAsk, long vol24)
	{
		return line(lastBuy, lastSell, bestBid, bestAsk, vol24, null);
	}

	/**
	 * The line stays within {@link #MAX_VISIBLE} visible characters: when it would be longer, the bid / ask part goes
	 * first, then the buy limit part gets its short form ("limit 2h 14m").
	 */
	static String line(Long lastBuy, Long lastSell, Long bestBid, Long bestAsk, long vol24, String limitReset)
	{
		if (lastBuy == null && lastSell == null && bestBid == null && bestAsk == null && vol24 <= 0)
		{
			return MARKER + " no trades yet" + limitPart(limitReset, false);
		}
		String last = " last " + gp(lastBuy) + "/" + gp(lastSell);
		String book = " · bid " + gp(bestBid) + " ask " + gp(bestAsk);
		String vol = " · " + QuantityFormatter.quantityToStackSize(Math.max(0, vol24)) + "/24h";
		String limit = limitPart(limitReset, false);
		String full = MARKER + last + book + vol + limit;
		if (fits(full))
		{
			return full;
		}
		String noBook = MARKER + last + vol + limit;
		if (fits(noBook) || limitReset == null)
		{
			return noBook;
		}
		return MARKER + last + vol + limitPart(limitReset, true);
	}

	/** " · limit resets in 2h 14m" (short: " · limit 2h 14m"), or empty without a reset time. */
	static String limitPart(String limitReset, boolean shortForm)
	{
		if (limitReset == null || limitReset.isEmpty())
		{
			return "";
		}
		return (shortForm ? " · limit " : " · limit resets in ") + limitReset;
	}

	private static boolean fits(String line)
	{
		return Text.removeTags(line).length() <= MAX_VISIBLE;
	}

	/** Compact gp ("1.25M", "9,999"), or "-" when unknown. */
	static String gp(Long v)
	{
		return v == null ? "-" : QuantityFormatter.quantityToStackSize(v);
	}
}
