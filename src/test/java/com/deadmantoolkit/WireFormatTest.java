package com.deadmantoolkit;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import com.deadmantoolkit.OfferTracker.SlotState;
import com.deadmantoolkit.OfferTracker.Status;
import com.google.gson.Gson;
import java.util.Arrays;
import org.junit.Test;

/**
 * Pins the JSON written to trades.jsonl, uploaded to the server and saved in the RuneLite profile, so a refactor
 * can't silently rename or reorder anything.
 */
public class WireFormatTest
{
	private final Gson gson = new Gson();

	private static TradeEvent fullEvent()
	{
		return TradeEvent.builder()
			.id("k1:fill:3")
			.kind(TradeEvent.FILL)
			.side("buy")
			.itemId(4151)
			.qty(3)
			.price(290_000)
			.total(870_000L)
			.slot(2)
			.offerKey("k1")
			.ts(1000)
			.late(true)
			.filled(3)
			.since(900L)
			.world(345)
			.name("Abyssal whip")
			.build();
	}

	@Test
	public void tradeEventJson()
	{
		assertEquals("{\"id\":\"k1:fill:3\",\"kind\":\"fill\",\"side\":\"buy\",\"itemId\":4151,\"qty\":3,\"price\":290000,"
			+ "\"total\":870000,\"slot\":2,\"offerKey\":\"k1\",\"ts\":1000,\"late\":true,\"filled\":3,\"since\":900,"
			+ "\"world\":345,\"name\":\"Abyssal whip\"}", gson.toJson(fullEvent()));
	}

	@Test
	public void tradeEventOmitsNulls()
	{
		TradeEvent ev = TradeEvent.builder()
			.id("hist:x")
			.kind(TradeEvent.HISTORY)
			.side("sell")
			.itemId(385)
			.qty(500)
			.price(900)
			.ts(2000)
			.late(false)
			.world(345)
			.build();
		assertEquals("{\"id\":\"hist:x\",\"kind\":\"history\",\"side\":\"sell\",\"itemId\":385,\"qty\":500,\"price\":900,"
			+ "\"ts\":2000,\"late\":false,\"world\":345}", gson.toJson(ev));
	}

	@Test
	public void kindValues()
	{
		assertEquals("placed", TradeEvent.PLACED);
		assertEquals("fill", TradeEvent.FILL);
		assertEquals("cancelled", TradeEvent.CANCELLED);
		assertEquals("history", TradeEvent.HISTORY);
	}

	@Test
	public void tradeLogLineRoundTrip()
	{
		String line = "{\"id\":\"k1:fill:3\",\"kind\":\"fill\",\"side\":\"buy\",\"itemId\":4151,\"qty\":3,\"price\":290000,"
			+ "\"total\":870000,\"slot\":2,\"offerKey\":\"k1\",\"ts\":1000,\"late\":true,\"filled\":3,\"since\":900,"
			+ "\"world\":345,\"name\":\"Abyssal whip\"}";
		assertEquals(fullEvent(), gson.fromJson(line, TradeEvent.class));
	}

	@Test
	public void localLogLineAddsAcctLast()
	{
		String line = gson.toJson(DeadmanToolKitPlugin.logLine(gson, fullEvent(), "abc"));
		assertEquals("{\"id\":\"k1:fill:3\",\"kind\":\"fill\",\"side\":\"buy\",\"itemId\":4151,\"qty\":3,\"price\":290000,"
			+ "\"total\":870000,\"slot\":2,\"offerKey\":\"k1\",\"ts\":1000,\"late\":true,\"filled\":3,\"since\":900,"
			+ "\"world\":345,\"name\":\"Abyssal whip\",\"acct\":\"abc\"}", line);
		// Reading it back ignores acct.
		assertEquals(fullEvent(), gson.fromJson(line, TradeEvent.class));
		// Unknown account: just the event.
		assertEquals(gson.toJson(fullEvent()), gson.toJson(DeadmanToolKitPlugin.logLine(gson, fullEvent(), null)));
	}

	@Test
	public void slotStateJson()
	{
		SlotState s = new SlotState("k1", 4151, true, 300_000, 10, 3, 870_000, Status.ACTIVE, 100, 150);
		String json = gson.toJson(s);
		assertEquals("{\"offerKey\":\"k1\",\"itemId\":4151,\"buy\":true,\"price\":300000,\"totalQty\":10,\"sold\":3,"
			+ "\"spent\":870000,\"status\":\"ACTIVE\",\"placedAt\":100,\"seenAt\":150}", json);
		assertEquals(s, gson.fromJson(json, SlotState.class));
	}

	@Test
	public void persistedSignatures()
	{
		assertEquals("buy|4151|10|2950000", OfferTracker.signature(true, 4151, 10, 2_950_000));
		assertEquals("sell|385|500|450000", OfferTracker.signature(false, 385, 500, 450_000));
	}

	@Test
	public void statusNames()
	{
		assertArrayEquals(new String[]{"EMPTY", "ACTIVE", "COMPLETE", "CANCELLED"},
			Arrays.stream(Status.values()).map(Enum::name).toArray(String[]::new));
	}
}
