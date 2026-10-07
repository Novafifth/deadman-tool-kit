package com.deadmantoolkit.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import com.deadmantoolkit.ActiveOffer;
import com.deadmantoolkit.OfferReport;
import com.deadmantoolkit.OfferWatch;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class OfferLineTest
{
	private static final long PLACED = 1_000_000;
	private static final long NOW = PLACED + 3 * 3600;

	private static final ActiveOffer SELL = new ActiveOffer(2, "k2", 536, "Dragon bones", false, 1_250_000, 100, 40, PLACED);
	private static final ActiveOffer BUY = new ActiveOffer(5, "k5", 4151, "Abyssal whip", true, 2_000_000, 1, 0, PLACED);

	private static OfferWatch.OfferStatus status(String key, OfferWatch.Flag flag, Long competing, String detail, long idle)
	{
		return new OfferWatch.OfferStatus(key, flag, competing, detail, idle);
	}

	@Test
	public void homeTextAndPlainBadge()
	{
		OfferLine l = OfferLine.home(SELL, null, NOW);
		assertEquals("Sell 1.25M Dragon bones", l.getText());
		assertEquals("40%", l.getBadge());
		assertEquals(OfferLine.Badge.PLAIN, l.getKind());
		assertEquals("Sell 1.25M Dragon bones · 40/100 filled · placed 3h ago", l.getTooltip());
		assertEquals("2|k2", l.key());
	}

	@Test
	public void flags()
	{
		OfferLine under = OfferLine.home(SELL, status("k2", OfferWatch.Flag.UNDERCUT, 1_240_000L, "Lowest ask 1,240,000", 60), NOW);
		assertEquals("undercut", under.getBadge());
		assertEquals(OfferLine.Badge.ALERT, under.getKind());
		assertTrue(under.getTooltip().contains("Lowest ask 1,240,000"));

		OfferLine out = OfferLine.home(BUY, status("k5", OfferWatch.Flag.OUTBID, 2_100_000L, "Highest bid 2,100,000", 60), NOW);
		assertEquals("outbid", out.getBadge());
		assertEquals("Buy 2M Abyssal whip", out.getText());

		OfferLine stale = OfferLine.home(BUY, status("k5", OfferWatch.Flag.STALE, null, "No fill for 7h", 7 * 3600 + 59), NOW);
		assertEquals("stale 7h", stale.getBadge());
		assertEquals(OfferLine.Badge.STALE, stale.getKind());
	}

	@Test
	public void itemViewTextIsTheOfferLine()
	{
		OfferLine l = OfferLine.item(SELL, null, NOW);
		assertEquals("Selling @ 1,250,000 · 40/100", l.getText());
	}

	@Test
	public void justPlaced()
	{
		ActiveOffer o = new ActiveOffer(0, "k", 1, "Item", true, 5, 1, 0, NOW);
		assertTrue(OfferLine.home(o, null, NOW).getTooltip().endsWith("placed just now"));
	}

	@Test
	public void sectionLinesMatchStatusesByOfferAndHideThemWhenDisconnected()
	{
		Map<Integer, OfferWatch.OfferStatus> st = new HashMap<>();
		st.put(2, status("k2", OfferWatch.Flag.UNDERCUT, 1L, "x", 0));
		// A status for an older offer that used to be in slot 5 doesn't apply to the new one.
		st.put(5, status("old", OfferWatch.Flag.OUTBID, 1L, "y", 0));
		OfferReport report = new OfferReport(st, 1, null);
		List<OfferLine> lines = OffersSection.lines(Arrays.asList(SELL, BUY), report, true, NOW);
		assertEquals("undercut", lines.get(0).getBadge());
		assertEquals("0%", lines.get(1).getBadge());

		List<OfferLine> off = OffersSection.lines(Arrays.asList(SELL, BUY), report, false, NOW);
		assertEquals("40%", off.get(0).getBadge());
		assertTrue(OffersSection.lines(Collections.emptyList(), report, true, NOW).isEmpty());
	}
}
