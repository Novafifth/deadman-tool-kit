package com.deadmantoolkit;

import com.google.gson.Gson;
import java.io.IOException;
import java.util.Collection;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import javax.swing.SwingUtilities;
import lombok.extern.slf4j.Slf4j;
import okhttp3.CacheControl;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Read side of the collection server. Requests run on OkHttp's pool; the public methods deliver their callbacks on the
 * Swing EDT, the package-private ones ({@link #itemAsync}, {@link #items}) on the OkHttp thread.
 */
@Slf4j
public class MarketClient
{
	/** Events per Market feed request; the panel also uses it to spot a possible gap in an incremental load. */
	public static final int RECENT_LIMIT = 60;
	private static final int ITEM_LIMIT = 40;
	/**
	 * The Market feed shows completed trades only: fills, GE History rows and imported RuneLite records (the server
	 * lists an imported one only while its own time is within the last 24 hours). Older servers ignore unknown kinds
	 * and this whole parameter; the panel filters too.
	 */
	static final String RECENT_KINDS = TradeEvent.FILL + "," + TradeEvent.HISTORY + "," + TradeEvent.IMPORTED;
	/** Error messages passed to the {@code err} callbacks; {@link #shortReason} makes a status-line version. */
	public static final String UNREACHABLE = "Can't reach the Deadman Tool Kit server.";
	public static final String BAD_RESPONSE = "Unexpected server response.";
	private static final String SERVER_ERROR_PREFIX = "Server error (";
	/**
	 * Opt-in extra fields: confirmed / trusted figures and fill-only last prices. Older servers ignore the parameter
	 * and the fields stay null.
	 */
	static final String INCLUDE = "trust,fill";
	private static final Executor EDT = SwingUtilities::invokeLater;
	/** Runs callbacks on the OkHttp thread that received the response. */
	private static final Executor DIRECT = Runnable::run;

	private final OkHttpClient http;
	private final Gson gson;
	private final HttpUrl base;
	private final int world;

	MarketClient(OkHttpClient http, Gson gson, HttpUrl base, int world)
	{
		this.http = http;
		this.gson = gson;
		this.base = base;
		this.world = world;
	}

	/**
	 * The Market feed and headline stats.
	 *
	 * @param afterId only events with a higher id, or 0 for the newest page. Older servers ignore it and return the
	 *                newest page, so callers must de-duplicate by id.
	 */
	public void recent(long afterId, Consumer<MarketData.Recent> ok, Consumer<String> err)
	{
		HttpUrl.Builder url = url("v1/recent")
			.addQueryParameter("limit", String.valueOf(RECENT_LIMIT))
			.addQueryParameter("kinds", RECENT_KINDS);
		if (afterId > 0)
		{
			url.addQueryParameter("after", String.valueOf(afterId));
		}
		get(url, false, MarketData.Recent.class, EDT, ok, err);
	}

	/** @param fresh skip any HTTP cache (a refresh the user asked for, or right after an upload) */
	public void item(int itemId, boolean fresh, Consumer<MarketData.ItemDetail> ok, Consumer<String> err)
	{
		get(url("v1/item/" + itemId).addQueryParameter("limit", String.valueOf(ITEM_LIMIT)), fresh,
			MarketData.ItemDetail.class, EDT, ok, err);
	}

	/**
	 * Price history. Newer servers return just {@code range}; older ones ignore it and return a year's worth of
	 * {@code step} buckets (365 of them), so callers trim to the range.
	 *
	 * @param range how far back, as the server names it (24h, 7d, 30d, 90d or 1y)
	 * @param step  bucket size, as the server names it (5m, 1h, 6h or 24h)
	 */
	public void series(int itemId, String range, String step, boolean fresh, Consumer<MarketData.Series> ok,
		Consumer<String> err)
	{
		get(url("v1/item/" + itemId + "/series").addQueryParameter("range", range).addQueryParameter("step", step),
			fresh, MarketData.Series.class, EDT, ok, err);
	}

	/**
	 * One item's summary for the GE price line, with callbacks on the OkHttp thread (never the EDT or client thread).
	 * Asks for a single trade: only the summary is used.
	 */
	void itemAsync(int itemId, Consumer<MarketData.ItemDetail> ok, Consumer<String> err)
	{
		get(url("v1/item/" + itemId).addQueryParameter("limit", "1"), false, MarketData.ItemDetail.class, DIRECT, ok, err);
	}

	/**
	 * Summaries of a few items, with callbacks on the OkHttp thread. Older servers ignore {@code ids} and return every
	 * item, so callers filter ({@link OfferQuotes#filter}).
	 */
	void items(Collection<Integer> ids, boolean fresh, Consumer<MarketData.ItemsResponse> ok, Consumer<String> err)
	{
		String list = new TreeSet<>(ids).stream().map(String::valueOf).collect(Collectors.joining(","));
		get(url("v1/items").addQueryParameter("ids", list), fresh, MarketData.ItemsResponse.class, DIRECT, ok, err);
	}

	/** The error message for an HTTP error status. */
	static String serverError(int code)
	{
		return SERVER_ERROR_PREFIX + code + ").";
	}

	/**
	 * A few words for a status line from an error message: "server unreachable", "server error 500" or "bad
	 * response"; anything else as given, without a trailing period.
	 */
	public static String shortReason(String message)
	{
		if (message == null || message.isEmpty())
		{
			return "unknown error";
		}
		if (UNREACHABLE.equals(message))
		{
			return "server unreachable";
		}
		if (BAD_RESPONSE.equals(message))
		{
			return "bad response";
		}
		if (message.startsWith(SERVER_ERROR_PREFIX) && message.endsWith(").") && message.length() > SERVER_ERROR_PREFIX.length() + 2)
		{
			return "server error " + message.substring(SERVER_ERROR_PREFIX.length(), message.length() - 2);
		}
		return message.endsWith(".") ? message.substring(0, message.length() - 1) : message;
	}

	private HttpUrl.Builder url(String path)
	{
		return base.newBuilder()
			.addPathSegments(path)
			.addQueryParameter("world", String.valueOf(world))
			.addQueryParameter("include", INCLUDE);
	}

	/** @param callbacks where {@code ok} / {@code err} run */
	private <T> void get(HttpUrl.Builder url, boolean fresh, Class<T> type, Executor callbacks, Consumer<T> ok,
		Consumer<String> err)
	{
		Request.Builder req = new Request.Builder().url(url.build()).get();
		if (fresh)
		{
			req.cacheControl(CacheControl.FORCE_NETWORK);
		}
		http.newCall(req.build()).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.debug("Market request failed: {}", call.request().url(), e);
				callbacks.execute(() -> err.accept(UNREACHABLE));
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				try (response)
				{
					ResponseBody body = response.body();
					if (!response.isSuccessful() || body == null)
					{
						int code = response.code();
						callbacks.execute(() -> err.accept(serverError(code)));
						return;
					}
					T data = gson.fromJson(body.charStream(), type);
					if (data == null)
					{
						callbacks.execute(() -> err.accept(BAD_RESPONSE));
						return;
					}
					callbacks.execute(() -> ok.accept(data));
				}
				catch (RuntimeException e)
				{
					log.debug("Bad market response", e);
					callbacks.execute(() -> err.accept(BAD_RESPONSE));
				}
			}
		});
	}
}
