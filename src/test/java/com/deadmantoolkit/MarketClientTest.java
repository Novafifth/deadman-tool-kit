package com.deadmantoolkit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import com.google.gson.Gson;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.Test;

public class MarketClientTest
{
	private final BlockingQueue<Request> requests = new LinkedBlockingQueue<>();

	private MarketClient market()
	{
		OkHttpClient http = new OkHttpClient.Builder()
			.addInterceptor(chain ->
			{
				requests.add(chain.request());
				return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("ok")
					.body(ResponseBody.create(null, "{}")).build();
			})
			.build();
		return new MarketClient(http, new Gson(), HttpUrl.get("http://localhost:1"), 345);
	}

	private Request next() throws InterruptedException
	{
		Request req = requests.poll(5, TimeUnit.SECONDS);
		assertNotNull(req);
		return req;
	}

	@Test
	public void recentAsksForCompletedTradesOnly() throws InterruptedException
	{
		market().recent(0, r ->
		{
		}, err ->
		{
		});
		Request req = next();
		assertEquals("/v1/recent", req.url().encodedPath());
		assertEquals("fill,history,imported", req.url().queryParameter("kinds"));
		assertEquals("345", req.url().queryParameter("world"));
		assertEquals("60", req.url().queryParameter("limit"));
		assertNull(req.url().queryParameter("after"));
	}

	@Test
	public void recentAfterOnlyWhenPositive() throws InterruptedException
	{
		market().recent(1234, r ->
		{
		}, err ->
		{
		});
		assertEquals("1234", next().url().queryParameter("after"));
	}

	@Test
	public void seriesSendsRangeAndStep() throws InterruptedException
	{
		market().series(4151, "30d", "6h", false, s ->
		{
		}, err ->
		{
		});
		Request req = next();
		assertEquals("/v1/item/4151/series", req.url().encodedPath());
		assertEquals("30d", req.url().queryParameter("range"));
		assertEquals("6h", req.url().queryParameter("step"));
		assertEquals("345", req.url().queryParameter("world"));
		assertNull(req.header("Cache-Control"));
	}

	@Test
	public void freshItemSkipsCaches() throws InterruptedException
	{
		market().item(4151, true, d ->
		{
		}, err ->
		{
		});
		Request req = next();
		assertEquals("/v1/item/4151", req.url().encodedPath());
		assertEquals("no-cache", req.header("Cache-Control"));
	}

	@Test
	public void itemsSendsSortedIds() throws InterruptedException
	{
		market().items(java.util.Arrays.asList(4151, 536, 4151, 11832), false, r ->
		{
		}, err ->
		{
		});
		Request req = next();
		assertEquals("/v1/items", req.url().encodedPath());
		assertEquals("536,4151,11832", req.url().queryParameter("ids"));
		assertEquals("345", req.url().queryParameter("world"));
	}

	@Test
	public void itemAsyncAsksForTheSummaryOnly() throws InterruptedException
	{
		BlockingQueue<String> threads = new LinkedBlockingQueue<>();
		market().itemAsync(4151, d -> threads.add(Thread.currentThread().getName()), err -> threads.add("err"));
		Request req = next();
		assertEquals("/v1/item/4151", req.url().encodedPath());
		assertEquals("1", req.url().queryParameter("limit"));
		String thread = threads.poll(5, TimeUnit.SECONDS);
		assertNotNull(thread);
		// Delivered on OkHttp's thread, not queued to the EDT.
		assertFalse(thread.contains("AWT-EventQueue"));
	}

	@Test
	public void everyRequestAsksForTrustAndFillFields() throws InterruptedException
	{
		MarketClient m = market();
		m.recent(0, r ->
		{
		}, err ->
		{
		});
		m.item(4151, false, d ->
		{
		}, err ->
		{
		});
		m.series(4151, "24h", "5m", false, s ->
		{
		}, err ->
		{
		});
		m.items(java.util.Collections.singletonList(4151), false, r ->
		{
		}, err ->
		{
		});
		m.itemAsync(4151, d ->
		{
		}, err ->
		{
		});
		for (int i = 0; i < 5; i++)
		{
			Request req = next();
			assertEquals(req.url().toString(), "trust,fill", req.url().queryParameter("include"));
			assertNull("reads never carry a token", req.header("Authorization"));
		}
	}

	@Test
	public void shortReasons()
	{
		assertEquals("server unreachable", MarketClient.shortReason(MarketClient.UNREACHABLE));
		assertEquals("server error 503", MarketClient.shortReason(MarketClient.serverError(503)));
		assertEquals("bad response", MarketClient.shortReason(MarketClient.BAD_RESPONSE));
		assertEquals("Something else", MarketClient.shortReason("Something else."));
		assertEquals("unknown error", MarketClient.shortReason(null));
	}
}
