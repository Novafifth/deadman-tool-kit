package com.deadmantoolkit.ui;

import com.deadmantoolkit.ActiveOffer;
import com.deadmantoolkit.OfferWatch;
import lombok.Value;
import net.runelite.client.util.QuantityFormatter;

/**
 * The text of one of your open offers in a list (pure): the offer, a badge on the right (undercut / outbid / stale,
 * or how much has filled) and a tooltip.
 */
@Value
class OfferLine
{
	enum Badge
	{
		/** Undercut or outbid. */
		ALERT,
		STALE,
		/** Just the fill percentage. */
		PLAIN
	}

	ActiveOffer offer;
	String text;
	String badge;
	Badge kind;
	String tooltip;

	/** Row identity: a new offer in the same slot is a new row. */
	String key()
	{
		return offer.getSlot() + "|" + offer.getOfferKey();
	}

	/**
	 * For the home view, which lists every item: "Sell 1.25M Dragon bones". The price comes before the name, so at
	 * the panel's width a long name is what gets cut off, not the price.
	 *
	 * @param status the offer's market status, or null (not connected, not checked yet)
	 * @param now    unix seconds
	 */
	static OfferLine home(ActiveOffer o, OfferWatch.OfferStatus status, long now)
	{
		String name = o.getName() == null ? "Item " + o.getItemId() : o.getName();
		String text = (o.isBuy() ? "Buy " : "Sell ") + QuantityFormatter.quantityToStackSize(o.getPrice()) + " " + name;
		return of(o, text, status, now);
	}

	/** For the item view, which already names the item: "Selling @ 1,250,000 · 40/100". */
	static OfferLine item(ActiveOffer o, OfferWatch.OfferStatus status, long now)
	{
		return of(o, ItemSections.offerText(o), status, now);
	}

	private static OfferLine of(ActiveOffer o, String text, OfferWatch.OfferStatus s, long now)
	{
		String badge;
		Badge kind;
		OfferWatch.Flag flag = s == null ? OfferWatch.Flag.OK : s.getFlag();
		switch (flag)
		{
			case UNDERCUT:
				badge = "undercut";
				kind = Badge.ALERT;
				break;
			case OUTBID:
				badge = "outbid";
				kind = Badge.ALERT;
				break;
			case STALE:
				badge = "stale " + OfferWatch.hours(s.getIdleSecs());
				kind = Badge.STALE;
				break;
			default:
				badge = ItemSections.offerRight(o);
				kind = Badge.PLAIN;
				break;
		}
		String ago = o.getPlacedAt() > 0 ? Format.ago(o.getPlacedAt(), now) : "";
		String placed = ago.isEmpty() ? "" : "now".equals(ago) ? " · placed just now" : " · placed " + ago + " ago";
		String filled = QuantityFormatter.formatNumber(o.getFilled()) + "/" + QuantityFormatter.formatNumber(o.getTotalQty())
			+ " filled" + placed;
		String tooltip = s == null ? text + " · " + filled : text + " · " + s.getDetail() + " · " + filled;
		return new OfferLine(o, text, badge, kind, tooltip);
	}
}
