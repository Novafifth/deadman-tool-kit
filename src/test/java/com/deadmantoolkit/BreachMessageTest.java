package com.deadmantoolkit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import java.time.Duration;
import java.util.Optional;
import org.junit.Test;

public class BreachMessageTest
{
	private static Optional<Duration> minutes(long m)
	{
		return Optional.of(Duration.ofMinutes(m));
	}

	@Test
	public void hoursAndMinutes()
	{
		assertEquals(minutes(65), BreachMessage.parse("The next breach will appear in 1 hour, 5 minutes."));
		assertEquals(minutes(121), BreachMessage.parse("The next breach will appear in 2 hours, 1 minute."));
		assertEquals(minutes(65), BreachMessage.parse("The next breach will appear in 1 hour and 5 minutes."));
		assertEquals(minutes(1985), BreachMessage.parse("The next breach will appear in 33 hours, 5 minutes."));
	}

	@Test
	public void missingPart()
	{
		assertEquals(minutes(45), BreachMessage.parse("The next breach will appear in 45 minutes."));
		assertEquals(minutes(60), BreachMessage.parse("The next breach will appear in 1 hour."));
		assertEquals(minutes(1), BreachMessage.parse("The next breach will appear in 1 minute."));
	}

	@Test
	public void tagsAndCase()
	{
		assertEquals(minutes(65), BreachMessage.parse("<col=ef1020>The next breach will appear in 1 hour, 5 minutes.</col>"));
		assertEquals(minutes(65), BreachMessage.parse("the NEXT breach will appear in 1 HOUR, 5 MINUTES"));
	}

	@Test
	public void lessThanAMinute()
	{
		assertEquals(minutes(1), BreachMessage.parse("The next breach will appear in less than a minute."));
	}

	@Test
	public void unrelated()
	{
		assertFalse(BreachMessage.parse("Welcome to Old School RuneScape.").isPresent());
		assertFalse(BreachMessage.parse("The next breach will appear soon.").isPresent());
		assertFalse(BreachMessage.parse(null).isPresent());
		assertFalse(BreachMessage.parse("The next breach will appear in 99999999999999999999 hours.").isPresent());
		assertFalse(BreachMessage.parse("The next breach will appear in 500 hours.").isPresent());
	}
}
