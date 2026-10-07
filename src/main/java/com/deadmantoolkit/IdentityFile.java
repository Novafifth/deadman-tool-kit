package com.deadmantoolkit;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.util.Filepath;

/**
 * Where this computer's install id and install token live: a small JSON file in the plugin's data folder, not the
 * RuneLite config. ConfigManager logs every value it saves at debug level (so a token would end up in client.log with
 * --debug), broadcasts it to all plugins in a ConfigChanged event, and syncs the config to RuneLite's servers for
 * players with a RuneLite account; a synced install id would also make two PCs fight over one id.
 * <p>
 * The file is read once (lazily) and cached; every change is written straight away (temp file, then rename). When the
 * folder can't be written, the values stay in memory for this session. Values are never logged.
 * Not thread-safe on its own: {@link InstallIdentity} serialises all calls.
 */
@Slf4j
final class IdentityFile implements InstallIdentity.Store
{
	static final String FILE_NAME = "server-identity.json";

	private final ProfitStore.Dir dir;
	private final Gson gson;
	private Map<String, String> values;
	/**
	 * The file is there but couldn't be read (locked by another program, no permission): this session works from memory
	 * and never writes over it, so the install keeps its id and key for the next start.
	 */
	private boolean readOnly;

	IdentityFile(ProfitStore.Dir dir, Gson gson)
	{
		this.dir = dir;
		this.gson = gson;
	}

	@Override
	public String get(String key)
	{
		return load().get(key);
	}

	@Override
	public void set(String key, String value)
	{
		load().put(key, value);
		save();
	}

	@Override
	public void unset(String key)
	{
		if (load().remove(key) != null)
		{
			save();
		}
	}

	/** True once the file was read (so a caller on the EDT can avoid triggering the read). */
	boolean loaded()
	{
		return values != null;
	}

	private Map<String, String> load()
	{
		if (values != null)
		{
			return values;
		}
		values = new HashMap<>();
		Filepath file = null;
		try
		{
			file = dir.get().joinSegment(FILE_NAME);
		}
		catch (IOException | RuntimeException ex)
		{
			log.debug("Couldn't open the plugin data folder: {}", ex.getClass().getSimpleName());
		}
		if (file != null && file.isFile())
		{
			// Read first, then parse: Gson reports a failed read as a syntax error, and the two must differ here.
			String text = null;
			try
			{
				text = readText(file);
			}
			catch (IOException | RuntimeException ex)
			{
				// Unreadable right now (locked by another program, no permission), not broken: don't replace it with a
				// new id, which would lose the key for good.
				log.debug("Couldn't read the install identity file: {}", ex.getClass().getSimpleName());
				readOnly = true;
			}
			if (text != null)
			{
				try
				{
					JsonObject o = gson.fromJson(text, JsonObject.class);
					if (o != null)
					{
						for (String k : new String[]{InstallIdentity.KEY_INSTALL_ID, InstallIdentity.KEY_INSTALL_TOKEN,
							InstallIdentity.KEY_LOST_IDS})
						{
							JsonElement e = o.get(k);
							if (e != null && e.isJsonPrimitive())
							{
								values.put(k, e.getAsString());
							}
						}
					}
				}
				catch (RuntimeException ex)
				{
					// Not JSON: nothing in it can be used, so it is replaced on the next save.
					log.debug("Couldn't parse the install identity file: {}", ex.getClass().getSimpleName());
				}
			}
		}
		return values;
	}

	private static String readText(Filepath file) throws IOException
	{
		StringBuilder sb = new StringBuilder();
		char[] buf = new char[1024];
		try (BufferedReader r = file.openBufferedReader())
		{
			int n;
			while ((n = r.read(buf)) != -1)
			{
				sb.append(buf, 0, n);
			}
		}
		return sb.toString();
	}

	/** Writes the values (temp file, then rename). False when the folder can't be written; never logs a value. */
	private boolean save()
	{
		if (readOnly)
		{
			return false;
		}
		try
		{
			Filepath d = dir.get();
			if (!d.exists())
			{
				d.createDirectories();
			}
			JsonObject o = new JsonObject();
			for (Map.Entry<String, String> e : values.entrySet())
			{
				o.addProperty(e.getKey(), e.getValue());
			}
			Filepath file = d.joinSegment(FILE_NAME);
			Filepath tmp = d.joinSegment(FILE_NAME + ".tmp");
			try (BufferedWriter w = tmp.openBufferedWriter(StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
				StandardOpenOption.WRITE))
			{
				gson.toJson(o, w);
			}
			try
			{
				tmp.moveTo(file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			}
			catch (AtomicMoveNotSupportedException ex)
			{
				tmp.moveTo(file, StandardCopyOption.REPLACE_EXISTING);
			}
			return true;
		}
		catch (IOException | RuntimeException ex)
		{
			log.debug("Couldn't save the install identity file: {}", ex.getClass().getSimpleName());
			return false;
		}
	}
}
