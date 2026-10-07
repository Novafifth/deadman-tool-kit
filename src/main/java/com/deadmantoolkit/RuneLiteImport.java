package com.deadmantoolkit;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.Value;

/**
 * Imports the trade history that RuneLite's own Grand Exchange plugin keeps, when the player asks for it. Pure: the
 * plugin reads the config value on the client thread and does everything here off it.
 * <p>
 * RuneLite's GrandExchangePlugin keeps, in the RS-profile config of each account (group {@value #CONFIG_GROUP}, key
 * {@value #CONFIG_KEY}), a JSON list of {@code {b: buy, i: item id, q: units filled, p: price, t: epoch ms}}: one
 * record per completed offer, written when the offer completes, or when it is cancelled after partly filling. {@code p}
 * is the gp spent divided by {@code q} with integer division (the floor of the average), and {@code t} is when RuneLite
 * saw the completion: if that happened while logged out, it is the next login, so times are approximate. RuneLite
 * keeps at most 1024 records and 365 days.
 * <p>
 * Each record becomes one {@link TradeEvent#IMPORTED} event with the deterministic id {@link #id}, so importing twice
 * (or uploading twice) adds nothing. Records the plugin already has are skipped ({@link #plan}).
 */
public final class RuneLiteImport
{
	/** RuneLite's Grand Exchange plugin's config group. Only ever read for the current RS profile. */
	public static final String CONFIG_GROUP = "grandexchange";
	public static final String CONFIG_KEY = "tradeHistory";
	/**
	 * RuneLite's DEADMAN profile type is shared by permanent world 345 and the seasonal Deadman events. The last
	 * seasonal event (Deadman: Annihilation) ran 30 Jan - 20 Feb 2026, and its stats were transferred to world 345
	 * afterwards. Records before this instant may belong to the seasonal economy, so they are ignored (the server
	 * refuses them too).
	 */
	public static final Instant CUTOFF = Instant.parse("2026-03-01T00:00:00Z");
	/** A live offer that completed (last fill or cancellation) within this many seconds of a record's time is it. */
	static final long MATCH_WINDOW_SECONDS = 600;
	static final String ID_PREFIX = "rl:";
	private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("MMM d", Locale.ENGLISH);
	private static final DateTimeFormatter DAY_YEAR = DateTimeFormatter.ofPattern("MMM d yyyy", Locale.ENGLISH);

	private RuneLiteImport()
	{
	}

	/** One record of RuneLite's trade history. */
	@Value
	static class Record
	{
		boolean buy;
		int itemId;
		int qty;
		/** Floor of the average price. */
		long price;
		/** Epoch milliseconds: when RuneLite saw the offer complete. */
		long time;

		/** The record's time in unix seconds. */
		long ts()
		{
			return Math.floorDiv(time, 1000L);
		}
	}

	/**
	 * The records in RuneLite's {@code tradeHistory} value. Lenient: anything that isn't a list gives none, and entries
	 * with a missing or impossible field (no item, no units, a price below 1, no time) are skipped.
	 */
	static List<Record> parse(Gson gson, String json)
	{
		List<Record> out = new ArrayList<>();
		if (json == null || json.isEmpty())
		{
			return out;
		}
		JsonElement root;
		try
		{
			root = gson.fromJson(json, JsonElement.class);
		}
		catch (RuntimeException ex)
		{
			return out;
		}
		if (root == null || !root.isJsonArray())
		{
			return out;
		}
		for (JsonElement el : root.getAsJsonArray())
		{
			Record r = record(el);
			if (r != null)
			{
				out.add(r);
			}
		}
		return out;
	}

	private static Record record(JsonElement el)
	{
		if (el == null || !el.isJsonObject())
		{
			return null;
		}
		JsonObject o = el.getAsJsonObject();
		Boolean buy = bool(o.get("b"));
		Long item = number(o.get("i")), qty = number(o.get("q")), price = number(o.get("p")), time = number(o.get("t"));
		if (buy == null || item == null || qty == null || price == null || time == null)
		{
			return null;
		}
		if (item <= 0 || item > Integer.MAX_VALUE || qty <= 0 || qty > Integer.MAX_VALUE || price < 1
			|| price > Integer.MAX_VALUE || time <= 0)
		{
			return null;
		}
		return new Record(buy, item.intValue(), qty.intValue(), price, time);
	}

	private static Boolean bool(JsonElement e)
	{
		if (e == null || !e.isJsonPrimitive())
		{
			return null;
		}
		JsonPrimitive p = e.getAsJsonPrimitive();
		return p.isBoolean() ? p.getAsBoolean() : null;
	}

	/** A whole number, or null (not a number, fractional, or out of range). */
	private static Long number(JsonElement e)
	{
		if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber())
		{
			return null;
		}
		try
		{
			return e.getAsJsonPrimitive().getAsBigDecimal().longValueExact();
		}
		catch (ArithmeticException | NumberFormatException ex)
		{
			return null;
		}
	}

	/** The record's own deterministic event id, e.g. {@code rl:1791300000000:4151:1:1500000:true}. */
	static String id(Record r)
	{
		return ID_PREFIX + r.getTime() + ":" + r.getItemId() + ":" + r.getQty() + ":" + r.getPrice() + ":" + r.isBuy();
	}

	/** The event for a record: kind imported, total = price * qty, the record's time (approximate), always late. */
	static TradeEvent toEvent(Record r, int world)
	{
		return TradeEvent.builder()
			.id(id(r))
			.kind(TradeEvent.IMPORTED)
			.side(TradeEvent.sideOf(r.isBuy()))
			.itemId(r.getItemId())
			.qty(r.getQty())
			.price(r.getPrice())
			.total(r.getPrice() * r.getQty())
			.ts(r.ts())
			.late(true)
			.world(world)
			.build();
	}

	/** True for a record before {@link #CUTOFF}. */
	static boolean beforeCutoff(Record r)
	{
		return r.getTime() < CUTOFF.toEpochMilli();
	}

	/** What an import adds, and how many records it skipped. */
	@Value
	static class Plan
	{
		/** New imported events, oldest first. */
		List<TradeEvent> add;
		/** Records the plugin already has (imported before, recorded live or read from GE History). */
		int already;
		/** Records before {@link #CUTOFF}, ignored. */
		int beforeCutoff;
		/** The newest record time (epoch ms) at or after the cutoff, or the old watermark when there is none. */
		long newestMs;
	}

	/** One of your live offers, from its fills (and cancellation), as RuneLite would have recorded it. */
	private static final class Offer
	{
		boolean buy;
		int itemId;
		int qty;
		long total;
		/** When it completed: its last fill or its cancellation (unix s). */
		long doneTs;
		boolean used;
	}

	/** A GE History row the plugin has. */
	private static final class Row
	{
		final boolean buy;
		final int itemId;
		final int qty;
		final long total;
		/** When the GE History list was read (unix s): the trade happened before then. */
		final long readTs;
		boolean used;

		Row(boolean buy, int itemId, int qty, long total, long readTs)
		{
			this.buy = buy;
			this.itemId = itemId;
			this.qty = qty;
			this.total = total;
			this.readTs = readTs;
		}
	}

	/**
	 * Which records to add. A record is already there when:
	 * <ul>
	 * <li>its time is at or before {@code watermarkMs} (a previous import looked at everything up to then);</li>
	 * <li>an imported event with its {@link #id} exists;</li>
	 * <li>the fills of one live offer add up to it (same item and side, the same units, a total within {@code q} gp
	 * of {@code p * q}, since p is the floor of the average) and that offer completed within
	 * {@link #MATCH_WINDOW_SECONDS} of its time; or</li>
	 * <li>a GE History row has the same side, item, units and a total within {@code q} gp, and was read after the
	 * record's time (give or take {@link #MATCH_WINDOW_SECONDS}; a row's only time is when the list was read).</li>
	 * </ul>
	 * Each offer and each GE History row matches at most one record; records are matched oldest first, each with the
	 * live offer closest in time.
	 *
	 * @param records     RuneLite's records, any order
	 * @param known       the events the plugin has for this account (duplicates are fine)
	 * @param watermarkMs the newest record time a previous import looked at, or 0
	 */
	static Plan plan(List<Record> records, Collection<TradeEvent> known, long watermarkMs, int world)
	{
		Set<String> importedIds = new HashSet<>();
		Map<String, Offer> offers = new LinkedHashMap<>();
		List<Row> rows = new ArrayList<>();
		Set<String> seen = new HashSet<>();
		for (TradeEvent e : known)
		{
			if (e == null || e.getKind() == null || !seen.add(knownKey(e)))
			{
				continue;
			}
			boolean buy = TradeEvent.BUY.equals(e.getSide());
			long total = e.getTotal() != null ? e.getTotal() : e.getPrice() * e.getQty();
			switch (e.getKind())
			{
				case TradeEvent.IMPORTED:
					if (e.getId() != null)
					{
						importedIds.add(e.getId());
					}
					break;
				case TradeEvent.HISTORY:
					rows.add(new Row(buy, e.getItemId(), e.getQty(), total, e.getTs()));
					break;
				case TradeEvent.FILL:
				case TradeEvent.CANCELLED:
				{
					String key = e.getOfferKey() != null ? "o:" + e.getOfferKey() : "e:" + knownKey(e);
					Offer o = offers.get(key);
					if (o == null)
					{
						o = new Offer();
						o.buy = buy;
						o.itemId = e.getItemId();
						offers.put(key, o);
					}
					if (TradeEvent.FILL.equals(e.getKind()))
					{
						o.qty += e.getQty();
						o.total += total;
					}
					o.doneTs = Math.max(o.doneTs, e.getTs());
					break;
				}
				default:
					break;
			}
		}

		List<Record> sorted = new ArrayList<>(records);
		sorted.sort((a, b) -> Long.compare(a.getTime(), b.getTime()));
		List<TradeEvent> add = new ArrayList<>();
		int already = 0, early = 0;
		long newest = watermarkMs;
		for (Record r : sorted)
		{
			if (beforeCutoff(r))
			{
				early++;
				continue;
			}
			newest = Math.max(newest, r.getTime());
			String id = id(r);
			if (r.getTime() <= watermarkMs || importedIds.contains(id) || matchOffer(r, offers.values()) || matchRow(r, rows))
			{
				already++;
				continue;
			}
			importedIds.add(id);
			add.add(toEvent(r, world));
		}
		return new Plan(Collections.unmodifiableList(add), already, early, newest);
	}

	/** Identity of a known event, so the same event from the log and from memory counts once. */
	private static String knownKey(TradeEvent e)
	{
		return e.getId() != null ? e.getId()
			: e.getKind() + "|" + e.getSide() + "|" + e.getItemId() + "|" + e.getQty() + "|" + e.getPrice() + "|" + e.getTs();
	}

	/** {@code total} is what {@code q} units at the floored average price {@code p} can have cost. */
	static boolean totalMatches(long total, Record r)
	{
		return Math.abs(total - r.getPrice() * r.getQty()) <= r.getQty();
	}

	private static boolean matchOffer(Record r, Collection<Offer> offers)
	{
		Offer best = null;
		long bestDt = Long.MAX_VALUE;
		for (Offer o : offers)
		{
			if (o.used || o.buy != r.isBuy() || o.itemId != r.getItemId() || o.qty != r.getQty() || !totalMatches(o.total, r))
			{
				continue;
			}
			long dt = Math.abs(o.doneTs - r.ts());
			if (dt <= MATCH_WINDOW_SECONDS && dt < bestDt)
			{
				best = o;
				bestDt = dt;
			}
		}
		if (best == null)
		{
			return false;
		}
		best.used = true;
		return true;
	}

	/**
	 * A GE History row is read after its trade, so a record can only be a row read at or after its time (give or
	 * take {@link #MATCH_WINDOW_SECONDS}); of those, the earliest read is taken.
	 */
	private static boolean matchRow(Record r, List<Row> rows)
	{
		Row best = null;
		for (Row row : rows)
		{
			if (!row.used && row.buy == r.isBuy() && row.itemId == r.getItemId() && row.qty == r.getQty()
				&& totalMatches(row.total, r) && r.ts() <= row.readTs + MATCH_WINDOW_SECONDS
				&& (best == null || row.readTs < best.readTs))
			{
				best = row;
			}
		}
		if (best == null)
		{
			return false;
		}
		best.used = true;
		return true;
	}

	/* ------------------------------------------- GE History rows read after an import */

	/**
	 * How many of the newest imported trades are remembered (per account) so that a GE History row read later for
	 * the same trade isn't recorded a second time. The GE History tab lists the last 20 trades.
	 */
	static final int MAX_RECENT = 50;

	/** A remembered imported trade: "side|item|qty|price|ts". */
	static String recentEntry(TradeEvent e)
	{
		return e.getSide() + "|" + e.getItemId() + "|" + e.getQty() + "|" + e.getPrice() + "|" + e.getTs();
	}

	/** {@code recent} plus the {@code added} imported trades, keeping the newest {@link #MAX_RECENT} by time. */
	static List<String> remember(List<String> recent, List<TradeEvent> added)
	{
		List<String> all = new ArrayList<>();
		for (String s : recent)
		{
			if (parseRecent(s) != null)
			{
				all.add(s);
			}
		}
		for (TradeEvent e : added)
		{
			all.add(recentEntry(e));
		}
		all.sort((a, b) -> Long.compare(parseRecent(a)[4], parseRecent(b)[4]));
		return new ArrayList<>(all.subList(Math.max(0, all.size() - MAX_RECENT), all.size()));
	}

	/**
	 * Whether a new GE History event {@code row} (its ts is when the list was read) is one of the {@code recent}
	 * imported trades: same side, item and units, a total within {@code q} gp of {@code p * q}, and imported with a
	 * time before the read (give or take {@link #MATCH_WINDOW_SECONDS}). The oldest such entry is removed from
	 * {@code recent}, so each matches one row.
	 */
	static boolean consumeRecent(List<String> recent, TradeEvent row)
	{
		long total = row.getTotal() != null ? row.getTotal() : row.getPrice() * row.getQty();
		int best = -1;
		long bestTs = Long.MAX_VALUE;
		for (int i = 0; i < recent.size(); i++)
		{
			long[] f = parseRecent(recent.get(i));
			if (f == null || (f[0] == 1) != TradeEvent.BUY.equals(row.getSide()) || f[1] != row.getItemId()
				|| f[2] != row.getQty() || Math.abs(total - f[3] * f[2]) > f[2] || f[4] > row.getTs() + MATCH_WINDOW_SECONDS)
			{
				continue;
			}
			if (f[4] < bestTs)
			{
				best = i;
				bestTs = f[4];
			}
		}
		if (best < 0)
		{
			return false;
		}
		recent.remove(best);
		return true;
	}

	/** {buy ? 1 : 0, item, qty, price, ts}, or null when malformed. */
	private static long[] parseRecent(String s)
	{
		String[] p = s == null ? new String[0] : s.split("\\|");
		if (p.length != 5 || (!TradeEvent.BUY.equals(p[0]) && !TradeEvent.SELL.equals(p[0])))
		{
			return null;
		}
		try
		{
			return new long[]{TradeEvent.BUY.equals(p[0]) ? 1 : 0, Long.parseLong(p[1]), Long.parseLong(p[2]),
				Long.parseLong(p[3]), Long.parseLong(p[4])};
		}
		catch (NumberFormatException ex)
		{
			return null;
		}
	}

	/**
	 * Why an import can't run now, or null when it can. Without the local trade log, the plugin can't see the trades
	 * it recorded in earlier sessions (already counted for profit and uploaded), so it would import them again.
	 */
	static String blocked(boolean onDeadman345, boolean localLog)
	{
		if (!onDeadman345)
		{
			return NEEDS_LOGIN;
		}
		return localLog ? null : NEEDS_LOCAL_LOG;
	}

	/**
	 * The earliest log month that can hold an event matching these records: a month before the oldest record (a
	 * live fill may be a little earlier than RuneLite's time). Null when there are no records.
	 */
	static YearMonth firstMonth(List<Record> records)
	{
		long min = Long.MAX_VALUE;
		for (Record r : records)
		{
			min = Math.min(min, r.ts());
		}
		return min == Long.MAX_VALUE ? null : LocalTradeLog.monthOf(min).minusMonths(1);
	}

	/**
	 * The result line, e.g. "Imported 812 trades (Jul 29 - Oct 7) - 210 already recorded".
	 *
	 * @param zone the time zone for the dates
	 */
	static String message(Plan plan, ZoneId zone)
	{
		int n = plan.getAdd().size();
		StringBuilder sb = new StringBuilder();
		if (n > 0)
		{
			sb.append("Imported ").append(n).append(n == 1 ? " trade" : " trades").append(" (")
				.append(range(plan.getAdd(), zone)).append(')');
		}
		else
		{
			if (plan.getAlready() == 0 && plan.getBeforeCutoff() == 0)
			{
				return EMPTY;
			}
			sb.append(plan.getAlready() > 0 ? "No new trades" : "No trades to import");
		}
		if (plan.getAlready() > 0)
		{
			sb.append(" - ").append(plan.getAlready()).append(" already recorded");
		}
		if (plan.getBeforeCutoff() > 0)
		{
			sb.append(" - ").append(plan.getBeforeCutoff()).append(" from before ")
				.append(DAY_YEAR.format(CUTOFF.atZone(ZoneOffset.UTC))).append(" ignored");
		}
		return sb.toString();
	}

	/** "Jul 29 - Oct 7", "Oct 7" for one day, with years when they differ. Events must not be empty. */
	static String range(List<TradeEvent> events, ZoneId zone)
	{
		long min = Long.MAX_VALUE, max = Long.MIN_VALUE;
		for (TradeEvent e : events)
		{
			min = Math.min(min, e.getTs());
			max = Math.max(max, e.getTs());
		}
		LocalDate from = Instant.ofEpochSecond(min).atZone(zone).toLocalDate();
		LocalDate to = Instant.ofEpochSecond(max).atZone(zone).toLocalDate();
		if (from.equals(to))
		{
			return DAY.format(from);
		}
		DateTimeFormatter f = from.getYear() == to.getYear() ? DAY : DAY_YEAR;
		return f.format(from) + " - " + f.format(to);
	}

	/** Shown when the import can't run now. */
	static final String NEEDS_LOGIN = "Log in on world 345 (Deadman) first.";
	static final String ACCOUNT_CHANGED = "The account changed during the import; nothing was added. Try again.";
	static final String STOPPED = "The plugin was stopped; nothing was added.";
	static final String FAILED = "Import failed; nothing was added. See the client log.";
	static final String NEEDS_LOCAL_LOG = "Turn on 'Save local trade log' to import: without it the plugin can't tell"
		+ " which trades it already has.";
	/** Nothing at all in RuneLite's history (its GE plugin is off, was never used on this account, or unreadable). */
	static final String EMPTY = "RuneLite's Grand Exchange plugin has no trade history for this account yet.";
}
