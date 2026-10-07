package com.deadmantoolkit;

import static com.deadmantoolkit.Onboarding.Step.CONNECT;
import static com.deadmantoolkit.Onboarding.Step.CONNECT_AND_IMPORT;
import static com.deadmantoolkit.Onboarding.Step.HIDDEN;
import static com.deadmantoolkit.Onboarding.Step.IMPORT;
import static com.deadmantoolkit.Onboarding.Step.IMPORTED_NOTICE;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class OnboardingTest
{
	@Test
	public void loggedIn()
	{
		assertEquals(CONNECT_AND_IMPORT, Onboarding.state(false, false, null));
		assertEquals(CONNECT, Onboarding.state(false, true, null));
		assertEquals(IMPORT, Onboarding.state(true, false, null));
		assertEquals(HIDDEN, Onboarding.state(true, true, null));
	}

	@Test
	public void notLoggedIn()
	{
		// Unknown import state: the steps (with "log in first") only ride along with the connect block.
		assertEquals(CONNECT_AND_IMPORT, Onboarding.state(false, null, null));
		assertEquals(HIDDEN, Onboarding.state(true, null, null));
		assertTrue(Onboarding.needsLogin(null));
		assertFalse(Onboarding.needsLogin(false));
		assertFalse(Onboarding.needsLogin(true));
	}

	@Test
	public void importNotice()
	{
		assertEquals(IMPORTED_NOTICE, Onboarding.state(true, true, 12));
		assertEquals(IMPORTED_NOTICE, Onboarding.state(true, true, 0));
		// Still not connected: the connect block stays (the card adds the notice line to it).
		assertEquals(CONNECT, Onboarding.state(false, true, 12));
	}

	@Test
	public void chatHint()
	{
		assertTrue(Onboarding.shouldShowChatHint(false, false));
		assertTrue(Onboarding.shouldShowChatHint(false, true));
		assertTrue(Onboarding.shouldShowChatHint(true, false));
		assertFalse(Onboarding.shouldShowChatHint(true, true));
	}
}
