package com.deadmantoolkit;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;

/**
 * Reads a text file's lines from the end towards the start, a chunk at a time, so the newest lines of a large
 * append-only log can be read without parsing the rest. Lines are split on the '\n' byte, which never occurs inside
 * a multi-byte UTF-8 sequence, so a character split across two chunks is decoded correctly. A trailing '\r' is
 * removed and empty lines are skipped. Not thread safe.
 */
final class ReverseLineReader implements Closeable
{
	static final int DEFAULT_CHUNK = 64 * 1024;

	private final SeekableByteChannel channel;
	private final int chunk;
	/** File offset of {@code data[0]}; everything before it hasn't been read yet. */
	private long pos;
	/** Bytes [pos, pos + len) of the file that haven't been returned as lines yet. */
	private byte[] data = new byte[0];
	private int len;

	ReverseLineReader(SeekableByteChannel channel) throws IOException
	{
		this(channel, DEFAULT_CHUNK);
	}

	ReverseLineReader(SeekableByteChannel channel, int chunkSize) throws IOException
	{
		if (chunkSize < 1)
		{
			throw new IllegalArgumentException("chunk size " + chunkSize);
		}
		this.channel = channel;
		this.chunk = chunkSize;
		this.pos = channel.size();
	}

	/** The line before the one returned last (the last line on the first call), or null at the start of the file. */
	String readLine() throws IOException
	{
		while (true)
		{
			int nl = lastNewline();
			if (nl >= 0)
			{
				String line = decode(nl + 1, len);
				len = nl;
				if (line != null)
				{
					return line;
				}
				continue;
			}
			if (pos == 0)
			{
				// The first line of the file has no newline before it.
				String line = decode(0, len);
				len = 0;
				return line;
			}
			readChunk();
		}
	}

	private int lastNewline()
	{
		for (int i = len - 1; i >= 0; i--)
		{
			if (data[i] == '\n')
			{
				return i;
			}
		}
		return -1;
	}

	/** Bytes [from, to) as a line without a trailing '\r', or null if that leaves it empty. */
	private String decode(int from, int to)
	{
		if (to > from && data[to - 1] == '\r')
		{
			to--;
		}
		return to > from ? new String(data, from, to - from, StandardCharsets.UTF_8) : null;
	}

	/** Read the chunk before {@link #pos} in front of the bytes not yet returned. */
	private void readChunk() throws IOException
	{
		int n = (int) Math.min(chunk, pos);
		byte[] next = new byte[n + len];
		System.arraycopy(data, 0, next, n, len);
		ByteBuffer buf = ByteBuffer.wrap(next, 0, n);
		long start = pos - n;
		channel.position(start);
		while (buf.hasRemaining())
		{
			if (channel.read(buf) < 0)
			{
				throw new IOException("File shrank while reading");
			}
		}
		data = next;
		len += n;
		pos = start;
	}

	@Override
	public void close() throws IOException
	{
		channel.close();
	}
}
