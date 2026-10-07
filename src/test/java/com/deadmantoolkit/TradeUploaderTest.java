package com.deadmantoolkit;

import static org.junit.Assert.assertEquals;
import com.deadmantoolkit.OfferTracker.SlotState;
import com.deadmantoolkit.OfferTracker.Status;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;

/**
 * Pins the submit request body, which is the server's contract.
 */
public class TradeUploaderTest
{
	private final Gson gson = new Gson();

	private static final String OFFER_JSON = "{\"slot\":2,\"offerKey\":\"k1\",\"side\":\"sell\",\"itemId\":4151,\"price\":300000,"
		+ "\"totalQty\":10,\"filledQty\":3,\"placedAt\":100}";

	private static SlotState slot()
	{
		return new SlotState("k1", 4151, false, 300_000, 10, 3, 900_000, Status.ACTIVE, 100, 150);
	}

	@Test
	public void offerJson()
	{
		assertEquals(OFFER_JSON, gson.toJson(TradeUploader.offerJson(2, slot())));
	}

	@Test
	public void submitBody()
	{
		TradeEvent ev = TradeEvent.builder()
			.id("k1:placed")
			.kind(TradeEvent.PLACED)
			.side(TradeEvent.SELL)
			.itemId(4151)
			.qty(10)
			.price(300_000)
			.slot(2)
			.offerKey("k1")
			.ts(100)
			.late(false)
			.world(345)
			.name("Abyssal whip")
			.build();
		TradeUploader.ItemInfo whip = new TradeUploader.ItemInfo(4151, "Abyssal whip");
		whip.icon = "AAAA";
		TradeUploader.ItemInfo shark = new TradeUploader.ItemInfo(385, "Shark");

		JsonObject body = TradeUploader.buildBody(gson, "install-1", new TradeUploader.BatchKey(345, null), 1234, Collections.singletonList(ev),
			Collections.singletonList(TradeUploader.offerJson(2, slot())), Arrays.asList(whip, shark));

		assertEquals("{\"installId\":\"install-1\",\"world\":345,\"sentAt\":1234,\"events\":[{\"id\":\"k1:placed\","
			+ "\"kind\":\"placed\",\"side\":\"sell\",\"itemId\":4151,\"qty\":10,\"price\":300000,\"slot\":2,\"offerKey\":\"k1\","
			+ "\"ts\":100,\"late\":false,\"world\":345,\"name\":\"Abyssal whip\"}],\"offers\":[" + OFFER_JSON + "],"
			+ "\"items\":[{\"id\":4151,\"name\":\"Abyssal whip\",\"icon\":\"AAAA\"},{\"id\":385,\"name\":\"Shark\"}]}",
			gson.toJson(body));
	}

	@Test
	public void submitBodyWithoutOpenOffers()
	{
		JsonObject body = TradeUploader.buildBody(gson, "install-1", new TradeUploader.BatchKey(345, null), 1234, Collections.emptyList(), null,
			Collections.emptyList());
		assertEquals("{\"installId\":\"install-1\",\"world\":345,\"sentAt\":1234,\"events\":[],\"items\":[]}", gson.toJson(body));
	}

	@Test
	public void submitBodyAppendsAccountIdLast()
	{
		JsonObject body = TradeUploader.buildBody(gson, "install-1", new TradeUploader.BatchKey(345, "abc123"), 1234,
			Collections.emptyList(), null, Collections.emptyList());
		assertEquals("{\"installId\":\"install-1\",\"world\":345,\"sentAt\":1234,\"events\":[],\"items\":[],"
			+ "\"accountId\":\"abc123\"}", gson.toJson(body));
	}

	@Test
	public void itemLimitOnlyWhenKnownAndLast()
	{
		TradeUploader.ItemInfo whip = new TradeUploader.ItemInfo(4151, "Abyssal whip", 70);
		whip.icon = "AAAA";
		TradeUploader.ItemInfo shark = new TradeUploader.ItemInfo(385, "Shark", 0);
		TradeUploader.ItemInfo bones = new TradeUploader.ItemInfo(536, "Dragon bones", 7500);
		JsonObject body = TradeUploader.buildBody(gson, "install-1", new TradeUploader.BatchKey(345, null), 1234,
			Collections.emptyList(), null, Arrays.asList(whip, shark, bones));
		assertEquals("{\"installId\":\"install-1\",\"world\":345,\"sentAt\":1234,\"events\":[],\"items\":["
			+ "{\"id\":4151,\"name\":\"Abyssal whip\",\"icon\":\"AAAA\",\"limit\":70},"
			+ "{\"id\":385,\"name\":\"Shark\"},"
			+ "{\"id\":536,\"name\":\"Dragon bones\",\"limit\":7500}]}", gson.toJson(body));
	}
}
