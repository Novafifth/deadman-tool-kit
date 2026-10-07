package com.deadmantoolkit;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.runelite.api.events.CommandExecuted;
import net.runelite.client.eventbus.Subscribe;

/**
 * The {@code ::breach} developer command ({@link BreachPreview}). Registered on the event bus only in developer mode
 * (RuneLite's {@code @Named("developerMode")}); normal installs never see it. Runs on the client thread.
 */
final class BreachCommands
{
	/** What the preview drives. */
	interface Target
	{
		/** The breach timer's clock offset now (zero when off). */
		Duration offset();

		/** Shift the breach timer's clock by {@code offset} and drop any correction from a game message. */
		void preview(Duration offset);

		/** Feed the breach message parser as if the game had sent {@code gameText}. */
		void gameMessage(String gameText);
	}

	private final Target target;
	/** Shows a line in the local chatbox only. */
	private final Consumer<String> chat;
	/** The real clock. */
	private final Supplier<Instant> clock;

	BreachCommands(Target target, Consumer<String> chat, Supplier<Instant> clock)
	{
		this.target = target;
		this.chat = chat;
		this.clock = clock;
	}

	@Subscribe
	public void onCommandExecuted(CommandExecuted e)
	{
		if (BreachPreview.COMMAND.equalsIgnoreCase(e.getCommand()))
		{
			run(e.getArguments());
		}
	}

	/** Run {@code ::breach <args>}; also used by the panel's dev tools buttons. Client thread. */
	void run(String[] args)
	{
		Optional<BreachPreview.Command> parsed = BreachPreview.parse(args);
		if (!parsed.isPresent())
		{
			chat.accept(BreachPreview.USAGE);
			return;
		}
		BreachPreview.Command c = parsed.get();
		if (c.getAction() == BreachPreview.Action.MSG)
		{
			target.gameMessage(BreachPreview.gameMessage(c.getHours(), c.getMinutes()));
		}
		else
		{
			target.preview(BreachPreview.offset(c.getAction(), clock.get(), target.offset()));
		}
		chat.accept(BreachPreview.confirmation(c));
	}
}
