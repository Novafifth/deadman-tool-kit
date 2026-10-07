package com.deadmantoolkit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * {@link ServerIdentity} over a small key-value {@link Store} ("installId", "installToken"): the plugin's
 * {@link IdentityFile} (a map in tests). All methods are synchronized, so two first-run callers can't each create and
 * save a different id.
 */
class InstallIdentity implements ServerIdentity
{
	static final String KEY_INSTALL_ID = "installId";
	static final String KEY_INSTALL_TOKEN = "installToken";
	/** Comma-separated older install ids whose key was lost ({@link #replaceInstallId()}). */
	static final String KEY_LOST_IDS = "lostInstallIds";
	private static final int MAX_LOST_IDS = 8;
	/** The server's token format: base64url of 32 random bytes, no padding. Anything else is treated as no token. */
	private static final Pattern TOKEN = Pattern.compile("^[A-Za-z0-9_-]{43}$");

	/** Where the values live (a file in the plugin's data folder, a map in tests). */
	interface Store
	{
		String get(String key);

		void set(String key, String value);

		void unset(String key);
	}

	private final Store store;
	private final Supplier<String> newId;

	InstallIdentity(Store store, Supplier<String> newId)
	{
		this.store = store;
		this.newId = newId;
	}

	/** True for a value shaped like a server-issued token. */
	static boolean isToken(String s)
	{
		return s != null && TOKEN.matcher(s).matches();
	}

	@Override
	public synchronized String installId()
	{
		return TrackerStore.installId(() -> store.get(KEY_INSTALL_ID), id -> store.set(KEY_INSTALL_ID, id), newId);
	}

	@Override
	public synchronized String token()
	{
		String t = store.get(KEY_INSTALL_TOKEN);
		return isToken(t) ? t : null;
	}

	@Override
	public synchronized void setToken(String installId, String token)
	{
		if (isToken(token) && installId != null && installId.equals(store.get(KEY_INSTALL_ID)))
		{
			store.set(KEY_INSTALL_TOKEN, token);
		}
	}

	@Override
	public synchronized void clearToken(String installId)
	{
		if (installId != null && installId.equals(store.get(KEY_INSTALL_ID)) && store.get(KEY_INSTALL_TOKEN) != null)
		{
			store.unset(KEY_INSTALL_TOKEN);
		}
	}

	@Override
	public synchronized String rotateInstallId()
	{
		if (store.get(KEY_INSTALL_TOKEN) != null)
		{
			store.unset(KEY_INSTALL_TOKEN);
		}
		String id = newId.get();
		store.set(KEY_INSTALL_ID, id);
		return id;
	}

	@Override
	public synchronized String replaceInstallId()
	{
		String old = store.get(KEY_INSTALL_ID);
		if (old != null && old.length() == TrackerStore.INSTALL_ID_LENGTH)
		{
			List<String> lost = new ArrayList<>(lostInstallIds());
			lost.remove(old);
			lost.add(old);
			while (lost.size() > MAX_LOST_IDS)
			{
				lost.remove(0);
			}
			store.set(KEY_LOST_IDS, String.join(",", lost));
		}
		return rotateInstallId();
	}

	@Override
	public synchronized List<String> lostInstallIds()
	{
		String v = store.get(KEY_LOST_IDS);
		if (v == null || v.isEmpty())
		{
			return Collections.emptyList();
		}
		List<String> out = new ArrayList<>();
		for (String id : v.split(","))
		{
			if (id.length() == TrackerStore.INSTALL_ID_LENGTH)
			{
				out.add(id);
			}
		}
		return out;
	}
}
