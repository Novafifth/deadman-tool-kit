package com.deadmantoolkit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class TrackerStoreTest
{
	private static final String UUID_A = "00000000-0000-0000-0000-00000000000a";
	private static final String UUID_B = "00000000-0000-0000-0000-00000000000b";

	@Test
	public void keepsStoredInstallId()
	{
		List<String> written = new ArrayList<>();
		assertEquals(UUID_A, TrackerStore.installId(() -> UUID_A, written::add, () -> UUID_B));
		assertEquals(0, written.size());
	}

	@Test
	public void createsAndSavesMissingOrBadInstallId()
	{
		List<String> written = new ArrayList<>();
		assertEquals(UUID_B, TrackerStore.installId(() -> null, written::add, () -> UUID_B));
		assertEquals(UUID_B, TrackerStore.installId(() -> "junk", written::add, () -> UUID_B));
		assertEquals(2, written.size());
		assertEquals(UUID_B, written.get(0));
	}

	private static final String TOKEN_A = FakeServer.tok('A');
	private static final String TOKEN_B = FakeServer.tok('B');

	@Test
	public void tokenSetClearAndRotate()
	{
		Map<String, String> config = new HashMap<>();
		InstallIdentity identity = FakeServer.identity(config);
		String id = identity.installId();
		assertEquals(id, config.get("installId"));
		assertNull(identity.token());

		identity.setToken(id, TOKEN_A);
		assertEquals(TOKEN_A, identity.token());
		assertEquals(TOKEN_A, config.get("installToken"));

		// A token for another id (the id was rotated meanwhile) is ignored, and so is a malformed one.
		identity.setToken("00000000-0000-0000-0000-000000000000", TOKEN_B);
		identity.setToken(id, "not-a-token");
		assertEquals(TOKEN_A, identity.token());

		identity.clearToken("00000000-0000-0000-0000-000000000000");
		assertEquals(TOKEN_A, identity.token());
		identity.clearToken(id);
		assertNull(identity.token());
		assertFalse(config.containsKey("installToken"));

		identity.setToken(id, TOKEN_B);
		String next = identity.rotateInstallId();
		assertNotEquals(id, next);
		assertEquals(next, identity.installId());
		assertEquals(next, config.get("installId"));
		assertNull("a new id has no token", identity.token());
		assertFalse(config.containsKey("installToken"));
	}

	@Test
	public void malformedStoredTokenCountsAsNone()
	{
		Map<String, String> config = new HashMap<>();
		config.put("installToken", "garbage");
		assertNull(FakeServer.identity(config).token());
	}

}
