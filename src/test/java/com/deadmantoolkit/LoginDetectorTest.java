package com.deadmantoolkit;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import net.runelite.api.GameState;
import org.junit.Test;

public class LoginDetectorTest
{
	@Test
	public void rule()
	{
		assertTrue(LoginDetector.isLogin(GameState.LOGIN_SCREEN, GameState.LOGGED_IN));
		assertTrue(LoginDetector.isLogin(GameState.LOGGING_IN, GameState.LOGGED_IN));
		assertTrue(LoginDetector.isLogin(GameState.HOPPING, GameState.LOGGED_IN));
		assertTrue(LoginDetector.isLogin(GameState.CONNECTION_LOST, GameState.LOGGED_IN));
		assertFalse(LoginDetector.isLogin(GameState.LOGGED_IN, GameState.LOGGED_IN));
		assertFalse(LoginDetector.isLogin(null, GameState.LOGGED_IN));
		assertFalse(LoginDetector.isLogin(GameState.LOGGING_IN, GameState.LOADING));
		assertFalse(LoginDetector.isLogin(GameState.LOGIN_SCREEN, GameState.LOGGING_IN));
	}

	@Test
	public void loadingScreensAfterLoginAreNotLogins()
	{
		LoginDetector d = new LoginDetector();
		d.reset(GameState.LOGIN_SCREEN);
		assertFalse(d.update(GameState.LOGGING_IN));
		assertTrue(d.update(GameState.LOGGED_IN));
		// Region changes: LOADING then LOGGED_IN again.
		assertFalse(d.update(GameState.LOADING));
		assertFalse(d.update(GameState.LOGGED_IN));
		assertFalse(d.update(GameState.LOADING));
		assertFalse(d.update(GameState.LOGGED_IN));
	}

	@Test
	public void hopReconnectAndReloginCountEvenThroughLoading()
	{
		LoginDetector d = new LoginDetector();
		d.reset(GameState.LOGGED_IN);
		assertFalse(d.update(GameState.HOPPING));
		assertFalse(d.update(GameState.LOADING));
		assertTrue(d.update(GameState.LOGGED_IN));
		assertFalse(d.update(GameState.CONNECTION_LOST));
		assertTrue(d.update(GameState.LOGGED_IN));
		assertFalse(d.update(GameState.LOGIN_SCREEN));
		assertFalse(d.update(GameState.LOGGING_IN));
		assertFalse(d.update(GameState.LOADING));
		assertTrue(d.update(GameState.LOGGED_IN));
	}

	@Test
	public void enabledWhileLoggedInIsNotALogin()
	{
		LoginDetector d = new LoginDetector();
		d.reset(GameState.LOGGED_IN);
		assertFalse(d.update(GameState.LOADING));
		assertFalse(d.update(GameState.LOGGED_IN));

		// Enabled during a loading screen: the LOGGED_IN after it counts once, so the open offers still get sent.
		d.reset(GameState.LOADING);
		assertTrue(d.update(GameState.LOGGED_IN));
		// Later loading screens are not logins.
		assertFalse(d.update(GameState.LOADING));
		assertFalse(d.update(GameState.LOGGED_IN));

		// Reset during loading but the next state is not LOGGED_IN (e.g. disconnected): flag is consumed.
		d.reset(GameState.LOADING);
		assertFalse(d.update(GameState.CONNECTION_LOST));
		assertTrue(d.update(GameState.LOGGED_IN));
		d.reset(GameState.LOADING);
		assertFalse(d.update(GameState.LOGIN_SCREEN));
		assertFalse(d.update(GameState.LOADING));
		assertTrue(d.update(GameState.LOGGED_IN));
	}
}
