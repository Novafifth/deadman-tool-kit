package com.deadmantoolkit;

import com.deadmantoolkit.world.WorldEventMessage;
import com.deadmantoolkit.world.WorldLocations;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.BufferedSource;

/**
 * World events and the server: reports the breach / chest broadcasts this client saw (POST v1/world-events: the place
 * as the game wrote it, the time, and how well the plugin could place it), reads the ones broadcast before you logged
 * in (GET v1/world-events), and reads the server's place list (GET v1/world-places), which can be updated without a
 * new plugin release. Only used while connected with "Share world events" on.
 * <p>
 * Reports carry the install token the uploader registered, but never take part in its upkeep (two registrations at
 * once would make one switch install id): without a token yet, or with a refused one, the report is dropped. All
 * requests are OkHttp enqueues; callbacks run on OkHttp threads.
 */
@Slf4j
class WorldEventClient
{
	/** GET v1/world-events. */
	static final class Active
	{
		long now;
		List<Reported> events;
	}

	/** One event other players reported. */
	static final class Reported
	{
		String kind;
		String place;
		String combat;
		Long at;
		Integer reporters;
	}

	/** Largest response read (the place list is about 45 KB). */
	static final long MAX_RESPONSE_BYTES = 1024 * 1024;

	private final OkHttpClient http;
	private final Gson gson;
	private final HttpUrl base;
	private final ServerIdentity identity;

	WorldEventClient(OkHttpClient http, Gson gson, HttpUrl base, ServerIdentity identity)
	{
		this.http = http;
		this.gson = gson;
		this.base = base;
		this.identity = identity;
	}

	/**
	 * Report a broadcast seen here: the broadcast and the install id only, no account id (nothing says which account
	 * was on the world when). Not on the client thread (the identity may read its file).
	 *
	 * @param ts when it arrived, unix seconds
	 */
	void report(WorldEventMessage m, long ts, int world, WorldLocations.Accuracy accuracy)
	{
		JsonObject body = new JsonObject();
		body.addProperty("world", world);
		body.addProperty("kind", m.getKind().id());
		body.addProperty("place", m.getLocation());
		if (m.getCombat() != null)
		{
			body.addProperty("combat", m.getCombat().id());
		}
		body.addProperty("ts", ts);
		body.addProperty("accuracy", accuracy.id());
		String token = identity.token();
		if (token == null)
		{
			// Registering here could race the uploader's registration (and make it switch install id): skip.
			log.debug("World event report skipped: no install token yet");
			return;
		}
		post(body, identity.installId(), token);
	}

	private void post(JsonObject body, String installId, String token)
	{
		body.addProperty("installId", installId);
		Request request = InstallAuth.bearer(new Request.Builder()
			.url(base.newBuilder().addPathSegments("v1/world-events").build())
			.post(RequestBody.create(InstallAuth.JSON, gson.toJson(body))), token)
			.build();
		http.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.debug("World event report failed: {}", e.getClass().getSimpleName());
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				try (response)
				{
					log.debug("World event report: HTTP {}", response.code());
				}
			}
		});
	}

	/** The events going on now on {@code world}, reported by other players. {@code ok} runs on an OkHttp thread. */
	void active(int world, Consumer<Active> ok)
	{
		HttpUrl url = base.newBuilder().addPathSegments("v1/world-events")
			.addQueryParameter("world", String.valueOf(world)).build();
		get(url, null, (body, etag) ->
		{
			Active a = gson.fromJson(body, Active.class);
			if (a != null && a.events != null)
			{
				ok.accept(a);
			}
		});
	}

	/**
	 * The server's place list, as JSON text (the format of {@code world-places.json}), with its ETag. Nothing is
	 * called when it hasn't changed since {@code etag} or can't be fetched. {@code ok} runs on an OkHttp thread.
	 */
	void places(String etag, BiConsumer<String, String> ok)
	{
		get(base.newBuilder().addPathSegments("v1/world-places").build(), etag, ok);
	}

	private void get(HttpUrl url, String etag, BiConsumer<String, String> ok)
	{
		Request.Builder req = new Request.Builder().url(url).get();
		if (etag != null)
		{
			req.header("If-None-Match", etag);
		}
		http.newCall(req.build()).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.debug("World events request failed: {}", e.getClass().getSimpleName());
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				try (response)
				{
					ResponseBody body = response.body();
					if (response.code() == 304 || !response.isSuccessful() || body == null)
					{
						return;
					}
					// Bounded: anything larger than any real answer is ignored, not read into memory.
					BufferedSource source = body.source();
					if (source.request(MAX_RESPONSE_BYTES + 1))
					{
						log.debug("World events response too large, ignored");
						return;
					}
					ok.accept(source.getBuffer().readUtf8(), response.header("ETag"));
				}
				catch (IOException | RuntimeException e)
				{
					log.debug("Bad world events response: {}", e.getClass().getSimpleName());
				}
			}
		});
	}
}
