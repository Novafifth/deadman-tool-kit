package com.deadmantoolkit.ui;

/**
 * Developer-mode-only actions behind the panel's "Dev tools" row. The plugin passes null on normal installs, and the
 * row isn't built at all. Called on the EDT; implementations must not block.
 */
public interface DevTools
{
	/** Breach preview, same as {@code ::breach <action>}: "soon", "active", "despawn" or "off". */
	void breach(String action);

	/** Re-arm the one-time chat hint and show it in the chatbox now, so it can be previewed. */
	void resetHints();

	/** Test world events, same as {@code ::dgt events <action>}: "sample" or "clear". */
	void worldEvents(String action);
}
