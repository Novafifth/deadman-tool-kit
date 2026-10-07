package com.deadmantoolkit;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import com.deadmantoolkit.OfferWatch.Flag;
import java.util.Collections;
import org.junit.Test;

public class AlertGateTest
{
	private static final long MIN = 60_000;
	private final AlertGate gate = new AlertGate();

	@Test
	public void onlyTransitionsIntoUndercutOrOutbid()
	{
		assertFalse(gate.shouldNotify("a", Flag.OK, 0));
		assertFalse(gate.shouldNotify("a", Flag.STALE, MIN));
		assertTrue(gate.shouldNotify("a", Flag.UNDERCUT, 2 * MIN));
		// Still undercut: no repeat.
		assertFalse(gate.shouldNotify("a", Flag.UNDERCUT, 3 * MIN));
		assertFalse(gate.shouldNotify("a", Flag.UNDERCUT, 60 * MIN));
	}

	@Test
	public void cooldownPerOfferAndFlag()
	{
		assertTrue(gate.shouldNotify("a", Flag.UNDERCUT, 0));
		assertFalse(gate.shouldNotify("a", Flag.OK, 2 * MIN));
		// Undercut again within 30 minutes: quiet, and that change is used up.
		assertFalse(gate.shouldNotify("a", Flag.UNDERCUT, 10 * MIN));
		assertFalse(gate.shouldNotify("a", Flag.UNDERCUT, 40 * MIN));
		// Cleared and undercut again after the cooldown: notifies.
		assertFalse(gate.shouldNotify("a", Flag.OK, 41 * MIN));
		assertTrue(gate.shouldNotify("a", Flag.UNDERCUT, 42 * MIN));
	}

	@Test
	public void globalGapRetriesLater()
	{
		assertTrue(gate.shouldNotify("a", Flag.UNDERCUT, 0));
		// Another offer within a minute: held back, but not forgotten.
		assertFalse(gate.shouldNotify("b", Flag.OUTBID, 30_000));
		assertTrue(gate.shouldNotify("b", Flag.OUTBID, MIN + 1));
	}

	@Test
	public void resetWhenOfferGoes()
	{
		assertTrue(gate.shouldNotify("a", Flag.UNDERCUT, 0));
		gate.retain(Collections.singletonList("b"));
		// "a" was forgotten, so the cooldown no longer applies to it.
		assertTrue(gate.shouldNotify("a", Flag.UNDERCUT, 2 * MIN));
	}
}
