package com.deadmantoolkit;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Keeps your open offers compared with the market: fetches quotes for their items (at most once a minute on its own,
 * or every {@link #DEMAND_MS} when asked), evaluates them ({@link OfferWatch}) and reports the result, plus an alert
 * text when an offer just got undercut or outbid ({@link AlertGate}).
 * <p>
 * Thread safe: offers arrive from the client thread, the periodic check from the executor, the refresh button from
 * the EDT and responses on an OkHttp thread. Nothing here blocks; listeners must hand work off to their own thread.
 */
class OfferMarketWatch
{
	/** Automatic checks at most this often. */
	static final long AUTO_MS = 60_000;
	/** Checks asked for (refresh button, new item in your offers) at most this often. */
	static final long DEMAND_MS = 10_000;

	/** Fetches item summaries; callbacks may run on any thread. */
	interface Fetcher
	{
		void items(Collection<Integer> ids, boolean fresh, Consumer<MarketData.ItemsResponse> ok, Consumer<String> err);
	}

	/** Both are called on whatever thread produced the result, and must only hand the work off (never block). */
	interface Listener
	{
		/** New statuses (also when the offers changed, evaluated with the quotes already held). */
		void onReport(OfferReport report);

		/** An offer was just undercut or outbid and the alert gate allows notifying. */
		void onAlert(String message);
	}

	private final Fetcher fetcher;
	private final LongSupplier clockMs;
	private final BooleanSupplier connected;
	private final BooleanSupplier notifyEnabled;
	/** Whether the side panel is shown; with notifications off, nothing else uses a check. */
	private final BooleanSupplier panelShown;
	private final LongSupplier staleSecs;
	private final Supplier<List<TradeEvent>> myTrades;
	private final Listener listener;
	private final AlertGate gate = new AlertGate();

	// Guarded by this.
	private List<ActiveOffer> offers = Collections.emptyList();
	private Map<Integer, OfferWatch.Quote> quotes = Collections.emptyMap();
	private long lastRequest = Long.MIN_VALUE;
	private boolean inFlight;
	/** A check was asked for but rate limited; the next periodic call makes it. */
	private boolean demandPending;
	private long checkedAt;
	private String error;
	/** Bumped by {@link #reset}, so responses to older requests are dropped. */
	private int generation;

	OfferMarketWatch(Fetcher fetcher, LongSupplier clockMs, BooleanSupplier connected, BooleanSupplier notifyEnabled,
		BooleanSupplier panelShown, LongSupplier staleSecs, Supplier<List<TradeEvent>> myTrades, Listener listener)
	{
		this.fetcher = fetcher;
		this.clockMs = clockMs;
		this.connected = connected;
		this.notifyEnabled = notifyEnabled;
		this.panelShown = panelShown;
		this.staleSecs = staleSecs;
		this.myTrades = myTrades;
		this.listener = listener;
	}

	/** Your open offers changed. A new item among them asks for a check; otherwise the held quotes are reused. */
	void setOffers(List<ActiveOffer> next)
	{
		boolean newItems;
		synchronized (this)
		{
			newItems = !itemIds(offers).containsAll(itemIds(next));
			offers = next;
			List<String> keys = new ArrayList<>(next.size());
			next.forEach(o -> keys.add(o.getOfferKey()));
			gate.retain(keys);
		}
		if (!(newItems && inUse() && refresh(true)))
		{
			reevaluate();
		}
	}

	/** A check's result is only used by the shown panel or by notifications. */
	private boolean inUse()
	{
		return notifyEnabled.getAsBoolean() || panelShown.getAsBoolean();
	}

	/**
	 * Check the market for your offers' items, unless rate limited, not connected, or there are no offers. Automatic
	 * checks are also skipped while nothing would use them (panel hidden and notifications off).
	 *
	 * @param onDemand asked for (button, new item): allowed every {@link #DEMAND_MS} instead of {@link #AUTO_MS}
	 * @return true if a request was started
	 */
	boolean refresh(boolean onDemand)
	{
		Set<Integer> ids;
		int gen;
		synchronized (this)
		{
			if (!connected.getAsBoolean() || offers.isEmpty())
			{
				demandPending = false;
				return false;
			}
			if (inFlight)
			{
				demandPending |= onDemand;
				return false;
			}
			long now = clockMs.getAsLong();
			long since = lastRequest == Long.MIN_VALUE ? Long.MAX_VALUE : now - lastRequest;
			if (onDemand || demandPending)
			{
				if (since < DEMAND_MS)
				{
					demandPending = true;
					return false;
				}
			}
			else if (since < AUTO_MS || !inUse())
			{
				return false;
			}
			demandPending = false;
			inFlight = true;
			lastRequest = now;
			ids = itemIds(offers);
			gen = generation;
		}
		fetcher.items(ids, onDemand, r -> onResponse(gen, ids, r), err -> onError(gen, err));
		return true;
	}

	/** Re-evaluate with the quotes held (offers, a fill, or the stale setting changed) and report. */
	void reevaluate()
	{
		report(false);
	}

	/** Forget quotes and pending checks (connection or account changed), then report. */
	void reset()
	{
		synchronized (this)
		{
			generation++;
			quotes = Collections.emptyMap();
			inFlight = false;
			demandPending = false;
			lastRequest = Long.MIN_VALUE;
			checkedAt = 0;
			error = null;
		}
		report(false);
	}

	private void onResponse(int gen, Set<Integer> ids, MarketData.ItemsResponse r)
	{
		synchronized (this)
		{
			if (gen != generation)
			{
				return;
			}
			inFlight = false;
			quotes = OfferQuotes.filter(r, ids);
			checkedAt = clockMs.getAsLong();
			error = null;
		}
		report(true);
	}

	private void onError(int gen, String err)
	{
		synchronized (this)
		{
			if (gen != generation)
			{
				return;
			}
			inFlight = false;
			error = err;
		}
		report(false);
	}

	/** @param alerts after a fresh check: let undercut / outbid changes notify */
	private void report(boolean alerts)
	{
		List<ActiveOffer> current;
		Map<Integer, OfferWatch.OfferStatus> statuses;
		synchronized (this)
		{
			current = offers;
			boolean on = connected.getAsBoolean();
			statuses = on && !current.isEmpty()
				? OfferWatch.evaluate(current, quotes, myTrades.get(), clockMs.getAsLong() / 1000, staleSecs.getAsLong())
				: Collections.emptyMap();
			OfferReport report = new OfferReport(Collections.unmodifiableMap(statuses), checkedAt, on ? error : null);
			// Inside the lock so reports reach the listener in the order they were made (it only hands them off).
			listener.onReport(report);
		}
		if (!alerts || !notifyEnabled.getAsBoolean())
		{
			return;
		}
		long now = clockMs.getAsLong();
		for (ActiveOffer o : current)
		{
			OfferWatch.OfferStatus s = statuses.get(o.getSlot());
			if (s != null && Objects.equals(o.getOfferKey(), s.getOfferKey()) && gate.shouldNotify(o.getOfferKey(), s.getFlag(), now))
			{
				String msg = OfferWatch.alertMessage(o, s);
				if (msg != null)
				{
					listener.onAlert(msg);
				}
			}
		}
	}

	private static Set<Integer> itemIds(List<ActiveOffer> offers)
	{
		Set<Integer> ids = new TreeSet<>();
		offers.forEach(o -> ids.add(o.getItemId()));
		return ids;
	}
}
