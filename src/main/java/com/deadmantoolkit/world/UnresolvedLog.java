package com.deadmantoolkit.world;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.StandardOpenOption;
import java.util.HashSet;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.util.Filepath;

/**
 * Broadcast places the plugin couldn't put on the map, appended to {@value #FILE_NAME} in the plugin folder (one JSON
 * object per line: time, kind, the place as the game wrote it) so the place list ({@link WorldPlaces#RESOURCE}) can be
 * improved. Each place is written once per session. The server keeps the same list across players (when connected),
 * which is where most new names will be found. Executor thread only (disk IO).
 */
@Slf4j
public final class UnresolvedLog
{
	public static final String FILE_NAME = "unknown-places.jsonl";
	/** A session never writes more lines than this (a flood of nonsense can't grow the file much). */
	static final int MAX_PER_SESSION = 100;

	/** The plugin's data folder; may do disk IO. */
	public interface Dir
	{
		Filepath get() throws IOException;
	}

	private final Dir dir;
	private final Gson gson;
	private final Set<String> written = new HashSet<>();

	public UnresolvedLog(Dir dir, Gson gson)
	{
		this.dir = dir;
		this.gson = gson;
	}

	/**
	 * Note a place that couldn't be resolved, or was only matched by a near spelling.
	 *
	 * @param ts      unix seconds
	 * @param test    fed by the developer tools, not the game
	 * @param matched the place a near spelling was read as, or null when it couldn't be placed at all
	 * @return true when a line was written
	 */
	public boolean record(WorldEventMessage.Kind kind, String place, long ts, boolean test, String matched)
	{
		String key = kind.id() + "|" + WorldLocations.key(place);
		if (written.size() >= MAX_PER_SESSION || !written.add(key))
		{
			return false;
		}
		JsonObject o = new JsonObject();
		o.addProperty("ts", ts);
		o.addProperty("kind", kind.id());
		o.addProperty("place", place);
		if (matched != null)
		{
			o.addProperty("readAs", matched);
		}
		if (test)
		{
			o.addProperty("test", true);
		}
		try
		{
			Filepath folder = dir.get();
			if (!folder.exists())
			{
				folder.createDirectories();
			}
			try (BufferedWriter w = folder.joinSegment(FILE_NAME)
				.openBufferedWriter(StandardOpenOption.CREATE, StandardOpenOption.APPEND))
			{
				w.write(gson.toJson(o));
				w.newLine();
			}
			return true;
		}
		catch (IOException ex)
		{
			log.debug("Couldn't note an unknown place: {}", ex.getClass().getSimpleName());
			written.remove(key);
			return false;
		}
	}
}
