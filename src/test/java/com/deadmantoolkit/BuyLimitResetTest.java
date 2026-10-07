package com.deadmantoolkit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import java.time.Duration;
import java.time.Instant;
import org.junit.Test;

public class BuyLimitResetTest
{
	private static final Instant NOW = Instant.parse("2026-10-07T12:00:00Z");

	@Test
	public void keyAndValue()
	{
		assertEquals("buylimit_4151", BuyLimitReset.key(4151));
		// RuneLite's ConfigManager stores an Instant as its ISO text.
		assertEquals(Instant.parse("2026-10-07T14:14:30.123Z"), BuyLimitReset.parse("2026-10-07T14:14:30.123Z"));
		assertEquals(Instant.ofEpochMilli(1791300000000L), BuyLimitReset.parse("1791300000000"));
		assertNull(BuyLimitReset.parse(null));
		assertNull(BuyLimitReset.parse(""));
		assertNull(BuyLimitReset.parse("soon"));
		assertNull(BuyLimitReset.parse("99999999999999999999999"));
	}

	@Test
	public void leftOutWhileRuneLitesGePluginShowsIt()
	{
		Instant in2h14 = NOW.plus(Duration.ofMinutes(134));
		// Defaults (no values saved): the GE plugin is on and shows "Buy limit: N (2:14)" itself.
		assertTrue(BuyLimitReset.coreShowsReset(null, null));
		assertTrue(BuyLimitReset.coreShowsReset("true", "true"));
		assertNull(BuyLimitReset.forGeLine(true, null, null, in2h14, NOW));
		// The GE plugin off, or its reset timer off: the DMM line shows it.
		assertFalse(BuyLimitReset.coreShowsReset("false", null));
		assertFalse(BuyLimitReset.coreShowsReset(null, "false"));
		assertEquals("2h 14m", BuyLimitReset.forGeLine(true, "false", null, in2h14, NOW));
		assertEquals("2h 14m", BuyLimitReset.forGeLine(true, "true", " false ", in2h14, NOW));
		// Never on a sell offer, nor when no reset is coming.
		assertNull(BuyLimitReset.forGeLine(false, "false", "false", in2h14, NOW));
		assertNull(BuyLimitReset.forGeLine(true, "false", "false", NOW.minusSeconds(1), NOW));
	}

	@Test
	public void remainingOnlyWhileInTheFuture()
	{
		assertEquals("2h 14m", BuyLimitReset.remaining(NOW.plus(Duration.ofMinutes(134)).plusSeconds(59), NOW));
		assertEquals("4h 0m", BuyLimitReset.remaining(NOW.plus(Duration.ofHours(4)), NOW));
		assertEquals("14m", BuyLimitReset.remaining(NOW.plus(Duration.ofMinutes(14)), NOW));
		assertEquals("<1m", BuyLimitReset.remaining(NOW.plusSeconds(59), NOW));
		assertNull(BuyLimitReset.remaining(NOW, NOW));
		assertNull(BuyLimitReset.remaining(NOW.minusSeconds(1), NOW));
		assertNull(BuyLimitReset.remaining(null, NOW));
		assertEquals(NOW.plusSeconds(1), BuyLimitReset.future(NOW.plusSeconds(1), NOW));
		assertNull(BuyLimitReset.future(NOW, NOW));
	}
}
