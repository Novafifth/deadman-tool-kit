package com.deadmantoolkit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.google.gson.Gson;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Collections;
import java.util.UUID;
import net.runelite.client.util.Filepath;
import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.slf4j.LoggerFactory;

/** The install id and token live in a file in the plugin folder, never in the (logged, shared, synced) config. */
public class IdentityFileTest
{
	@Rule
	public TemporaryFolder tmp = new TemporaryFolder();

	private final Gson gson = new Gson();

	private Filepath dir()
	{
		return Filepath.Unchecked.getRooted(tmp.getRoot().toPath()).joinSegment("deadman-tool-kit");
	}

	private Path file()
	{
		return tmp.getRoot().toPath().resolve("deadman-tool-kit").resolve(IdentityFile.FILE_NAME);
	}

	private InstallIdentity identity()
	{
		return new InstallIdentity(new IdentityFile(this::dir, gson), () -> UUID.randomUUID().toString());
	}

	@Test
	public void tokenAndIdGoToTheFileAndSurviveARestart() throws IOException
	{
		InstallIdentity a = identity();
		String id = a.installId();
		String token = FakeServer.tok('t');
		a.setToken(id, token);
		String text = new String(Files.readAllBytes(file()), StandardCharsets.UTF_8);
		assertTrue(text.contains(id) && text.contains(token));

		InstallIdentity b = identity();
		assertEquals(id, b.installId());
		assertEquals(token, b.token());
		b.clearToken(id);
		assertNull(identity().token());
		assertNotEquals(id, b.rotateInstallId());
		assertNotEquals(id, identity().installId());
	}

	@Test
	public void anUnwritableFolderKeepsTheValuesForTheSession()
	{
		InstallIdentity a = new InstallIdentity(new IdentityFile(() ->
		{
			throw new IOException("read-only");
		}, gson), () -> UUID.randomUUID().toString());
		String id = a.installId();
		assertEquals("the same id all session", id, a.installId());
		a.setToken(id, FakeServer.tok('s'));
		assertEquals(FakeServer.tok('s'), a.token());
	}

	@Test
	public void aFileThatCantBeReadRightNowIsNeverReplaced() throws IOException
	{
		String id = "00000000-0000-0000-0000-0000000000cc";
		Files.createDirectories(file().getParent());
		Files.write(file(), ("{\"installId\":\"" + id + "\",\"installToken\":\"" + FakeServer.tok('u') + "\"}")
			.getBytes(StandardCharsets.UTF_8));
		InstallIdentity a;
		String temp;
		try (FileChannel ch = FileChannel.open(file(), StandardOpenOption.WRITE); FileLock lock = ch.lock())
		{
			boolean blocked;
			try
			{
				Files.readAllBytes(file());
				blocked = false;
			}
			catch (IOException ex)
			{
				blocked = true;
			}
			Assume.assumeTrue("needs a lock that blocks reads (Windows)", blocked);
			a = identity();
			temp = a.installId();
			assertNotEquals(id, temp);
		}
		// Readable again later in the session: the session's values still stay in memory only.
		a.setToken(temp, FakeServer.tok('v'));
		assertEquals(FakeServer.tok('v'), a.token());
		assertEquals("the file still holds this PC's id and key", id, identity().installId());
		assertEquals(FakeServer.tok('u'), identity().token());
	}

	@Test
	public void lostIdsAreKeptInTheFile()
	{
		InstallIdentity a = identity();
		String first = a.installId();
		String second = a.replaceInstallId();
		a.rotateInstallId();
		assertNotEquals(first, second);
		assertEquals(Collections.singletonList(first), identity().lostInstallIds());
	}

	@Test
	public void aCorruptFileIsReplacedAndNothingIsLogged() throws IOException
	{
		Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
		ListAppender<ILoggingEvent> logs = new ListAppender<>();
		logs.start();
		root.addAppender(logs);
		Level level = root.getLevel();
		root.setLevel(Level.DEBUG);
		try
		{
			Files.createDirectories(file().getParent());
			Files.write(file(), "{not json".getBytes(StandardCharsets.UTF_8));
			InstallIdentity a = identity();
			String id = a.installId();
			String token = FakeServer.tok('c');
			a.setToken(id, token);
			assertEquals(token, identity().token());
			for (ILoggingEvent e : logs.list)
			{
				assertFalse(e.getFormattedMessage().contains(token));
			}
		}
		finally
		{
			root.detachAppender(logs);
			root.setLevel(level);
		}
	}
}
