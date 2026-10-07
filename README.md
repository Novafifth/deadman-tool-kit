# Deadman Tool Kit

Grand Exchange prices, profit tracking and world-event markers for Old School RuneScape's **permanent Deadman world
(345)**. The plugin only does anything on world 345.

Deadman has its own economy: the regular GE price guides don't apply, and the market is thin. Deadman Tool Kit builds a
shared world 345 price index from the trades of the players who use it, and helps you keep track of your own.

## Features

- **Market prices for world 345.** Recent trades, last bought and sold prices, 24h averages and volume, open bids and
  asks, and a price chart (24h to 1 year) for any item, all from real world 345 trades.
- **Your offers vs the market.** Each open offer is flagged when someone sells below it, bids above it, or when it
  hasn't filled for a while. You can optionally get a notification.
- **Prices in the GE.** Setting up an offer shows that item's Deadman prices next to RuneLite's own GE text, and opens
  it in the side panel.
- **Profit tracking.** Realized profit today, over 7 days and all time, per item, worked out on your computer from your
  own trades.
- **Buy limit timers.** When your 4-hour GE buy limit for an item resets.
- **Breach countdown.** Time to the next weekend breach, and whether one is active right now.
- **Breaches and Deadman's Chests on the map.** When the game announces a breach or a chest, it is marked on the world
  map and the minimap, like clue and quest markers. Click a marker at the edge of the map to jump to it.
- **Your trade history.** Picks up the game's GE History tab, and can import the history RuneLite's own Grand
  Exchange plugin keeps (only when you click *Import*).

## Screenshots

Breaches and a Deadman's Chest on the world map. Markers that are off screen sit on the edge of the map as arrows.

![World map markers](./images/world-map.png)

The same events on the minimap, and Deadman prices when you set up an offer:

![Minimap arrows](./images/minimap.png) ![GE offer setup](./images/ge-offer.png)

The side panel: current events, your offers, profit and the market feed, and the view for a single item:

![Side panel](./images/panel.png) ![Item view](./images/item.png)

## Getting started

1. Install **Deadman Tool Kit** from the Plugin Hub and log in to world 345.
2. Open the panel (the gold coin icon in the sidebar) and follow the *Get started* card. Press **Connect** to see the
   shared market data, and open your **GE History** tab once to pick up past trades.
3. Settings are under **Configuration > Deadman Tool Kit**, or the gear button in the panel.

Your own trades, profit and the map markers work without connecting.

## Privacy

- Nothing is sent anywhere until you press **Connect**. It is off by default, and RuneLite shows its standard
  third-party warning first.
- Once connected, the plugin shares your own world 345 Grand Exchange trades and open offers (item, price, quantity,
  time), and the breach and chest broadcasts you see. They are sent with a random install id and a hashed account id.
  Your name is never sent, and the server never stores IP addresses.
- **Delete my shared data** in the panel removes everything your computer has shared. You can turn sharing off at any
  time.
- The full privacy notice:
  [deadman-tool-kit-107023686748.europe-north1.run.app/privacy](https://deadman-tool-kit-107023686748.europe-north1.run.app/privacy)

## Feedback

Found a bug or have an idea? [Open an issue](https://github.com/Novafifth/deadman-tool-kit/issues), or message
**@Novith** on Discord.

How it all works in detail: [docs/TECHNICAL.md](docs/TECHNICAL.md).
