package com.deadmantoolkit;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.util.Filepath;

/**
 * Uploads not sent yet, kept in {@value #FILE_NAME} in the plugin's data folder so closing RuneLite (or a crash, or
 * imports the server asked to send later) doesn't lose them. The server ignores events it already has (it de-duplicates
 * by event id), so resending a spooled event that did reach it is harmless. The file is removed when nothing is waiting,
 * which includes when sharing is turned off (the queue is cleared then). Executor thread only: this is disk IO.
 */
@Slf4j
final class UploadSpool
{
	static final String FILE_NAME = "pending-uploads.json";

	/** The plugin's data folder; may do disk IO. */
	interface Dir
	{
		Filepath get() throws IOException;
	}

	private final Dir dir;
	private final Gson gson;
	/** What was last written, so an unchanged queue isn't rewritten every 30 seconds. */
	private List<TradeUploader.Queued> lastSaved = Collections.emptyList();
	/** A failed save was already logged as a warning. */
	private boolean warned;

	UploadSpool(Dir dir, Gson gson)
	{
		this.dir = dir;
		this.gson = gson;
	}

	/** The spooled uploads, oldest first; empty when there is no file or it can't be read. */
	List<TradeUploader.Queued> load()
	{
		try
		{
			Filepath file = dir.get().joinSegment(FILE_NAME);
			if (!file.exists())
			{
				return Collections.emptyList();
			}
			TradeUploader.Queued[] list;
			try (BufferedReader r = file.openBufferedReader())
			{
				list = gson.fromJson(r, TradeUploader.Queued[].class);
			}
			List<TradeUploader.Queued> out = new ArrayList<>();
			if (list != null)
			{
				for (TradeUploader.Queued q : list)
				{
					if (q != null && q.getEvent() != null && q.getEvent().getId() != null && q.getEvent().getKind() != null)
					{
						out.add(q);
					}
				}
			}
			lastSaved = out;
			return out;
		}
		catch (IOException | JsonParseException ex)
		{
			log.warn("Couldn't read the pending uploads file; those uploads are skipped", ex);
			return Collections.emptyList();
		}
	}

	/** Write what's still waiting (or remove the file when nothing is). Atomic: a temp file is moved into place. */
	void save(List<TradeUploader.Queued> pending)
	{
		if (pending.equals(lastSaved))
		{
			return;
		}
		try
		{
			Filepath folder = dir.get();
			Filepath file = folder.joinSegment(FILE_NAME);
			if (pending.isEmpty())
			{
				file.deleteIfExists();
			}
			else
			{
				if (!folder.exists())
				{
					folder.createDirectories();
				}
				Filepath tmp = folder.joinSegment(FILE_NAME + ".tmp");
				try (BufferedWriter w = tmp.openBufferedWriter(StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING))
				{
					gson.toJson(pending.toArray(new TradeUploader.Queued[0]), w);
				}
				tmp.moveTo(file, StandardCopyOption.REPLACE_EXISTING);
			}
			lastSaved = new ArrayList<>(pending);
			warned = false;
		}
		catch (IOException ex)
		{
			// Retried every 30 seconds: warn once, then only at debug level until a save works again.
			if (!warned)
			{
				warned = true;
				log.warn("Couldn't save the pending uploads file", ex);
			}
			else
			{
				log.debug("Couldn't save the pending uploads file: {}", ex.getClass().getSimpleName());
			}
		}
	}
}
