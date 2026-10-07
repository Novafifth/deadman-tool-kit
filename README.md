# Deadman Tool Kit

A RuneLite plugin that records your own Grand Exchange trades on the permanent Deadman world and, if you opt in,
shares them to build a Deadman price index.

## What it does

- Records your GE offers as they are placed, partial and complete fills at the actual price paid or received, and
  cancellations.
- Imports trades from the in-game GE History tab. These have no timestamp and are shown as "history".
- Only when you click **Import RuneLite GE history**, imports the trade history RuneLite's own Grand Exchange plugin
  keeps for the account you are logged in with (see [below](#importing-runelites-ge-history)). These are shown as
  "imported" with an approximate time.
- Adds a sidebar panel with:
  - a countdown to the next weekend breach (see below);
  - item search, a settings (gear) button and the connection status;
  - a "Get started" card until you have connected and imported your GE History;
  - "Your offers": each of your open world 345 offers, flagged *undercut* (someone sells below your sell price),
    *outbid* (someone bids above your buy price) or *stale* (no fill for a while) while connected, otherwise how
    much has filled. Offers are compared with the open order book and, in the item view, with live fills made since
    you placed the offer (never GE History rows, whose time is only when they were imported);
  - "Profit": your realized profit today, over the last 7 days and all time, and your best items (see
    [Profit](#profit));
  - a market feed and a "My trades" list (50 rows at a time, with "Show more"), each with a refresh button and
    when it was last updated (or, after a failed refresh, why, e.g. "Update failed: server unreachable"; the old data
    stays);
  - an item view with your open offers for the item, stats, a price chart (24h / 7d / 30d / 90d / 1y), the
    open-offer book, recent trades from all players, your own trades and your profit on the item. Your own
    offers and fills show up there immediately; new trades are briefly highlighted. On world 345 it also shows
    when your 4-hour GE buy limit for the item resets ("Buy limit resets in 2h 14m"), from RuneLite's own Grand
    Exchange plugin, while that window is running.
- Opens the item in the panel when you set up a GE offer for it (can be turned off).
- While connected, adds a line to the GE offer setup's item text with the Deadman prices: last bought / sold, best
  bid / ask and units traded in the last 24 hours, e.g. `DMM last 1.25M/1.24M · bid 1.23M ask 1.26M · 523/24h`.
  On a buy offer, while your 4-hour buy limit window for the item is running (RuneLite's own Grand Exchange plugin
  records when it resets), the line also says when it resets: `... · limit resets in 2h 14m` (the bid / ask part is
  left out when the line would get too long). It does so only when RuneLite's Grand Exchange plugin isn't already
  showing that reset on the same screen (by default it is, as "Buy limit: 70 (2:14)"), so by default the line keeps
  the bid / ask part.
  RuneLite's own GE text stays as it is.
- Marks the game's breach and Deadman's Chest broadcasts on the world map and minimap and lists them in the panel
  (see [World events](#world-events)).
- Optionally notifies you when one of your offers gets undercut or outbid.
- Adds a "DMM prices" right-click option on tradeable items.
- On your first login on world 345 it shows one chat message (only in your own chatbox) pointing at the panel.

## Settings

All settings are on RuneLite's configuration page (**Configuration > Deadman Tool Kit**, or the gear button in
the panel):

- **Data sharing**: *Connect to Deadman Tool Kit server* (off by default; shows the warning below when turned on)
  and *Share my trades* (on once connected).
- **Panel**: *Open item when setting up a GE offer* and *Show breach timer* (both on by default).
- **Offer alerts**: *Flag offers with no fill for (hours)* (6 by default) and *Notify when undercut or outbid* (off by
  default; uses RuneLite's notification settings). The market checks need the connection.
- **World events**: *Show breaches and chests*, *Chests on the map*, *Breaches on the map* and *Arrows on the
  minimap* (all on by default; the two "on the map" settings hide one kind from the world map and minimap, the panel
  still lists it) and *Hint arrow to the chest* (off by default). *Share
  world events* (under Data sharing, on once connected) sends and receives them; see [World events](#world-events).
- **Local**: *Save local trade log* (on by default).

## Breach countdown

Permanent Deadman breaches happen every Saturday and Sunday at 02:00, 06:00, 10:00, 14:00, 18:00 and 22:00 UTC.
The banner at the top of the panel shows the next one in your own time zone, then "Breach active" for the ~15
minutes bosses spawn and the ~30 minutes until any left despawn. When the game tells you "The next breach will
appear in ..." on world 345, the countdown is corrected from that message. Nothing about breaches is sent anywhere.

**Developer mode only:** the `::breach` command previews breaches so the banner can be tested on a weekday. It is
registered only when RuneLite runs in developer mode (`./gradlew run`) and only shifts the breach timer's clock or
feeds its message parser; the banner then runs through its phases on its own. `::breach soon` (starts in 2 minutes),
`::breach active` (started just now), `::breach despawn` (spawning ended just now), `::breach msg <h> <m>` (as if the
game said "The next breach will appear in h hour(s), m minute(s)") and `::breach off` (the real schedule). Each prints
a confirmation in your own chatbox.

## World events

On world 345 the game broadcasts where breaches open ("A breach has spawned at the Bone Yard! ... (Multi)") and,
about once an hour around :15, where a Deadman's Chest appears ("A Deadman's Chest has spawned at north of the
Warriors' Guild!"). The plugin reads those broadcasts and:

- marks each one on the world map, like clue and quest markers: a pin whose tip is the spot; off screen, a badge on
  the edge of the map with an arrow turned towards the event (click it and the map moves there). Hovering shows
  "Focus on Breach (Multi): North of Edgeville", with how rough the marker is when it isn't exact;
- with *Arrows on the minimap* (on by default), shows them on the minimap: at the spot when close, otherwise as an
  arrow on the minimap's edge pointing towards them (a place on another map is pointed at through its entrance);
- lists them under the breach banner in the panel: the chest with how long ago it spawned, each breach with Single /
  Multi and how long it has left. Click a line to point the game's hint arrow at it (click again to stop);
- with *Hint arrow to the chest* on, keeps the hint arrow on the current chest when its spot is known.

Times come from the broadcast itself, not the clock: the game is up to a minute or so off :00 / :15. A chest stays
listed for 10 minutes (it's soon looted) or until the next one, a breach for 45 minutes (15 spawning, 30 until the
rest despawn).

**Where is it?** Broadcasts name a place, not coordinates. The plugin matches the text against a list of places
(`world-places.json`: towns, guilds, landmarks, Wilderness spots, plus kingdoms and regions), ignoring case and
punctuation, "the", compass spelling ("Northwest" / "north-west") and wording such as "Throughout", "Within",
"Surrounding", "On top of", "The mounds at":

- a known place ("Zanaris", "the Bone Yard"), or the game's own wording of a known chest or breach spot ("North of
  the Edgeville Monastery"), is marked exactly; places on another map (Zanaris, the Mole Hole, Mor Ul Rek) get a
  second marker at their surface entrance;
- a near spelling or shortening of a whole name ("North of Edge Monastry") is read as that place when every word
  lines up and only one place fits; the marker says what it was read as, and such wordings are noted like unknown
  ones so they can be added;
- a direction from a place ("north of the Warriors' Guild") is marked about 24 tiles that way, as approximate;
- a kingdom or region ("Kingdom of Asgarnia", "North of Asgarnia", "the southern Asgarnian kingdom") gets a rough
  marker in that part of the region, with a "?" badge;
- anything else isn't marked; it is listed in the panel as "place unknown" and noted (see below).

The place list ships with the plugin, and the server serves the current one, so places can be added or fixed without
a plugin update: while connected the plugin checks for a newer list hourly and keeps it in the plugin folder for
offline use.

**Places it couldn't map** are appended to `unknown-places.jsonl` in the plugin folder (time, kind and the place as
the game wrote it; once per place per session), and, while sharing, counted on the server, where the list of places
plugins couldn't map is what the place list is grown from.

**Sharing** (connected, *Share world events* on): the plugin sends each broadcast it receives (the place text, when it
arrived and how exactly it could be mapped, with the install id only: no account id, never your position) and, when
you log in on world 345 and every 5 minutes, fetches the events broadcast before you logged in. The server lists an
event once two players reported it (or one established player), and deletes who reported what after 2 days. Reports
use the install token the uploads registered; until there is one, nothing is sent.

**Developer mode only:** `::dgt chest <place>`, `::dgt breach <place> [single|multi]`, `::dgt events sample` (a chest
and four breaches, including a region, a place on another map and an unknown place) and `::dgt events clear`; the
panel's Dev tools have *World events: Sample / Clear*. Test events go through the same parser but are never sent to
the server.

## Profit

Profit is worked out locally from your own world 345 fills, GE History rows and imported RuneLite trades, per
account, and never leaves your computer.

- For each item it keeps the units you hold (bought minus sold, within what the plugin has seen), their average cost
  (a moving average) and the profit realized when you sell. Deadman has no GE tax.
- Selling more units than the plugin saw you buy (bought before you installed it, or while it was off) has no known
  cost. Those proceeds are shown as "sold without known cost" and are not counted as profit.
- GE History rows have no real time, so they count on the day you import them; the panel says how much of the total
  came from GE History.
- Imported RuneLite trades count on their own (approximate) day. An import recounts profit from the local trade log
  in time order, so imported buys become the cost of later sells. If some trades were counted while *Save local trade
  log* was off (so a recount would lose them), imported trades are counted on top instead; **Rebuild** recounts on
  request.
- "Today" and "7 days" use your computer's time zone.

The running totals are saved to `profit-<id>.json` in the plugin folder (next to the trade log), where `<id>` is the
first 16 characters of the pseudonymous account id described below, never your name. Start-up reads only that small
file. **Rebuild** (in the Profit section) recounts everything from the local trade log in the background; it needs
*Save local trade log*. Log lines written by older versions have no account id, so a rebuild counts them for the
account you are logged in with.

## Importing RuneLite's GE history

RuneLite's built-in Grand Exchange plugin keeps a trade history for each account (in RuneLite's own per-account
config): one record per completed offer, with the side, item, units, the average price rounded down and when RuneLite
saw the offer complete. It keeps up to 1024 records and 365 days. This plugin reads it **only when you click**
*Import RuneLite's GE history* on the "Get started" card or *Import RuneLite GE history* on the My trades tab; that
click is your consent. It never reads it on its own.

- Only while you are logged in on world 345 (RuneLite's DEADMAN profile), and only the logged-in account's own
  history. Other accounts' data is never read.
- Only with *Save local trade log* on: the log is how the plugin knows which trades it recorded in earlier sessions
  (already counted for profit and uploaded), so without it they would be imported a second time.
- RuneLite's DEADMAN profile is shared by world 345 and the seasonal Deadman events. Records from before
  1 March 2026 (`RuneLiteImport.CUTOFF`; the last seasonal event, Deadman: Annihilation, ran 30 January - 20 February
  2026) are ignored, as they may belong to the seasonal economy.
- Trades the plugin already has are skipped: an earlier import of the same record (each record has a fixed id,
  `rl:<time>:<item>:<units>:<price>:<buy>`), the live fills of one offer that add up to the record (same item, side
  and units, a total within one gp per unit, finished within 10 minutes of its time), or a GE History row with the
  same side, item, units and total that was read after the record's time (each used once). The plugin also
  remembers the newest record it looked at per account, so importing again adds nothing.
- The other way round too: the plugin remembers the newest 50 imported trades per account, and a GE History row read
  later that matches one of them (same side, item, units, a total within one gp per unit) isn't recorded again.
- New trades are added to My trades and the item view (labelled "imported", with an approximate time such as "~3d"),
  to the local trade log (each to the file of its own month) and to your profit (recounted from the log in time
  order, so imported buys are the cost basis of later sells). While connected with *Share my
  trades* on, they are uploaded as events of kind `imported` (at most 100 per upload; the server defers the rest,
  and the plugin sends those again later).
- The result line says what happened, e.g. "Imported 812 trades (Jul 29 - Oct 7) - 210 already recorded".
- Times are approximate: if an offer completed while you were logged out, RuneLite only saw it at your next login.

## World 345 only

The plugin only tracks the permanent Deadman world, 345, and also checks that the world has the DEADMAN world type.
Seasonal Deadman worlds have their own economy and are ignored, as are all other worlds.

## Opt-in data sharing

Nothing contacts a server until you press **Connect** in the panel or turn on *Connect to Deadman Tool Kit server*
in the settings. Both ask you to confirm this warning first:

> This feature submits your IP address to a 3rd-party server not controlled or verified by RuneLite developers.

While connected, the panel reads market data from the server (`v1/recent` with
`kinds=fill,history,imported`, `v1/item/{id}`, `v1/item/{id}/series`, all with `world=345`; the feed lists other
players' imported trades only while their own time is within the last 24 hours, labelled "imported"), the GE offer
setup line reads `v1/item/{id}` for the item you are setting up (cached for 30 seconds), and while you have open offers on world 345 their item ids are checked with
`v1/items?ids=...` at most once a minute while the side panel is open or offer notifications are on (and when you
press refresh or open the panel, at most every 10 seconds). Like any
connection, these requests reach the server from your network address; see *IP addresses* below. Right after one of your
offers fills, the upload is sent within a couple of seconds (instead of at the next 30-second upload) so the item
view can show the new market data.

**Share my trades** (on once connected) uploads:

- a random install id (a UUID; the server stores it together with the hashed account id below);
- a pseudonymous account id (hashed, not your name or RSN): the SHA-256 hash of `deadman-ge-tracker:` followed by
  RuneLite's account hash, so the server can keep one account's trades together without learning who you are. It is
  pseudonymous, not anonymous: it is the same for every upload of that account, and the server links it to the install
  id. The server's privacy notice (`<server>/privacy`) says how long each is kept and how to delete them;
- the world;
- your trade events: an event id (derived from the random offer key, random for GE History rows, or the record's
  own fields for imported RuneLite trades), kind, buy/sell, item id and name, quantity, price, total, GE slot, a
  random offer key, the time it was observed, a late flag, the filled count and the last time the offer was seen
  (imported trades have no slot, offer key, filled count or last-seen time);
- a snapshot of your open offers: slot, offer key, side, item id, price, total quantity, filled quantity and when it
  was placed;
- item names and icons, and the item's GE buy limit when RuneLite knows it (from RuneLite's own item stats; the
  server uses it to flag buys over the 4-hour limit for review, never to reject them, and only accepts it from
  established sources);
- the time the upload was sent, so the server can correct for your PC's clock.

Uploads never include your IP address, your RSN, the account hash itself or anything about other players.

**IP addresses.** Nothing the plugin sends (no request body, header or URL) contains your IP address, and the server
never stores, hashes or logs it. Like any connection, the server sees the address you connect from while it answers
the request, and uses it only for short-lived in-memory flood protection (for example, how many requests one address
sent in the last minute); those counters are never written to a database, disk or log and expire on their own. The
only identifiers the server keeps are the random install id and the hashed account id. The RuneLite warning above
is the standard text RuneLite requires for every plugin that contacts a 3rd-party server. Turning **Connect** off in the settings stops all contact with the server;
uploads that were not sent yet are discarded. While connected, uploads that weren't sent yet (the server was down, or
RuneLite closed first) wait in `pending-uploads.json` in the plugin folder and go out after the next start; the server
ignores anything it already has. The file is removed when nothing is waiting.

**Install token.** Before the first upload the plugin registers its install id with the server (`POST v1/register`,
which only sends the install id) and receives a random install token. Every upload carries it
(`Authorization: Bearer ...`), so nobody else can upload, or delete data, under your install id. Registration is part
of uploading: it only happens while connected with *Share my trades* on. The install id and the token are kept in
`server-identity.json` in the plugin folder (`~/.runelite/plugin-data/deadman-tool-kit/`), not in the RuneLite
config: RuneLite logs config values it saves at debug level, passes them to every plugin, and syncs the config to its
servers for players with a RuneLite account. So the token is never written to a log or synced, and each computer has
its own install id. The token only authorises this install's uploads and the deletion of its data. If the server refuses the token, the
plugin registers once more; if the install id is taken it switches to a new random one, and remembers the old one
(*Delete my shared data* lists it, since data shared under it can't be deleted from here any more). If the file can't
be read when RuneLite starts (another program has it locked), the plugin never writes over it.

**Confirmed prices.** Market requests ask for `include=trust,fill`. Newer servers then also send confirmed figures
(trades from established players at a normal price, or matched by an independent counterparty). The item view shows
"Typical (confirmed)", the median of recent confirmed trades, when there is one, the confirmed / unconfirmed split in
the 24h volume tooltip, and unconfirmed trades in a muted colour. Undercut / outbid checks prefer the best bid / ask
from trusted offers when the server sends them. Older servers ignore the parameter and nothing changes.

**Privacy menu.** The panel's **Privacy** link opens a small menu:

- *Privacy notice* opens the server's privacy notice (`<server>/privacy`).
- *Delete my shared data...* asks for confirmation, then deletes everything this computer has shared (your trades,
  offers and ids; `DELETE v1/me` with the install token) and recalculates the market totals without them. It then
  disconnects the plugin, and if you connect again later you start over as a new, unlinked install id. Your local trade
  log and profit stay on your PC. This is the one request the plugin may send while disconnected, because you asked for
  it; it is only offered when this computer has a token or is connected. Data uploaded from another computer has to be
  deleted from that computer. If this computer's key was lost (the server holds the install id under a token the
  plugin no longer has), nothing can be deleted from here: the plugin says so, disconnects and shows the install id,
  which you can send to the contact on the privacy notice.

## Local log

**Save local trade log** is on by default and works without connecting. It appends one JSON object per line to a
file per month (by the trade's time in UTC): `~/.runelite/plugin-data/deadman-tool-kit/trades-YYYY-MM.jsonl`
(`%USERPROFILE%\.runelite\plugin-data\deadman-tool-kit\trades-YYYY-MM.jsonl` on Windows). Each line also carries
`acct`, the same pseudonymous account id as above, so per-account numbers can be rebuilt from the log. The single
`trades.jsonl` written by older versions stays in the same folder and is still read.

The profit totals are kept in `profit-<id>.json` files in the same folder (see [Profit](#profit)); they are updated
even when the trade log is off.

When the plugin starts it reads only the end of the newest files, until it has the newest 300 entries for
"My trades", so start-up stays fast however long the log gets.

Per-account offer state is kept in your RuneLite profile config, so fills that happen while you are logged out are
recorded the next time you log in and are marked late.

## Building and running

Requires JDK 11.

- `./gradlew test` runs the unit tests. `ServerContractTest` is skipped unless the environment variable
  `DGT_CONTRACT_SERVER` holds the base URL of a running collection server; it then registers, uploads, reads and
  deletes against it with the plugin's own classes.
- `./gradlew run` starts a developer-mode RuneLite client with the plugin loaded. To log in with a Jagex account, follow
  [Using Jagex Accounts](https://github.com/runelite/runelite/wiki/Using-Jagex-Accounts).
- `./gradlew shadowJar` builds a standalone jar.

The server address is fixed in code (`DeadmanToolKitPlugin.PRODUCTION_SERVER_URL`); players can't change it. Only a
developer-mode client (`./gradlew run`) uses a local server, or the one given with `-Ddeadmantoolkit.server=<url>`.
