package com.deadmantoolkit;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Decides when an offer alert may notify (pure apart from its own state; thread safe). Only a change into UNDERCUT or
 * OUTBID notifies, the same offer and flag at most once per {@link #COOLDOWN_MS}, and any two alerts at least
 * {@link #GLOBAL_GAP_MS} apart.
 */
final class AlertGate
{
	static final long COOLDOWN_MS = 30 * 60_000L;
	static final long GLOBAL_GAP_MS = 60_000L;

	/** The last flag seen per offer key. */
	private final Map<String, OfferWatch.Flag> lastFlag = new HashMap<>();
	/** When "offerKey|flag" last notified (epoch ms). */
	private final Map<String, Long> lastFired = new HashMap<>();
	private long lastAny = Long.MIN_VALUE;

	/**
	 * Record the offer's current flag and say whether to notify now.
	 *
	 * @param nowMs epoch ms
	 */
	synchronized boolean shouldNotify(String offerKey, OfferWatch.Flag flag, long nowMs)
	{
		OfferWatch.Flag prev = lastFlag.put(offerKey, flag);
		if ((flag != OfferWatch.Flag.UNDERCUT && flag != OfferWatch.Flag.OUTBID) || flag == prev)
		{
			return false;
		}
		String key = offerKey + "|" + flag;
		Long fired = lastFired.get(key);
		if (fired != null && nowMs - fired < COOLDOWN_MS)
		{
			// Already told recently; this change is used up.
			return false;
		}
		if (lastAny != Long.MIN_VALUE && nowMs - lastAny < GLOBAL_GAP_MS)
		{
			// Too soon after another alert: forget we saw it, so the next check (a minute later) tries again.
			restore(offerKey, prev);
			return false;
		}
		lastFired.put(key, nowMs);
		lastAny = nowMs;
		return true;
	}

	/** Forget offers that are no longer open. */
	synchronized void retain(Collection<String> openOfferKeys)
	{
		Set<String> keep = new HashSet<>(openOfferKeys);
		lastFlag.keySet().retainAll(keep);
		lastFired.keySet().removeIf(k -> !keep.contains(k.substring(0, k.lastIndexOf('|'))));
	}

	private void restore(String offerKey, OfferWatch.Flag prev)
	{
		if (prev == null)
		{
			lastFlag.remove(offerKey);
		}
		else
		{
			lastFlag.put(offerKey, prev);
		}
	}
}
