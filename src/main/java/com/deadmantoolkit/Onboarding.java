package com.deadmantoolkit;

/**
 * What the "Get started" card on the home view shows, and whether the one-time chat hint is worth sending.
 */
public final class Onboarding
{
	public enum Step
	{
		/** Nothing to do: no card. */
		HIDDEN,
		/** Not connected; GE History is already imported (or unknown while connected). */
		CONNECT,
		/** Connected, but this account's GE History hasn't been imported. */
		IMPORT,
		/** Neither. */
		CONNECT_AND_IMPORT,
		/** All done, and the "Imported N trades" line hasn't been dismissed yet. */
		IMPORTED_NOTICE,
	}

	private Onboarding()
	{
	}

	/**
	 * @param connected       the connected setting
	 * @param historyImported whether the logged-in account's GE History was read at least once; null when not logged
	 *                        in on world 345, so it isn't known
	 * @param importNotice    trades added by the first import, while that notice is still showing; else null
	 */
	public static Step state(boolean connected, Boolean historyImported, Integer importNotice)
	{
		boolean needConnect = !connected;
		// Unknown (not logged in) only shows the import steps next to the connect block. A connected player who
		// imported long ago shouldn't see the card every time the client sits on the login screen.
		boolean needImport = historyImported == null ? needConnect : !historyImported;
		if (needConnect && needImport)
		{
			return Step.CONNECT_AND_IMPORT;
		}
		if (needConnect)
		{
			return Step.CONNECT;
		}
		if (needImport)
		{
			return Step.IMPORT;
		}
		return importNotice != null ? Step.IMPORTED_NOTICE : Step.HIDDEN;
	}

	/** True when the card's import block should also say to log in on world 345 first. */
	public static boolean needsLogin(Boolean historyImported)
	{
		return historyImported == null;
	}

	/** The one-time chat hint is only worth sending when the player still has something to set up. */
	public static boolean shouldShowChatHint(boolean connected, boolean historyImported)
	{
		return !connected || !historyImported;
	}
}
