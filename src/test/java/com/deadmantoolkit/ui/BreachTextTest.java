package com.deadmantoolkit.ui;

import static org.junit.Assert.assertEquals;
import com.deadmantoolkit.BreachSchedule;
import com.deadmantoolkit.BreachSchedule.Phase;
import com.deadmantoolkit.BreachSchedule.State;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.Test;

public class BreachTextTest
{
	private static final Instant NOW = Instant.parse("2026-07-02T21:48:00Z");
	private static final State NEXT = new State(Phase.UPCOMING, Instant.parse("2026-07-04T02:00:00Z"));

	@Test
	public void upcomingInLocalTime()
	{
		assertEquals("Next breach in 1d 4h 12m (Sat 02:00)", BreachText.of(NEXT, NOW, ZoneOffset.UTC));
		// British Summer Time.
		assertEquals("Next breach in 1d 4h 12m (Sat 03:00)", BreachText.of(NEXT, NOW, ZoneId.of("Europe/London")));
		assertEquals("Next breach in 1d 4h 12m (Fri 22:00)", BreachText.of(NEXT, NOW, ZoneId.of("America/New_York")));
	}

	@Test
	public void upcomingFromTheSchedule()
	{
		Instant now = Instant.parse("2026-10-09T23:00:00Z");
		assertEquals("Next breach in 3h 0m (Sat 02:00)", BreachText.of(BreachSchedule.state(now, null), now, ZoneOffset.UTC));
	}

	@Test
	public void clocks()
	{
		Instant now = Instant.parse("2026-10-10T02:03:05Z");
		assertEquals("Breach active, spawning ends in 11:55", BreachText.of(BreachSchedule.state(now, null), now, ZoneOffset.UTC));
		Instant later = Instant.parse("2026-10-10T02:44:59Z");
		assertEquals("Breach bosses despawn in ~00:01", BreachText.of(BreachSchedule.state(later, null), later, ZoneOffset.UTC));
		assertEquals("00:00", BreachText.clock(Duration.ofSeconds(-5)));
		assertEquals("30:00", BreachText.clock(Duration.ofMinutes(30)));
	}

	@Test
	public void countdowns()
	{
		assertEquals("<1m", BreachText.countdown(Duration.ofSeconds(59)));
		assertEquals("<1m", BreachText.countdown(Duration.ofSeconds(-3)));
		assertEquals("1m", BreachText.countdown(Duration.ofSeconds(60)));
		assertEquals("59m", BreachText.countdown(Duration.ofMinutes(59).plusSeconds(59)));
		assertEquals("1h 0m", BreachText.countdown(Duration.ofHours(1)));
		assertEquals("1d 0h 5m", BreachText.countdown(Duration.ofDays(1).plusMinutes(5)));
		assertEquals("6d 23h 59m", BreachText.countdown(Duration.ofDays(7).minusSeconds(1)));
	}
}
