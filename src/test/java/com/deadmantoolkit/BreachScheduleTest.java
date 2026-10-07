package com.deadmantoolkit;

import static org.junit.Assert.assertEquals;
import com.deadmantoolkit.BreachSchedule.Phase;
import com.deadmantoolkit.BreachSchedule.State;
import java.time.Duration;
import java.time.Instant;
import org.junit.Test;

public class BreachScheduleTest
{
	// 2026-10-09 is a Friday, 10th Saturday, 11th Sunday, 12th Monday.
	private static Instant t(String iso)
	{
		return Instant.parse(iso);
	}

	@Test
	public void fridayNightGivesSaturday0200()
	{
		assertEquals(t("2026-10-10T02:00:00Z"), BreachSchedule.nextStart(t("2026-10-09T23:00:00Z")));
		assertEquals(new State(Phase.UPCOMING, t("2026-10-10T02:00:00Z")), BreachSchedule.state(t("2026-10-09T23:00:00Z"), null));
	}

	@Test
	public void phasesAroundAStart()
	{
		assertEquals(new State(Phase.ACTIVE, t("2026-10-10T02:15:00Z")), BreachSchedule.state(t("2026-10-10T02:00:00Z"), null));
		assertEquals(new State(Phase.ACTIVE, t("2026-10-10T02:15:00Z")), BreachSchedule.state(t("2026-10-10T02:14:59Z"), null));
		assertEquals(new State(Phase.DESPAWN, t("2026-10-10T02:45:00Z")), BreachSchedule.state(t("2026-10-10T02:15:00Z"), null));
		assertEquals(new State(Phase.DESPAWN, t("2026-10-10T02:45:00Z")), BreachSchedule.state(t("2026-10-10T02:44:59Z"), null));
		assertEquals(new State(Phase.UPCOMING, t("2026-10-10T06:00:00Z")), BreachSchedule.state(t("2026-10-10T02:45:00Z"), null));
		// Just before: still upcoming.
		assertEquals(new State(Phase.UPCOMING, t("2026-10-10T02:00:00Z")), BreachSchedule.state(t("2026-10-10T01:59:59Z"), null));
	}

	@Test
	public void sundayEveningAndAfter()
	{
		assertEquals(new State(Phase.ACTIVE, t("2026-10-11T22:15:00Z")), BreachSchedule.state(t("2026-10-11T22:10:00Z"), null));
		assertEquals(new State(Phase.DESPAWN, t("2026-10-11T22:45:00Z")), BreachSchedule.state(t("2026-10-11T22:30:00Z"), null));
		assertEquals(new State(Phase.UPCOMING, t("2026-10-17T02:00:00Z")), BreachSchedule.state(t("2026-10-11T23:00:00Z"), null));
	}

	@Test
	public void weekdaysGiveNextSaturday()
	{
		for (int day = 12; day <= 16; day++)
		{
			for (int hour : new int[]{0, 2, 12, 22, 23})
			{
				Instant now = t(String.format("2026-10-%02dT%02d:30:00Z", day, hour));
				assertEquals(now.toString(), new State(Phase.UPCOMING, t("2026-10-17T02:00:00Z")), BreachSchedule.state(now, null));
			}
		}
	}

	@Test
	public void twelveStartsPerWeek()
	{
		Instant now = t("2026-10-12T00:00:00Z");
		Instant end = now.plus(Duration.ofDays(7));
		int count = 0;
		for (Instant s = BreachSchedule.nextStart(now); s.isBefore(end); s = BreachSchedule.nextStart(s))
		{
			count++;
			assertEquals(s, BreachSchedule.lastStart(s));
			assertEquals(s, BreachSchedule.lastStart(s.plusSeconds(3599)));
		}
		assertEquals(12, count);
	}

	@Test
	public void correctionOverridesUpcomingThenExpires()
	{
		Instant now = t("2026-10-10T03:00:00Z");
		// The game says 04:30, off schedule: trusted.
		Instant corr = BreachSchedule.correct(now, Duration.ofMinutes(90));
		assertEquals(t("2026-10-10T04:30:00Z"), corr);
		assertEquals(new State(Phase.UPCOMING, corr), BreachSchedule.state(now, corr));
		assertEquals(new State(Phase.ACTIVE, t("2026-10-10T04:45:00Z")), BreachSchedule.state(t("2026-10-10T04:31:00Z"), corr));
		assertEquals(new State(Phase.DESPAWN, t("2026-10-10T05:15:00Z")), BreachSchedule.state(t("2026-10-10T05:00:00Z"), corr));
		// After its 45 minutes the schedule takes over again.
		assertEquals(new State(Phase.UPCOMING, t("2026-10-10T06:00:00Z")), BreachSchedule.state(t("2026-10-10T05:15:00Z"), corr));
	}

	@Test
	public void correctionSnapsToANearbyScheduledStart()
	{
		// "1 hour, 5 minutes" at 00:55:40 predicts 02:00:40; the game rounds, so it's the 02:00 breach.
		assertEquals(t("2026-10-10T02:00:00Z"), BreachSchedule.correct(t("2026-10-10T00:55:40Z"), Duration.ofMinutes(65)));
		assertEquals(t("2026-10-10T02:00:00Z"), BreachSchedule.correct(t("2026-10-10T01:59:30Z"), Duration.ofMinutes(1)));
		// Ten minutes off is a real change.
		assertEquals(t("2026-10-10T02:10:00Z"), BreachSchedule.correct(t("2026-10-10T01:00:00Z"), Duration.ofMinutes(70)));
	}

	@Test
	public void correctionInThePastIsIgnored()
	{
		Instant corr = t("2026-10-09T10:00:00Z");
		assertEquals(new State(Phase.UPCOMING, t("2026-10-10T02:00:00Z")), BreachSchedule.state(t("2026-10-09T23:00:00Z"), corr));
	}
}
