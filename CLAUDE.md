VillagerLobotimizer is a Minecraft Paper plugin (Java 21) that disables villager AI when trapped in trading halls. Folia-compatible.

Note: The project is misspelled on purpose. You'll need to use VillagerLobotomizer and not VillagerLobotimizer.

## Commands
- Build: `./gradlew build`
- Test Server: `./gradlew runServer`
- In-game: `/lobotomy info|debug|wake|reload`

## Architecture

### Threading
- Per-entity tasks via `EntityScheduler` (thread-safe villager operations)
- Chunk processing via `GlobalRegionScheduler`
- Folia-safe: never touches entities from wrong thread
- `shuttingDown` flag prevents tasks during disable

### Core Files

**VillagerLobotomizer.java** - Main class: lifecycle, config, Folia detection, update checker, debug teams (non-Folia only)

**LobotomizeStorage.java** - Core logic:
- Sets: `activeVillagers`, `inactiveVillagers`
- Maps: `villagerTasks` (UUID→Task), `changedChunks`, `villagerTaskIntervals`
- Lobotomy decision delegated to `policy.VillagerActivityPolicy` (name overrides "nobrain"/exempt, water/vehicle/profession checks, movement check, optional roof check); `LobotomizeStorage` adapts a live `Villager`/`World` into `VillagerState`/`BlockGrid` per check (`villagerStateOf`, `gridOf`)
- Trade refresh: PDC-tracked restock timing, job site proximity, profession sounds, level-up effects. Restocking (only) is daytime-only in the overworld; leveling is not - it runs as soon as accumulated experience crosses a threshold regardless of time of day, since gating both behind the same daytime check made a villager's level-up silently wait for the next in-game dawn if its XP threshold was crossed at night (issue #7). Two restock-gating modes, chosen by `restock-schedule.mode`: legacy real-time `interval` (wall-clock, `restock-interval`/`restock-random-range`), or `schedule` (fixed in-game daily clock times via `policy.RestockSchedule`, computed inline in `refreshTrades` from the villager's own `World.getFullTime()` — deliberately not a separate cross-region tracker task, to avoid reading region-owned world-time state off its owning thread). `isScheduleModeActive()`/`getRestockSchedule()` expose the parsed schedule to `LobotomizeCommand`.
- Job-site-missing warning: if `isJobSiteNearby` fails for an already-lobotomized villager (most commonly after a zombify/cure round trip moved it off its workstation — issue #1), `refreshTrades` logs a one-time `WARNING` via `warnJobSiteMissingOnce` naming the villager and the missing workstation material, tracked in the in-memory `jobSiteMissingWarned` set (cleared once the job site is found again). Deliberately no automatic teleport/reposition — `isJobSiteNearby` is a plain material scan, so the vanilla-friendly fix is placing a matching workstation block near the villager's current position (or physically moving the villager with water/pistons), documented in README.md's Troubleshooting section.
- `restock-schedule.times`/`mode` mismatch reminder: in the constructor, if `mode` is not `"schedule"` but `restock-schedule.times` has entries, logs a one-time `INFO` reminder that `times` has no effect until `mode` is set to `"schedule"` — a runtime check rather than a config.yml comment, since `CommentPreservingYamlMigrator` can never deliver an updated comment to a server that already migrated past the version that introduced it (issue #3).
- Open trade window refresh: after every successful restock, `refreshOpenTradeWindow` re-opens the merchant (`HumanEntity#openMerchant(Merchant, true)`) for whoever is currently trading with the villager (`Villager#isTrading()`/`getTrader()`), dispatched via the trader's own `EntityScheduler` — `Merchant#setRecipes` explicitly does not update an already-open trade window on its own (issue #6).

**policy/** - Pure, unit-tested lobotomy decision logic (no Bukkit live objects, scheduler, or shared sets):
- `VillagerActivityPolicy.shouldBeActive(VillagerState, BlockGrid)` - decision rules + `canMoveCardinally`/`canMoveThrough`/`testImpassable`
- `BlockClassifier` - Material sets (`impassableRegular`, `impassableTall`, `impassableAll`, `cropBlocks`, `doorBlocks`, `professionBlocks`), built once via `fromServerRegistry()`
- `BlockSnapshot` (type/passable/solid), `BlockGrid` (coord→snapshot, `null`=unloaded), `VillagerState` (villager properties)
- `RestockSchedule` - parses `restock-schedule.times` (HH:mm, daytime-only) into sorted ticks; `passId(fullTime)` returns the absolute tick (+1) of the latest configured point crossed so far today, or `0` if none has (catches up after sleep/`/time set` jumps; deliberately not `day * size() + crossedCount`, since that older encoding became incomparable with a freshly computed value whenever `restock-schedule.times`' entry count changed between two `RestockSchedule` constructions, e.g. across a restart or `/lobotomy reload`), `nextOccurrence(fullTime)`/`formatTick()` back it for display

**EntityListener.java** - Events: `EntityAddToWorldEvent`, `EntityRemoveFromWorldEvent`, `BlockBreakEvent/PlaceEvent` (chunk updates), `InventoryOpenEvent` (optional trade prevention via merchant-inventory check), `EntityTransformEvent` (`INFECTION`/`CURED` only - bridges restock/lobotomization PDC state across the villager↔zombie villager entity replacement via `LobotomizeStorage.bridgeRestockState`, since Bukkit fires these as a brand-new entity rather than mutating the original in place)

**LobotomizeCommand.java** - Brigadier commands registered via `LifecycleEvents.COMMANDS`, permission `lobotomy.command` (op), raycasting for targeting. Wake command clears `isLobotomized` PDC marker. `debug` shows the next scheduled restock time/world when `restock-schedule.mode: schedule` is active.
- Per-villager `debug` output always shows job-site-nearby, restocks-today/cap (schedule mode's cap is `RestockSchedule.size()`, interval mode's is `VillagerUtils.defaultMaxRestocksPerDay()`), and (schedule mode only) the raw `lastRestockPassId` value via `LobotomizeStorage.getLastRestockPassId()` - not gated behind `plugin.isDebugging()`, since the command itself is an on-demand debug tool. When job-site-nearby is false, an additional "Needed workstation" line names the exact `Material` the villager's profession requires (from `VillagerUtils.PROFESSION_TO_STATION`), skipped for job-siteless professions (`NITWIT`/`NONE`).

**VillagerUtils.java** - Maps: `PROFESSION_TO_STATION`, `PROFESSION_TO_SOUND`. Methods: `isJobSiteNearby()` (3x3x3 box), `shouldRestock()`/`allowedToRestock()` (PDC+day-time logic; overloaded with an explicit `maxRestocksPerDay` so schedule mode's cap is the configured schedule length instead of the interval-mode hardcoded 2)

### Config (read in constructors)
`check-interval`, `inactive-check-interval`, `restock-interval`, `restock-random-range`, `restock-schedule.mode` (`interval`|`schedule`), `restock-schedule.times` (HH:mm list, schedule mode only), `restock-sound`, `level-up-sound`, `debug`, `chunk-debug`, `create-debug-teams` (Folia-incompatible), `check-roof`, `ignore-non-solid-blocks`, `disable-chunk-villager-updates`, `persist-lobotomized-state`

### PDC Keys
- `lastRestock` (LONG): Last trade refresh timestamp (interval mode)
- `isLobotomized` (BYTE): Persistence marker (when `persist-lobotomized-state: true`)
- `lastRestockCheckDayTime` (LONG): Full game time (absolute ticks) at last restock check; used for day-rollover detection
- `lastRestockPassId` (LONG): `RestockSchedule` pass counter at the villager's last successful restock (schedule mode)
- All four PDC keys above (`lastRestock`, `isLobotomized`, `lastRestockCheckDayTime`, `lastRestockPassId`) are bridged across a villager's infection/cure by `EntityListener`'s `EntityTransformEvent` handler, since that transform replaces the entity object rather than mutating it in place

## Development

### Paper Rules
- Never use NMS (`net.minecraft.*`, `org.bukkit.craftbukkit.*`)
- Thread safety: Use `EntityScheduler` for entities, `GlobalRegionScheduler` for chunks, never touch entities from wrong thread
- Check `chunk.isLoaded()` before accessing entities
- Sounds: `RegistryAccess.registryAccess().getRegistry(RegistryKey.SOUND_EVENT)` with NamespacedKeys
- Legacy sounds: Convert via `StringUtils.convertLegacySoundNameFormat()`

### Patterns
- Early returns, guard clauses
- Gate debug logs: `plugin.isDebugging()`, `plugin.isChunkDebugging()`
- Config changes: Update `src/main/resources/config.yml` with defaults
- New commands: Add to LobotomizeCommand + `plugin.yml` permissions
- PDC keys: `new NamespacedKey(plugin, "keyName")`
