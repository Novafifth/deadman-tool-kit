package com.deadmantoolkit;

import static com.deadmantoolkit.FakeServer.tok;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import net.runelite.api.coords.WorldPoint;
import org.junit.Test;

/** What a shooting star report sends (POST v1/stars), and reading the star the server lists. */
public class StarReportTest
{
	private final Gson gson = new Gson();
	private final FakeServer server = new FakeServer();
	private final Map<String, String> config = new ConcurrentHashMap<>();
	private final InstallIdentity identity = FakeServer.identity(config);
	private final WorldEventClient client = new WorldEventClient(server.client(), gson, FakeServer.BASE, identity);
	private static final WorldPoint AT = new WorldPoint(3254, 3418, 0);

	@Test
	public void aStarIsItsTileTierAndTimeWithTheInstallIdOnly() throws InterruptedException
	{
		String token = tok('s');
		identity.setToken(identity.installId(), token);
		client.reportStar(345, AT, 7, true, 1792550000L);
		FakeServer.Seen r = server.next();
		assertEquals("/v1/stars", r.path());
		assertEquals("Bearer " + token, r.auth());
		JsonObject b = r.json();
		assertEquals(345, b.get("world").getAsInt());
		assertEquals(3254, b.get("x").getAsInt());
		assertEquals(3418, b.get("y").getAsInt());
		assertEquals(0, b.get("plane").getAsInt());
		assertEquals(7, b.get("tier").getAsInt());
		assertTrue(b.get("changed").getAsBoolean());
		assertEquals(1792550000L, b.get("ts").getAsLong());
		assertEquals(identity.installId(), b.get("installId").getAsString());
		assertFalse("no account id", b.has("accountId"));
		assertFalse(b.has("gone"));
		// world, x, y, plane, tier, changed, ts, installId: nothing else.
		assertEquals(8, b.size());

		client.reportStar(345, AT, 7, false, 1792550050L, "guarded");
		b = server.next().json();
		assertEquals("guarded", b.get("zone").getAsString());
		assertFalse(b.has("changed"));

		client.reportStar(345, AT, 0, false, 1792550100L);
		b = server.next().json();
		assertTrue(b.get("gone").getAsBoolean());
		assertFalse(b.has("tier"));
		assertFalse(b.has("changed"));
	}

	@Test
	public void noTokenNoReport() throws InterruptedException
	{
		client.reportStar(345, AT, 7, false, 1792550000L);
		assertEquals(null, server.requests.poll(300, TimeUnit.MILLISECONDS));
	}

	@Test
	public void theServersStarIsRead() throws InterruptedException
	{
		server.on("/v1/world-events", FakeServer.json(200, "{\"now\":1792550000,\"events\":[],\"stars\":[{\"x\":3254,\"y\":3418,"
			+ "\"plane\":0,\"tier\":6,\"tierAt\":1792549900,\"goneBy\":1792552420,\"firstSeen\":1792549000,\"reporters\":2}]}"));
		BlockingQueue<WorldEventClient.Active> got = new LinkedBlockingQueue<>();
		client.active(345, got::add);
		WorldEventClient.Active a = got.poll(5, TimeUnit.SECONDS);
		assertNotNull(a);
		assertEquals(1, a.stars.size());
		WorldEventClient.ReportedStar s = a.stars.get(0);
		assertEquals(3254, (int) s.x);
		assertEquals(6, (int) s.tier);
		assertEquals(1792552420L, (long) s.goneBy);
		assertEquals(2, (int) s.reporters);
	}

	@Test
	public void aServerWithoutStarsStillWorks() throws InterruptedException
	{
		server.on("/v1/world-events", FakeServer.json(200, "{\"now\":1792550000,\"events\":[]}"));
		BlockingQueue<WorldEventClient.Active> got = new LinkedBlockingQueue<>();
		client.active(345, got::add);
		WorldEventClient.Active a = got.poll(5, TimeUnit.SECONDS);
		assertNotNull(a);
		assertEquals(null, a.stars);
	}
}
