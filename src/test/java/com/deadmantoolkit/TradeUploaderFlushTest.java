package com.deadmantoolkit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.function.Consumer;
import java.util.HashMap;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.Call;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import org.junit.Test;

/**
 * Flush must never throw or leave the in-flight flag stuck, whatever state the queues are in. No network: requests
 * are answered by an interceptor.
 */
public class TradeUploaderFlushTest
{
	private static final HttpUrl BASE = HttpUrl.get("http://localhost:1");
	private static final String PNG_BASE64_PREFIX = "iVBORw0KGgo";

	private final Gson gson = new Gson();
	/** Requests seen by the fake server, in order. */
	private final BlockingQueue<Request> requests = new LinkedBlockingQueue<>();

	/** The plugin's uploader, with a token already issued (as after registering): uploads go straight out. */
	private TradeUploader uploader(OkHttpClient http, Consumer<Set<Integer>> onSent)
	{
		InstallIdentity id = FakeServer.identity(new HashMap<>());
		id.setToken(id.installId(), FakeServer.tok('f'));
		return new TradeUploader(http, gson, BASE, onSent, id, () -> true);
	}

	private OkHttpClient fakeServer()
	{
		return new OkHttpClient.Builder()
			.addInterceptor(chain ->
			{
				requests.add(chain.request());
				return new Response.Builder()
					.request(chain.request())
					.protocol(Protocol.HTTP_1_1)
					.code(200)
					.message("fake")
					.body(ResponseBody.create(null, ""))
					.build();
			})
			.build();
	}

	private static TradeEvent fill(int world)
	{
		return TradeEvent.builder().id("k:fill:1").kind(TradeEvent.FILL).side(TradeEvent.BUY).itemId(4151).qty(1)
			.price(1).total(1L).ts(1).world(world).build();
	}

	private static BufferedImage visibleImage()
	{
		BufferedImage img = new BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB);
		img.setRGB(1, 1, 0xff00ff00);
		return img;
	}

	/** An image that counts pixel reads, so a test can tell when the PNG encoding (which scans pixels) ran. */
	private static final class CountingImage extends BufferedImage
	{
		final AtomicInteger reads = new AtomicInteger();

		CountingImage()
		{
			super(4, 4, BufferedImage.TYPE_INT_ARGB);
			super.setRGB(1, 1, 0xff00ff00);
		}

		@Override
		public int getRGB(int x, int y)
		{
			reads.incrementAndGet();
			return super.getRGB(x, y);
		}
	}

	/** Waits for the response callback to clear the in-flight flag. */
	private static void awaitIdle(TradeUploader up) throws InterruptedException
	{
		for (int i = 0; i < 500 && up.isInFlight(); i++)
		{
			TimeUnit.MILLISECONDS.sleep(10);
		}
		assertFalse("in-flight flag stuck", up.isInFlight());
	}

	@Test
	public void nextKeyHandlesEmptySnapshot()
	{
		Map<TradeUploader.BatchKey, List<JsonObject>> offers = new ConcurrentHashMap<>();
		// The old flush assumed the offers map was still non-empty here and threw NoSuchElementException.
		assertNull(TradeUploader.nextKey(null, offers));
		offers.put(new TradeUploader.BatchKey(345, "a"), Collections.emptyList());
		assertEquals(new TradeUploader.BatchKey(345, "a"), TradeUploader.nextKey(null, offers));
		assertEquals(new TradeUploader.BatchKey(318, "b"),
			TradeUploader.nextKey(new TradeUploader.Queued(fill(318), "b"), offers));
	}

	private JsonObject nextBody() throws InterruptedException, IOException
	{
		Request req = requests.poll(5, TimeUnit.SECONDS);
		assertNotNull("expected a request", req);
		Buffer buf = new Buffer();
		req.body().writeTo(buf);
		return gson.fromJson(buf.readUtf8(), JsonObject.class);
	}

	@Test
	public void batchesNeverMixAccounts() throws InterruptedException, IOException
	{
		TradeUploader up = uploader(fakeServer(), ids ->
		{
		});
		TradeEvent a1 = fill(345).toBuilder().id("a1").build();
		TradeEvent b1 = fill(345).toBuilder().id("b1").build();
		TradeEvent a2 = fill(345).toBuilder().id("a2").build();
		TradeEvent n1 = fill(345).toBuilder().id("n1").build();
		up.add(a1, "acct-a");
		up.add(b1, "acct-b");
		up.add(a2, "acct-a");
		up.add(n1, null);

		up.flush();
		JsonObject first = nextBody();
		awaitIdle(up);
		assertEquals("acct-a", first.get("accountId").getAsString());
		assertEquals(2, first.getAsJsonArray("events").size());
		assertEquals("a1", first.getAsJsonArray("events").get(0).getAsJsonObject().get("id").getAsString());
		assertEquals("a2", first.getAsJsonArray("events").get(1).getAsJsonObject().get("id").getAsString());

		up.flush();
		JsonObject second = nextBody();
		awaitIdle(up);
		assertEquals("acct-b", second.get("accountId").getAsString());
		assertEquals(1, second.getAsJsonArray("events").size());
		assertEquals("b1", second.getAsJsonArray("events").get(0).getAsJsonObject().get("id").getAsString());

		// Unknown account: its own batch, and no accountId field at all.
		up.flush();
		JsonObject third = nextBody();
		awaitIdle(up);
		assertFalse(third.has("accountId"));
		assertEquals("n1", third.getAsJsonArray("events").get(0).getAsJsonObject().get("id").getAsString());

		up.flush();
		assertNull(requests.poll(200, TimeUnit.MILLISECONDS));
	}

	@Test
	public void openOffersGoWithTheirAccount() throws InterruptedException, IOException
	{
		TradeUploader up = uploader(fakeServer(), ids ->
		{
		});
		JsonObject offer = new JsonObject();
		offer.addProperty("slot", 1);
		up.setOpenOffers(345, "acct-b", Collections.singletonList(offer));
		up.add(fill(345), "acct-a");

		up.flush();
		JsonObject first = nextBody();
		awaitIdle(up);
		assertEquals("acct-a", first.get("accountId").getAsString());
		assertFalse(first.has("offers"));

		up.flush();
		JsonObject second = nextBody();
		awaitIdle(up);
		assertEquals("acct-b", second.get("accountId").getAsString());
		assertEquals(0, second.getAsJsonArray("events").size());
		assertEquals(1, second.getAsJsonArray("offers").size());
	}

	@Test
	public void flushOnEmptyAfterClearDoesNotWedge() throws InterruptedException
	{
		TradeUploader up = uploader(fakeServer(), ids ->
		{
		});
		// Open offers queued, then dropped (sharing switched off) right before the flush ran.
		up.setOpenOffers(345, null, Collections.emptyList());
		up.clear();
		up.flush();
		assertFalse(up.isInFlight());
		assertNull(requests.poll());

		// The uploader still works afterwards.
		up.add(fill(345), null);
		up.flush();
		assertNotNull(requests.poll(5, TimeUnit.SECONDS));
		awaitIdle(up);
	}

	@Test
	public void flushThatThrowsResetsInFlight() throws InterruptedException
	{
		AtomicInteger calls = new AtomicInteger();
		OkHttpClient real = fakeServer();
		OkHttpClient failingOnce = new OkHttpClient()
		{
			@Override
			public Call newCall(Request request)
			{
				if (calls.getAndIncrement() == 0)
				{
					throw new IllegalStateException("boom");
				}
				return real.newCall(request);
			}
		};
		TradeUploader up = uploader(failingOnce, ids ->
		{
		});
		up.add(fill(345), null);

		up.flush(); // must not throw
		assertFalse(up.isInFlight());

		up.flush();
		assertNotNull(requests.poll(5, TimeUnit.SECONDS));
		awaitIdle(up);
	}

	@Test
	public void iconIsEncodedOnlyOnceRendered()
	{
		TradeUploader.ItemInfo info = new TradeUploader.ItemInfo(4151, "Abyssal whip");
		info.image = visibleImage();

		// Added but not rendered yet: nothing to encode.
		TradeUploader.encodeIcon(info);
		assertNull(info.icon);

		info.imageReady = true;
		TradeUploader.encodeIcon(info);
		assertNotNull(info.icon);
		assertTrue(info.icon.startsWith(PNG_BASE64_PREFIX));
	}

	@Test
	public void addItemDoesNotEncodeButFlushSendsTheIcon() throws InterruptedException, IOException
	{
		TradeUploader up = uploader(fakeServer(), ids ->
		{
		});
		CountingImage img = new CountingImage();
		up.addItem(4151, "Abyssal whip", img);
		up.setIcon(4151, img);
		assertFalse(up.needsItem(4151));
		// addItem/setIcon run on the client thread: they must not scan or encode the image.
		assertEquals(0, img.reads.get());

		// Items ride along with a batch of events.
		up.add(fill(345), null);
		up.flush();
		Request req = requests.poll(5, TimeUnit.SECONDS);
		assertNotNull(req);
		// The encoding happened in flush.
		assertTrue(img.reads.get() > 0);
		Buffer buf = new Buffer();
		req.body().writeTo(buf);
		JsonObject body = gson.fromJson(buf.readUtf8(), JsonObject.class);
		JsonObject item = body.getAsJsonArray("items").get(0).getAsJsonObject();
		assertEquals("Abyssal whip", item.get("name").getAsString());
		assertTrue(item.get("icon").getAsString().startsWith(PNG_BASE64_PREFIX));
		awaitIdle(up);
	}

	private OkHttpClient serverAnswering(int code)
	{
		return new OkHttpClient.Builder()
			.addInterceptor(chain ->
			{
				requests.add(chain.request());
				return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code)
					.message("fake").body(ResponseBody.create(null, "")).build();
			})
			.build();
	}

	@Test
	public void onSentGetsTheBatchItemIdsOnlyOnSuccess() throws InterruptedException
	{
		BlockingQueue<Set<Integer>> sent = new LinkedBlockingQueue<>();
		TradeUploader up = uploader(fakeServer(), sent::add);
		up.add(fill(345), "a");
		up.add(fill(345).toBuilder().id("x").itemId(536).build(), "a");
		up.flush();
		assertNotNull(requests.poll(5, TimeUnit.SECONDS));
		assertEquals(new HashSet<>(Arrays.asList(4151, 536)), sent.poll(5, TimeUnit.SECONDS));
		awaitIdle(up);

		// Offers only: nothing traded, nothing to report.
		up.setOpenOffers(345, "a", Collections.emptyList());
		up.flush();
		assertNotNull(requests.poll(5, TimeUnit.SECONDS));
		awaitIdle(up);
		assertNull(sent.poll(200, TimeUnit.MILLISECONDS));

		// Server errors: not sent, not reported.
		for (int code : new int[]{500, 400})
		{
			TradeUploader failing = uploader(serverAnswering(code), sent::add);
			failing.add(fill(345), "a");
			failing.flush();
			assertNotNull(requests.poll(5, TimeUnit.SECONDS));
			awaitIdle(failing);
			assertNull(sent.poll(200, TimeUnit.MILLISECONDS));
		}
	}

	@Test
	public void throwingOnSentDoesNotWedgeTheUploader() throws InterruptedException
	{
		TradeUploader up = uploader(fakeServer(), ids ->
		{
			throw new IllegalStateException("boom");
		});
		up.add(fill(345), null);
		up.flush();
		assertNotNull(requests.poll(5, TimeUnit.SECONDS));
		awaitIdle(up);
		up.add(fill(345).toBuilder().id("2").build(), null);
		up.flush();
		assertNotNull(requests.poll(5, TimeUnit.SECONDS));
		awaitIdle(up);
	}
}
