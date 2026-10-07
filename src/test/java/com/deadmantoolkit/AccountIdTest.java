package com.deadmantoolkit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class AccountIdTest
{
	@Test
	public void knownVectors()
	{
		// sha256("deadman-ge-tracker:123456789")
		assertEquals("77c9f0b595b7169fa5ed9141da926410d64dcc0e29f230bfbf782246d49e6b01", AccountId.of(123456789L));
		// sha256("deadman-ge-tracker:-42")
		assertEquals("5e91e63dc4c2292f25e8b179ee7bab1cef0a7ab2603f3fbb76a5656af6350f27", AccountId.of(-42L));
	}

	@Test
	public void saltedLowercaseHex()
	{
		String id = AccountId.of(Long.MAX_VALUE);
		assertEquals(64, id.length());
		assertTrue(id.matches("[0-9a-f]{64}"));
		// Not the plain hash of the account hash: the prefix is included.
		assertNotEquals("15e2b0d3c33891ebb0f1ef609ec419420c20e320ce94c65fbc8c3312448eb225", AccountId.of(123456789L));
		assertNotEquals(AccountId.of(1L), AccountId.of(2L));
		assertEquals(AccountId.of(7L), AccountId.of(7L));
	}
}
