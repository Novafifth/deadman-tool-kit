package com.deadmantoolkit;

import static com.deadmantoolkit.FakeServer.REGISTER;
import static com.deadmantoolkit.FakeServer.SUBMIT;
import static com.deadmantoolkit.FakeServer.awaitIdle;
import static com.deadmantoolkit.FakeServer.ioFailure;
import static com.deadmantoolkit.FakeServer.json;
import static com.deadmantoolkit.FakeServer.status;
import static com.deadmantoolkit.FakeServer.tok;
import static com.deadmantoolkit.FakeServer.token;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.read.ListAppender;
import com.google.gson.Gson;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;
import org.slf4j.LoggerFactory;

/** Install tokens on upload: register first, Authorization on every submit, one re-register on 401, never logged. */
public class TradeUploaderAuthTest
{
	private final Gson gson = new Gson();
	private final FakeServer server = new FakeServer();
	private final Map<String, String> config = new ConcurrentHashMap<>();
	private final InstallIdentity identity = FakeServer.identity(config);
	private final AtomicBoolean allowed = new AtomicBoolean(true);

	private TradeUploader uploader()
	{
		return new TradeUploader(server.client(), gson, FakeServer.BASE, ids ->
		{
		}, identity, allowed::get);
	}

	private static TradeEvent fill(String id)
	{
		return TradeEvent.builder().id(id).kind(TradeEvent.FILL).side(TradeEvent.BUY).itemId(4151).qty(1)
			.price(1).total(1L).ts(1).world(345).build();
	}

	@Test
	public void firstFlushRegistersThenSubmitsWithTheToken() throws InterruptedException
	{
		TradeUploader up = uploader();
		up.add(fill("e1"), "acct");
		up.flush();

		FakeServer.Seen reg = server.next();
		assertEquals(REGISTER, reg.path());
		assertNull(reg.auth());
		String id = identity.installId();
		assertEquals(id, reg.json().get("installId").getAsString());
		assertTrue(reg.request.body().contentType().toString().startsWith("application/json"));

		FakeServer.Seen sub = server.next();
		assertEquals(SUBMIT, sub.path());
		String token = identity.token();
		assertEquals("Bearer " + token, sub.auth());
		assertEquals(id, sub.json().get("installId").getAsString());
		awaitIdle(up);
		assertFalse(up.hasPending());

		// The token is kept: the next upload doesn't register again.
		up.add(fill("e2"), "acct");
		up.flush();
		FakeServer.Seen again = server.next();
		assertEquals(SUBMIT, again.path());
		assertEquals("Bearer " + token, again.auth());
		awaitIdle(up);
	}

	@Test
	public void nothingIsSentWhileNotConnectedOrSharing() throws InterruptedException
	{
		TradeUploader up = uploader();
		allowed.set(false);
		up.add(fill("e1"), "acct");
		up.flush();
		assertNull("nothing, not even a registration, before Connect", server.poll(200));
		assertFalse(up.isInFlight());
		assertNull(identity.token());

		// Allowed, but nothing queued: no registration either.
		allowed.set(true);
		TradeUploader empty = uploader();
		empty.flush();
		assertNull(server.poll(200));
		assertFalse(empty.isInFlight());

		up.flush();
		assertEquals(REGISTER, server.next().path());
		assertEquals(SUBMIT, server.next().path());
		awaitIdle(up);
	}

	@Test
	public void disconnectingDuringRegistrationStopsTheChain() throws InterruptedException
	{
		TradeUploader up = uploader();
		up.add(fill("e1"), "acct");
		server.beforeReply = () -> allowed.set(false);
		up.flush();
		assertEquals(REGISTER, server.next().path());
		awaitIdle(up);
		assertNull("no upload after the player disconnected", server.poll(200));
		assertTrue(up.hasPending());
	}

	@Test
	public void unauthorizedRegistersOnceAndResends() throws InterruptedException
	{
		String old = tok('o');
		String fresh = tok('n');
		String id = identity.installId();
		identity.setToken(id, old);
		server.on(SUBMIT, status(401), status(200)).on(REGISTER, token(fresh));
		TradeUploader up = uploader();
		up.add(fill("e1"), "acct");
		up.flush();

		FakeServer.Seen first = server.next();
		assertEquals(SUBMIT, first.path());
		assertEquals("Bearer " + old, first.auth());
		FakeServer.Seen reg = server.next();
		assertEquals(REGISTER, reg.path());
		assertEquals(id, reg.json().get("installId").getAsString());
		FakeServer.Seen retry = server.next();
		assertEquals(SUBMIT, retry.path());
		assertEquals("Bearer " + fresh, retry.auth());
		assertEquals("the same batch", first.json().get("events"), retry.json().get("events"));
		awaitIdle(up);
		assertEquals(fresh, identity.token());
		assertFalse(up.hasPending());
	}

	@Test
	public void secondUnauthorizedDoesNotLoopAndKeepsTheQueue() throws InterruptedException
	{
		identity.setToken(identity.installId(), tok('o'));
		server.on(SUBMIT, status(401), status(401));
		TradeUploader up = uploader();
		up.add(fill("e1"), "acct");
		up.flush();
		assertEquals(SUBMIT, server.next().path());
		assertEquals(REGISTER, server.next().path());
		assertEquals(SUBMIT, server.next().path());
		awaitIdle(up);
		assertNull("no loop", server.poll(300));
		assertTrue("queue kept", up.hasPending());

		// The next flush sends it again.
		up.flush();
		FakeServer.Seen s = server.next();
		assertEquals(SUBMIT, s.path());
		assertEquals("e1", s.json().getAsJsonArray("events").get(0).getAsJsonObject().get("id").getAsString());
		awaitIdle(up);
		assertFalse(up.hasPending());
	}

	@Test
	public void conflictOnRegisterRotatesTheInstallId() throws InterruptedException
	{
		String first = identity.installId();
		server.on(REGISTER, status(409));
		TradeUploader up = uploader();
		up.add(fill("e1"), "acct");
		up.flush();

		assertEquals(first, server.next().json().get("installId").getAsString());
		FakeServer.Seen second = server.next();
		assertEquals(REGISTER, second.path());
		String rotated = second.json().get("installId").getAsString();
		assertNotEquals(first, rotated);
		FakeServer.Seen sub = server.next();
		assertEquals(SUBMIT, sub.path());
		assertEquals("uploads use the id the token was issued for", rotated, sub.json().get("installId").getAsString());
		awaitIdle(up);
		assertEquals(rotated, identity.installId());
		assertEquals("another key holds the old id: it is remembered for an erasure request",
			Collections.singletonList(first), identity.lostInstallIds());
	}

	@Test
	public void aDeletedIdIsRotatedWithoutBeingRemembered() throws InterruptedException
	{
		String first = identity.installId();
		server.on(REGISTER, json(409, "{\"error\":\"installId deleted\"}"));
		TradeUploader up = uploader();
		up.add(fill("e1"), "acct");
		up.flush();
		assertEquals(first, server.next().json().get("installId").getAsString());
		assertEquals(REGISTER, server.next().path());
		assertEquals(SUBMIT, server.next().path());
		awaitIdle(up);
		assertNotEquals(first, identity.installId());
		assertTrue("its data is gone already", identity.lostInstallIds().isEmpty());
	}

	@Test
	public void twoConflictsGiveUpUntilTheNextFlush() throws InterruptedException
	{
		server.on(REGISTER, status(409), status(409));
		TradeUploader up = uploader();
		up.add(fill("e1"), "acct");
		up.flush();
		assertEquals(REGISTER, server.next().path());
		assertEquals(REGISTER, server.next().path());
		awaitIdle(up);
		assertNull(server.poll(200));
		assertTrue(up.hasPending());
	}

	@Test
	public void inFlightIsNeverStuck() throws InterruptedException
	{
		FakeServer.Reply[] registerFailures = {status(429), status(500), ioFailure(), json(200, "not json"),
			json(200, "{\"token\":\"too-short\"}"), json(200, "{}")};
		for (FakeServer.Reply r : registerFailures)
		{
			server.on(REGISTER, r);
			TradeUploader up = uploader();
			up.add(fill("e1"), "acct");
			up.flush();
			assertEquals(REGISTER, server.next().path());
			awaitIdle(up);
			assertNull(server.poll(100));
			assertTrue(up.hasPending());
			assertNull(identity.token());
		}

		// Submit failures with a token.
		identity.setToken(identity.installId(), tok('t'));
		for (FakeServer.Reply r : new FakeServer.Reply[]{ioFailure(), status(500), status(429)})
		{
			server.on(SUBMIT, r);
			TradeUploader up = uploader();
			up.add(fill("e1"), "acct");
			up.flush();
			assertEquals(SUBMIT, server.next().path());
			awaitIdle(up);
			assertTrue(up.hasPending());
		}

		// 401, then the re-registration fails.
		server.on(SUBMIT, status(401)).on(REGISTER, ioFailure());
		TradeUploader up = uploader();
		up.add(fill("e1"), "acct");
		up.flush();
		assertEquals(SUBMIT, server.next().path());
		assertEquals(REGISTER, server.next().path());
		awaitIdle(up);
		assertTrue(up.hasPending());
	}

	@Test
	public void pausedUploaderSendsNothingAndWhenIdleWaitsForTheChain() throws InterruptedException
	{
		java.util.concurrent.CountDownLatch hold = new java.util.concurrent.CountDownLatch(1);
		identity.setToken(identity.installId(), tok('t'));
		server.on(SUBMIT, FakeServer.heldUntil(hold));
		TradeUploader up = uploader();
		up.add(fill("e1"), "acct");
		up.flush();
		assertEquals(SUBMIT, server.next().path());
		assertTrue(up.isInFlight());

		up.pause();
		AtomicBoolean ran = new AtomicBoolean();
		up.whenIdle(() -> ran.set(true));
		assertFalse("still in flight", ran.get());
		hold.countDown();
		assertTrue(FakeServer.await(ran::get));

		up.add(fill("e2"), "acct");
		up.flush();
		assertNull("paused", server.poll(200));
		up.resume();
		up.flush();
		assertEquals(SUBMIT, server.next().path());
		awaitIdle(up);

		// Idle: runs right away.
		AtomicBoolean now = new AtomicBoolean();
		up.whenIdle(() -> now.set(true));
		assertTrue(now.get());
		assertFalse(up.isInFlight());
	}

	@Test
	public void everyUploadCarriesTheToken() throws InterruptedException
	{
		String id = identity.installId();
		identity.setToken(id, tok('u'));
		TradeUploader up = uploader();
		up.add(fill("e1"), null);
		up.flush();
		FakeServer.Seen s = server.next();
		assertEquals(SUBMIT, s.path());
		assertEquals("Bearer " + tok('u'), s.auth());
		awaitIdle(up);
	}

	@Test
	public void theTokenIsNeverLogged() throws InterruptedException
	{
		Logger pluginLogger = (Logger) LoggerFactory.getLogger("com.deadmantoolkit");
		Level oldLevel = pluginLogger.getLevel();
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		pluginLogger.addAppender(appender);
		pluginLogger.setLevel(Level.ALL);
		try
		{
			String old = tok('q');
			String a = tok('r');
			String b = tok('s');
			identity.setToken(identity.installId(), old);
			// 401 -> re-register -> 401 again; then a 409 rotation and a malformed register body.
			server.on(SUBMIT, status(401), status(401), status(500))
				.on(REGISTER, token(a), status(409), token(b));
			TradeUploader up = new TradeUploader(server.client(), gson, FakeServer.BASE, ids ->
			{
				throw new IllegalStateException("listener boom");
			}, identity, allowed::get);
			up.add(fill("e1"), "acct");
			up.flush();
			for (int i = 0; i < 3; i++)
			{
				server.next();
			}
			awaitIdle(up);
			identity.clearToken(identity.installId());
			up.flush();
			for (int i = 0; i < 3; i++)
			{
				server.next();
			}
			awaitIdle(up);
			server.on(REGISTER, json(200, "{\"token\":\"" + old + "\" broken"));
			identity.clearToken(identity.installId());
			up.flush();
			server.next();
			awaitIdle(up);

			assertFalse("the test should exercise some logging", appender.list.isEmpty());
			for (ILoggingEvent e : appender.list)
			{
				String text = e.getFormattedMessage() + throwableText(e.getThrowableProxy());
				for (String t : new String[]{old, a, b})
				{
					assertFalse("token in log: " + text, text.contains(t));
				}
			}
		}
		finally
		{
			pluginLogger.detachAppender(appender);
			pluginLogger.setLevel(oldLevel);
		}
	}

	private static String throwableText(IThrowableProxy t)
	{
		StringBuilder sb = new StringBuilder();
		for (IThrowableProxy p = t; p != null; p = p.getCause())
		{
			sb.append(' ').append(p.getClassName()).append(": ").append(p.getMessage());
		}
		return sb.toString();
	}
}
