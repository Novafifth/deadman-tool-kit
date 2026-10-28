package com.deadmantoolkit;

import com.deadmantoolkit.world.WorldEventMessage;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Consumer;
import lombok.Value;
import net.runelite.api.events.CommandExecuted;
import net.runelite.client.eventbus.Subscribe;

/**
 * The {@code ::dgt} developer command: a fresh-install preview of the panel, re-arming the one-time hints, and test
 * world events (fed through the same parser as the game's broadcasts, but never reported to the server).
 * Registered on the event bus only in developer mode; normal installs never see it. Runs on the client thread.
 */
final class DevCommands
{
	static final String COMMAND = "dgt";
	static final String USAGE = "Usage: ::dgt fresh | fresh off | hints | chest <place> | breach <place> [single|multi]"
		+ " | star <tier 1-9> | zone | events sample | events clear";

	enum Action
	{
		/** Show the panel as a brand-new install would (UI only; nothing real changes). */
		FRESH_ON,
		/** Back to the real state. */
		FRESH_OFF,
		/** Re-arm the one-time chat hint and show it now. */
		HINTS,
		/** A test broadcast: {@link Command#getGameText()}. */
		WORLD_EVENT,
		/** A wave of test events: a chest and breaches at known, rough, other-map and unknown places. */
		EVENTS_SAMPLE,
		/** Forget all world events. */
		EVENTS_CLEAR,
		/** A test shooting star of {@link Command#getTier()} on your tile. */
		STAR,
		/** Print what the game's Deadman zone widgets show here (to find the one that says safe / guarded / dangerous). */
		ZONE,
	}

	@Value
	static class Command
	{
		Action action;
		/** WORLD_EVENT: the broadcast to feed, in the game's wording. */
		String gameText;
		/** STAR: its tier (1-9). */
		int tier;

		Command(Action action, String gameText)
		{
			this(action, gameText, 0);
		}

		Command(Action action, String gameText, int tier)
		{
			this.action = action;
			this.gameText = gameText;
			this.tier = tier;
		}
	}

	/** The sample wave, in the game's wording. */
	static final String[] SAMPLE = {
		WorldEventMessage.gameText(WorldEventMessage.Kind.CHEST, "north of the Warriors' Guild", null),
		WorldEventMessage.gameText(WorldEventMessage.Kind.BREACH, "the Bone Yard", WorldEventMessage.Combat.MULTI),
		WorldEventMessage.gameText(WorldEventMessage.Kind.BREACH, "North of Asgarnia", WorldEventMessage.Combat.SINGLE),
		WorldEventMessage.gameText(WorldEventMessage.Kind.BREACH, "Zanaris", WorldEventMessage.Combat.SINGLE),
		WorldEventMessage.gameText(WorldEventMessage.Kind.BREACH, "the Whispering Glade", WorldEventMessage.Combat.MULTI),
	};

	/** What the command drives. */
	interface Target
	{
		void freshPreview(boolean on);

		void resetHints();

		/** Feed a test broadcast. */
		void worldEvent(String gameText);

		void clearWorldEvents();

		/** A test shooting star on your tile. */
		void testStar(int tier);

		/** What the Deadman zone widgets show at your spot, as one line. */
		String zoneInfo();
	}

	private final Target target;
	/** Shows a line in the local chatbox only. */
	private final Consumer<String> chat;

	DevCommands(Target target, Consumer<String> chat)
	{
		this.target = target;
		this.chat = chat;
	}

	@Subscribe
	public void onCommandExecuted(CommandExecuted e)
	{
		if (COMMAND.equalsIgnoreCase(e.getCommand()))
		{
			run(e.getArguments());
		}
	}

	/** Run {@code ::dgt <args>}; also used by the panel's dev tools buttons. Client thread. */
	void run(String[] args)
	{
		Optional<Command> parsed = parse(args);
		if (!parsed.isPresent())
		{
			chat.accept(USAGE);
			return;
		}
		Command c = parsed.get();
		switch (c.getAction())
		{
			case FRESH_ON:
				target.freshPreview(true);
				chat.accept("Fresh-install preview on (panel only; nothing real changed). ::dgt fresh off to end it.");
				break;
			case FRESH_OFF:
				target.freshPreview(false);
				chat.accept("Fresh-install preview off.");
				break;
			case HINTS:
				target.resetHints();
				break;
			case WORLD_EVENT:
				target.worldEvent(c.getGameText());
				chat.accept("Test event: " + c.getGameText());
				break;
			case EVENTS_SAMPLE:
				for (String text : SAMPLE)
				{
					target.worldEvent(text);
				}
				chat.accept("Test events: a chest and 4 breaches (one at a place the plugin doesn't know).");
				break;
			case EVENTS_CLEAR:
				target.clearWorldEvents();
				chat.accept("World events cleared.");
				break;
			case ZONE:
				chat.accept(target.zoneInfo());
				break;
			case STAR:
				target.testStar(c.getTier());
				chat.accept("Test star: tier " + c.getTier() + " on your tile.");
				break;
		}
	}

	/**
	 * {@code fresh [on|off]}, {@code hints}, {@code chest <place>}, {@code breach <place> [single|multi]},
	 * {@code star <tier>}, {@code zone}, {@code events sample}, {@code events clear}; case-insensitive.
	 */
	static Optional<Command> parse(String[] args)
	{
		if (args == null || args.length == 0)
		{
			return Optional.empty();
		}
		String first = args[0].toLowerCase(Locale.ROOT);
		String second = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : null;
		switch (first)
		{
			case "fresh":
				if (args.length > 2)
				{
					return Optional.empty();
				}
				if (second == null || second.equals("on"))
				{
					return Optional.of(new Command(Action.FRESH_ON, null));
				}
				return second.equals("off") ? Optional.of(new Command(Action.FRESH_OFF, null)) : Optional.empty();
			case "hints":
				return args.length == 1 ? Optional.of(new Command(Action.HINTS, null)) : Optional.empty();
			case "events":
				if (args.length != 2)
				{
					return Optional.empty();
				}
				return second.equals("sample") ? Optional.of(new Command(Action.EVENTS_SAMPLE, null))
					: second.equals("clear") ? Optional.of(new Command(Action.EVENTS_CLEAR, null)) : Optional.empty();
			case "zone":
				return args.length == 1 ? Optional.of(new Command(Action.ZONE, null)) : Optional.empty();
			case "star":
			{
				if (args.length != 2 || !second.matches("[1-9]"))
				{
					return Optional.empty();
				}
				return Optional.of(new Command(Action.STAR, null, Integer.parseInt(second)));
			}
			case "chest":
			{
				String place = String.join(" ", Arrays.copyOfRange(args, 1, args.length)).trim();
				return place.isEmpty() ? Optional.empty() : Optional.of(new Command(Action.WORLD_EVENT,
					WorldEventMessage.gameText(WorldEventMessage.Kind.CHEST, place, null)));
			}
			case "breach":
			{
				int end = args.length;
				WorldEventMessage.Combat combat = null;
				String last = args[end - 1].toLowerCase(Locale.ROOT);
				if (end > 2 && (last.equals("single") || last.equals("multi")))
				{
					combat = last.equals("multi") ? WorldEventMessage.Combat.MULTI : WorldEventMessage.Combat.SINGLE;
					end--;
				}
				String place = String.join(" ", Arrays.copyOfRange(args, 1, end)).trim();
				return place.isEmpty() ? Optional.empty() : Optional.of(new Command(Action.WORLD_EVENT,
					WorldEventMessage.gameText(WorldEventMessage.Kind.BREACH, place, combat)));
			}
			default:
				return Optional.empty();
		}
	}
}
