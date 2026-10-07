package com.deadmantoolkit.ui;

import java.util.function.Consumer;

/**
 * "Import RuneLite GE history": reads, with the player's consent (the click), the trade history RuneLite's own Grand
 * Exchange plugin keeps for the logged-in world 345 account. Called on the EDT; must not block.
 */
public interface RuneLiteImportAction
{
	/**
	 * Start an import. Returns false when one is already running.
	 *
	 * @param onDone gets the result to show (e.g. "Imported 812 trades (Jul 29 - Oct 7) - 210 already recorded"),
	 *               on the EDT
	 */
	boolean start(Consumer<Result> onDone);

	/** The result line, and whether it reports a problem (the import was blocked or failed), shown in orange. */
	final class Result
	{
		private final String text;
		private final boolean problem;

		private Result(String text, boolean problem)
		{
			this.text = text;
			this.problem = problem;
		}

		/** The import ran (it may have added nothing). */
		public static Result done(String text)
		{
			return new Result(text, false);
		}

		/** The import couldn't run or failed. */
		public static Result problem(String text)
		{
			return new Result(text, true);
		}

		public String getText()
		{
			return text;
		}

		public boolean isProblem()
		{
			return problem;
		}
	}
}
