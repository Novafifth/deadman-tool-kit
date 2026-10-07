package com.deadmantoolkit.world;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import com.deadmantoolkit.world.WorldEventMessage.Combat;
import com.deadmantoolkit.world.WorldEventMessage.Kind;
import org.junit.Test;

public class WorldEventMessageTest
{
	private static WorldEventMessage parse(String s)
	{
		return WorldEventMessage.parse(s).orElseThrow(() -> new AssertionError("not parsed: " + s));
	}

	@Test
	public void chestBroadcast()
	{
		WorldEventMessage m = parse("A Deadman's Chest has spawned at north of the Warriors' Guild!");
		assertEquals(Kind.CHEST, m.getKind());
		assertEquals("north of the Warriors' Guild", m.getLocation());
		assertNull(m.getCombat());
	}

	@Test
	public void tagsCurlyApostrophesAndCaseDontMatter()
	{
		WorldEventMessage m = parse("<col=ef1020>A deadman’s chest  has spawned at <col=ffffff>Zanaris</col>!</col>");
		assertEquals(Kind.CHEST, m.getKind());
		assertEquals("Zanaris", m.getLocation());
		assertEquals("Kingdom Of Asgarnia", parse("A Deadman's Chest has spawned at Kingdom Of Asgarnia.").getLocation());
	}

	@Test
	public void breachWithCombat()
	{
		WorldEventMessage m = parse("A breach has spawned at the Bone Yard! Powerful monsters now roam the area (Multi).");
		assertEquals(Kind.BREACH, m.getKind());
		assertEquals("the Bone Yard", m.getLocation());
		assertEquals(Combat.MULTI, m.getCombat());
		assertEquals(Combat.SINGLE,
			parse("A breach has spawned at Lumbridge! Powerful monsters now roam the area (Single).").getCombat());
		assertEquals(Combat.MULTI, parse("A breach has spawned at Lumbridge! It is multi-way combat.").getCombat());
	}

	@Test
	public void breachWithoutCombatOrExclamationMark()
	{
		WorldEventMessage m = parse("A breach has spawned at North of Asgarnia. Powerful monsters now roam the area.");
		assertEquals("North of Asgarnia", m.getLocation());
		assertNull(m.getCombat());
		m = parse("A breach has spawned at Varrock (Multi).");
		assertEquals("Varrock", m.getLocation());
		assertEquals(Combat.MULTI, m.getCombat());
	}

	@Test
	public void otherMessagesAreIgnored()
	{
		assertFalse(WorldEventMessage.parse("The next breach will appear in 2 hours, 5 minutes.").isPresent());
		assertFalse(WorldEventMessage.parse("Welcome to Old School RuneScape.").isPresent());
		assertFalse(WorldEventMessage.parse("A Deadman's Chest has spawned at !").isPresent());
		assertFalse(WorldEventMessage.parse(null).isPresent());
		StringBuilder long_ = new StringBuilder("A Deadman's Chest has spawned at ");
		for (int i = 0; i < 100; i++)
		{
			long_.append('x');
		}
		assertFalse("absurdly long place", WorldEventMessage.parse(long_ + "!").isPresent());
	}

	@Test
	public void gameTextRoundTrips()
	{
		for (Combat c : new Combat[]{null, Combat.SINGLE, Combat.MULTI})
		{
			WorldEventMessage m = parse(WorldEventMessage.gameText(Kind.BREACH, "south of Falador", c));
			assertEquals("south of Falador", m.getLocation());
			assertEquals(c, m.getCombat());
		}
		assertEquals("Zanaris", parse(WorldEventMessage.gameText(Kind.CHEST, "Zanaris", null)).getLocation());
	}
}
