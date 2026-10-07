package com.deadmantoolkit;

import static com.deadmantoolkit.FakeServer.ME;
import static com.deadmantoolkit.FakeServer.REGISTER;
import static com.deadmantoolkit.FakeServer.SUBMIT;
import static com.deadmantoolkit.FakeServer.json;
import static com.deadmantoolkit.FakeServer.status;
import static com.deadmantoolkit.FakeServer.tok;
import static com.deadmantoolkit.FakeServer.token;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import com.google.gson.Gson;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;

/** "Delete my shared data": DELETE v1/me with the token, then disconnect and start over as a new install. */
public class DataDeletionTest
{
	private final Gson gson = new Gson();
	private final FakeServer server = new FakeServer();
	private final Map<String, String> config = new ConcurrentHashMap<>();
	private final InstallIdentity identity = FakeServer.identity(config);
	/** The "connected" setting. */
	private final AtomicBoolean connected = new AtomicBoolean(true);
	private final TradeUploader uploader = new TradeUploader(server.client(), gson, FakeServer.BASE, ids ->
	{
	}, identity, connected::get);
	private final DataDeletion deletion = new DataDeletion(server.client(), gson, FakeServer.BASE, identity, uploader,
		() -> connected.set(false));
	private final BlockingQueue<DataDeletion.Result> results = new LinkedBlockingQueue<>();

	private static TradeEvent fill(String id)
	{
		return TradeEvent.builder().id(id).kind(TradeEvent.FILL).side(TradeEvent.BUY).itemId(4151).qty(1)
			.price(1).total(1L).ts(1).world(345).build();
	}

	private DataDeletion.Result result() throws InterruptedException
	{
		DataDeletion.Result r = results.poll(5, TimeUnit.SECONDS);
		assertNotNull("expected a result", r);
		return r;
	}

	@Test
	public void deletesWithTheTokenThenDisconnectsAndStartsOver() throws InterruptedException
	{
		String id = identity.installId();
		String token = tok('d');
		identity.setToken(id, token);
		uploader.add(fill("e1"), "acct");

		assertTrue(deletion.start(results::add));
		FakeServer.Seen del = server.next();
		assertEquals("DELETE", del.request.method());
		assertEquals(ME, del.path());
		assertEquals("Bearer " + token, del.auth());
		assertEquals(id, del.json().get("installId").getAsString());
		assertTrue(del.request.body().contentType().toString().startsWith("application/json"));

		DataDeletion.Result r = result();
		assertEquals(DataDeletion.Outcome.DELETED, r.outcome);
		assertFalse("disconnected", connected.get());
		assertNull("token cleared", identity.token());
		assertNotEquals("new install id", id, identity.installId());
		assertFalse("queue cleared", uploader.hasPending());
		assertFalse(uploader.isPaused());
		assertFalse(deletion.isRunning());
		assertNull(server.poll(100));
	}

	@Test
	public void acceptedForLaterIsPending() throws InterruptedException
	{
		identity.setToken(identity.installId(), tok('d'));
		server.on(ME, status(202));
		deletion.start(results::add);
		assertEquals(DataDeletion.Outcome.PENDING, result().outcome);
		assertFalse(connected.get());
		assertNull(identity.token());
	}

	@Test
	public void serverErrorKeepsEverything() throws InterruptedException
	{
		String id = identity.installId();
		String token = tok('d');
		identity.setToken(id, token);
		uploader.add(fill("e1"), "acct");
		server.on(ME, status(500));
		deletion.start(results::add);
		server.next();
		DataDeletion.Result r = result();
		assertEquals(DataDeletion.Outcome.FAILED, r.outcome);
		assertEquals(500, r.code);
		assertTrue("still connected", connected.get());
		assertEquals(id, identity.installId());
		assertEquals(token, identity.token());
		assertFalse("uploads resumed", uploader.isPaused());
		assertTrue("queue kept", uploader.hasPending());
		assertEquals("Couldn't delete your data (server error 500). Try again later.", DataDeletion.message(r));

		uploader.flush();
		assertEquals(SUBMIT, server.next().path());
		FakeServer.awaitIdle(uploader);
	}

	@Test
	public void unreachableServerFails() throws InterruptedException
	{
		identity.setToken(identity.installId(), tok('d'));
		server.on(ME, FakeServer.ioFailure());
		deletion.start(results::add);
		DataDeletion.Result r = result();
		assertEquals(DataDeletion.Outcome.FAILED, r.outcome);
		assertEquals("Couldn't reach the Deadman Tool Kit server. Try again later.", DataDeletion.message(r));
		assertTrue(connected.get());
	}

	@Test
	public void waitsForTheUploadInFlight() throws InterruptedException
	{
		identity.setToken(identity.installId(), tok('d'));
		CountDownLatch hold = new CountDownLatch(1);
		server.on(SUBMIT, FakeServer.heldUntil(hold));
		uploader.add(fill("e1"), "acct");
		uploader.flush();
		assertEquals(SUBMIT, server.next().path());

		assertTrue(deletion.start(results::add));
		assertFalse("only one deletion at a time", deletion.start(results::add));
		assertTrue(uploader.isPaused());
		assertNull("waits for the upload", server.poll(300));
		hold.countDown();
		assertEquals(ME, server.next().path());
		assertEquals(DataDeletion.Outcome.DELETED, result().outcome);
		assertNull(results.poll(100, TimeUnit.MILLISECONDS));
	}

	@Test
	public void withoutATokenRegistersFirst() throws InterruptedException
	{
		String id = identity.installId();
		connected.set(false); // deleting is an explicit action, allowed while disconnected
		server.on(REGISTER, token(tok('l')));
		deletion.start(results::add);
		FakeServer.Seen reg = server.next();
		assertEquals(REGISTER, reg.path());
		assertEquals(id, reg.json().get("installId").getAsString());
		FakeServer.Seen del = server.next();
		assertEquals(ME, del.path());
		assertEquals("Bearer " + tok('l'), del.auth());
		assertEquals(DataDeletion.Outcome.DELETED, result().outcome);
	}

	@Test
	public void alreadyDeletedCountsAsNothingToDelete() throws InterruptedException
	{
		String id = identity.installId();
		identity.setToken(id, tok('d'));
		// The token is refused, and the server says the id was deleted: nothing left to delete.
		server.on(ME, status(401)).on(REGISTER, json(409, "{\"error\":\"installId deleted\"}"));
		deletion.start(results::add);
		assertEquals(ME, server.next().path());
		assertEquals(REGISTER, server.next().path());
		DataDeletion.Result r = result();
		assertEquals(DataDeletion.Outcome.NOTHING, r.outcome);
		assertFalse(connected.get());
		assertNotEquals(id, identity.installId());
		assertNull(server.poll(100));
	}

	@Test
	public void aLostKeyIsNeverReportedAsNothingToDelete() throws InterruptedException
	{
		String id = identity.installId();
		identity.setToken(id, tok('d'));
		uploader.add(fill("q1"), "acct");
		// The token is refused, and another token holds the id: the data may still be there. Also for a 409 without
		// the server's explicit "deleted" (or an unreadable body), and for the no-token path.
		server.on(ME, status(401)).on(REGISTER, json(409, "{\"error\":\"installId already registered\"}"));
		deletion.start(results::add);
		assertEquals(ME, server.next().path());
		assertEquals(REGISTER, server.next().path());
		DataDeletion.Result r = result();
		assertEquals(DataDeletion.Outcome.KEY_LOST, r.outcome);
		assertEquals(id, r.installId);
		assertFalse(connected.get());
		assertEquals("the id is kept so the player can quote it", id, identity.installId());
		assertNull(identity.token());
		assertTrue(DataDeletion.message(r).contains(id));
		assertFalse(DataDeletion.message(r).contains("nothing to delete"));

		connected.set(true);
		server.on(REGISTER, status(409));
		deletion.start(results::add);
		assertEquals(REGISTER, server.next().path());
		assertEquals(DataDeletion.Outcome.KEY_LOST, result().outcome);
		assertEquals(id, identity.installId());
		assertNull(server.poll(100));
	}

	@Test
	public void unauthorizedRegistersOnceAndRetries() throws InterruptedException
	{
		identity.setToken(identity.installId(), tok('d'));
		server.on(ME, status(401), status(200)).on(REGISTER, token(tok('m')));
		deletion.start(results::add);
		assertEquals("Bearer " + tok('d'), server.next().auth());
		assertEquals(REGISTER, server.next().path());
		assertEquals("Bearer " + tok('m'), server.next().auth());
		assertEquals(DataDeletion.Outcome.DELETED, result().outcome);

		// A second 401 with a fresh token is a failure, not a loop.
		DataDeletion again = new DataDeletion(server.client(), gson, FakeServer.BASE, identity, uploader,
			() -> connected.set(false));
		connected.set(true);
		identity.setToken(identity.installId(), tok('d'));
		server.on(ME, status(401), status(401));
		again.start(results::add);
		for (int i = 0; i < 3; i++)
		{
			server.next();
		}
		DataDeletion.Result r = result();
		assertEquals(DataDeletion.Outcome.FAILED, r.outcome);
		assertEquals(401, r.code);
		assertNull(server.poll(200));
		assertTrue(connected.get());
	}

	@Test
	public void cancelledDeletionStillUpdatesLocalStateButReportsNothing() throws InterruptedException
	{
		identity.setToken(identity.installId(), tok('d'));
		CountDownLatch hold = new CountDownLatch(1);
		server.on(ME, FakeServer.heldUntil(hold));
		deletion.start(results::add);
		server.next();
		deletion.cancel();
		hold.countDown();
		assertTrue(FakeServer.await(() -> !connected.get()));
		assertTrue(FakeServer.await(() -> !deletion.isRunning()));
		assertNull(results.poll(200, TimeUnit.MILLISECONDS));
	}

	@Test
	public void messages()
	{
		assertEquals("Your shared data was deleted. The plugin is now disconnected.",
			DataDeletion.message(new DataDeletion.Result(DataDeletion.Outcome.DELETED, 200)));
		assertTrue(DataDeletion.message(new DataDeletion.Result(DataDeletion.Outcome.PENDING, 202))
			.startsWith("Your shared data is being deleted"));
		assertTrue(DataDeletion.message(new DataDeletion.Result(DataDeletion.Outcome.NOTHING, 409))
			.contains("nothing to delete"));
		String lost = DataDeletion.message(new DataDeletion.Result(DataDeletion.Outcome.KEY_LOST, 409, "0f0e-id"));
		assertTrue(lost.contains("0f0e-id"));
		assertTrue(lost.contains("Privacy notice"));
		String older = DataDeletion.message(new DataDeletion.Result(DataDeletion.Outcome.DELETED, 200, "new-id",
			Arrays.asList("old-1", "old-2")));
		assertTrue(older.startsWith("Your shared data was deleted."));
		assertTrue(older.contains("old-1, old-2") && older.contains("those ids") && older.contains("Privacy notice"));
		assertFalse(DataDeletion.message(new DataDeletion.Result(DataDeletion.Outcome.FAILED, 500, null,
			Collections.singletonList("old-1"))).contains("old-1"));
	}
}
