package com.deadmantoolkit;

import net.runelite.api.GameState;

/**
 * Tells a real login (or world hop / reconnect) apart from the LOGGED_IN that follows every loading screen.
 * The client goes LOGGED_IN -> LOADING -> LOGGED_IN on each region change, so a LOGGED_IN only counts as a login
 * when the state before it (ignoring LOADING) was the login screen, logging in, hopping or a lost connection.
 * Client thread only.
 */
class LoginDetector
{
	/** The last game state seen other than LOADING, or null when unknown. */
	private GameState previous;
	/**
	 * Reset while a loading screen was showing (mid-login or mid region change): the next LOGGED_IN is treated as a
	 * login so the open offers still get sent, at the cost of at most one extra short late window.
	 */
	private boolean resetDuringLoading;

	/** Start over from the client's current state, e.g. when the plugin is enabled. */
	void reset(GameState current)
	{
		resetDuringLoading = current == GameState.LOADING;
		previous = resetDuringLoading ? null : current;
	}

	/** Feed a game state change. Returns true if it is a login. */
	boolean update(GameState state)
	{
		if (state == GameState.LOADING)
		{
			// Loading screens sit between the states we care about; skip over them.
			return false;
		}
		boolean login = isLogin(previous, state) || (resetDuringLoading && state == GameState.LOGGED_IN);
		resetDuringLoading = false;
		previous = state;
		return login;
	}

	/** True when moving from {@code previous} (ignoring LOADING) to {@code current} is a login. */
	static boolean isLogin(GameState previous, GameState current)
	{
		if (current != GameState.LOGGED_IN || previous == null)
		{
			return false;
		}
		switch (previous)
		{
			case LOGIN_SCREEN:
			case LOGIN_SCREEN_AUTHENTICATOR:
			case LOGGING_IN:
			case HOPPING:
			case CONNECTION_LOST:
				return true;
			default:
				return false;
		}
	}
}
