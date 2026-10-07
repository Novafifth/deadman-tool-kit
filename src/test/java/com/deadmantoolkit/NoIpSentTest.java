package com.deadmantoolkit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import org.junit.Test;

/**
 * The plugin never sends an IP address: no request (register, submit, delete, market reads) carries an IP-like field,
 * header or query parameter, and no request contains an address of this machine or any IPv4 literal. The user-facing
 * texts no longer list "your IP address" among what is shared, while RuneLite's mandatory warning stays verbatim
 * (the welcome card's line is checked in WelcomeCardTest).
 */
public class NoIpSentTest
{
	private static final Pattern IP_NAME = Pattern.compile("(?i)(^|[-_])ip($|[-_])|ipaddr|ip_?hash|^ip[A-Z]|"
		+ "address|forwarded|real-?ip|client-?ip|remote|^via$|^host$");
	private static final Pattern IPV4 = Pattern.compile("(?<![\\d.])(\\d{1,3}\\.){3}\\d{1,3}(?![\\d.])");

	private final Gson gson = new Gson();
	private final FakeServer server = new FakeServer();
	private final Map<String, String> config = new ConcurrentHashMap<>();
	private final InstallIdentity identity = FakeServer.identity(config);
	private final AtomicBoolean connected = new AtomicBoolean(true);

	private static TradeEvent fill(String id)
	{
		return TradeEvent.builder().id(id).kind(TradeEvent.FILL).side(TradeEvent.BUY).itemId(4151).qty(3)
			.price(290_000).total(870_000L).slot(2).offerKey("k1").ts(1000).late(false).filled(3).since(900L)
			.world(345).name("Abyssal whip").build();
	}

	/** Every address of this machine's network interfaces, as text (e.g. "192.168.1.5", "fe80:0:0:0:..."). */
	private static List<String> localAddresses() throws Exception
	{
		List<String> out = new ArrayList<>();
		Enumeration<NetworkInterface> nis = NetworkInterface.getNetworkInterfaces();
		for (NetworkInterface ni : nis == null ? Collections.<NetworkInterface>emptyList() : Collections.list(nis))
		{
			for (InetAddress a : Collections.list(ni.getInetAddresses()))
			{
				String s = a.getHostAddress();
				int pct = s.indexOf('%');
				out.add(pct >= 0 ? s.substring(0, pct) : s);
			}
		}
		out.add(InetAddress.getLocalHost().getHostAddress());
		return out;
	}

	private static void collectKeys(JsonElement e, Set<String> keys)
	{
		if (e == null || e.isJsonNull() || e.isJsonPrimitive())
		{
			return;
		}
		if (e.isJsonArray())
		{
			e.getAsJsonArray().forEach(c -> collectKeys(c, keys));
			return;
		}
		for (Map.Entry<String, JsonElement> en : e.getAsJsonObject().entrySet())
		{
			keys.add(en.getKey());
			collectKeys(en.getValue(), keys);
		}
	}

	private static void assertNoIp(okhttp3.Request req, String body, List<String> local)
	{
		String where = req.method() + " " + req.url().encodedPath();
		for (String h : req.headers().names())
		{
			assertFalse(where + " header " + h, IP_NAME.matcher(h).find());
		}
		for (String q : req.url().queryParameterNames())
		{
			assertFalse(where + " query " + q, IP_NAME.matcher(q).find());
		}
		Set<String> keys = new TreeSet<>();
		if (body != null && !body.isEmpty())
		{
			collectKeys(new Gson().fromJson(body, JsonElement.class), keys);
		}
		for (String k : keys)
		{
			assertFalse(where + " field " + k, IP_NAME.matcher(k).find());
		}
		String all = req.url() + "\n" + req.headers() + "\n" + (body == null ? "" : body);
		assertFalse(where + " has an IPv4 literal: " + all, IPV4.matcher(all).find());
		String lower = all.toLowerCase(Locale.ROOT);
		for (String a : local)
		{
			assertFalse(where + " contains local address " + a, lower.contains(a.toLowerCase(Locale.ROOT)));
		}
	}

	@Test
	public void registerSubmitAndDeleteCarryNoIp() throws Exception
	{
		List<String> local = localAddresses();
		TradeUploader up = new TradeUploader(server.client(), gson, FakeServer.BASE, ids ->
		{
		}, identity, connected::get);
		up.add(fill("k1:fill:3"), "acct-hash");
		up.addItem(4151, "Abyssal whip", null, 70);
		JsonObject offer = TradeUploader.offerJson(2, new OfferTracker.SlotState("k1", 4151, true, 290_000, 10, 3,
			870_000, OfferTracker.Status.ACTIVE, 900, 1000));
		up.setOpenOffers(345, "acct-hash", Collections.singletonList(offer));
		up.flush();

		FakeServer.Seen reg = server.next();
		assertEquals(FakeServer.REGISTER, reg.path());
		// Registration sends the install id and nothing else.
		assertEquals(Collections.singleton("installId"), reg.json().keySet());
		assertNoIp(reg.request, reg.body, local);

		FakeServer.Seen sub = server.next();
		assertEquals(FakeServer.SUBMIT, sub.path());
		assertTrue(sub.json().has("offers"));
		assertNoIp(sub.request, sub.body, local);
		FakeServer.awaitIdle(up);

		DataDeletion deletion = new DataDeletion(server.client(), gson, FakeServer.BASE, identity, up,
			() -> connected.set(false));
		LinkedBlockingQueue<DataDeletion.Result> results = new LinkedBlockingQueue<>();
		assertTrue(deletion.start(results::add));
		FakeServer.Seen del = server.next();
		assertEquals(FakeServer.ME, del.path());
		assertNoIp(del.request, del.body, local);
		assertNull(server.poll(100));
	}

	@Test
	public void marketReadsCarryNoIp() throws Exception
	{
		List<String> local = localAddresses();
		MarketClient market = new MarketClient(server.client(), gson, FakeServer.BASE, 345);
		market.recent(5, r ->
		{
		}, err ->
		{
		});
		market.item(4151, true, r ->
		{
		}, err ->
		{
		});
		market.items(java.util.Arrays.asList(4151, 385), true, r ->
		{
		}, err ->
		{
		});
		for (int i = 0; i < 3; i++)
		{
			FakeServer.Seen s = server.next();
			assertNoIp(s.request, s.body, local);
		}
	}

	@Test
	public void ownTextsSayIpsAreNeverStoredButTheRuneLiteWarningIsVerbatim() throws Exception
	{
		String warning = "This feature submits your IP address to a 3rd-party server not controlled or verified by"
			+ " RuneLite developers";
		assertEquals(warning, DeadmanToolKitConfig.THIRD_PARTY_WARNING);
		net.runelite.client.config.ConfigItem item = DeadmanToolKitConfig.class.getMethod("connected")
			.getAnnotation(net.runelite.client.config.ConfigItem.class);
		assertEquals(warning, item.warning());

		List<String> ours = new ArrayList<>();
		ours.add(item.description());
		ours.add(DeadmanToolKitPlugin.class.getAnnotation(net.runelite.client.plugins.PluginDescriptor.class)
			.description());
		java.util.Properties props = new java.util.Properties();
		try (java.io.InputStream in = new java.io.FileInputStream("runelite-plugin.properties"))
		{
			props.load(in);
		}
		ours.add(props.getProperty("description"));
		for (String s : ours)
		{
			assertFalse(s, s.contains("your IP address"));
			assertTrue(s, s.contains("never stored") || s.contains("never stores IP addresses"));
		}
		String readme = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get("README.md")),
			java.nio.charset.StandardCharsets.UTF_8);
		assertTrue(readme.contains("> " + warning + "."));
		assertFalse(readme.contains("reveal your IP address"));
		assertFalse(readme.contains("expose your IP address"));
		assertTrue(readme.contains("never stores, hashes or logs it"));
	}
}
