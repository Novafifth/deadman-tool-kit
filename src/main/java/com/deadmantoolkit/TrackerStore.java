package com.deadmantoolkit;

import com.google.gson.Gson;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.config.ConfigManager;

/**
 * Per-account offer state and GE History signatures (in the RuneLite profile config), plus the install id and install
 * token ({@link ServerIdentity}, in a file in the plugin's data folder: {@link IdentityFile}). Each account / game mode
 * has its own RuneLite profile.
 */
@Slf4j
class TrackerStore implements ServerIdentity
{
	private static final String KEY_SLOT = "slot";
	private static final String KEY_COMPLETED = "completed";
	private static final String KEY_HISTORY = "history";
	/**
	 * The newest RuneLite trade-history record time (epoch ms) an import has looked at, per account. Records up to
	 * it were imported or found already recorded, so importing again skips them even without a local trade log.
	 */
	static final String KEY_RL_IMPORTED_THROUGH = "rlImportedThrough";
	/**
	 * The newest imported RuneLite trades ({@link RuneLiteImport#remember}), per account, so a GE History row read
	 * after the import isn't recorded a second time.
	 */
	static final String KEY_RL_IMPORTED_RECENT = "rlImportedRecent";
	/** Length of a UUID string; anything else stored under the install id key is replaced. */
	static final int INSTALL_ID_LENGTH = 36;

	private final ConfigManager configManager;
	private final Gson gson;
	private final InstallIdentity identity;
	private final IdentityFile identityFile;
	/** The plugin's data folder (set in startUp, before anything uses the identity). */
	private volatile ProfitStore.Dir dataDir;
	/** Read on the client thread, reset from startUp/shutDown. */
	private volatile String loadedProfile;

	@Inject
	TrackerStore(ConfigManager configManager, Gson gson)
	{
		this.configManager = configManager;
		this.gson = gson;
		this.identityFile = new IdentityFile(() ->
		{
			ProfitStore.Dir d = dataDir;
			if (d == null)
			{
				throw new IOException("no data folder yet");
			}
			return d.get();
		}, gson);
		this.identity = new InstallIdentity(identityFile, () -> UUID.randomUUID().toString());
	}

	/** Where the install id and token file lives (the plugin's data folder). Call before using the identity. */
	void useDataDir(ProfitStore.Dir dir)
	{
		this.dataDir = dir;
	}

	/** Reads the identity file now (off the client thread and the EDT: the executor at startUp). */
	void loadIdentity()
	{
		identity.token();
	}

	/**
	 * Whether this PC holds a token, without reading the file when that hasn't happened yet (EDT callers; startUp
	 * reads it on the executor right away).
	 */
	boolean hasToken()
	{
		synchronized (identity)
		{
			return identityFile.loaded() && identity.token() != null;
		}
	}

	/** Forget which profile was loaded, so the next {@link #ensureLoaded} reads it again. */
	void reset()
	{
		loadedProfile = null;
	}

	/** Load slot state for the current account into {@code tracker}, unless it's already loaded. */
	boolean ensureLoaded(OfferTracker tracker)
	{
		String profile = configManager.getRSProfileKey();
		if (profile == null)
		{
			return false;
		}
		if (profile.equals(loadedProfile))
		{
			return true;
		}
		for (int slot = 0; slot < OfferTracker.SLOTS; slot++)
		{
			String json = configManager.getRSProfileConfiguration(DeadmanToolKitConfig.GROUP, KEY_SLOT + slot);
			OfferTracker.SlotState state = null;
			if (json != null)
			{
				try
				{
					state = gson.fromJson(json, OfferTracker.SlotState.class);
				}
				catch (RuntimeException ex)
				{
					log.debug("Bad saved slot {}", slot, ex);
				}
			}
			tracker.setSlot(slot, state);
		}
		tracker.setCompleted(loadList(KEY_COMPLETED));
		loadedProfile = profile;
		return true;
	}

	void saveSlot(OfferTracker tracker, int slot)
	{
		OfferTracker.SlotState s = tracker.getSlot(slot);
		if (s == null)
		{
			configManager.unsetRSProfileConfiguration(DeadmanToolKitConfig.GROUP, KEY_SLOT + slot);
		}
		else
		{
			setProfileValue(KEY_SLOT + slot, gson.toJson(s));
		}
	}

	void saveCompleted(OfferTracker tracker)
	{
		setProfileValue(KEY_COMPLETED, gson.toJson(tracker.getCompleted()));
	}

	/** Row signatures from the last GE History read, newest first. */
	List<String> loadHistory()
	{
		return loadList(KEY_HISTORY);
	}

	/**
	 * True once this account's GE History has been read at least once. {@link #saveHistory} always writes the key
	 * (even an empty list), so players who imported before this existed count too. Needs a loaded profile.
	 */
	boolean historyImported()
	{
		return configManager.getRSProfileConfiguration(DeadmanToolKitConfig.GROUP, KEY_HISTORY) != null;
	}

	/** The RS profile {@link #ensureLoaded} last loaded, or null. */
	String loadedProfile()
	{
		return loadedProfile;
	}

	void saveHistory(List<String> signatures)
	{
		setProfileValue(KEY_HISTORY, gson.toJson(signatures));
	}

	/**
	 * The trade history RuneLite's own Grand Exchange plugin keeps for the current RS profile (raw JSON), or null.
	 * Only ever the current profile: never another account's. Read it on the client thread.
	 */
	String runeLiteTradeHistory()
	{
		return configManager.getRSProfileConfiguration(RuneLiteImport.CONFIG_GROUP, RuneLiteImport.CONFIG_KEY);
	}

	/** {@link #KEY_RL_IMPORTED_THROUGH} of the current profile, or 0. */
	long runeLiteImportedThrough()
	{
		String v = configManager.getRSProfileConfiguration(DeadmanToolKitConfig.GROUP, KEY_RL_IMPORTED_THROUGH);
		try
		{
			return v == null ? 0 : Math.max(0, Long.parseLong(v.trim()));
		}
		catch (NumberFormatException ex)
		{
			return 0;
		}
	}

	void saveRuneLiteImportedThrough(long epochMs)
	{
		setProfileValue(KEY_RL_IMPORTED_THROUGH, String.valueOf(epochMs));
	}

	/** {@link #KEY_RL_IMPORTED_RECENT} of the current profile (a new, mutable list). */
	List<String> loadRuneLiteRecent()
	{
		return loadList(KEY_RL_IMPORTED_RECENT);
	}

	void saveRuneLiteRecent(List<String> recent)
	{
		setProfileValue(KEY_RL_IMPORTED_RECENT, gson.toJson(recent));
	}

	/**
	 * When the current profile's 4-hour GE buy limit for {@code itemId} resets, as RuneLite's Grand Exchange plugin
	 * recorded it ({@link BuyLimitReset}), or null. A config read only; safe off the client thread.
	 */
	Instant buyLimitReset(int itemId)
	{
		return BuyLimitReset.parse(configManager.getRSProfileConfiguration(BuyLimitReset.CONFIG_GROUP, BuyLimitReset.key(itemId)));
	}

	/**
	 * Random id for this RuneLite install (pseudonymous: the server stores it with the hashed account id). Never the
	 * player's name. Synchronized (in {@link InstallIdentity}) so that two first-run callers (the upload executor and a
	 * shutdown flush) can't each create and save a different id.
	 */
	@Override
	public String installId()
	{
		return identity.installId();
	}

	/** The install token, or null. Never log it. */
	@Override
	public String token()
	{
		return identity.token();
	}

	@Override
	public void setToken(String installId, String token)
	{
		identity.setToken(installId, token);
	}

	@Override
	public void clearToken(String installId)
	{
		identity.clearToken(installId);
	}

	@Override
	public String rotateInstallId()
	{
		return identity.rotateInstallId();
	}

	@Override
	public String replaceInstallId()
	{
		return identity.replaceInstallId();
	}

	@Override
	public List<String> lostInstallIds()
	{
		return identity.lostInstallIds();
	}

	/** The stored id, or a new one (saved) when there is none or it isn't a UUID. Not atomic; callers lock. */
	static String installId(Supplier<String> read, Consumer<String> write, Supplier<String> newId)
	{
		String id = read.get();
		if (id == null || id.length() != INSTALL_ID_LENGTH)
		{
			id = newId.get();
			write.accept(id);
		}
		return id;
	}

	private void setProfileValue(String key, String value)
	{
		configManager.setRSProfileConfiguration(DeadmanToolKitConfig.GROUP, key, value);
	}

	private List<String> loadList(String key)
	{
		String json = configManager.getRSProfileConfiguration(DeadmanToolKitConfig.GROUP, key);
		if (json == null)
		{
			return new ArrayList<>();
		}
		try
		{
			String[] list = gson.fromJson(json, String[].class);
			return list == null ? new ArrayList<>() : new ArrayList<>(Arrays.asList(list));
		}
		catch (RuntimeException ex)
		{
			return new ArrayList<>();
		}
	}
}
