package com.deadmantoolkit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import com.deadmantoolkit.world.WorldEventMessage;
import java.util.Optional;
import org.junit.Test;

public class DevCommandsTest
{
	private static Optional<DevCommands.Action> action(String... args)
	{
		return DevCommands.parse(args).map(DevCommands.Command::getAction);
	}

	private static String gameText(String... args)
	{
		return DevCommands.parse(args).map(DevCommands.Command::getGameText).orElseThrow(AssertionError::new);
	}

	@Test
	public void freshPreview()
	{
		assertEquals(Optional.of(DevCommands.Action.FRESH_ON), action("fresh"));
		assertEquals(Optional.of(DevCommands.Action.FRESH_ON), action("FRESH", "on"));
		assertEquals(Optional.of(DevCommands.Action.FRESH_OFF), action("fresh", "Off"));
	}

	@Test
	public void hints()
	{
		assertEquals(Optional.of(DevCommands.Action.HINTS), action("hints"));
	}

	@Test
	public void worldEvents()
	{
		WorldEventMessage chest = WorldEventMessage.parse(gameText("chest", "north", "of", "the", "Warriors'", "Guild")).get();
		assertEquals(WorldEventMessage.Kind.CHEST, chest.getKind());
		assertEquals("north of the Warriors' Guild", chest.getLocation());

		WorldEventMessage breach = WorldEventMessage.parse(gameText("breach", "the", "Bone", "Yard", "MULTI")).get();
		assertEquals("the Bone Yard", breach.getLocation());
		assertEquals(WorldEventMessage.Combat.MULTI, breach.getCombat());
		// A one-word place that happens to be a combat word is still the place.
		assertEquals("Single", WorldEventMessage.parse(gameText("breach", "Single")).get().getLocation());

		assertEquals(Optional.of(DevCommands.Action.EVENTS_SAMPLE), action("events", "sample"));
		assertEquals(Optional.of(DevCommands.Action.EVENTS_CLEAR), action("events", "CLEAR"));
		for (String s : DevCommands.SAMPLE)
		{
			assertTrue(s, WorldEventMessage.parse(s).isPresent());
		}
	}

	@Test
	public void rejectsAnythingElse()
	{
		assertFalse(action().isPresent());
		assertFalse(DevCommands.parse(null).isPresent());
		assertFalse(action("fresh", "maybe").isPresent());
		assertFalse(action("hints", "now").isPresent());
		assertFalse(action("fresh", "off", "now").isPresent());
		assertFalse(action("breach").isPresent());
		assertFalse(action("chest").isPresent());
		assertFalse(action("events").isPresent());
		assertFalse(action("events", "maybe").isPresent());
	}
}
