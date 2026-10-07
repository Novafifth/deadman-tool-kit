package com.deadmantoolkit;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.Supplier;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.Value;
import net.runelite.api.GrandExchangeOffer;

/**
 * Turns the raw per-slot offer updates the client gives us into trade events by diffing each update
 * against the last state we saw for that slot. Pure logic so it can be unit tested without a client.
 */
public class OfferTracker
{
	public static final int SLOTS = 8;
	private static final int MAX_COMPLETED = 64;

	public enum Status
	{
		EMPTY, ACTIVE, COMPLETE, CANCELLED
	}

	/** What the client currently shows in a slot. */
	@Value
	public static class Snapshot
	{
		int itemId;
		boolean buy;
		long price;
		int totalQty;
		int sold;
		long spent;
		Status status;

		/** Map the client's offer state to ours. */
		public static Snapshot of(GrandExchangeOffer o)
		{
			Status status;
			boolean buy;
			switch (o.getState())
			{
				case BUYING:
					status = Status.ACTIVE;
					buy = true;
					break;
				case BOUGHT:
					status = Status.COMPLETE;
					buy = true;
					break;
				case CANCELLED_BUY:
					status = Status.CANCELLED;
					buy = true;
					break;
				case SELLING:
					status = Status.ACTIVE;
					buy = false;
					break;
				case SOLD:
					status = Status.COMPLETE;
					buy = false;
					break;
				case CANCELLED_SELL:
					status = Status.CANCELLED;
					buy = false;
					break;
				default:
					status = Status.EMPTY;
					buy = false;
					break;
			}
			return new Snapshot(o.getItemId(), buy, o.getPrice(), o.getTotalQuantity(), o.getQuantitySold(), o.getSpent(), status);
		}
	}

	/** What we remember about a slot between updates (persisted per account). */
	@Data
	@NoArgsConstructor
	@AllArgsConstructor
	public static class SlotState
	{
		String offerKey;
		int itemId;
		boolean buy;
		long price;
		int totalQty;
		/** Units filled so far, for buy and sell offers alike. */
		int sold;
		/** Gp that changed hands so far, for buy and sell offers alike. */
		long spent;
		Status status;
		long placedAt;
		/** Last time the client showed us this offer (unix s). */
		long seenAt;
	}

	private final SlotState[] slots = new SlotState[SLOTS];
	/** Signatures of offers we watched finish, used to skip the same trades when they show up in GE History. */
	private final Deque<String> completed = new ArrayDeque<>();
	private final Supplier<String> keyGen;

	public OfferTracker(Supplier<String> keyGen)
	{
		this.keyGen = keyGen;
	}

	public SlotState getSlot(int slot)
	{
		return slots[slot];
	}

	public void setSlot(int slot, SlotState state)
	{
		slots[slot] = state;
	}

	public List<String> getCompleted()
	{
		return new ArrayList<>(completed);
	}

	public void setCompleted(List<String> sigs)
	{
		completed.clear();
		if (sigs != null)
		{
			sigs.stream().skip(Math.max(0, sigs.size() - MAX_COMPLETED)).forEach(completed::addLast);
		}
	}

	/**
	 * Record that every active offer was still unchanged at {@code ts}.
	 *
	 * @return the slots that changed, in slot order, so the caller can save them
	 */
	public List<Integer> markSeen(long ts)
	{
		List<Integer> changed = new ArrayList<>();
		for (int slot = 0; slot < SLOTS; slot++)
		{
			SlotState s = slots[slot];
			if (s != null && s.status == Status.ACTIVE)
			{
				s.seenAt = ts;
				changed.add(slot);
			}
		}
		return changed;
	}

	/**
	 * If a GE History entry matches an offer we already recorded live, consume that match and return true.
	 */
	public boolean consumeCompleted(String signature)
	{
		return completed.removeFirstOccurrence(signature);
	}

	public static String signature(boolean buy, int itemId, int qty, long total)
	{
		return TradeEvent.sideOf(buy) + "|" + itemId + "|" + qty + "|" + total;
	}

	/**
	 * Apply a new snapshot for a slot.
	 *
	 * @param late true while the client is replaying offers on login - anything new we see then happened while we weren't watching
	 * @return events produced by the change (possibly empty)
	 */
	public List<TradeEvent> update(int slot, Snapshot now, boolean late, long ts, int world)
	{
		List<TradeEvent> events = new ArrayList<>();
		SlotState prev = slots[slot];

		if (now.status == Status.EMPTY)
		{
			// Collected / cleared. Anything that filled since our last look was already reported via
			// the non-empty update that preceded collection, or will show up in GE History.
			slots[slot] = null;
			return events;
		}

		// A finished offer never becomes active again, so an active one in its place was placed anew, even with
		// identical terms (the EMPTY update for the collection in between can be missed, e.g. while logged out).
		boolean isNew = prev == null
			|| (prev.status != Status.ACTIVE && now.status == Status.ACTIVE)
			|| prev.itemId != now.itemId
			|| prev.buy != now.buy
			|| prev.price != now.price
			|| prev.totalQty != now.totalQty
			|| now.sold < prev.sold
			|| now.spent < prev.spent;

		SlotState cur;
		if (isNew)
		{
			cur = new SlotState(keyGen.get(), now.itemId, now.buy, now.price, now.totalQty, 0, 0, Status.ACTIVE, ts, 0);
			events.add(base(TradeEvent.PLACED, cur, slot, ts, world)
				.id(cur.offerKey + ":placed")
				.qty(now.totalQty)
				.price(now.price)
				.late(late)
				.build());
		}
		else
		{
			cur = new SlotState(prev.offerKey, prev.itemId, prev.buy, prev.price, prev.totalQty, prev.sold, prev.spent, prev.status, prev.placedAt, prev.seenAt);
		}

		int dQty = now.sold - cur.sold;
		long dSpent = now.spent - cur.spent;
		if (dQty > 0 && dSpent > 0)
		{
			events.add(base(TradeEvent.FILL, cur, slot, ts, world)
				.id(cur.offerKey + ":fill:" + now.sold)
				.qty(dQty)
				.price(TradeEvent.avgPrice(dSpent, dQty))
				.total(dSpent)
				.filled(now.sold)
				.late(late)
				.since(late && cur.seenAt > 0 ? cur.seenAt : null)
				.build());
		}

		if (now.status == Status.CANCELLED && cur.status != Status.CANCELLED && now.sold < now.totalQty)
		{
			events.add(base(TradeEvent.CANCELLED, cur, slot, ts, world)
				.id(cur.offerKey + ":cancelled")
				.qty(now.totalQty - now.sold)
				.price(now.price)
				.late(late)
				.build());
		}

		boolean finished = now.status == Status.COMPLETE || now.status == Status.CANCELLED;
		boolean wasFinished = cur.status == Status.COMPLETE || cur.status == Status.CANCELLED;
		if (finished && !wasFinished && now.sold > 0)
		{
			completed.addLast(signature(now.buy, now.itemId, now.sold, now.spent));
			while (completed.size() > MAX_COMPLETED)
			{
				completed.removeFirst();
			}
		}

		cur.sold = now.sold;
		cur.spent = now.spent;
		cur.status = now.status;
		cur.seenAt = ts;
		slots[slot] = cur;
		return events;
	}

	private static TradeEvent.TradeEventBuilder base(String kind, SlotState s, int slot, long ts, int world)
	{
		return TradeEvent.builder()
			.kind(kind)
			.side(TradeEvent.sideOf(s.buy))
			.itemId(s.itemId)
			.slot(slot)
			.offerKey(s.offerKey)
			.ts(ts)
			.world(world);
	}
}
