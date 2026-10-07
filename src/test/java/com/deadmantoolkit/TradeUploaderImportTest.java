package com.deadmantoolkit;

import static com.deadmantoolkit.FakeServer.REGISTER;
import static com.deadmantoolkit.FakeServer.SUBMIT;
import static com.deadmantoolkit.FakeServer.awaitIdle;
import static com.deadmantoolkit.FakeServer.json;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;

/** Imported trades upload at most 100 per request, and what the server defers stays queued instead of being lost. */
public class TradeUploaderImportTest
{
	private final Gson gson = new Gson();
	private final FakeServer server = new FakeServer();
	private final Map<String, String> config = new ConcurrentHashMap<>();
	private final InstallIdentity identity = FakeServer.identity(config);
	private final AtomicLong now = new AtomicLong(1_791_300_000L);

	private TradeUploader uploader()
	{
		TradeUploader up = new TradeUploader(server.client(), gson, FakeServer.BASE, ids ->
		{
		}, identity, () -> true);
		up.setClock(now::get);
		return up;
	}

	private static TradeEvent imported(int i)
	{
		return RuneLiteImport.toEvent(new RuneLiteImport.Record(true, 4151, 1, 100 + i, 1_790_000_000_000L + i), 345);
	}

	private static TradeEvent fill(String id)
	{
		return TradeEvent.builder().id(id).kind(TradeEvent.FILL).side(TradeEvent.BUY).itemId(4151).qty(1)
			.price(1).total(1L).ts(1).world(345).build();
	}

	private static List<String> ids(FakeServer.Seen s)
	{
		List<String> out = new ArrayList<>();
		for (JsonElement e : s.json().getAsJsonArray("events"))
		{
			out.add(e.getAsJsonObject().get("id").getAsString());
		}
		return out;
	}

	private FakeServer.Seen nextSubmit() throws InterruptedException
	{
		FakeServer.Seen s = server.next();
		while (REGISTER.equals(s.path()))
		{
			s = server.next();
		}
		assertEquals(SUBMIT, s.path());
		return s;
	}

	private static String deferred(List<String> ids, long retryAfter)
	{
		JsonArray arr = new JsonArray();
		ids.forEach(arr::add);
		return "{\"ok\":true,\"accepted\":0,\"rejected\":0,\"paired\":0,\"deferred\":" + ids.size() + ",\"deferredIds\":"
			+ arr + (retryAfter > 0 ? ",\"retryAfter\":" + retryAfter : "") + "}";
	}

	@Test
	public void atMostOneHundredImportedPerRequest() throws InterruptedException
	{
		TradeUploader up = uploader();
		for (int i = 0; i < 150; i++)
		{
			up.add(imported(i), "acct");
		}
		up.add(fill("live"), "acct");
		up.flush();
		FakeServer.Seen first = nextSubmit();
		List<String> sent = ids(first);
		// 100 imported plus the live fill behind them; the other 50 wait for the next request.
		assertEquals(101, sent.size());
		assertTrue(sent.contains("live"));
		assertEquals(100, sent.stream().filter(id -> id.startsWith("rl:")).count());
		awaitIdle(up);
		assertEquals(50, up.queued());

		up.flush();
		assertEquals(50, ids(nextSubmit()).size());
		awaitIdle(up);
		assertEquals(0, up.queued());
		assertFalse(up.hasPending());
	}

	@Test
	public void deferredIdsStayQueuedAndRetryAfterHoldsImportsOnly() throws InterruptedException
	{
		TradeUploader up = uploader();
		List<TradeEvent> events = new ArrayList<>();
		for (int i = 0; i < 5; i++)
		{
			events.add(imported(i));
			up.add(imported(i), "acct");
		}
		up.add(fill("live1"), "acct");
		// The server stored 3 and deferred 2 (its daily allowance ran out); retry in an hour.
		List<String> late = Arrays.asList(events.get(3).getId(), events.get(4).getId());
		server.on(SUBMIT, json(200, deferred(late, 3600)));
		up.flush();
		assertEquals(6, ids(nextSubmit()).size());
		awaitIdle(up);
		assertEquals(2, up.queued());
		assertEquals(now.get() + 3600, up.importsHeldUntil());

		// Held: imported events aren't sent, and with only them queued there is nothing to send.
		assertFalse(up.hasPending());
		up.flush();
		assertNull(server.poll(200));

		// Live events still go, without the held imports.
		up.add(fill("live2"), "acct");
		up.flush();
		assertEquals(Collections.singletonList("live2"), ids(nextSubmit()));
		awaitIdle(up);

		// After the hold, the deferred ones go again.
		now.addAndGet(3600);
		assertTrue(up.hasPending());
		up.flush();
		assertEquals(late, ids(nextSubmit()));
		awaitIdle(up);
		assertEquals(0, up.queued());
	}

	@Test
	public void deferredWithoutRetryAfterGoesInTheNextRequest() throws InterruptedException
	{
		TradeUploader up = uploader();
		up.add(imported(1), "acct");
		up.add(imported(2), "acct");
		server.on(SUBMIT, json(200, deferred(Collections.singletonList(imported(2).getId()), 0)));
		up.flush();
		assertEquals(2, ids(nextSubmit()).size());
		awaitIdle(up);
		assertEquals(1, up.queued());
		assertEquals(0, up.importsHeldUntil());
		assertTrue(up.hasPending());
		up.flush();
		assertEquals(Collections.singletonList(imported(2).getId()), ids(nextSubmit()));
		awaitIdle(up);
		assertEquals(0, up.queued());
	}

	@Test
	public void deferredParsing()
	{
		assertTrue(TradeUploader.Deferred.parse(gson, null).ids.isEmpty());
		assertTrue(TradeUploader.Deferred.parse(gson, "").ids.isEmpty());
		assertTrue(TradeUploader.Deferred.parse(gson, "{\"ok\":true,\"accepted\":3}").ids.isEmpty());
		assertTrue(TradeUploader.Deferred.parse(gson, "not json").ids.isEmpty());
		TradeUploader.Deferred d = TradeUploader.Deferred.parse(gson, "{\"deferredIds\":[\"a\",7,null,\"b\"],\"retryAfter\":-5}");
		assertEquals(2, d.ids.size());
		assertEquals(0, d.retryAfter);
	}
}
