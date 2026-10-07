package com.deadmantoolkit;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * "Delete my shared data": asks the server to delete everything this install sent (DELETE v1/me with the install
 * token), then disconnects and starts over as a new, unlinkable install id. Only ever started by the player (the
 * panel's Privacy menu, after a confirm dialog), so it may contact the server while disconnected.
 * <p>
 * Steps: pause the uploader; once no upload is in flight (a callback, never blocking), send the DELETE. Without a
 * token (an install that uploaded before tokens existed) it registers first. A 401 registers once and retries. If
 * that registration gets 409, only the server's explicit "installId deleted" counts as nothing to delete; a 409 for
 * an id held by another token means this PC lost its key, so the data may still be there: the player is told so,
 * with the install id to quote to the privacy contact, and the id is kept. All network calls are OkHttp enqueues; the
 * result callback runs on an OkHttp thread (or the caller's thread when idle and nothing could be started). Identity
 * and config writes happen there too, never on the client thread.
 */
@Slf4j
class DataDeletion
{
	enum Outcome
	{
		/** 200: deleted. */
		DELETED,
		/** 202: the server accepted it and finishes in the background. */
		PENDING,
		/** The server says this install id's data was deleted already. */
		NOTHING,
		/**
		 * The install id is held by a token this PC doesn't have (its key was lost or replaced): nothing could be
		 * deleted, and the data may still be on the server. The id is kept so the player can quote it.
		 */
		KEY_LOST,
		/** Server error, rate limited or unreachable; still connected. */
		FAILED
	}

	/**
	 * The outcome, with the HTTP status for {@link Outcome#FAILED} (-1 when the server couldn't be reached) and, for
	 * {@link Outcome#KEY_LOST}, the install id (not a secret) to quote in an erasure request.
	 */
	static final class Result
	{
		final Outcome outcome;
		final int code;
		final String installId;
		/** Older install ids of this PC whose key was lost: their data can't be deleted from here. */
		final List<String> lostIds;

		Result(Outcome outcome, int code)
		{
			this(outcome, code, null);
		}

		Result(Outcome outcome, int code, String installId)
		{
			this(outcome, code, installId, Collections.emptyList());
		}

		Result(Outcome outcome, int code, String installId, List<String> lostIds)
		{
			this.outcome = outcome;
			this.code = code;
			this.installId = installId;
			this.lostIds = lostIds;
		}

		@Override
		public String toString()
		{
			return outcome + "(" + code + ")";
		}
	}

	private final OkHttpClient http;
	private final Gson gson;
	private final HttpUrl meUrl;
	private final ServerIdentity identity;
	private final InstallAuth auth;
	private final TradeUploader uploader;
	/** Turns the "connected" setting off. */
	private final Runnable disconnect;
	private final AtomicBoolean running = new AtomicBoolean();
	/** Set on plugin shutdown: the result isn't reported any more (the local state is still updated). */
	private volatile boolean cancelled;

	DataDeletion(OkHttpClient http, Gson gson, HttpUrl base, ServerIdentity identity, TradeUploader uploader,
		Runnable disconnect)
	{
		this.http = http;
		this.gson = gson;
		this.meUrl = base.newBuilder().addPathSegments("v1/me").build();
		this.identity = identity;
		this.auth = new InstallAuth(http, gson, base, identity);
		this.uploader = uploader;
		this.disconnect = disconnect;
	}

	/** True while a deletion is running. */
	boolean isRunning()
	{
		return running.get();
	}

	/** Stop reporting results (plugin shutdown). Never blocks. */
	void cancel()
	{
		cancelled = true;
	}

	/**
	 * Start a deletion. Returns false (and does nothing) when one is already running.
	 *
	 * @param done called once with the result
	 */
	boolean start(Consumer<Result> done)
	{
		if (!running.compareAndSet(false, true))
		{
			return false;
		}
		uploader.pause();
		uploader.whenIdle(() -> step(done));
		return true;
	}

	/** No upload in flight any more: send the deletion (registering first when there is no token). */
	private void step(Consumer<Result> done)
	{
		try
		{
			String installId = identity.installId();
			String token = identity.token();
			if (token == null)
			{
				registerThenDelete(done);
			}
			else
			{
				delete(installId, token, false, done);
			}
		}
		catch (RuntimeException e)
		{
			log.warn("Data deletion failed", e);
			finish(new Result(Outcome.FAILED, -1), done);
		}
	}

	private void registerThenDelete(Consumer<Result> done)
	{
		// No rotation on 409: the id is either deleted already, or held by a token this PC doesn't have.
		auth.register(false, r ->
		{
			switch (r.getStatus())
			{
				case OK:
					delete(r.getInstallId(), r.getToken(), true, done);
					break;
				case DELETED:
					finish(new Result(Outcome.NOTHING, r.getCode()), done);
					break;
				case CONFLICT:
					finish(new Result(Outcome.KEY_LOST, r.getCode(), r.getInstallId()), done);
					break;
				default:
					finish(new Result(Outcome.FAILED, r.getCode()), done);
			}
		});
	}

	private void delete(String installId, String token, boolean reauthed, Consumer<Result> done)
	{
		JsonObject body = new JsonObject();
		body.addProperty("installId", installId);
		Request request = InstallAuth.bearer(new Request.Builder()
			.url(meUrl)
			.delete(RequestBody.create(InstallAuth.JSON, gson.toJson(body))), token)
			.build();
		Call call;
		try
		{
			call = http.newCall(request);
		}
		catch (RuntimeException e)
		{
			log.warn("Couldn't start data deletion", e);
			finish(new Result(Outcome.FAILED, -1), done);
			return;
		}
		call.enqueue(new Callback()
		{
			@Override
			public void onFailure(Call c, IOException e)
			{
				log.debug("Data deletion request failed: {}", e.getClass().getSimpleName());
				finish(new Result(Outcome.FAILED, -1), done);
			}

			@Override
			public void onResponse(Call c, Response response)
			{
				int code;
				try (response)
				{
					code = response.code();
				}
				log.debug("Data deletion: HTTP {}", code);
				if (code == 200)
				{
					finish(new Result(Outcome.DELETED, code), done);
				}
				else if (code == 202)
				{
					finish(new Result(Outcome.PENDING, code), done);
				}
				else if (code == 401 && !reauthed)
				{
					// The token isn't accepted (lost, or the data is already gone): register once and retry.
					identity.clearToken(installId);
					registerThenDelete(done);
				}
				else
				{
					finish(new Result(Outcome.FAILED, code), done);
				}
			}
		});
	}

	/**
	 * Done. After a deletion (or when there was nothing to delete): disconnect, switch to a new install id without a
	 * token and drop anything queued. When the key was lost: disconnect and drop the queue too (the player asked for
	 * their data to go), but keep the install id, which the message shows. After a failure: stay connected and resume
	 * uploading.
	 */
	private void finish(Result result, Consumer<Result> done)
	{
		try
		{
			if (result.outcome == Outcome.FAILED)
			{
				uploader.resume();
			}
			else
			{
				disconnect.run();
				if (result.outcome != Outcome.KEY_LOST)
				{
					identity.rotateInstallId();
				}
				uploader.clear();
				uploader.resume();
			}
		}
		catch (RuntimeException e)
		{
			log.warn("Data deletion: couldn't update local state", e);
		}
		finally
		{
			running.set(false);
		}
		if (result.outcome != Outcome.FAILED)
		{
			try
			{
				List<String> lost = new ArrayList<>(identity.lostInstallIds());
				lost.remove(result.installId);
				if (!lost.isEmpty())
				{
					result = new Result(result.outcome, result.code, result.installId, lost);
				}
			}
			catch (RuntimeException e)
			{
				log.warn("Data deletion: couldn't read older install ids", e);
			}
		}
		if (cancelled)
		{
			return;
		}
		try
		{
			done.accept(result);
		}
		catch (RuntimeException e)
		{
			log.warn("Data deletion callback failed", e);
		}
	}

	/** What to tell the player afterwards. */
	static String message(Result r)
	{
		String m = outcomeMessage(r);
		if (r.outcome == Outcome.FAILED || r.lostIds.isEmpty())
		{
			return m;
		}
		return m + " This computer also shared data under older install ids whose key was lost ("
			+ String.join(", ", r.lostIds) + "), which can't be deleted from here. To have it removed, send "
			+ (r.lostIds.size() == 1 ? "that id" : "those ids") + " to the contact on the privacy notice "
			+ "(Privacy > Privacy notice).";
	}

	private static String outcomeMessage(Result r)
	{
		switch (r.outcome)
		{
			case DELETED:
				return "Your shared data was deleted. The plugin is now disconnected.";
			case PENDING:
				return "Your shared data is being deleted; the server finishes this in the background. "
					+ "The plugin is now disconnected.";
			case NOTHING:
				return "The server says this computer's shared data was already deleted, so there was nothing to delete. "
					+ "The plugin is now disconnected.";
			case KEY_LOST:
				return "This computer's key for the server was lost or replaced, so the plugin can't prove the shared data "
					+ "is yours, and nothing was deleted. To have it removed, send your install id " + r.installId
					+ " to the contact on the privacy notice (Privacy > Privacy notice). The plugin is now disconnected.";
			default:
				return r.code < 0
					? "Couldn't reach the Deadman Tool Kit server. Try again later."
					: "Couldn't delete your data (server error " + r.code + "). Try again later.";
		}
	}
}
