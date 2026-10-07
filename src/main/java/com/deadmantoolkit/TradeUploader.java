package com.deadmantoolkit;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Queues trade events and ships them to the collection server in batches. All network calls go through OkHttp's
 * own thread pool via enqueue(). Item icons are only referenced when added (on the client thread) and PNG-encoded
 * later, in {@link #flush}, off the client thread.
 * <p>
 * One request carries one world and one account: events and open offers are grouped by {@link BatchKey}.
 * <p>
 * With a {@link ServerIdentity} (the plugin), every upload carries the install token ({@code Authorization: Bearer}).
 * When there is no token yet, the flush first registers (POST v1/register) as part of the same in-flight chain; a 409
 * there switches to a new random install id. A 401 on submit clears the token, registers again and resends the batch
 * once per flush; a second 401 leaves the queue as it is until the next flush. The token is never logged. Without an
 * identity (the older constructors) uploads carry no token, as before.
 * <p>
 * Imported trades ({@link TradeEvent#IMPORTED}): at most {@link #MAX_IMPORTED_PER_BATCH} per request (the server's
 * limit). Newer servers list valid imported events they did not store yet (over the per-request or the per-install
 * daily allowance) in {@code deferredIds}: those stay queued, and with a {@code retryAfter} (seconds) no imported event
 * is sent again until then. Everything else of an accepted batch leaves the queue as before.
 */
@Slf4j
class TradeUploader
{
	private static final MediaType JSON = InstallAuth.JSON;
	private static final int MAX_BATCH = 200;
	/** Imported events per request; the server's IMPORTED_PER_SUBMIT. */
	static final int MAX_IMPORTED_PER_BATCH = 100;
	/** Longest hold the server can ask for on imported events (its allowance is per UTC day). */
	private static final long MAX_IMPORT_HOLD_SECONDS = 86400;
	private static final int MAX_QUEUE = 5000;
	private static final int MAX_ITEMS_PER_BATCH = 50;

	private final OkHttpClient http;
	private final Gson gson;
	private final HttpUrl submitUrl;
	/** Told the item ids of each batch of events the server accepted (on an OkHttp thread). */
	private final Consumer<Set<Integer>> onSent;

	private final Deque<Queued> queue = new ConcurrentLinkedDeque<>();
	/** Item names/icons waiting to be sent, keyed by item id. */
	private final Map<Integer, ItemInfo> pendingItems = new ConcurrentHashMap<>();
	/** Items the server already has. */
	private final Set<Integer> sentItems = ConcurrentHashMap.newKeySet();
	/** Latest open offers per world and account, replaced wholesale on each login/heartbeat. */
	private final Map<BatchKey, List<JsonObject>> pendingOffers = new ConcurrentHashMap<>();
	private final AtomicBoolean inFlight = new AtomicBoolean();
	private final ServerIdentity identity;
	private final InstallAuth auth;
	/** Whether uploading (and registering) is allowed now: connected and sharing. Re-checked before each request. */
	private final BooleanSupplier allowed;
	/** While paused (a data deletion is running), flush does nothing. */
	private volatile boolean paused;
	/** Run once the in-flight chain (if any) has finished; see {@link #whenIdle}. */
	private final AtomicReference<Runnable> idleAction = new AtomicReference<>();
	/** Unix seconds before which no imported event is sent (the server's daily allowance is used up); 0 = none. */
	private volatile long importsHeldUntil;
	/** Unix seconds now; replaceable for tests. */
	private volatile LongSupplier clock = () -> Instant.now().getEpochSecond();

	/** A queued event and the hashed account ({@link AccountId}) it belongs to; null when unknown. */
	@Value
	static class Queued
	{
		TradeEvent event;
		String accountId;
	}

	/** What one request may contain: a single world and a single account (null account = unknown). */
	@Value
	static class BatchKey
	{
		int world;
		String accountId;

		static BatchKey of(Queued q)
		{
			return new BatchKey(q.getEvent().getWorld(), q.getAccountId());
		}
	}

	static class ItemInfo
	{
		final int id;
		final String name;
		/** GE buy limit (RuneLite's ItemStats), or 0 when unknown. */
		final int limit;
		/** The item's image, encoded into {@link #icon} during flush once {@link #imageReady} is set. */
		volatile BufferedImage image;
		/** Set when the image has been rendered (AsyncBufferedImage starts out blank). */
		volatile boolean imageReady;
		/** Base64 PNG, or null if not encoded yet. */
		volatile String icon;

		ItemInfo(int id, String name)
		{
			this(id, name, 0);
		}

		ItemInfo(int id, String name, int limit)
		{
			this.id = id;
			this.name = name;
			this.limit = Math.max(0, limit);
		}
	}

	/**
	 * The plugin's uploader: uploads carry the install token, registering first when there is none.
	 *
	 * @param onSent   called with the item ids of the events in each batch the server accepted (not for batches with
	 *                 only open offers or items). Runs on an OkHttp thread and must be quick.
	 * @param identity the install id and token
	 * @param allowed  whether uploading (and registering) is allowed now, i.e. connected and sharing; checked by
	 *                 {@link #flush()} and again before each follow-up request of a chain
	 */
	TradeUploader(OkHttpClient http, Gson gson, HttpUrl base, Consumer<Set<Integer>> onSent, ServerIdentity identity,
		BooleanSupplier allowed)
	{
		this.http = http;
		this.gson = gson;
		this.submitUrl = base.newBuilder().addPathSegments("v1/submit").build();
		this.onSent = onSent;
		this.identity = identity;
		this.auth = new InstallAuth(http, gson, base, identity);
		this.allowed = allowed;
	}

	/** @param accountId the hashed account id ({@link AccountId}), or null when unknown */
	void add(TradeEvent e, String accountId)
	{
		queue.addLast(new Queued(e, accountId));
		while (queue.size() > MAX_QUEUE)
		{
			queue.pollFirst();
		}
	}

	boolean needsItem(int itemId)
	{
		return !sentItems.contains(itemId) && !pendingItems.containsKey(itemId);
	}

	/** Queue an item's name and image. Cheap: the image is only referenced here and PNG-encoded during flush. */
	void addItem(int itemId, String name, BufferedImage image)
	{
		addItem(itemId, name, image, 0);
	}

	/** {@link #addItem(int, String, BufferedImage)} with the item's GE buy limit (0 = unknown). */
	void addItem(int itemId, String name, BufferedImage image, int limit)
	{
		ItemInfo info = new ItemInfo(itemId, name, limit);
		info.image = image;
		pendingItems.put(itemId, info);
	}

	/** The item's image has finished rendering, so flush may encode it. Cheap; safe on the client thread. */
	void setIcon(int itemId, BufferedImage image)
	{
		ItemInfo info = pendingItems.get(itemId);
		if (info != null)
		{
			info.image = image;
			info.imageReady = true;
		}
	}

	void setOpenOffers(int world, String accountId, List<JsonObject> offers)
	{
		pendingOffers.put(new BatchKey(world, accountId), offers);
	}

	/** Everything still waiting to be sent, oldest first (for {@link UploadSpool}). Includes a batch in flight. */
	List<Queued> pending()
	{
		return new ArrayList<>(queue);
	}

	/** Put spooled uploads from a previous session back, ahead of anything queued since; the oldest go if too many. */
	void restore(List<Queued> spooled)
	{
		for (int i = spooled.size() - 1; i >= 0; i--)
		{
			queue.addFirst(spooled.get(i));
		}
		while (queue.size() > MAX_QUEUE)
		{
			queue.pollFirst();
		}
	}

	/** Drop everything not sent yet. Items already sent are remembered, since the server keeps them. */
	void clear()
	{
		queue.clear();
		pendingItems.clear();
		pendingOffers.clear();
	}

	/**
	 * Send one batch with the install token, registering first when there is none. Does nothing while not
	 * {@code allowed}, while paused, while another request is running, or when there's nothing to send (so nothing,
	 * registration included, is ever sent before Connect). Never throws: a failure here would otherwise leave
	 * {@link #inFlight} stuck, or kill the scheduled task that calls this.
	 */
	void flush()
	{
		// The in-flight flag is claimed before the queues are looked at, so a concurrent clear() can't leave it set.
		if (paused || !allowed.getAsBoolean() || !claim())
		{
			return;
		}
		boolean enqueued = false;
		try
		{
			if (firstKey() == null)
			{
				return;
			}
			String installId = identity.installId();
			String token = identity.token();
			if (token == null)
			{
				enqueued = true;
				registerThenSend();
			}
			else
			{
				enqueued = send(installId, token, false);
			}
		}
		catch (RuntimeException e)
		{
			log.warn("Trade upload failed", e);
		}
		finally
		{
			if (!enqueued)
			{
				release();
			}
		}
	}

	/**
	 * Part of an in-flight chain: register, then send the batch with the new token, for the id it was issued for.
	 * The chain always ends by releasing the in-flight flag.
	 */
	private void registerThenSend()
	{
		auth.register(true, r ->
		{
			boolean enqueued = false;
			try
			{
				if (r.getStatus() == InstallAuth.Status.OK && !paused && allowed.getAsBoolean())
				{
					// A fresh token: a 401 now means something else is wrong, so no further re-registration.
					enqueued = send(r.getInstallId(), r.getToken(), true);
				}
				else
				{
					log.debug("Upload registration: {} (HTTP {}), will retry", r.getStatus(), r.getCode());
				}
			}
			catch (RuntimeException e)
			{
				log.warn("Trade upload failed", e);
			}
			finally
			{
				if (!enqueued)
				{
					release();
				}
			}
		});
	}

	/** Take the in-flight flag; false when paused or a request is already running. */
	private boolean claim()
	{
		if (paused || !inFlight.compareAndSet(false, true))
		{
			return false;
		}
		if (paused)
		{
			release();
			return false;
		}
		return true;
	}

	/** End of an in-flight chain: clear the flag, then run a waiting {@link #whenIdle} action. */
	private void release()
	{
		inFlight.set(false);
		Runnable action = idleAction.getAndSet(null);
		if (action != null)
		{
			try
			{
				action.run();
			}
			catch (RuntimeException e)
			{
				log.warn("Upload idle action failed", e);
			}
		}
	}

	/** True while a request is running. For tests. */
	boolean isInFlight()
	{
		return inFlight.get();
	}

	/** True when events or open offers are waiting to be sent now (held imported events don't count). For tests. */
	boolean hasPending()
	{
		return firstKey() != null;
	}

	/** Events waiting in the queue, held ones included. For tests. */
	int queued()
	{
		return queue.size();
	}

	/** For tests. */
	void setClock(LongSupplier clock)
	{
		this.clock = clock;
	}

	/** For tests: unix seconds before which imported events wait, or 0. */
	long importsHeldUntil()
	{
		return importsHeldUntil;
	}

	/** Whether {@code q} may go into a request now: imported events wait while the server asked them to. */
	private boolean sendable(Queued q, long now)
	{
		return !TradeEvent.IMPORTED.equals(q.getEvent().getKind()) || now >= importsHeldUntil;
	}

	/** {@link #nextKey} for the oldest event that may be sent now. */
	private BatchKey firstKey()
	{
		long now = clock.getAsLong();
		for (Queued q : queue)
		{
			if (sendable(q, now))
			{
				return BatchKey.of(q);
			}
		}
		return nextKey(null, pendingOffers);
	}

	/** Stop uploading: flush does nothing until {@link #resume()}. A request already running still completes. */
	void pause()
	{
		paused = true;
	}

	void resume()
	{
		paused = false;
	}

	boolean isPaused()
	{
		return paused;
	}

	/**
	 * Run {@code action} once no upload is in flight: right away (on this thread) when idle, else on the OkHttp
	 * thread that finishes the running chain. Never blocks. Call {@link #pause()} first, so no new upload starts in
	 * between.
	 */
	void whenIdle(Runnable action)
	{
		idleAction.set(action);
		if (inFlight.compareAndSet(false, true))
		{
			release();
		}
	}

	/**
	 * The world and account to send next: those of the oldest queued event, else of any pending open offers, else
	 * null when there's nothing to send. Copes with the offers map being emptied concurrently.
	 */
	static BatchKey nextKey(Queued head, Map<BatchKey, ?> offers)
	{
		if (head != null)
		{
			return BatchKey.of(head);
		}
		// ConcurrentHashMap iterators never throw on concurrent removal; an empty map just yields nothing.
		for (BatchKey key : offers.keySet())
		{
			return key;
		}
		return null;
	}

	/**
	 * Build and enqueue one request. Returns false (nothing enqueued) when there is nothing to send.
	 *
	 * @param token    the install token for {@code installId}
	 * @param reauthed the token was just issued in this chain, so a 401 isn't answered by registering again
	 */
	private boolean send(String installId, String token, boolean reauthed)
	{
		// One request = one world and one account.
		BatchKey key = firstKey();
		if (key == null)
		{
			return false;
		}

		long now = clock.getAsLong();
		List<Queued> batch = new ArrayList<>();
		List<TradeEvent> events = new ArrayList<>();
		int imported = 0;
		for (Queued q : queue)
		{
			if (batch.size() >= MAX_BATCH)
			{
				break;
			}
			if (!key.equals(BatchKey.of(q)) || !sendable(q, now))
			{
				continue;
			}
			if (TradeEvent.IMPORTED.equals(q.getEvent().getKind()) && ++imported > MAX_IMPORTED_PER_BATCH)
			{
				// The rest go in later requests.
				continue;
			}
			batch.add(q);
			events.add(q.getEvent());
		}

		List<JsonObject> offers = pendingOffers.get(key);

		List<ItemInfo> items = new ArrayList<>();
		for (ItemInfo info : pendingItems.values())
		{
			if (items.size() >= MAX_ITEMS_PER_BATCH)
			{
				break;
			}
			encodeIcon(info);
			items.add(info);
		}

		JsonObject body = buildBody(gson, installId, key, Instant.now().getEpochSecond(), events, offers, items);

		Request.Builder request = InstallAuth.bearer(new Request.Builder()
			.url(submitUrl)
			.post(RequestBody.create(JSON, gson.toJson(body))), token);

		http.newCall(request.build()).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.debug("Trade upload failed, will retry", e);
				release();
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				boolean continued = false;
				try (response)
				{
					if (response.code() == 401)
					{
						if (!reauthed && !paused && allowed.getAsBoolean())
						{
							// The server doesn't accept this token (lost, or the install was deleted): get a new
							// one and resend the batch, once.
							log.debug("Trade upload got HTTP 401, registering again");
							identity.clearToken(installId);
							continued = true;
							registerThenSend();
						}
						else
						{
							log.debug("Trade upload got HTTP 401 again, will retry later");
						}
					}
					else if (response.isSuccessful())
					{
						Deferred deferred = Deferred.parse(gson, response.body() == null ? null : response.body().string());
						List<Queued> done = new ArrayList<>(batch.size());
						List<TradeEvent> sent = new ArrayList<>(events.size());
						for (Queued q : batch)
						{
							// Events the server didn't store yet stay queued (newer servers name them).
							if (!deferred.ids.contains(q.getEvent().getId()))
							{
								done.add(q);
								sent.add(q.getEvent());
							}
						}
						if (!deferred.ids.isEmpty())
						{
							log.debug("Upload: {} imported events deferred by the server, retry in {}s", deferred.ids.size(),
								deferred.retryAfter);
							if (deferred.retryAfter > 0)
							{
								importsHeldUntil = clock.getAsLong() + Math.min(MAX_IMPORT_HOLD_SECONDS, deferred.retryAfter);
							}
						}
						queue.removeAll(done);
						pendingOffers.remove(key, offers);
						for (ItemInfo info : items)
						{
							// Keep retrying items whose icon wasn't rendered yet.
							if (info.icon != null)
							{
								pendingItems.remove(info.id, info);
								sentItems.add(info.id);
							}
						}
						notifySent(sent);
					}
					else if (response.code() == 400)
					{
						// Server rejected the payload; drop it rather than retry forever.
						log.debug("Trade upload rejected: {}", response.code());
						queue.removeAll(batch);
						pendingOffers.remove(key, offers);
					}
					else
					{
						log.debug("Trade upload got HTTP {}, will retry", response.code());
					}
				}
				catch (IOException | RuntimeException e)
				{
					log.warn("Trade upload response handling failed", e);
				}
				finally
				{
					if (!continued)
					{
						release();
					}
				}
			}
		});
		return true;
	}

	/** What a submit response says about events the server didn't store yet; empty for older servers. */
	static final class Deferred
	{
		static final Deferred NONE = new Deferred(Collections.emptySet(), 0);

		final Set<String> ids;
		/** Seconds before imported events should be sent again, or 0. */
		final long retryAfter;

		Deferred(Set<String> ids, long retryAfter)
		{
			this.ids = ids;
			this.retryAfter = retryAfter;
		}

		/** Reads {@code deferredIds} and {@code retryAfter} from a submit response body; lenient. */
		static Deferred parse(Gson gson, String body)
		{
			if (body == null || body.isEmpty())
			{
				return NONE;
			}
			try
			{
				JsonObject o = gson.fromJson(body, JsonObject.class);
				if (o == null || !o.has("deferredIds") || !o.get("deferredIds").isJsonArray())
				{
					return NONE;
				}
				Set<String> ids = new HashSet<>();
				for (JsonElement e : o.getAsJsonArray("deferredIds"))
				{
					if (e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isString())
					{
						ids.add(e.getAsString());
					}
				}
				JsonElement ra = o.get("retryAfter");
				long retry = ra != null && ra.isJsonPrimitive() && ra.getAsJsonPrimitive().isNumber()
					? Math.max(0, ra.getAsLong()) : 0;
				return new Deferred(ids, retry);
			}
			catch (RuntimeException ex)
			{
				return NONE;
			}
		}
	}

	private void notifySent(List<TradeEvent> events)
	{
		Set<Integer> ids = itemIds(events);
		if (ids.isEmpty())
		{
			return;
		}
		try
		{
			onSent.accept(ids);
		}
		catch (RuntimeException e)
		{
			log.warn("Upload listener failed", e);
		}
	}

	/** The distinct item ids of {@code events}. */
	static Set<Integer> itemIds(List<TradeEvent> events)
	{
		Set<Integer> ids = new HashSet<>();
		for (TradeEvent e : events)
		{
			ids.add(e.getItemId());
		}
		return ids;
	}

	/**
	 * The submit request body. Key names and order are the server's contract; "accountId" is newer, so it comes last
	 * and only when known (older servers ignore unknown fields).
	 */
	static JsonObject buildBody(Gson gson, String installId, BatchKey key, long sentAt, List<TradeEvent> batch,
		List<JsonObject> offers, List<ItemInfo> items)
	{
		JsonObject body = new JsonObject();
		body.addProperty("installId", installId);
		body.addProperty("world", key.getWorld());
		// Lets the server correct for this PC's clock when pairing buyer and seller reports.
		body.addProperty("sentAt", sentAt);
		body.add("events", gson.toJsonTree(batch));

		if (offers != null)
		{
			JsonArray arr = new JsonArray();
			offers.forEach(arr::add);
			body.add("offers", arr);
		}

		JsonArray itemArr = new JsonArray();
		for (ItemInfo info : items)
		{
			JsonObject o = new JsonObject();
			o.addProperty("id", info.id);
			o.addProperty("name", info.name);
			if (info.icon != null)
			{
				o.addProperty("icon", info.icon);
			}
			if (info.limit > 0)
			{
				// Newer field, last in the entry; older servers ignore it.
				o.addProperty("limit", info.limit);
			}
			itemArr.add(o);
		}
		body.add("items", itemArr);
		if (key.getAccountId() != null)
		{
			body.addProperty("accountId", key.getAccountId());
		}
		return body;
	}

	/** One open offer, as sent in the submit body's "offers" list. */
	static JsonObject offerJson(int slot, OfferTracker.SlotState s)
	{
		JsonObject o = new JsonObject();
		o.addProperty("slot", slot);
		o.addProperty("offerKey", s.getOfferKey());
		o.addProperty("side", TradeEvent.sideOf(s.isBuy()));
		o.addProperty("itemId", s.getItemId());
		o.addProperty("price", s.getPrice());
		o.addProperty("totalQty", s.getTotalQty());
		o.addProperty("filledQty", s.getSold());
		o.addProperty("placedAt", s.getPlacedAt());
		return o;
	}

	/** PNG-encode the item's image once it has rendered. Runs in flush, off the client thread. */
	static void encodeIcon(ItemInfo info)
	{
		BufferedImage img = info.image;
		if (info.icon == null && info.imageReady && img != null)
		{
			info.icon = encodePng(img);
		}
	}

	private static String encodePng(BufferedImage img)
	{
		if (img == null || isBlank(img))
		{
			return null;
		}
		// An in-memory stream: ImageIO.write to an OutputStream would buffer through a temp file on disk.
		Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("png");
		if (!writers.hasNext())
		{
			return null;
		}
		ImageWriter writer = writers.next();
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		try (ImageOutputStream ios = new MemoryCacheImageOutputStream(out))
		{
			writer.setOutput(ios);
			writer.write(img);
		}
		catch (IOException e)
		{
			return null;
		}
		finally
		{
			writer.dispose();
		}
		return Base64.getEncoder().encodeToString(out.toByteArray());
	}

	/** AsyncBufferedImage starts fully transparent until the sprite is rendered. */
	private static boolean isBlank(BufferedImage img)
	{
		for (int y = 0; y < img.getHeight(); y++)
		{
			for (int x = 0; x < img.getWidth(); x++)
			{
				if ((img.getRGB(x, y) >>> 24) != 0)
				{
					return false;
				}
			}
		}
		return true;
	}
}
