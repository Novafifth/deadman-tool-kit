package com.deadmantoolkit.ui;

import java.util.function.Consumer;

/** What the panel's Privacy menu can do with the data this computer shared. Called on the EDT; must not block. */
public interface SharedDataActions
{
	/** Whether this computer may have shared anything: it has an install token, or it is connected. */
	boolean mayHaveShared();

	/** Whether a deletion is running now. */
	boolean isDeleting();

	/**
	 * Delete everything this computer shared, then disconnect. Returns false when a deletion is already running.
	 *
	 * @param onDone gets the message to show the player, on the EDT
	 */
	boolean delete(Consumer<String> onDone);
}
