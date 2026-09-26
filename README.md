# Ethereal Dumpster

One player's trash is another player's treasure.

A shared SQLite dumpster for Paper 1.21 / Java 21. Players discard whole stacks,
then discover a random selection of stacks deposited by the server. There is no
search, filtering, pricing, or generated loot. **Folia is not supported.**

## Build and install

```sh
mvn clean verify
```

Copy `target/EtherealDumpster-1.0.0.jar` (not `original-*.jar`) into `plugins/`,
then start Paper. SQLite JDBC is bundled. Configuration and storage are created in
`plugins/EtherealDumpster/`. This implementation is a release candidate: complete
[the real-server acceptance checks](docs/TESTING.md) before production use.

## Playing

- `/dumpster` opens the main menu; `/dump` and `/ed` are aliases.
- Deposit: move stacks freely into or out of the deposit inventory. Closing it
  commits the remaining stacks, including when disconnecting normally.
- Dive: `/dumpster dive`. Left-click a stack to claim it; Refresh draws another
  sample. Views can overlap, but the database arbitrates ownership.
- Full inventory: the claim is released before delivery; make room and retry.
- Blacklisted or overflow stacks become private returns. Returns are attempted
  after depositing and on login. `/dumpster recover` retries them after making room.
- Ordinary Creative transfers into the deposit GUI are allowed; creative cloning
  and direct creative inventory injection are blocked.
- Main and Statistics menus use compact, decorated three-row layouts; Deposit
  and Dive retain their six-row inventories.
- Dive’s Back button returns to the main menu. The main menu’s Statistics button
  opens an inventory dashboard; `/dumpster stats` still prints to chat.

The default five-second refresh cooldown also applies to closing/reopening and
reconnecting. Deposit menus have 53 item slots and a protected Back button in the bottom-right.
Back commits the deposit and opens the main menu; Esc commits and closes the menu. Dive and main menu items are views that cannot be moved normally.

## Administration

Look at a block within six blocks and run `/dumpster create` to register it. It
must match `dumpster.access-block` (default `ENDER_CHEST`). `/dumpster remove`
unregisters the targeted block. Multiple access points share one pool; removing a
block never removes pool contents. Registration uses world UUID and coordinates.
Registered containers do not expose their vanilla inventory through interaction.
Existing vanilla contents are not imported into the pool.

| Command | Purpose |
| --- | --- |
| `/dumpster stats` | Available stacks, rescued stacks, pending returns/deliveries |
| `/dumpster reload` | Validate and replace settings; invalid settings leave the current configuration active |
| `/dumpster purge 30d` | Request removal of available entries older than 30 days |
| `/dumpster purge all` | Request removal of all currently available entries |
| `/dumpster purge confirm` | Confirm your own request within 30 seconds |

A purge uses the timestamp captured when requested: later deposits are excluded.
It never removes reservations, uncertain deliveries, or private returns. Purges
and expiration are destructive. The default expiration is **30 days**; set
`storage.expire-after-days: 0` to disable it. Maintenance runs every minute.
`storage.max-entries: 0` removes the capacity limit; otherwise overflow is returned
privately rather than evicting existing items. Active reservations also count
against capacity conservatively.

Permissions `etherealdumpster.use`, `.deposit`, and `.dive` default to everyone.
`etherealdumpster.admin` defaults to operators and grants `.admin.create`,
`.admin.remove`, `.admin.stats`, `.admin.reload`, and `.admin.purge`. Non-admins
cannot break registered blocks. Explosions, fire, and pistons are prevented from
removing/moving registered access blocks. All player messages are configurable.

## Ownership, persistence, and recovery

See [the transaction and recovery guide](docs/RECOVERY.md) before manipulating data.

- One stack is one UUID entry. Serialized bytes use Paper's native
  [`ItemStack.serializeAsBytes()` / `deserializeBytes()`](https://jd.papermc.io/paper/1.21.0/org/bukkit/inventory/ItemStack.html),
  including the Minecraft data version and item metadata. Do not downgrade a
  server after storing newer-version items.
- Deposits use a synced journal before clearing the menu, followed by a player
  save before pool publication. READY journals can be replayed idempotently.
- SQLite conditional writes reserve an entry for one claimant. A durable
  DELIVERING state precedes inventory mutation. After delivery and player save,
  the entry is marked CLAIMED. Uncertain deliveries are quarantined, never
  automatically replayed.
- GUI state and player operations stay on the server thread. A dedicated worker
  owns ordinary database work; SQLite transactions enforce integrity independently
  of that worker. Startup recovery and the deposit custody journal/player save
  are synchronous durability boundaries. Player scheduling is isolated for a
  future Folia implementation, but has not been audited for Folia.
- Sampling uses an indexed random-key pivot plus wraparound: it reads at most 45
  rows rather than loading/shuffling the pool. This is approximate random sampling,
  not an exactly uniform sample of all combinations.
- Completed/purged payloads are removed while UUID tombstones remain to prevent
  stale journal replay. Tombstones still consume some space; the configured limit
  limits the live pool, not the total historical database file size.

Normal close, quit, and disable paths finalize deposits. On disable the database
queue drains and unresolved reservations/deliveries remain durable. Disk failure
before journaling retains the live deposit inventory for retry and logs an error.
A simultaneous disk failure and process termination cannot provide durable recovery
of that in-memory fallback. Abrupt crashes with an open, not-yet-finalized deposit
menu have the same cross-file durability limitations as other virtual inventories;
see the recovery guide. Never restore player data and dumpster data from different
backup points.

## Verification

`mvn verify` covers SQLite persistence, concurrent claims through separate
connections, capacity contention, transactional rollback, private returns,
expiration, tombstone replay protection, journal recovery/corruption, inventory
capacity, and GUI event cancellation. GUI tests use mocked Bukkit APIs; they do
not substitute for a real server's inventory event semantics or native NBT tests.
The metadata round-trip and two-player release gates are documented in
[docs/TESTING.md](docs/TESTING.md) and remain manual until run on Paper.

## Milestones and titles

Open **Your Profile** from the main menu or run `/dumpster profile`. Profiles use
three rows; the 20-title collection and milestone pages use six rows. Titles stay
inside Dumpster GUIs: no chat prefix/TAB plugin or extra dependency is needed.

There are ten milestones per track, with cumulative targets of **100, 250, 500,
1,000, 2,000, 3,500, 5,000, 7,500, 10,000 and 20,000**:

- **Pengambilan:** successful completed claims of another player's deposits.
- **Kontribusi:** your deposits successfully claimed by another player.

One successful whole-entry claim counts as **one action**, whether it contains one
item or 64. Self-claims, failed clicks, reservations, uncertain deliveries and
private returns do not count. Progress never resets on reaching a milestone.
Counts come directly from completed claim records, including existing history
when upgrading; the database automatically upgrades from schema 1 to 2.

Earned titles are stored permanently with stable IDs (`rescue-1`…`rescue-10` and
`contribution-1`…`contribution-10`). Select one unlocked title or **No Title**.
The profile shows your totals, selected title and next goal for each track. Earned
titles remain unlocked if an admin later raises targets. Unlocks are reconciled
on completion, login and profile reads; pending notifications survive logout and
restart, and play one short sound when delivered. Notification delivery is
at-least-once across a crash, so a notice may repeat without changing progress.

After a successful Dive claim, **Depositor Profile** opens the last depositor's
profile, showing their selected title. Toggle **Public / Anonymous** in your own
profile to hide your name, title and totals from other viewers. Profiles are
public by default. A hidden profile still accumulates progress normally. This
does not modify item metadata or place names/titles on the claimed item.

Edit `milestones.rescue` and `milestones.contribution` in `config.yml` to customize
exactly ten increasing integer targets (1..1,000,000,000) and ten display names per
track. Old configurations without these sections use defaults automatically;
copy the new sections from the bundled config if you want to customize them.
Run `/dumpster reload`; invalid lists leave the current configuration active.
Cosmetic progress does not award currency/items or prevent friends from farming
repeated deposit/claim cycles. There is no economy reward to exploit.
