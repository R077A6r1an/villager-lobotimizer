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
| Pure unit | `utils/VillagerUtilsTest.java` (extend) | New schedule-aware `allowedToRestock` overload / cap parameter. |
| MockBukkit | new test under `src/test/java/.../LobotomizeStorageScheduleTest.java` (or extend `EntityListenerTest`'s pattern) | End-to-end: a villager doesn't restock before a schedule point, does restock after one fires, respects the `times.size()` daily cap, and a simulated large `world.setFullTime()` jump (sleep/`/time set` equivalent) triggers a catch-up restock on the villager's next periodic check. |
| Config migration | `utils/CommentPreservingYamlMigratorTest.java` (extend) | Upgrading a v6 config (no `restock-schedule` key) to v7 adds the new section with defaults, preserving existing keys/comments. |
| Manual/in-game | — | `./gradlew runServer`: verify `/lobotomy reload` picks up a `mode: schedule` config, `/lobotomy debug` shows the next restock time, and a lobotomized villager actually restocks at a configured time (can be sped up in a test server via `/time set` to just before a configured time). |

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
