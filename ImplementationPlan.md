# Implementation Plan: Tick-Scheduled Villager Restocks

Source feature request: `Features/Feature_scheduled_restock.md`

This plan turns that feature request into concrete engineering steps against the current
codebase. Design decisions below were resolved interactively with the project owner — see
"Resolved Design Decisions" for the record of what was chosen and why, before any code section.

---

## 1. Resolved Design Decisions

These resolve the feature request's "Open Questions" section, including a few that were
answered ambiguously or left incomplete in the source doc.

| # | Question | Decision |
|---|----------|----------|
| 1 | Time format | **HH:mm strings** (`"08:00"`), validated to the vanilla daytime window (ticks 0–12000, i.e. 06:00–18:00 in-game). Non-daytime entries are rejected at load with a config warning, config falls back to defaults for that entry. |
| 2 | Scope of worlds | Schedule applies to **all worlds** identically (already decided in the source doc). No per-world override. |
| 3 | Sleep skip | Restocks skipped over while sleeping **fire immediately on wake** (already decided). |
| 4 | `/time set` jumps | Crossed schedule boundaries **fire retroactively** (already decided). |
| 5 | Disabled daylight cycle | **Undefined behavior** — no fallback safety timer. Servers running `doDaylightCycle:false` with `mode: schedule` will simply stop restocking; this is documented as a known limitation, not a bug. |
| 6 | Jitter | **No jitter.** Configured times fire exactly. The `jitter-ticks` key from the source doc's example config is **dropped entirely** — it is not carried into the implemented schema. |
| 7 | Legacy `interval` mode | **Kept indefinitely.** `mode: interval` remains the default for existing configs (no forced migration); `mode: schedule` is an opt-in alternative. Both are supported long-term, not a deprecation path. |
| 8 | Frequency limits | No hard cap on number of `times` entries. A **soft warning is not implemented as a separate limit** — see decision for Q11, which subsumes this. |
| 9 | Economy/demand decay | **No separate system exists in this codebase.** `villager.updateDemand()` is called inline at the same point as the trade-uses reset (`LobotomizeStorage.refreshTrades`). It simply continues to fire at that same point, now driven by the schedule instead of the interval timer — no new demand/decay logic is added. |
| 10 | Per-villager opt-out | **Out of scope.** No new naming convention. Scheduled restock applies uniformly to all lobotomized villagers eligible under the existing `only-lobotomize-villagers-with-professions` / `only-lobotomize-villagers-with-experience` filters, same as today. |
| 11 | Ordering/spacing guardrails | **Warn only, don't reject.** On config load, warn if two configured times are identical or within a small tick threshold of each other (near-duplicate). This also covers Q8: no separate max-count warning is added. |

### Additional decisions (not in the source doc's open questions, but required for implementation)

| Topic | Decision |
|-------|----------|
| **Daily restock cap** | The existing hardcoded "max 2 restocks/day" (`VillagerUtils.allowedToRestock`) is a real-time approximation of vanilla and stays **unchanged for `interval` mode**. In `schedule` mode, the cap becomes **the number of configured `times` entries** — each configured time is one restock opportunity per villager per day, so a villager can restock at most `times.size()` times/day. |
| **Debug/info surface** | The "next scheduled restock" info goes into the existing **per-villager `/lobotomy debug`** output (the villager's world's next scheduled restock time + index), not the global `/lobotomy info` command. |
| **Scheduler architecture** | **Lazy pull**, not proactive push. A lightweight global per-world tracker (modeled on the existing `chunkProcessingTask` pattern) detects crossed schedule boundaries and bumps a per-world monotonic counter. Each villager's *existing* per-entity periodic task (already running every `check-interval`/`inactive-check-interval` ticks) picks up the new counter value on its own next check and reuses all existing restock plumbing (job-site check, sounds, leveling, PDC bookkeeping). This means restocks land within one check-interval window (default 150 ticks / 7.5s) of the scheduled time, not on the exact tick — acceptable per "fire ... on wake"/"fire retroactively" being about *not silently dropping* a restock, not about tick-exact timing. |

---

## 2. Config Schema Changes

### `config.yml` — new section, `config-version: 6` → `7`

```yaml
#Restock scheduling mode. "interval" = legacy real-time timer (restock-interval/restock-random-range).
#"schedule" = fixed in-game daily clock times (restock-schedule.times below).
restock-schedule:
  mode: "interval"

  #List of in-game times at which a restock pass runs, in 24h HH:mm format, evaluated against each
  #world's own day/night cycle. Only daytime values are valid (06:00-18:00 inclusive); night-time
  #entries are rejected with a warning on load.
  times:
    - "08:00"
    - "10:30"
    - "13:00"
```

- `restock-interval` / `restock-random-range` are **unchanged** and stay active for `mode: interval`.
- `restock-schedule.times` is only read/validated when `mode: schedule`.
- Bump `ConfigMigrator.CURRENT_CONFIG_VERSION` from `6` to `7` in
  `src/main/java/dev/mja00/villagerLobotomizer/utils/ConfigMigrator.java`.
- The comment-preserving migrator (`CommentPreservingYamlMigrator`) already walks nested sections
  generically via `addMissingKeys`/`mergeSection`, so adding `restock-schedule` as a new top-level
  section to the shipped default `config.yml` should be picked up automatically for upgrading
  servers — **verify this with a migration test** (see Testing section) rather than assuming it.

---

## 3. New Code: `policy.RestockSchedule` (pure, unit-testable)

Following the existing pattern where decision logic that doesn't touch live Bukkit objects lives
in `policy/` and is unit tested without MockBukkit (see `VillagerActivityPolicy`,
`BlockClassifier`), add a new pure class:

**`src/main/java/dev/mja00/villagerLobotomizer/policy/RestockSchedule.java`**

Responsibilities:
- Parse a `List<String>` of HH:mm entries into a sorted `int[]` of tick-of-day values
  (0–24000 scale, matching `world.getFullTime() % 24000`, where 0/24000 = 06:00).
- Validate: reject entries outside the daytime window (ticks 0–12000, i.e. 06:00–18:00); malformed
  strings (bad format, out-of-range hour/minute) are rejected with a warning and dropped.
- Warn (not reject) on duplicate or near-duplicate entries (e.g. within a configurable small tick
  threshold, such as 20 ticks / 1 second in-game).
- Dedup and sort the final accepted list.
- Expose:
  - `int size()` — number of accepted schedule points (used for the daily cap in schedule mode).
  - `long passId(long fullTime)` — a monotonically increasing value encoding
    `dayNumber * size() + indexOfLastCrossedPointToday`, used so a villager/world can cheaply
    compare "has a new restock opportunity occurred since I last restocked" via simple `>` on two
    longs, without re-deriving day/tick-of-day math at each comparison site.
  - `long nextOccurrence(long fullTime)` — full-time tick of the next scheduled point strictly
    after `fullTime`, for the `/lobotomy debug` display.

This class takes no config object, plugin instance, or logger reference beyond what's needed to
report validation problems — construct it with a `List<String>` and a way to report warnings
(e.g. return a result object with `List<String> warnings` alongside the parsed schedule, mirroring
how `BlockClassifier.fromServerRegistry()` is constructed once and injected), so it stays testable
exactly like `VillagerActivityPolicyTest` does today (plain JUnit, no MockBukkit).

**Tests**: `src/test/java/dev/mja00/villagerLobotomizer/policy/RestockScheduleTest.java`
- Valid HH:mm parsing at boundary values (06:00, 18:00, 06:01, 17:59).
- Rejection of night-time entries (e.g. "20:00", "02:00").
- Rejection of malformed strings ("8:00", "25:00", "12:60", "", null entries).
- Duplicate/near-duplicate warning emission.
- `passId` monotonicity across a simulated sequence of `fullTime` values including: normal forward
  ticking, a large forward jump crossing multiple points in one step (sleep skip / `/time set`
  forward), and a same-day-then-next-day rollover.
- `nextOccurrence` correctness including wraparound to the next day when `fullTime` is past the
  last scheduled point for the current day.

---

## 4. Schedule Parsing in `LobotomizeStorage` (revised — no separate global tracker)

**Deviation from the originally sketched architecture**, made during implementation: the plan
originally called for a separate global per-world tracker task (a `GlobalRegionScheduler` task
polling every world's `getFullTime()` and caching a `passId`). That was dropped. Folia is known to
regionize per-world time state for some use cases, and this codebase already goes to considerable
lengths elsewhere (`scheduleChunkVillagerProcessing`, the `flush()` Folia-reload path) to never
read/touch region-owned state from the `GlobalRegionScheduler` thread without dispatching onto the
owning region first. Introducing a new cross-region read of `World.getFullTime()` for every loaded
world from that thread would be exactly the kind of access this plugin is otherwise careful to
avoid, and there was no existing precedent in the codebase to confirm it's safe.

Instead, `RestockSchedule.passId(long fullTime)` (see §3) is called **directly inside
`refreshTrades()`**, using `villager.getWorld().getFullTime()` at the point where this method
already runs — on the villager's own `EntityScheduler` callback, i.e. the thread that legitimately
owns that villager's region. This is the same thread `refreshTrades()` already reads
`villager.getWorld().isDayTime()` from today, so it carries no new thread-safety risk. `passId()`'s
work (a linear scan over at most a handful of configured times) is cheap enough that computing it
per-villager, once per that villager's own `check-interval`/`inactive-check-interval` tick, needs
no shared cache. This still satisfies "lazy pull via the existing per-villager loop" — there is
just no intermediate push-into-cache step.

Net effect on `LobotomizeStorage`:
- `private final RestockSchedule restockSchedule` — parsed once in the constructor (re-parsed on
  `/lobotomy reload`, same lifecycle as `activityPolicy`), from
  `plugin.getConfig().getStringList("restock-schedule.times")`.
- `private final boolean scheduleModeActive` — `true` only when `restock-schedule.mode` is
  `"schedule"` **and** at least one time parsed successfully; an empty/misconfigured schedule falls
  back to interval-mode behavior (with a warning) rather than never restocking, per §7.
- Parse warnings (from `RestockSchedule.warnings()`) are logged only when `mode: schedule` is
  configured, so interval-mode servers with stray/unused `times` entries aren't warned about a
  section they aren't using.
- No new scheduled task, no new `Map<World, ...>` state, no change to the constructor's existing
  `chunkProcessingTask`/`watchdogTask` wiring.

---

## 5. `LobotomizeStorage.refreshTrades()` Changes

Current logic (lines ~673–768) gates restock on:
```java
boolean intervalPassed = timeSinceLastRestock > effectiveInterval; // millis-based
...
if (intervalPassed && shouldRestock(villager)) { ... }
```

New logic, branching on `mode`:

- **`mode: interval`** — unchanged, byte-for-byte.
- **`mode: schedule`**:
  - Replace the millis-based `intervalPassed` computation with a comparison against the new PDC
    key `lastRestockPassId` (LONG) vs. `restockSchedule.passId(villager.getWorld().getFullTime())`
    computed inline (see §4). A new opportunity exists when `currentPassId > lastRestockPassId`.
  - `VillagerUtils.allowedToRestock` needs a schedule-aware variant (or an added `int maxPerDay`
    parameter) so the cap is `restockSchedule.size()` instead of the hardcoded `2`. Keep the
    existing 2-arg version for `interval` mode so behavior there is provably unchanged.
  - `VillagerUtils.shouldRestock`'s day-rollover reset of `restocksToday` (via
    `lastRestockCheckDayTimeKey`) is reused as-is — day boundaries are day boundaries regardless of
    mode.
  - On successful restock, set `lastRestockPassId` PDC key to the world's current `passId` (instead
    of / alongside updating the existing `lastRestock` millis key, which becomes unused in schedule
    mode but is harmless to leave untouched for simplicity).
  - The existing daytime-in-overworld check and `isJobSiteNearby` gate are unchanged — they compose
    with the new gate exactly as they compose with `intervalPassed` today.
  - Sound playback, `updateDemand()`, and the leveling block below the restock block are unchanged
    and unconditionally reused.

New PDC key (per CLAUDE.md's PDC Keys convention):
- `lastRestockPassId` (LONG): world-schedule pass counter at the villager's last successful restock
  under `schedule` mode. Add to `LobotomizeStorage`'s existing `NamespacedKey` fields and to the
  CLAUDE.md "PDC Keys" list.

---

## 6. `/lobotomy debug` Changes

In `LobotomizeCommand.getVillagerDetails`, when `restock-schedule.mode == "schedule"`, append a
line showing the villager's world's next scheduled restock time, formatted back to HH:mm from
`RestockSchedule.nextOccurrence(world.getFullTime())`. When in `interval` mode, no change to the
existing output (there is currently no restock countdown displayed at all in this command —
the source doc's framing of "in addition to a real-time countdown" does not match current code;
this plan adds the schedule display as new information, not a replacement of something that
doesn't exist).

---

## 7. Validation & Logging

- Extend the existing config-load validation path in `LobotomizeStorage`'s constructor (where
  `validateInterval` and friends already run) with schedule parsing: construct `RestockSchedule`
  from `plugin.getConfig().getStringList("restock-schedule.times")` when `mode: schedule`, and log
  every warning it returns via `plugin.getLogger().warning(...)`, matching the style of
  `validateInterval`'s existing warnings.
- If `mode: schedule` is configured but `times` is empty or every entry fails validation, log a
  warning and behave as if `mode: interval` (fail safe rather than silently never restocking).
- Re-run this validation on `/lobotomy reload` (already goes through
  `VillagerLobotomizer.reloadPluginState()` → new `LobotomizeStorage` construction, so this falls
  out of the constructor change for free).

---

## 8. Documentation Updates

- `CLAUDE.md`:
  - Add `restock-schedule` to the "Config (read in constructors)" key list.
  - Add `lastRestockPassId` to the "PDC Keys" list.
  - Mention the new `policy/RestockSchedule` class alongside the existing `policy/` bullet.
- `README.md`: check whether it documents `restock-interval`/`restock-random-range` for end users;
  if so, add the `restock-schedule` section there too.

---

## 9. Test Plan Summary

| Layer | File | Coverage |
|-------|------|----------|
| Pure unit | `policy/RestockScheduleTest.java` (new) | Parsing, validation, `passId`, `nextOccurrence` — see §3. |
| Pure unit | `utils/VillagerUtilsTest.java` (extend) | New schedule-aware `allowedToRestock`/`shouldRestock` overloads with an explicit cap. |
| MockBukkit | `LobotomizeStorageScheduleTest.java` (new) | Config-parsing surface only: `isScheduleModeActive()`/`getRestockSchedule()` reflect `mode`/`times`, including the fail-safe fallback to interval mode. |
| MockBukkit end-to-end | `RestockScheduleEndToEndTest.java` (new) | Drives a real `LobotomizeStorage` through its actual per-villager `EntityScheduler` task via MockBukkit's tick-driven scheduler: no restock before the scheduled point, a restock once it's passed, the daily cap holding without a new crossing, and catch-up after a simulated large `world.setFullTime()` jump (sleep/`/time set` equivalent). **Required two MockBukkit-specific workarounds** (documented in the test's `tick()` javadoc), neither indicating a production bug: (1) `VillagerMock.updateDemand()` is unimplemented and throws `UnimplementedOperationException` (itself a JUnit `TestAbortedException`) — since it's called *after* the state this suite asserts on is already mutated, it's swallowed; (2) `PaperScheduledTask.cancel()`/`isCancelled()` are unconditionally unimplemented too, and `BukkitSchedulerMock` only re-registers a repeating task for its next run once the runnable returns normally — so a runnable that threw permanently drops the repeating task, unlike real Bukkit/Folia. The test re-tracks the villager (`removeVillager` then `addVillager`, not a second `addVillager` alone, since `removeVillager` cancels through the already-defensive `safeCancel`) whenever this is caught, stopping that call's tick budget immediately after to avoid a same-call race with `removeVillager`'s own queued wake-up task. |
| Config migration | `utils/CommentPreservingYamlMigratorTest.java` (extend) | Upgrading a v6 config (no `restock-schedule` key) to v7 adds the new section with defaults, preserving existing keys/comments. Confirmed (not asserted) during implementation: the migrator does *not* carry over nested comments (e.g. the one documenting `times:`) for a section that's entirely new — a pre-existing migrator limitation, not something this feature introduces. |
| Manual/in-game | — | `./gradlew runServer`: verify `/lobotomy reload` picks up a `mode: schedule` config, `/lobotomy debug` shows the next restock time, and a lobotomized villager actually restocks at a configured time (can be sped up in a test server via `/time set` to just before a configured time). Not yet done — still pending. |

---

## 10. Rollout Order

1. `policy/RestockSchedule` + its unit tests (no wiring yet — pure, reviewable in isolation).
2. Config schema: `config.yml` default + `ConfigMigrator` version bump + migration test.
3. `LobotomizeStorage`: parse schedule in constructor, add the new `lastRestockPassId` PDC key, and
   branch `refreshTrades`/`shouldRestock` on mode (§4–§5); `VillagerUtils.allowedToRestock`/
   `shouldRestock` gain schedule-aware overloads. *(Originally split into two steps around a
   separate global tracking task; collapsed into one once that task was dropped — see §4.)*
4. `/lobotomy debug` display.
5. `CLAUDE.md` / `README.md` updates.
6. MockBukkit end-to-end tests + manual in-game verification via `runServer`.

This order keeps every step independently buildable and testable: steps 1–2 land with zero
behavior change for existing servers (still defaulting to `mode: interval`), and step 3 onward is
additive and gated entirely behind the new `mode: schedule` opt-in.

---

## 11. QA Follow-Up: Bug Fix Milestones (post-launch)

Testing department confirmed both of the following as **real bugs**, reproducible with the feature
configured correctly (`mode: "schedule"`, valid `times`). Root-caused in
`reports/QA_report_24082026_1.md`; this section turns that report into the next implementation
milestones, in the order they should land. Both bugs also affect `mode: interval` (they predate
this feature's `restock-schedule` work at the `PersistentDataContainer`/native-field level), so
fixing them benefits both modes, not just `schedule`.

| Milestone | Bug (QA report §) | Priority | Depends on |
|---|---|---|---|
| A | §1 — zombify→cure breaks restock/level-up | High | none |
| B | §2 — `passId` unstable across `times` edits | High | none (independent of A) |
| C | §3 — docs/diagnostics to prevent recurrence | Medium | A, B (debug fields read the keys they touch) |

A and B are independent and can be implemented/reviewed in parallel; C should land last since its
debug-output additions are most useful once A and B's fixes (and their new/changed PDC semantics)
are in place.

---

### 11.1 Milestone A — Preserve restock/lobotomization state across zombify → cure — **Implemented**

`EntityListener.onTransform` + `LobotomizeStorage.bridgeRestockState` + the `VillagerUtils`
sentinel change landed, with `EntityTransformBridgeTest` (MockBukkit, fires
`EntityTransformEvent` directly per A5's testing note) and two new `VillagerUtilsTest` cases.
Full suite green (`./gradlew test`). One refinement made during implementation, deviating from
this section's original A4 sketch — noted here rather than rewritten above so the "resolved
design decisions" table stays a record of what was decided *before* implementation:

- **A4, as implemented:** the sentinel changed from `0L` to `-1L` as planned, but the guard stayed
  "only reset when a real prior value is known" (`lastRestockCheckDayTime >= 0L`), **not** "force a
  reset whenever unknown". An earlier draft of this fix forced a reset on "never checked", but that
  breaks the same continuity A1 requires — a villager whose PDC bridge worked correctly (A2/A3)
  legitimately reaches the "never checked" state only when it truly is fresh (native `restocksToday`
  is then also genuinely `0`, so nothing needs resetting); forcing a reset there is redundant for
  that case and actively wrong for a hypothetical future entity-replacement path that isn't bridged
  yet, since it would silently re-introduce the A1 exploit (an unbridged reset path could be used to
  force a free cap reset). The `0L`→`-1L` change is therefore narrower than first sketched: it only
  fixes the genesis-tick edge case (a **real** check recorded at world tick 0 is no longer
  indistinguishable from "never checked"), confirmed by
  `shouldRestockTreatsRealTickZeroCheckAsCheckedRatherThanNeverChecked`. The actual fix for the bug
  reported by QA is entirely carried by A2/A3 (the PDC bridge itself) — A4 is exactly the
  defense-in-depth it was scoped as, not a second independent fix for the same symptom.

**Root cause (QA report §1):** `isLobotomized`, `lastRestockPassId`, `lastRestockCheckDayTime`,
and `lastRestock` all live in the villager's PDC. Villager → Zombie Villager infection and
Zombie Villager → Villager curing are entity *replacements* (new object, new UUID), and nothing in
this codebase listens for `EntityTransformEvent` to carry PDC values across that replacement, so
they silently reset to their defaults on the cured entity. `VillagerUtils.shouldRestock`'s
day-rollover guard (`lastRestockCheckDayTime > 0L`) compounds this: because it can't tell "never
checked" apart from "reset to zero", a stale `restocksToday` count that *did* survive on the
native entity (profession/level/experience/gossip are documented vanilla `VillagerData` fields
that the conversion process is designed to preserve) is never rolled back to 0 by the plugin,
potentially leaving the villager stuck at its old cap for up to a full extra in-game day.

#### Resolved design decisions

| # | Question | Decision |
|---|---|---|
| A1 | Continuity vs. fresh reset on cure | **Continuity: copy the plugin's PDC state forward.** Resetting the villager to a "fresh" state instead (ignoring what it already did today) was considered and **rejected** — it would let a player bypass the daily restock cap on demand by repeatedly infecting and curing a villager (feed a zombie villager a golden apple + ring a bell to force a resync). Copying state forward keeps a cured villager exactly as capped/leveled as it was a moment before infection, which is the only option that isn't exploitable. |
| A2 | Which PDC keys to bridge | All four: `isLobotomized`, `lastRestockPassId`, `lastRestockCheckDayTime`, `lastRestock`. Bridging `isLobotomized` also removes the current one-`check-interval`-tick window where a cured villager is briefly treated as "active" before `processVillager` re-lobotomizes it (harmless today, but no longer necessary once the marker travels with the entity). |
| A3 | Where to intercept | A new `EntityTransformEvent` handler in `EntityListener`, for both `TransformReason.INFECTION` (Villager → Zombie Villager) and `TransformReason.CURED` (Zombie Villager → Villager). The event exposes both the pre-transform entity (`event.getEntity()`) and the post-transform entity (`event.getTransformedEntity()`) together, so PDC values can be copied directly between them without needing an intermediate cache keyed by UUID. Concretely: infection copies plugin PDC from the Villager onto the *Zombie Villager* (which can hold arbitrary PDC like any entity, even though this plugin never tracks zombie villagers itself), and curing copies it back off the Zombie Villager onto the newly created Villager. This uses the zombie villager stage purely as a carrier — no new plugin-level cache/map is introduced. |
| A4 | Day-rollover sentinel | Change `VillagerUtils.shouldRestock`'s rollover guard from `lastRestockCheckDayTime > 0L` to an explicit "has a previous check ever been recorded" sentinel (default `-1L` instead of `0L`) so a reset/absent PDC value can never silently skip the rollover reset of `restocksToday`, regardless of *why* it was reset. This is defense-in-depth beyond A2/A3 — with the PDC bridge in place this guard should no longer be exercised by the zombify/cure path specifically, but it closes the same class of bug for any other future or existing path that clears this key without also resetting the native counter. |
| A5 | Event ordering risk | Bukkit/Paper does not guarantee `EntityTransformEvent` fires before the resulting entity's `EntityAddToWorldEvent` via listener priority (they're different event types — priority only orders listeners of the *same* event). The expected/causal order is transform-before-add (the transform is what causes the new entity to be added), and PDC is written directly onto the `event.getTransformedEntity()` object rather than depending on it already being in the world, so the write should be visible by the time `EntityListener.onAdd`/`LobotomizeStorage.addVillager()` runs regardless of exact event-firing order. **This assumption must be confirmed with a manual `/runServer` test** (see Testing below) before this milestone is considered done, since MockBukkit cannot simulate real disease/curing to exercise the natural ordering end-to-end. |

#### Code changes

**`src/main/java/dev/mja00/villagerLobotomizer/listeners/EntityListener.java`**
- Add:
  ```java
  @EventHandler
  public final void onTransform(EntityTransformEvent event) {
      if (event.getTransformReason() != EntityTransformEvent.TransformReason.INFECTION
              && event.getTransformReason() != EntityTransformEvent.TransformReason.CURED) {
          return;
      }
      if (!(event.getEntity() instanceof LivingEntity source)
              || !(event.getTransformedEntity() instanceof LivingEntity target)) {
          return;
      }
      this.plugin.getStorage().bridgeRestockState(source, target);
  }
  ```
- `bridgeRestockState` is intentionally reason-agnostic (same copy both directions) since both the
  Villager→Zombie Villager and Zombie Villager→Villager hops need the exact same PDC keys copied.

**`src/main/java/dev/mja00/villagerLobotomizer/LobotomizeStorage.java`**
- Add a package-visible `bridgeRestockState(LivingEntity source, LivingEntity target)` method next
  to the existing `NamespacedKey` fields, copying `key`, `lobotomizedKey`,
  `lastRestockCheckDayTimeKey`, and `lastRestockPassIdKey` (whichever are present — `PDC.has(...)`
  guarded, no-op for absent ones) from `source.getPersistentDataContainer()` to
  `target.getPersistentDataContainer()`. Keeping this in `LobotomizeStorage` (rather than
  `EntityListener`) matches the existing pattern of `LobotomizeStorage` owning all
  `NamespacedKey`/PDC access, and means `EntityListener` doesn't need its own copies of the keys.

**`src/main/java/dev/mja00/villagerLobotomizer/utils/VillagerUtils.java`**
- `shouldRestock` (`VillagerUtils.java:216-233`): change the default read from
  `pdc.getOrDefault(lastRestockCheckDayTimeKey, PersistentDataType.LONG, 0L)` to a `-1L` default,
  and the guard from `if (lastRestockCheckDayTime > 0L)` to `if (lastRestockCheckDayTime >= 0L)`.
  `0L` remains a perfectly valid *real* recorded value (world tick 0); only the default sentinel
  changes.

#### Tests

- **MockBukkit, new — `EntityTransformBridgeTest.java`**: since MockBukkit cannot simulate actual
  zombie infection/curing AI, construct the scenario directly: spawn a `VillagerMock`, set its
  `isLobotomized`/`lastRestockPassId`/`lastRestockCheckDayTime` PDC values and `restocksToday` to
  simulate "already at the daily cap mid-day", spawn a `ZombieVillagerMock` standing in for the
  transformed entity, fire `EntityTransformEvent` via
  `server.getPluginManager().callEvent(...)` with `TransformReason.INFECTION`, and assert the PDC
  values landed on the zombie villager. Then fire a second `EntityTransformEvent` with
  `TransformReason.CURED` from that zombie villager to a fresh `VillagerMock`, and assert the
  original PDC values (including the daily cap state) landed on the new villager unchanged —
  proving continuity, not a free reset (guards against re-introducing the A1 exploit).
- **`utils/VillagerUtilsTest.java` (extend)**: a case that starts with the PDC key entirely absent
  (simulating a freshly-created entity, sentinel `-1L`) and a native `restocksToday` already at the
  cap, asserting the very first `shouldRestock` call now correctly resets `restocksToday` to 0
  when the current day differs from tick 0 — the exact scenario A4 fixes.
- **Manual `/runServer` verification (required before closing this milestone):** lobotomize a
  villager in a trading hall, deplete its trades to the daily cap, let a zombie infect it, cure it
  with a golden apple + bell, and confirm via `/lobotomy debug` that it resumes restocking/leveling
  on schedule rather than needing a full extra day — this is the only way to confirm assumption A5
  and the real-vanilla-NBT-persistence assumption underlying the root cause analysis.

#### Manual verification result (2026-08-25, GitHub issue #2)

Performed on a real test server (`mode: schedule`, `times: [08:00, 10:30, 13:00]`), a
Master-level, cap-depleted villager was infected and cured within the same in-game day, then
carried through to the next day's schedule. Findings:

- **A5 (event ordering) confirmed fine in practice.** `lastRestockPassId` (271001) and the other
  bridged keys survived the infection→cure round trip intact, and no double-restock occurred later
  the same day (22:00) — `restockOpportunityAvailable` (`currentPassId > lastRestockPassId`)
  correctly stayed `false` since no new schedule point had been crossed since the pre-infection
  restock. Whatever the exact `EntityTransformEvent`/`EntityAddToWorldEvent` firing order is in
  practice, it did not produce an observable exploit or state loss.
- **Level/experience persistence confirmed** (vanilla `VillagerData`, as expected): level stayed at
  5 (Master), experience carried forward unchanged across the cure.
- **One correction to this section's root-cause framing:** native `restocksToday` did **not**
  survive the round trip — it read back as `0` immediately after cure, not "stale at the old cap"
  as the root-cause paragraph above hypothesized. This turns out not to matter: the actual gate in
  `refreshTrades` is `restockOpportunityAvailable && shouldRestock(villager)`, an AND of the
  plugin's own bridged `lastRestockPassId` (restrictive, correctly preserved) with vanilla's
  `restocksToday` (reset to 0, permissive) — the bridged value is what actually blocked the
  same-day double restock, and `restocksToday` resetting is harmless under this design. Worth
  keeping in mind that `/lobotomy debug`'s "Restocks today: n/cap" line can read `0/n` immediately
  after a cure even though the villager is correctly still capped for the rest of that day — a
  cosmetic quirk, not a functional bug.
- **Following day confirmed fully normal:** all three schedule points fired individually
  (`08:00`/`10:30`/`13:00`), ending at `3/3` with a freshly-advanced `lastRestockPassId` (295001) —
  no permanent stall, no stuck state.

This satisfies the manual verification this milestone was blocked on; see issue #2 (closed).

---

### 11.2 Milestone B — Make `passId` comparisons stable across `restock-schedule.times` edits — **Implemented**

`RestockSchedule.passId` now returns the absolute `fullTime` tick (+1) of the latest configured
point crossed so far today, rather than `day * size() + crossed`, exactly per decision B1/B2 below
— no PDC key rename, no migration code. Three existing `RestockScheduleTest` assertions that
hardcoded the old encoding's values were updated to the new ones (`passId` returning e.g. `2001`
instead of a small per-day count), and a new
`passIdComparisonRemainsValidWhenScheduleSizeChangesAcrossReload` test proves comparisons stay
valid whether `times` grows or shrinks. A new MockBukkit end-to-end test,
`restockKeepsWorkingAfterTimesListShrinksAcrossReload`, drives the exact regression scenario
through a real `plugin.reloadPluginState()` call (3 times → 1 time, matching the QA report's
"admin edits the config and restarts" framing) and confirms restocking doesn't stall. Full suite
green (`./gradlew test`, 82 tests including these).

**Root cause (QA report §2):** `RestockSchedule.passId(fullTime)` encodes
`day * ticks.length + crossed` (`policy/RestockSchedule.java:169-185`). `ticks.length` (i.e.
`restockSchedule.size()`) is re-derived from config on every `LobotomizeStorage` construction
(server start, `/lobotomy reload`). A villager's stored `lastRestockPassId` PDC value was encoded
under whichever `size()` was active when it was written. If the accepted `times` entry count
changes before the next read — including via silent near-duplicate dedup, not just an intentional
edit — the stored value and a freshly computed `currentPassId` are no longer comparable, and
`currentPassId > lastRestockPassId` can spuriously read `false` for as many in-game days as it
takes the new sequence to numerically catch up, silently stalling restocks with no error or
warning.

#### Resolved design decision

| # | Question | Decision |
|---|---|---|
| B1 | Fix approach | **Redesign the `passId` formula to be size-independent, keep the method name and comparability via plain `>`.** (See "Corrected contract" note below the table for the precise guarantee this relies on — it is narrower than plain monotonicity.) Rejected alternative: store `size()` (or a schedule hash/version) alongside `lastRestockPassId` and treat any mismatch as invalidated — this works but requires a second PDC key and an explicit invalidation branch at every read site; encoding the schedule point's own absolute tick instead makes the value inherently comparable with no extra key or branch, since absolute ticks never depend on how many *other* points are configured. |
| B2 | New encoding | `passId(fullTime)` returns `dayStart + tick + 1` for the **latest** configured tick-of-day crossed so far today (`0` if none has been crossed yet today or the schedule is empty), where `dayStart = day * TICKS_PER_DAY`. The `+1` keeps every real crossing's value `>= 1`, so it's unambiguously distinguishable from the PDC default-read sentinel `0L` ("never restocked under schedule mode") — this preserves the existing `pdc.getOrDefault(this.lastRestockPassIdKey, PersistentDataType.LONG, 0L)` call at `LobotomizeStorage.java:724` unchanged. |
| B3 | Multiple points crossed in one jump | Unchanged behavior, verified by existing test `villagerCatchesUpAfterSleepingPastMultipleScheduledPoints`: `passId` under the new encoding still only reflects the single *latest* point crossed (not a per-point count), so one restock action still clears all due trades at once rather than firing once per skipped point. |
| B4 | Upgrade/compatibility path | **No explicit migration.** A villager with an old-encoding `lastRestockPassId` (a small `day * oldSize + crossed` integer) will almost always compare as smaller than a freshly computed new-encoding value (an absolute tick, typically in the tens of thousands or more), so upgrading this fix produces **at most one bonus catch-up restock per villager** the next time each is checked, then behaves correctly forever after. This is the same class of benign one-time effect as a version bump and needs no dedicated handling — call it out in the CHANGELOG/release notes rather than writing migration code for it. |

**Corrected contract (issue #4):** an earlier draft of this section described the new encoding as
"a later call for a larger `fullTime` never returns a smaller value" — i.e. globally monotonic.
That's not actually true, and was never true even for this new encoding: `passId` intentionally
dips back to `0` at the start of each new in-game day until that day's own first configured point
is crossed (exercised directly by
`RestockScheduleTest.passIdAtDayRolloverDoesNotExceedPreviousDaysLastCrossedValueBeforeItsOwnFirstPoint`).
The property that actually holds, and that `LobotomizeStorage.refreshTrades`'s
`currentPassId > lastRestockPassId` comparison actually depends on, is narrower: **a value stored
from an actual prior restock is always eventually exceeded by a later real crossing.** Nothing in
the code depends on, or needs, global monotonicity across arbitrary `fullTime` values — only this
narrower guarantee, which the day-rollover dip does not violate (a stored value is never read back
*as* a smaller value than itself; it's only ever compared against a later crossing, which by
definition happens after any same-day dip has already passed).

#### Code changes

**`src/main/java/dev/mja00/villagerLobotomizer/policy/RestockSchedule.java`**
- Replace the `passId` body (`policy/RestockSchedule.java:169-185`):
  ```java
  public long passId(long fullTime) {
      if (isEmpty()) {
          return 0L;
      }
      long day = Math.floorDiv(fullTime, TICKS_PER_DAY);
      long tickOfDay = Math.floorMod(fullTime, TICKS_PER_DAY);
      long dayStart = day * TICKS_PER_DAY;

      long lastCrossed = 0L;
      for (long tick : this.ticks) {
          if (tickOfDay >= tick) {
              lastCrossed = dayStart + tick + 1;
          } else {
              break;
          }
      }
      return lastCrossed;
  }
  ```
- Update the method's Javadoc to state the new contract explicitly: the returned value no longer
  depends on `size()`/`ticks.length` and remains meaningfully comparable across a config reload
  that changes the number or positions of configured `times`, which is the property the old
  encoding was missing.
- `size()` itself is untouched and still used as-is for the daily cap
  (`VillagerUtils.allowedToRestock(villager, restockSchedule.size())`) — that usage was never the
  problem; only the stored-comparison value was.

#### Tests

- **`policy/RestockScheduleTest.java` (extend)**: construct a schedule `A` with `times =
  ["08:00"]`, compute `passIdA = A.passId(fullTime)` after crossing it; construct a *different*
  schedule `B` with `times = ["08:00", "10:30", "13:00"]` for the same `fullTime` window, and
  assert `B`'s `passId` progression is still strictly increasing against `passIdA` as `fullTime`
  advances — i.e., swapping the schedule's size never makes a later `fullTime` produce a smaller or
  equal `passId` than an earlier one did under a different-sized schedule. Also assert the specific
  regression scenario from the QA report: a schedule shrinking from 3 entries to 1 does not require
  multiple in-game days to "catch up" to a previously stored larger-size-encoded value under the
  fix (this is really validating the *new* code has no such catch-up delay at all, since it no
  longer depends on size).
- **`RestockScheduleEndToEndTest.java` (extend)**: new test
  `restockKeepsWorkingAfterTimesListSizeChangesAcrossReload` — mirrors the existing
  `restockPersistsAcrossReconstructedStorageViaPdc` test but reconstructs `LobotomizeStorage` with
  a **different** `times` list (both a larger and a smaller variant, as two sub-cases) between two
  `tick()` calls, and asserts the villager still restocks at its next legitimate scheduled crossing
  rather than silently stalling.

---

### 11.3 Milestone C — Documentation and diagnostics to prevent recurrence — **Implemented**

- `config.yml`/`README.md`: added an explicit `IMPORTANT:` line above `restock-schedule:` stating
  that populating `times` does nothing until `mode` is set to `"schedule"`, plus a README callout
  advising against changing the *number* of `times` entries on a live server pre-fix (now
  addressed by Milestone B, but the advice stays useful for admins on older builds).
- `LobotomizeCommand.getVillagerDetails` now always shows (not gated behind `plugin.isDebugging()`,
  since the command is itself an on-demand debug tool): "Job site nearby" via
  `VillagerUtils.isJobSiteNearby`, and "Restocks today: n / cap" using
  `VillagerUtils.defaultMaxRestocksPerDay()` (a new public accessor for the previously-private
  interval-mode cap) or `RestockSchedule.size()` depending on mode. Schedule mode additionally
  shows the raw `lastRestockPassId`, via a new `LobotomizeStorage.getLastRestockPassId(Villager)`
  accessor.
- `CLAUDE.md` updated: `LobotomizeCommand.java` bullet documents the new always-shown fields
  (the `EntityTransformEvent`/PDC-bridging notes were already added when Milestone A landed).
- Tests: `VillagerUtilsTest.defaultMaxRestocksPerDayMatchesTheImplicitIntervalModeCap` and two new
  `RestockScheduleEndToEndTest` cases (`getLastRestockPassId` returns the PDC-written value after a
  real restock, and defaults to `0` before any schedule-mode restock) cover the new accessors the
  debug output reads from. The command's Brigadier output formatting itself is not
  unit-tested — consistent with the rest of `LobotomizeCommand`, which has no existing test
  coverage — and remains a manual `/lobotomy debug` check, per the original plan. Full suite green
  (`./gradlew build`, 84 tests).

Lower priority than A/B (no behavior bug), but directly addresses why the testing department
couldn't self-diagnose §1/§2 and had to escalate as "unclear if this works at all."

- **`src/main/resources/config.yml`**: strengthen the comment above `restock-schedule:` to state
  up front, before the `mode` key, that populating `times` has **no effect** unless `mode` is also
  set to `"schedule"` (the shipped default is `"interval"` with `times` pre-filled, which is the
  most likely source of "unclear if 3 restocks... are working" per QA report §3).
- **`README.md`**: mirror the same clarification wherever `restock-schedule` is documented for end
  users (per the existing plan's §8, already flagged as a doc task — this is additive detail to
  that same section, not a new doc target).
- **`LobotomizeCommand.getVillagerDetails`** (`LobotomizeCommand.java:168-193`): add three
  always-shown diagnostic lines (not gated behind `plugin.isDebugging()`, since this command is
  itself an on-demand debug tool an admin runs while looking at one villager):
  - `Job site nearby: <bool>` from `VillagerUtils.isJobSiteNearby(villager)` — directly answers "why
    isn't this villager restocking" for the position-drift scenario noted in QA report §1 (a cured
    villager that wandered away from its workstation as a zombie).
  - `Restocks today: <n> / <cap>` from `villager.getRestocksToday()` and the active cap
    (`restockSchedule.size()` in schedule mode, `2` in interval mode).
  - In schedule mode only, alongside the existing "Next scheduled restock" line
    (`LobotomizeCommand.java:186-193`): the raw `lastRestockPassId` PDC value, to make a future
    passId-comparison issue immediately visible in the field instead of requiring a code review.
- **`CLAUDE.md`**: add the new `EntityTransformEvent` handling to the `EntityListener.java` bullet
  under "Core Files", and add a short note under "PDC Keys" that all four restock/lobotomization
  keys are now bridged across zombify/cure transforms rather than being tracked as a caveat.

#### Tests
- No new automated tests beyond what A/B already add; this milestone is documentation plus a
  `LobotomizeCommand` debug-output change, which is easiest to verify manually via `/lobotomy
  debug` during the same `/runServer` pass used to close out Milestone A.

---

### 11.4 Milestone D — Diagnose and document recovery from job-site drift after zombify/cure — **Implemented**

**GitHub issue:** #1 ("Zombified villagers may permanently stop restocking/leveling if they drift
off their job site while cured").

**Root cause:** Infection converts a lobotomized `Villager` into a `ZombieVillager`, which this
plugin never tracks or lobotomizes — it keeps normal hostile AI and can physically wander for as
long as the infection lasts. Curing converts it back to a `Villager` *in place*, at wherever the
zombie villager currently stands, not back at its original position. Milestone A already bridges
all of this plugin's *data* (PDC state) across both hops, but nothing bridges the villager's
*position*. `VillagerUtils.isJobSiteNearby`'s 3×3×3 scan is required for both restocking and
leveling (`LobotomizeStorage.refreshTrades`, `LobotomizeStorage.java:811`), and a re-lobotomized
villager (AI disabled) that comes back even a few blocks off its workstation has no way to path
back on its own — so this scans `false` forever, silently stalling both, with no admin-visible
error. Any hall that isn't fully sealed against a wandering zombie (open-air, climbable, or a
player leading the infected zombie elsewhere before curing) can trigger this; tightly-enclosed
halls — the common case — usually trap the zombie too, which is why testing hasn't observed it.

#### Revision: why this milestone does *not* auto-teleport the villager

An earlier draft of this section proposed recording a "last known good" position (PDC anchor) and
automatically `teleportAsync`-ing a drifted villager back to it once its workstation went out of
range. That approach was reconsidered and dropped before implementation, for reasons worth
recording so they aren't re-litigated later:

- **Teleporting is not vanilla behavior.** This plugin's whole premise (per `CLAUDE.md`) is
  disabling AI on trapped villagers while otherwise leaving them as normal vanilla villagers — it
  deliberately never moves them itself. An admin who has physically relocated a villager on purpose
  (rebuilding a hall, moving it via boat/minecart to a new location, mid-decoration) would have it
  silently snapped back to an old position the next time a check runs, which is surprising and hard
  to explain from server logs alone.
- **It isn't actually necessary.** `VillagerUtils.isJobSiteNearby` is a plain material scan over a
  3×3×3 cube — it is not tied to a specific block, a POI, or the villager's original workstation.
  Any block of the right `Material` within range satisfies it. That means an admin can *already*
  fix a drifted villager today, with zero plugin changes, by placing a spare workstation block next
  to wherever the villager currently is — the villager doesn't need to move at all. The only real
  gap was **visibility**: nothing told the admin this was even possible, what material was needed,
  or that the villager was stuck in the first place, short of noticing "Job site nearby: false" on
  a `/lobotomy debug` they happened to run.

So this milestone is diagnostics- and documentation-only: it makes the existing, fully-vanilla,
zero-code-required recovery path (place a matching workstation nearby, or physically move the
villager back with water/pistons/a boat — both ordinary vanilla techniques) discoverable and
actionable, rather than adding new movement behavior. No PDC schema change, no new config key, no
`config-version` bump.

#### Resolved design decisions

| # | Question | Decision |
|---|---|---|
| D1 | Automatic repositioning vs. documentation-only | **Documentation-only; no automatic repositioning of any kind.** See the revision note above. The fix is telling the admin what's wrong and how to resolve it themselves, in-world, exactly as they would build any other villager containment. |
| D2 | How an admin discovers the problem | Two channels, both already-existing mechanisms extended rather than new infrastructure: (1) `/lobotomy debug`'s existing "Job site nearby: false" line gets a new "Needed workstation: `<MATERIAL>`" line right under it; (2) `LobotomizeStorage.refreshTrades` logs a one-time `WARNING` (not gated behind `debug: true`, since a silently stalled villager is otherwise invisible without running `/lobotomy debug` against that exact villager) the first time a lobotomized villager is found without its job site, naming the villager, its profession, the missing workstation material, and its current coordinates. |
| D3 | Warning spam control | Tracked via an in-memory `Set<UUID> jobSiteMissingWarned` on `LobotomizeStorage` (mirrors the existing `activeVillagers`/`inactiveVillagers` `Set` pattern already used for tracking, not persisted — a restart re-arms the warning if the villager is still stuck, which is desired). Cleared the moment `isJobSiteNearby` next returns `true` for that villager, so a later, separate drift episode warns again rather than staying permanently silenced. |
| D4 | How an admin actually fixes it | Two vanilla-legal options, both documented in README.md's new Troubleshooting section: (a) place any block of the needed workstation material within one block of the villager's *current* position — doesn't need to be the original block, since the check is a pure material scan; or (b) physically move the villager back to its original workstation using ordinary vanilla techniques (water currents, pistons, a boat/minecart) — a lobotomized villager has `NoAI` but is still a normal physics-affected entity. Either fix takes effect on the villager's very next periodic check, no plugin change or command needed. |
| D5 | Scope vs. the original "warn an admin" suggestion | Issue #1's own suggested fast-follow was "detecting job-site loss and either warning an admin or allowing the villager to briefly re-enable AI to path home." This milestone implements the "warn an admin" half only, and explicitly rejects the "re-enable AI to path home" half (D1) since it reintroduces the real pathfinding cost this plugin removes and can fail outright depending on hall geometry. |

#### Code changes

**`src/main/java/dev/mja00/villagerLobotomizer/LobotomizeStorage.java`**
- Add `private final Set<UUID> jobSiteMissingWarned = Collections.newSetFromMap(new ConcurrentHashMap<>());`
  alongside the existing tracking sets.
- In `refreshTrades`'s existing `if (!VillagerUtils.isJobSiteNearby(villager))` branch
  (`LobotomizeStorage.java:811`), call a new private `warnJobSiteMissingOnce(villager)` before the
  existing debug-gated skip log. On the success path just below (job site confirmed present),
  `this.jobSiteMissingWarned.remove(villager.getUniqueId())` clears any prior warning.
- New `warnJobSiteMissingOnce`: no-ops (via `Set.add`'s `false` return) if this villager is already
  in `jobSiteMissingWarned`; otherwise logs the `WARNING` described in D2 and adds the UUID.
- No changes to `bridgeRestockState`, no new `PersistentDataType`s, no new config reads.

**`src/main/java/dev/mja00/villagerLobotomizer/LobotomizeCommand.java`**
- `getVillagerDetails`: when `jobSiteNearby` is `false`, append a "Needed workstation: `<MATERIAL>`"
  line reading `VillagerUtils.PROFESSION_TO_STATION.get(villager.getProfession())`, skipped for
  job-siteless professions (`NITWIT`/`NONE`, whose station is `Material.AIR`) since there's nothing
  meaningful to suggest placing.

**`README.md`**
- New "Troubleshooting" section (after "Restock Scheduling", before "Special Villager Names")
  explaining the zombify/cure job-site-drift scenario, why the plugin doesn't auto-teleport, and
  both vanilla-legal fixes (place a matching block nearby, or physically relocate the villager).

**`CLAUDE.md`**
- `LobotomizeStorage.java` bullet: note `warnJobSiteMissingOnce`/`jobSiteMissingWarned` and the
  explicit no-auto-teleport decision.
- `LobotomizeCommand.java` bullet: note the new "Needed workstation" debug line.

#### Tests
- **MockBukkit, new — `JobSiteDriftWarningTest.java`**: a lobotomized (`"nobrain"`-named) farmer
  with no `COMPOSTER` in range warns exactly once across several check-interval ticks (not once per
  tick); placing the missing block clears the warning state, and removing it again afterward
  produces a second, independent warning — proving both the spam guard and the re-arm-after-recovery
  behavior from D3. A third case asserts a lobotomized `NITWIT` (no workstation by design, so
  `isJobSiteNearby` is permanently `false`) never warns at all — caught during implementation as a
  real bug in an early draft of `warnJobSiteMissingOnce` that would have logged a nonsensical
  warning for every job-siteless profession on its very first check.
- **`/lobotomy debug`'s new "Needed workstation" line**: not covered by an automated test (no
  existing `LobotomizeCommandTest` harness for brigadier command dispatch in this suite) — verify
  manually, same as Milestone C's own debug-field additions.
- **Manual `/runServer` verification (recommended before closing issue #1):** reproduce the
  scenario from the issue — lobotomize a villager at its workstation, let it get infected, walk or
  lead the resulting zombie a few blocks off its job site pad (in an open-air setup, since a fully
  sealed hall traps the zombie too), cure it there, and confirm the villager is now visibly stuck
  (`/lobotomy debug` shows "Job site nearby: false" + "Needed workstation"), the server log shows
  exactly one warning for it, and placing a spare workstation block nearby (without moving the
  villager) resumes restocking/leveling on the next check. Unlike Milestone A, this isn't gated on
  an unverifiable event-ordering assumption — the fix here is inherently manual/admin-driven, so
  this pass is about confirming discoverability, not an automatic code path.

---

### 11.5 Milestone E — Deliver the restock-schedule mode/times clarification at runtime — **Implemented**

**GitHub issue:** #3 ("restock-schedule config clarification comment never reaches already-migrated
(config-version 7) servers").

**Root cause:** Milestone C added an `IMPORTANT:` comment above `restock-schedule:` in the shipped
`config.yml` clarifying that populating `times` does nothing unless `mode` is explicitly set to
`"schedule"`. That comment can only ever reach a server through `ConfigMigrator`'s migration path,
and two independent things block it from reaching any server that already has a config-version-7
`config.yml` (i.e. every server that could have hit the original "unclear if 3 restocks are
working" confusion in the first place):

1. `ConfigMigrator.migrate()` early-returns with zero merge work whenever
   `existingConfig.getInt("config-version", 0) == CURRENT_CONFIG_VERSION` — an already-current
   config file is left completely untouched on plugin update.
2. **Even a forced re-migration wouldn't have fixed it.** `CommentPreservingYamlMigrator.mergeRecursive`
   (the `Map`-vs-`Map` branch used for the `restock-schedule:` section) does
   `comment = existingComments.get(keyPath); if (comment == null) { comment = defaultComments.get(keyPath); }`
   — it only ever falls back to the new default comment when the admin's file has *no* comment at
   that key/section at all. Since `restock-schedule:` has had *some* comment since it was
   introduced (config-version 6→7), every already-migrated server's old comment text wins
   regardless. This means the `config-version` bump floated as an option in the issue itself would
   **not** actually have delivered the updated comment — confirmed by tracing the merge logic rather
   than assumed, since silently shipping a version bump that doesn't fix anything would be worse
   than not bumping at all.

#### Resolved design decisions

| # | Question | Decision |
|---|---|---|
| E1 | Config-version bump | **Rejected**, for the reason traced above — bumping `CURRENT_CONFIG_VERSION` triggers a merge pass, but `CommentPreservingYamlMigrator` would still prefer the admin's pre-existing `restock-schedule:` comment over the new one, so the delivery gap would persist even after a bump. Forcing an overwrite of the admin's comment for this one key was also considered and rejected — it would require carving out a special case that breaks the general "never clobber an admin's own comment edits" contract `CommentPreservingYamlMigratorTest` already locks in, for a fix that's really about reaching *new* information, not preserving/discarding old text. |
| E2 | CHANGELOG/release-notes callout | **Rejected as the primary fix**, though not incompatible with one existing independently. This project has no `CHANGELOG.md` today, and introducing one is a bigger structural change than this issue calls for; more fundamentally, a changelog entry still depends on an admin actually reading it, whereas the chosen fix (E3) reaches every affected server unconditionally with zero admin action required. |
| E3 | Runtime log reminder | **Chosen fix.** `LobotomizeStorage`'s constructor (already the place `restock-schedule` config is read and validated) now logs a one-time `INFO` line whenever `restock-schedule.times` has entries but `mode` is not `"schedule"` — the exact confusing state the original QA finding was about. This is a runtime check against the *live parsed config*, not a static file comparison, so it is completely unaffected by config-version/migration history: a server that migrated years ago sees it exactly the same as a fresh install. It fires once per enable/reload (matching the existing `restockSchedule.warnings()` logging pattern immediately above it in the constructor), at `INFO` rather than `WARNING` since nothing is actually broken — this is a "did you mean to..." nudge, not an error. |
| E4 | Detecting "did the admin mean to enable it" vs. spam | Deliberately **not** attempted. The shipped default `config.yml` itself ships `mode: interval` with `restock-schedule.times` pre-populated as an example, so *every* untouched fresh install matches the trigger condition (`times` non-empty, `mode` != `"schedule"`) — there is no reliable way to distinguish "admin edited `times` believing it was active" from "admin never touched this section" using only the parsed config. Rather than build a fragile heuristic (e.g. diffing against the shipped default list, which breaks the moment that example list changes in a later version), the reminder is accepted as always-relevant informational output, consistent with this plugin's existing precedent of unconditional startup `INFO` lines (update-checker status, Sentry status) that most admins will never need to act on. |
| E5 | README.md | No change needed to the substance (QA already confirmed it's current and accurate and not subject to the migration-comment problem since it's never merged into anyone's config) — added one cross-reference sentence noting the new runtime reminder exists, so a reader who spots the log line and greps for it in docs finds an explanation. |

#### Code changes

**`src/main/java/dev/mja00/villagerLobotomizer/LobotomizeStorage.java`**
- Constructor: capture the raw `plugin.getConfig().getStringList("restock-schedule.times")` result
  in a local (`configuredRestockTimes`) before parsing it into `this.restockSchedule`, so the
  reminder can check the *raw* configured list (independent of whether individual entries parse as
  valid) rather than the post-validation `RestockSchedule`.
- Add an `else if (!configuredRestockTimes.isEmpty())` branch alongside the existing
  `if (scheduleModeConfigured) { ... }` block, logging the one-time `INFO` reminder described in E3.

**`README.md`**
- One added sentence in the "Restock Scheduling" section cross-referencing the new runtime
  reminder and this plan section.

**`CLAUDE.md`**
- New bullet under `LobotomizeStorage.java`'s "Core Files" entry documenting the reminder and why
  it's a runtime check rather than a config comment.

#### Tests
- **`LobotomizeStorageScheduleTest` (extend)**: three new cases, using a `java.util.logging.Handler`
  attached to `plugin.getLogger()` to capture emitted records (the same technique
  `JobSiteDriftWarningTest` introduced in Milestone D) — the reminder logs when `times` is populated
  under `mode: interval` (the exact confusing state), does not log when `times` is empty, and does
  not log when `mode: schedule` is already active (since `times` is genuinely in effect there).

---

### 11.6 Milestone F — Close the sequential multi-restock E2E coverage gap — **Implemented**

**GitHub issue:** #5 ("No end-to-end test drives sequential multi-restock-per-day scenarios in
schedule mode").

**Gap (not a defect):** `RestockScheduleEndToEndTest` already covered a 1-point schedule, a
2-point schedule collapsed into one catch-up restock via a single large forward jump
(`villagerCatchesUpAfterSleepingPastMultipleScheduledPoints`), and a 3-point schedule reached only
after all three points already fired together or via a shrink-then-reload scenario
(`restockKeepsWorkingAfterTimesListShrinksAcrossReload`). Nothing drove three *separate*,
individually-triggered checks across one in-game day for one villager — restock at 08:00, use a
trade again, restock at 10:30, use it again, restock at 13:00 — each via its own distinct tick
rather than one combined jump. As the issue itself notes, the underlying gating logic
(`RestockSchedule.passId` / `LobotomizeStorage.refreshTrades`) is identical regardless of how the
schedule is reached, so this was purely about closing an end-to-end confidence gap, not chasing a
suspected bug.

#### Code changes

**`src/test/java/dev/mja00/villagerLobotomizer/RestockScheduleEndToEndTest.java`**
- New test `villagerRestocksAtEachOfThreeScheduledPointsIndividually`: loads `mode: schedule` with
  `times = ["08:00", "10:30", "13:00"]`, ticks the villager once before any point (lobotomizes it,
  0 restocks), then advances `world.setFullTime(...)` to each of tick 2000/4500/7000 in turn with a
  separate `tick(...)` call and a trade re-marked as used between each, asserting `tradesWereReset`
  and `restocksToday` incrementing to 1, 2, then 3 — proving each configured point restocks
  individually rather than only being reachable via a combined catch-up jump.
- No production code changed; this is additive test coverage only, exactly as the issue's own
  "Blocking for a first beta release?" answer describes.

#### Tests
- The new test itself is the deliverable. Full suite green (`./gradlew test`, 91 tests including
  this one).

---

### 11.7 Milestone G — Refresh an open trade window on restock — **Implemented, pending manual verification**

**GitHub issue:** #6 ("Restocked trades don't appear in an already-open trade GUI until reopened").

**Confirmed root cause (via `org.bukkit.inventory.Merchant`'s own Javadoc, checked directly rather
than assumed):** `Merchant#setRecipes` states outright that it *"will not change the selected
trades of players currently trading with this merchant."* `LobotomizeStorage.refreshTrades` calls
exactly this method to apply a restock, with no further action — so a restock that fires while a
player is mid-trade is applied server-side but never pushed to that player's already-open trade
screen, matching the reported symptom exactly. This is a general Bukkit/Paper `Merchant` API
characteristic (answers the issue's open question 1: not specific to lobotomization, schedule
mode, or this plugin), and since `refreshTrades` is one shared method for both restock-gating
modes, it affects `interval` mode identically (answers open question 3) — the owner's request to
prioritize `schedule` mode is satisfied automatically by fixing the shared code path rather than
needing two separate fixes.

Also checked directly against `Merchant`'s own API surface (answers open question 2): it exposes
`isTrading()` and `getTrader()` (the player currently trading, or `null`), but no method to push an
updated offer list to an inventory that's already open. The closest supported mechanism is
re-opening the merchant for that player via `HumanEntity#openMerchant(Merchant, boolean force)`
(`force=true`, since Bukkit refuses to open a merchant for a player already mid-trade without it) —
deprecated since Paper 1.21.4 in favor of `MenuType.MERCHANT` + `openInventory(InventoryView)`, but
not removed, and the replacement's public API doesn't expose a way to bind an existing `Merchant`
instance to a fresh view, so the deprecated overload is what's actually usable here.

#### Resolved design decisions

| # | Question | Decision |
|---|---|---|
| G1 | Fix mechanism | After every successful restock in `refreshTrades` (right after `setRecipes`/`updateDemand`), call new method `refreshOpenTradeWindow(villager)`: if `villager.isTrading()`, fetch `villager.getTrader()` and re-open the merchant for them (`force=true`), so the client re-requests the current (now-updated) offers. No config toggle — this is a pure bug fix with no legitimate reason an admin would want the stale-trades behavior, unlike Milestone D's teleport decision. |
| G2 | Thread safety | The trader is a different entity from the villager `refreshTrades` is already running for (via the villager's own `EntityScheduler` task); per this codebase's Folia-safety rules, mutating a different entity requires dispatching through *that entity's own* scheduler rather than assuming same-region locality, even though a trading player is almost always right next to the villager. Dispatched via `trader.getScheduler().run(this.plugin, SentryTaskWrapper.wrap(...), null)`, matching the existing pattern used elsewhere in this class (e.g. `wakeVillager` dispatch). |
| G3 | Re-check before acting | Since the scheduler dispatch is asynchronous relative to the restock, the scheduled task re-checks `trader.isValid() && trader.equals(villager.getTrader())` before calling `openMerchant` — the trade may have already ended (player closed the window, walked away, etc.) by the time the task actually runs. |
| G4 | Verification | **MockBukkit cannot verify the fix itself** — its `HumanEntity#openMerchant` is an unimplemented stub, and even a real implementation couldn't confirm client-visible rendering from a headless test. `RefreshOpenTradeWindowTest` only proves the dispatch logic runs without throwing (no-op when nobody's trading; a scheduled reopen when somebody is). Whether the trade window actually visibly refreshes for a real client — and whether doing so causes any visible side effect (e.g. the window flickering closed/reopened, the selected trade slot resetting, the villager's ambient trade sound replaying) — requires a manual `/runServer` pass: have a player open a lobotomized villager's trade screen, trigger a restock (wait for the schedule/interval to fire, or use `/lobotomy debug` timing to predict it), and confirm the new trades appear without closing the window. |

#### Code changes

**`src/main/java/dev/mja00/villagerLobotomizer/LobotomizeStorage.java`**
- Add `import org.bukkit.entity.HumanEntity;`.
- New package-visible method `refreshOpenTradeWindow(@NotNull Villager villager)` (package-visible
  rather than `private` specifically so `RefreshOpenTradeWindowTest` can exercise it directly — see
  G4): early-returns if `!villager.isTrading()` or `getTrader()` is `null`; otherwise dispatches the
  re-check-and-`openMerchant(villager, true)` call through the trader's own `EntityScheduler`.
- Call it once, right after `villager.updateDemand()` in the successful-restock branch of
  `refreshTrades` — no other control flow in `refreshTrades` changes.

#### Tests
- **MockBukkit, new — `RefreshOpenTradeWindowTest.java`**: `doesNothingWhenNobodyIsTrading` (no
  trader set, method returns without throwing) and
  `schedulesAMerchantReopenWithoutThrowingWhenSomeoneIsTrading` (a `PlayerMock` set as the
  villager's trader via `VillagerMock#setTrader`, dispatch doesn't throw — it never reaches
  MockBukkit's unimplemented `openMerchant` stub since that only runs inside the scheduled task).
- Full suite green (`./gradlew test`, 93 tests including these two). All existing restock tests
  (`RestockScheduleEndToEndTest`, `JobSiteDriftWarningTest`, etc.) continued passing unchanged,
  confirming `refreshOpenTradeWindow`'s no-trader no-op path doesn't disturb any existing restock
  flow.
- **Manual `/runServer` verification (required before closing issue #6):** per G4 — this is the
  only way to confirm the actual fix (a visibly-refreshing trade window), and to check for any
  distracting visual side effect from re-opening the merchant mid-trade.

---

### Updated rollout order (supersedes/extends §10 for this follow-up work)

1. **Milestone A — done, manually verified.** `EntityListener` transform handler +
   `LobotomizeStorage.bridgeRestockState` + `VillagerUtils` sentinel fix, with their MockBukkit
   tests. The manual `/runServer` zombify/cure round trip (assumption A5 + the vanilla-persistence
   premise) was performed and confirmed working — see issue #2 (closed) and §11.1's "Manual
   verification result" addendum.
2. **Milestone B — done.** `RestockSchedule.passId` redesign + its unit/E2E tests. The pure-unit
   and real end-to-end (`reloadPluginState()`) coverage is strong per `reports/QA_report_24082026_2.md`
   §2; a live `/runServer` restart with an edited `times` list has not been separately reported as
   performed and remains a nice-to-have, not a blocker (unlike Milestone A, this isn't gated on
   anything MockBukkit can't already exercise).
3. **Milestone C — done.** Docs + `/lobotomy debug` diagnostic fields, landed on top of A/B's
   final PDC semantics.
4. **Milestone D — done.** Job-site-drift diagnostics for issue #1: `/lobotomy debug`'s "Needed
   workstation" line, `refreshTrades`'s one-time `warnJobSiteMissingOnce` warning (with
   `JobSiteDriftWarningTest` MockBukkit coverage), and README.md's Troubleshooting section
   documenting the two vanilla-legal fixes (place a matching workstation nearby, or physically
   relocate the villager). No automatic teleport/reposition — see §11.4's revision note for why
   that earlier draft was dropped. A manual `/runServer` reproduction pass (walk an infected
   zombie off its pad, cure it, confirm the warning/debug line fire and that placing a spare
   workstation nearby resumes restocking) is recommended before closing issue #1, but — unlike
   Milestone A — isn't gated on any unverifiable assumption, since the fix itself is inherently
   admin-driven rather than an automatic code path.
5. **Milestone E — done.** Runtime reminder for issue #3: `LobotomizeStorage`'s constructor logs a
   one-time `INFO` line whenever `restock-schedule.times` has entries but `mode` isn't
   `"schedule"`, with `LobotomizeStorageScheduleTest` coverage. Explicitly does not rely on a
   `config-version` bump — traced through `CommentPreservingYamlMigrator` and confirmed a bump
   would not have actually delivered the updated comment to already-migrated servers (see §11.5's
   root-cause section), so no config schema change was made. No manual `/runServer` pass needed;
   this is a plain constructor-logic change already fully covered by MockBukkit.
6. **Milestone F — done.** Closes the issue #5 test-coverage gap: a new
   `villagerRestocksAtEachOfThreeScheduledPointsIndividually` case in
   `RestockScheduleEndToEndTest` drives three separate, individually-triggered restocks across one
   in-game day, rather than one combined catch-up jump. Purely additive test coverage, no
   production code changed, no manual verification needed.
7. **Milestone G — code done, manual verification pending.** Fix for issue #6:
   `LobotomizeStorage.refreshTrades` now calls a new `refreshOpenTradeWindow` after every restock,
   re-opening the merchant for whoever is currently trading with the villager (confirmed root
   cause: `Merchant#setRecipes`'s own Javadoc documents it doesn't update an already-open trade
   window). `RefreshOpenTradeWindowTest` covers the dispatch logic, but — unlike Milestones D/E/F —
   this one genuinely can't be confirmed by MockBukkit at all (no packet/client rendering
   simulation), so a manual `/runServer` pass (open a trade window, trigger a restock, confirm it
   updates live with no distracting visual side effect) is required before closing issue #6, not
   just recommended.
