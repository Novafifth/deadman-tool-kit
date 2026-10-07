package com.deadmantoolkit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.runelite.api.events.CommandExecuted;
import org.junit.Test;

/** The developer-mode ::breach preview: command parsing, clock offsets and the command handler. */
public class BreachPreviewTest
{
	/** A Wednesday: no breach for days. */
	private static final Instant WED = Instant.parse("2026-10-07T12:34:56Z");
	/** A Saturday during a real breach (14:00 UTC start). */
	private static final Instant SAT = Instant.parse("2026-10-10T14:05:00Z");

	private static Optional<BreachPreview.Command> parse(String... args)
	{
		return BreachPreview.parse(args);
	}

	@Test
	public void parsesCommands()
	{
		assertEquals(BreachPreview.Action.SOON, parse("soon").get().getAction());
		assertEquals(BreachPreview.Action.ACTIVE, parse("ACTIVE").get().getAction());
		assertEquals(BreachPreview.Action.DESPAWN, parse("despawn").get().getAction());
		assertEquals(BreachPreview.Action.OFF, parse("off").get().getAction());
		assertEquals(new BreachPreview.Command(BreachPreview.Action.MSG, 1, 5), parse("msg", "1", "5").get());
		assertEquals(new BreachPreview.Command(BreachPreview.Action.MSG, 0, 0), parse("msg", "0", "0").get());
		assertFalse(parse().isPresent());
		assertFalse(BreachPreview.parse(null).isPresent());
		assertFalse(parse("later").isPresent());
		assertFalse(parse("soon", "now").isPresent());
		assertFalse(parse("msg", "1").isPresent());
		assertFalse(parse("msg", "1", "x").isPresent());
		assertFalse(parse("msg", "-1", "5").isPresent());
		assertFalse(parse("msg", "1000", "5").isPresent());
	}

	private static BreachSchedule.State shifted(BreachPreview.Action a, Instant real)
	{
		Duration off = BreachPreview.offset(a, real, Duration.ZERO);
		return BreachSchedule.state(real.plus(off), null);
	}

	@Test
	public void offsetsPutTheRealScheduleWhereAsked()
	{
		for (Instant real : new Instant[]{WED, SAT})
		{
			Duration soon = BreachPreview.offset(BreachPreview.Action.SOON, real, Duration.ZERO);
			BreachSchedule.State s = BreachSchedule.state(real.plus(soon), null);
			assertEquals(BreachSchedule.Phase.UPCOMING, s.getPhase());
			assertEquals(Duration.ofMinutes(2), Duration.between(real.plus(soon), s.getAt()));
			// ...and two minutes later it is active, then despawning: the banner runs through its phases on its own.
			assertEquals(BreachSchedule.Phase.ACTIVE, BreachSchedule.state(real.plus(soon).plus(Duration.ofMinutes(2)), null).getPhase());

			BreachSchedule.State active = shifted(BreachPreview.Action.ACTIVE, real);
			assertEquals(BreachSchedule.Phase.ACTIVE, active.getPhase());
			assertEquals(BreachSchedule.SPAWN, Duration.between(real.plus(BreachPreview.offset(BreachPreview.Action.ACTIVE,
				real, Duration.ZERO)), active.getAt()));

			Duration d = BreachPreview.offset(BreachPreview.Action.DESPAWN, real, Duration.ZERO);
			BreachSchedule.State despawn = BreachSchedule.state(real.plus(d), null);
			assertEquals(BreachSchedule.Phase.DESPAWN, despawn.getPhase());
			assertEquals(BreachSchedule.DESPAWN, Duration.between(real.plus(d), despawn.getAt()));
		}
		Duration current = Duration.ofHours(5);
		assertEquals(Duration.ZERO, BreachPreview.offset(BreachPreview.Action.OFF, WED, current));
		assertEquals(current, BreachPreview.offset(BreachPreview.Action.MSG, WED, current));
	}

	@Test
	public void msgFeedsTheParserInTheGamesWords()
	{
		assertEquals("The next breach will appear in 1 hour, 5 minutes.", BreachPreview.gameMessage(1, 5));
		assertEquals("The next breach will appear in 2 hours, 1 minute.", BreachPreview.gameMessage(2, 1));
		assertEquals(Optional.of(Duration.ofMinutes(65)), BreachMessage.parse(BreachPreview.gameMessage(1, 5)));
		assertEquals(Optional.of(Duration.ofMinutes(121)), BreachMessage.parse(BreachPreview.gameMessage(2, 1)));
		assertEquals(Optional.of(Duration.ZERO), BreachMessage.parse(BreachPreview.gameMessage(0, 0)));
	}

	@Test
	public void commandHandler()
	{
		List<String> chat = new ArrayList<>();
		List<String> fed = new ArrayList<>();
		Duration[] offset = {Duration.ZERO};
		BreachCommands commands = new BreachCommands(new BreachCommands.Target()
		{
			@Override
			public Duration offset()
			{
				return offset[0];
			}

			@Override
			public void preview(Duration o)
			{
				offset[0] = o;
			}

			@Override
			public void gameMessage(String gameText)
			{
				fed.add(gameText);
			}
		}, chat::add, () -> WED);

		commands.onCommandExecuted(new CommandExecuted("other", new String[]{"soon"}));
		assertTrue(chat.isEmpty());

		commands.onCommandExecuted(new CommandExecuted("breach", new String[]{"soon"}));
		assertEquals(BreachPreview.offset(BreachPreview.Action.SOON, WED, Duration.ZERO), offset[0]);
		assertEquals("Breach preview: next breach starts in 2 minutes.", chat.get(0));

		commands.onCommandExecuted(new CommandExecuted("breach", new String[]{"msg", "1", "5"}));
		assertEquals(1, fed.size());
		assertEquals("The next breach will appear in 1 hour, 5 minutes.", fed.get(0));
		// msg only feeds the parser; the clock stays where it was.
		assertEquals(BreachPreview.offset(BreachPreview.Action.SOON, WED, Duration.ZERO), offset[0]);

		commands.onCommandExecuted(new CommandExecuted("breach", new String[]{"off"}));
		assertEquals(Duration.ZERO, offset[0]);
		assertEquals("Breach preview off: real clock and schedule.", chat.get(chat.size() - 1));

		commands.onCommandExecuted(new CommandExecuted("breach", new String[]{"bogus"}));
		assertEquals(BreachPreview.USAGE, chat.get(chat.size() - 1));
	}
}
