package com.deadmantoolkit;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;

/**
 * A scripted collection server for tests: an OkHttp interceptor answers each path from a queue of canned replies
 * (default 200). No network. Records every request with its body.
 */
final class FakeServer
{
	static final HttpUrl BASE = HttpUrl.get("http://localhost:1");
	static final String REGISTER = "/v1/register";
	static final String SUBMIT = "/v1/submit";
	static final String ME = "/v1/me";
	private static final MediaType JSON = MediaType.parse("application/json");

	/** A canned reply: an HTTP status and body, or a network failure. */
	static final class Reply
	{
		final int code;
		final String body;
		final boolean ioFailure;
		/** When set, the reply waits for this latch (to keep a request in flight). */
		final CountDownLatch hold;

		private Reply(int code, String body, boolean ioFailure, CountDownLatch hold)
		{
			this.code = code;
			this.body = body;
			this.ioFailure = ioFailure;
			this.hold = hold;
		}
	}

	static Reply status(int code)
	{
		return new Reply(code, "{}", false, null);
	}

	static Reply json(int code, String body)
	{
		return new Reply(code, body, false, null);
	}

	static Reply ioFailure()
	{
		return new Reply(0, null, true, null);
	}

	static Reply heldUntil(CountDownLatch latch)
	{
		return new Reply(200, "{\"ok\":true}", false, latch);
	}

	/** A register response issuing {@code token}. */
	static Reply token(String token)
	{
		return json(200, "{\"installId\":\"x\",\"token\":\"" + token + "\"}");
	}

	/** A token-shaped value (43 characters of base64url) made of {@code c}. */
	static String tok(char c)
	{
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < 43; i++)
		{
			sb.append(c);
		}
		return sb.toString();
	}

	/** A recorded request. */
	static final class Seen
	{
		final Request request;
		final String body;

		Seen(Request request, String body)
		{
			this.request = request;
			this.body = body;
		}

		String path()
		{
			return request.url().encodedPath();
		}

		String auth()
		{
			return request.header("Authorization");
		}

		JsonObject json()
		{
			return new Gson().fromJson(body, JsonObject.class);
		}
	}

	final BlockingQueue<Seen> requests = new LinkedBlockingQueue<>();
	private final Map<String, Deque<Reply>> replies = new HashMap<>();
	private final AtomicInteger tokenSeq = new AtomicInteger();
	/** Runs before each reply is sent, e.g. to flip a setting mid-chain. */
	volatile Runnable beforeReply = () ->
	{
	};

	/** Queue replies for {@code path}, used in order; after they run out, the default applies. */
	synchronized FakeServer on(String path, Reply... rs)
	{
		Deque<Reply> q = replies.computeIfAbsent(path, k -> new ArrayDeque<>());
		for (Reply r : rs)
		{
			q.addLast(r);
		}
		return this;
	}

	private synchronized Reply next(String path)
	{
		Deque<Reply> q = replies.get(path);
		Reply r = q == null ? null : q.pollFirst();
		if (r != null)
		{
			return r;
		}
		// Default: registration issues a new token each time, everything else succeeds.
		if (REGISTER.equals(path))
		{
			return token(tok((char) ('a' + tokenSeq.getAndIncrement() % 26)));
		}
		return json(200, "{\"ok\":true}");
	}

	OkHttpClient client()
	{
		return new OkHttpClient.Builder()
			.addInterceptor(chain ->
			{
				Request req = chain.request();
				String body = null;
				if (req.body() != null)
				{
					Buffer buf = new Buffer();
					req.body().writeTo(buf);
					body = buf.readUtf8();
				}
				requests.add(new Seen(req, body));
				Reply r = next(req.url().encodedPath());
				if (r.hold != null)
				{
					try
					{
						r.hold.await(10, TimeUnit.SECONDS);
					}
					catch (InterruptedException e)
					{
						Thread.currentThread().interrupt();
					}
				}
				beforeReply.run();
				if (r.ioFailure)
				{
					throw new IOException("fake network failure");
				}
				return new Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(r.code).message("fake")
					.body(ResponseBody.create(JSON, r.body)).build();
			})
			.build();
	}

	Seen next() throws InterruptedException
	{
		Seen s = requests.poll(5, TimeUnit.SECONDS);
		assertNotNull("expected a request", s);
		return s;
	}

	/** The next request, or null if none arrives within {@code ms}. */
	Seen poll(long ms) throws InterruptedException
	{
		return requests.poll(ms, TimeUnit.MILLISECONDS);
	}

	/** Waits for the uploader's in-flight chain to end. */
	static void awaitIdle(TradeUploader up) throws InterruptedException
	{
		for (int i = 0; i < 500 && up.isInFlight(); i++)
		{
			TimeUnit.MILLISECONDS.sleep(10);
		}
		assertFalse("in-flight flag stuck", up.isInFlight());
	}

	/** Waits until {@code cond} holds (up to 5 s). */
	static boolean await(BooleanSupplier cond) throws InterruptedException
	{
		for (int i = 0; i < 500; i++)
		{
			if (cond.getAsBoolean())
			{
				return true;
			}
			TimeUnit.MILLISECONDS.sleep(10);
		}
		return cond.getAsBoolean();
	}

	/** An identity over an in-memory map, with real random UUIDs. */
	static InstallIdentity identity(Map<String, String> store)
	{
		return new InstallIdentity(new InstallIdentity.Store()
		{
			@Override
			public synchronized String get(String key)
			{
				return store.get(key);
			}

			@Override
			public synchronized void set(String key, String value)
			{
				store.put(key, value);
			}

			@Override
			public synchronized void unset(String key)
			{
				store.remove(key);
			}
		}, () -> UUID.randomUUID().toString());
	}
}
