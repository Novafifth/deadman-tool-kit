package com.deadmantoolkit;

import static org.junit.Assert.assertEquals;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

public class ReverseLineReaderTest
{
	private static final int[] CHUNKS = {1, 2, 3, 5, 64 * 1024};

	/** An in-memory channel, so no files are needed. */
	private static final class BytesChannel implements SeekableByteChannel
	{
		private final byte[] bytes;
		private long position;
		private boolean open = true;

		BytesChannel(byte[] bytes)
		{
			this.bytes = bytes;
		}

		@Override
		public int read(ByteBuffer dst)
		{
			if (position >= bytes.length)
			{
				return -1;
			}
			int n = (int) Math.min(dst.remaining(), bytes.length - position);
			dst.put(bytes, (int) position, n);
			position += n;
			return n;
		}

		@Override
		public int write(ByteBuffer src)
		{
			throw new UnsupportedOperationException();
		}

		@Override
		public long position()
		{
			return position;
		}

		@Override
		public SeekableByteChannel position(long p)
		{
			position = p;
			return this;
		}

		@Override
		public long size()
		{
			return bytes.length;
		}

		@Override
		public SeekableByteChannel truncate(long size)
		{
			throw new UnsupportedOperationException();
		}

		@Override
		public boolean isOpen()
		{
			return open;
		}

		@Override
		public void close()
		{
			open = false;
		}
	}

	private static List<String> readAll(String text, int chunk) throws IOException
	{
		List<String> lines = new ArrayList<>();
		try (ReverseLineReader r = new ReverseLineReader(new BytesChannel(text.getBytes(StandardCharsets.UTF_8)), chunk))
		{
			String line;
			while ((line = r.readLine()) != null)
			{
				lines.add(line);
			}
			// Stays at the start.
			assertEquals(null, r.readLine());
		}
		return lines;
	}

	private static void check(String text, String... newestFirst) throws IOException
	{
		for (int chunk : CHUNKS)
		{
			assertEquals("chunk " + chunk, Arrays.asList(newestFirst), readAll(text, chunk));
		}
	}

	@Test
	public void linesComeBackNewestFirst() throws IOException
	{
		check("one\ntwo\nthree\n", "three", "two", "one");
	}

	@Test
	public void noTrailingNewline() throws IOException
	{
		check("one\ntwo", "two", "one");
	}

	@Test
	public void emptyFile() throws IOException
	{
		check("");
		check("\n\n\r\n");
	}

	@Test
	public void blankLinesAreSkipped() throws IOException
	{
		check("\none\n\n\ntwo\n\n", "two", "one");
	}

	@Test
	public void crlfIsStripped() throws IOException
	{
		check("one\r\ntwo\r\n", "two", "one");
	}

	@Test
	public void multiByteCharactersSplitAcrossChunks() throws IOException
	{
		// "×" and "é" are two bytes each; small chunk sizes split them.
		check("{\"n\":\"3 × café\"}\n{\"n\":\"é×é\"}\n", "{\"n\":\"é×é\"}", "{\"n\":\"3 × café\"}");
		check("×\né", "é", "×");
	}

	@Test
	public void longLinesSpanManyChunks() throws IOException
	{
		String a = String.join("", Collections.nCopies(500, "a")), b = String.join("", Collections.nCopies(300, "bé"));
		check(a + "\n" + b + "\n", b, a);
	}
}
