package com.deadmantoolkit;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Anonymous, stable id for one game account: a salted SHA-256 of RuneLite's account hash. It lets the server keep
 * one account's trades together without ever learning the account hash or the player's name.
 */
final class AccountId
{
	/**
	 * Part of the id's format, so it keeps the plugin's first name: changing it would give every account a new id (the
	 * server's per-account data and trust would start over).
	 */
	static final String PREFIX = "deadman-ge-tracker:";
	private static final char[] HEX = "0123456789abcdef".toCharArray();

	private AccountId()
	{
	}

	/** Lowercase hex SHA-256 of {@code "deadman-ge-tracker:" + accountHash} (UTF-8): always 64 characters. */
	static String of(long accountHash)
	{
		MessageDigest md;
		try
		{
			md = MessageDigest.getInstance("SHA-256");
		}
		catch (NoSuchAlgorithmException e)
		{
			// Every Java platform is required to provide SHA-256.
			throw new IllegalStateException(e);
		}
		byte[] digest = md.digest((PREFIX + accountHash).getBytes(StandardCharsets.UTF_8));
		char[] out = new char[digest.length * 2];
		for (int i = 0; i < digest.length; i++)
		{
			out[i * 2] = HEX[(digest[i] >> 4) & 0xf];
			out[i * 2 + 1] = HEX[digest[i] & 0xf];
		}
		return new String(out);
	}
}
