package com.deadmantoolkit;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Gets an install token from the collection server (POST v1/register {installId}) and stores it in the
 * {@link ServerIdentity}. Asynchronous (OkHttp enqueue); the callback runs exactly once, on an OkHttp thread, or on
 * the caller's thread when the request can't even be started.
 * <p>
 * The token is a secret for this install: it is never logged, and neither are response bodies or request headers.
 * Only status codes are logged, at debug level.
 */
@Slf4j
class InstallAuth
{
	static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
	/** The server's 409 error for an install id whose data was deleted (vs "installId already registered"). */
	static final String DELETED_ERROR = "installId deleted";

	enum Status
	{
		/** A token was issued; {@link Result#getToken()} has it. */
		OK,
		/**
		 * 409 "installId already registered": another token holds this install id (this PC's was lost or replaced, or
		 * the id isn't ours). Only when not rotating.
		 */
		CONFLICT,
		/** 409 "installId deleted": this install id's data was deleted already. Only when not rotating. */
		DELETED,
		/** Rate limited, server error, bad response or network failure. */
		FAILED
	}

	/** The outcome of a registration. Deliberately has no toString, so the token can't end up in a log line. */
	static final class Result
	{
		private final Status status;
		private final String installId;
		private final String token;
		/** HTTP status, or -1 for a network failure. */
		private final int code;

		Result(Status status, String installId, String token, int code)
		{
			this.status = status;
			this.installId = installId;
			this.token = token;
			this.code = code;
		}

		Status getStatus()
		{
			return status;
		}

		/** The install id the token was issued for (after any rotation). */
		String getInstallId()
		{
			return installId;
		}

		String getToken()
		{
			return token;
		}

		int getCode()
		{
			return code;
		}
	}

	private final OkHttpClient http;
	private final Gson gson;
	private final HttpUrl registerUrl;
	private final ServerIdentity identity;

	InstallAuth(OkHttpClient http, Gson gson, HttpUrl base, ServerIdentity identity)
	{
		this.http = http;
		this.gson = gson;
		this.registerUrl = base.newBuilder().addPathSegments("v1/register").build();
		this.identity = identity;
	}

	/** Adds the install token to a request. */
	static Request.Builder bearer(Request.Builder req, String token)
	{
		return req.header("Authorization", "Bearer " + token);
	}

	/**
	 * Register the current install id.
	 *
	 * @param rotateOnConflict on 409, switch to a new random install id and try once more (uploads); when false a
	 *                         409 is reported as {@link Status#CONFLICT} (deleting data: the id is gone or not ours)
	 * @param done             called exactly once with the outcome
	 */
	void register(boolean rotateOnConflict, Consumer<Result> done)
	{
		String installId = identity.installId();
		JsonObject body = new JsonObject();
		body.addProperty("installId", installId);
		Request request = new Request.Builder()
			.url(registerUrl)
			.post(RequestBody.create(JSON, gson.toJson(body)))
			.build();
		Call call;
		try
		{
			call = http.newCall(request);
		}
		catch (RuntimeException e)
		{
			log.warn("Couldn't start registration", e);
			deliver(done, new Result(Status.FAILED, installId, null, -1));
			return;
		}
		call.enqueue(new Callback()
		{
			@Override
			public void onFailure(Call c, IOException e)
			{
				log.debug("Registration failed: {}", e.getClass().getSimpleName());
				deliver(done, new Result(Status.FAILED, installId, null, -1));
			}

			@Override
			public void onResponse(Call c, Response response)
			{
				Result result;
				boolean retry = false;
				try (response)
				{
					int code = response.code();
					log.debug("Registration: HTTP {}", code);
					if (code == 200)
					{
						String token = readToken(response.body());
						if (InstallIdentity.isToken(token))
						{
							identity.setToken(installId, token);
							result = new Result(Status.OK, installId, token, code);
						}
						else
						{
							result = new Result(Status.FAILED, installId, null, code);
						}
					}
					else if (code == 409 && rotateOnConflict)
					{
						// Someone else holds this id (or it was deleted): continue as a new random install. When it is held by
						// another token, this PC's key for it was lost, and what it shared under it may still be on the server.
						if (DELETED_ERROR.equals(readError(response.body())))
						{
							identity.rotateInstallId();
						}
						else
						{
							identity.replaceInstallId();
						}
						retry = true;
						result = null;
					}
					else if (code == 409)
					{
						// Only an explicit "deleted" counts as already deleted; anything else may still have data.
						result = new Result(DELETED_ERROR.equals(readError(response.body())) ? Status.DELETED : Status.CONFLICT,
							installId, null, code);
					}
					else
					{
						result = new Result(Status.FAILED, installId, null, code);
					}
				}
				catch (RuntimeException e)
				{
					log.debug("Bad registration response: {}", e.getClass().getSimpleName());
					result = new Result(Status.FAILED, installId, null, -1);
				}
				if (retry)
				{
					register(false, done);
				}
				else
				{
					deliver(done, result);
				}
			}
		});
	}

	/** The "error" field of an error response, or null. */
	private String readError(ResponseBody body)
	{
		if (body == null)
		{
			return null;
		}
		try
		{
			JsonObject o = gson.fromJson(body.charStream(), JsonObject.class);
			JsonElement e = o == null ? null : o.get("error");
			return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
		}
		catch (RuntimeException ex)
		{
			return null;
		}
	}

	/** The "token" field of a register response, or null. Never logs the body. */
	private String readToken(ResponseBody body)
	{
		if (body == null)
		{
			return null;
		}
		JsonObject o = gson.fromJson(body.charStream(), JsonObject.class);
		JsonElement t = o == null ? null : o.get("token");
		return t != null && t.isJsonPrimitive() ? t.getAsString() : null;
	}

	private static void deliver(Consumer<Result> done, Result result)
	{
		try
		{
			done.accept(result);
		}
		catch (RuntimeException e)
		{
			log.warn("Registration callback failed", e);
		}
	}
}
