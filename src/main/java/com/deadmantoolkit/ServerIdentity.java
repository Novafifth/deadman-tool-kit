package com.deadmantoolkit;

import java.util.List;

/**
 * This install's identity towards the collection server: the random install id and the install token the server
 * issued for it (POST v1/register). The token only authorises this install's uploads and the deletion of its data;
 * it must never be logged. Implementations are thread-safe (used from the executor, OkHttp threads and the EDT).
 */
interface ServerIdentity
{
	/** The install id, created (and saved) on first use. */
	String installId();

	/** The token issued for the current install id, or null when there is none yet. */
	String token();

	/** Store {@code token} for {@code installId}; ignored when the install id has changed meanwhile. */
	void setToken(String installId, String token);

	/** Forget the token of {@code installId} (the server refused it); ignored when the install id has changed. */
	void clearToken(String installId);

	/** Start over as a new, unlinkable source: a new random install id and no token. Returns the new id. */
	String rotateInstallId();

	/**
	 * Like {@link #rotateInstallId()}, for when the server gave the current id to another token (this PC's key was lost
	 * or replaced): what this PC shared under the old id may still be on the server, and can no longer be deleted from
	 * here, so the old id is remembered ({@link #lostInstallIds()}) for the player to quote in an erasure request.
	 */
	String replaceInstallId();

	/** Older install ids of this PC whose key was lost (oldest first; not secrets). */
	List<String> lostInstallIds();
}
