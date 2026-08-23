# Feature: Tick-Scheduled Villager Restocks

## Summary

Replace the current real-time interval restock system (`restock-interval` / `restock-random-range`, both in milliseconds) with a **fixed daily schedule based on in-game time-of-day**, configurable in `config.yml`. Instead of "restock every N minutes of wall-clock time," the plugin should restock all eligible lobotomized villagers at specific points in the Minecraft day cycle — e.g. 08:00, 10:30, and 13:00 in-game — repeating every vanilla day.

## Motivation

The current interval system is a real-time approximation of vanilla restock cadence (default 540000ms ≈ 9 min, chosen to loosely mirror "up to 2 restocks per Minecraft day"). It has no relationship to actual in-game time, work-hour windows, day/night state, or server-side time manipulation (`/time set`, sleeping, disabled daylight cycle). This makes restock timing feel arbitrary and disconnected from vanilla villager behavior, and makes it hard for admins to reason about "when will my villagers restock" in terms players are used to.

We want restocks to occur at admin-defined **in-game clock times**, recurring daily, so behavior is predictable, configurable per server economy design, and closer in spirit to vanilla's "villager restocks while working during the day" model — without reintroducing the actual pathfinding/AI cost we removed via lobotomization.

## Current Behavior (for reference)

```yaml
restock-interval: 540000       # ms between forced restocks
restock-random-range: 0        # ms of jitter before restock-interval; 0 = no randomization
```

Restocks are scheduled purely on a repeating real-time timer, independent of world time.

## Proposed Behavior

Introduce a new scheduling mode driven by a list of in-game times of day:

```yaml
restock-schedule:
  # Restock mode: "interval" (legacy real-time) or "schedule" (new tick-based)
  mode: "schedule"

  # List of in-game times at which a restock pass runs, in 24h HH:mm format.
  # Interpreted against the standard Minecraft day clock (0 ticks = 06:00, 24000 ticks = 06:00 next day).
  times:
    - "08:00"
    - "10:30"
    - "13:00"

  # Optional jitter applied per scheduled time, in ticks, to avoid players
  # timing trades exactly on the restock tick. 0 = exact, fires precisely at the configured time.
  jitter-ticks: 0
```

At each configured time, every eligible lobotomized villager (subject to the existing `only-lobotomize-villagers-with-professions` / `only-lobotomize-villagers-with-experience` filters) has its trade restock logic run, equivalent to what currently happens on the real-time timer tick.

### Time source

- Times are evaluated against each world's own day/night cycle (`world.getTime() % 24000`), not a global server clock, since worlds can have independent or frozen time.
- Which world(s) this applies to (all worlds vs. a configured list) needs to be decided — see open questions.

### Handling time skips (sleeping, `/time set`, disabled daylight cycle)

Since scheduling is tick-based rather than a wall-clock timer, we need explicit rules for:
- **Sleeping through the night** (server-side "everyone sleeps" skip): does this fire any restock windows that were skipped over, or are they simply missed until the next day's occurrence?
- **`/time set` jumps**: if an admin or plugin jumps time forward/backward, do we detect crossed schedule boundaries and fire them, or ignore and just check on the next natural tick?
- **Disabled daylight cycle** (`doDaylightCycle` false): if time never advances, scheduled restocks would never fire — should there be a fallback?

We should implement this as a per-world tick listener that checks, once per tick or at a reasonable poll interval, whether the world time has crossed one of the configured schedule points since the last check — not a naive "if current tick == target tick" comparison, which would silently break on any time skip.

## Config Migration

- `mode: interval` should remain supported so existing servers aren't forced to migrate immediately.
- `restock-interval` / `restock-random-range` keys stay for `interval` mode; `restock-schedule.times` / `jitter-ticks` are only read in `schedule` mode.
- Config version bump (`config-version`) with a migration note in the changelog.

## Related Systems to Reconsider

- **"Night-time trade refresh" feature**: currently a separate toggle. Under the new schedule model, night-time refresh is just another entry in `times` — this feature may become redundant and could be deprecated/removed in favor of the schedule list.
- **`/lobotomy debug`**: should be updated to show the next scheduled restock time (in-game clock time and world) instead of, or in addition to, a real-time countdown.
- **Sounds / level-up celebration**: no change expected — should trigger the same way, just driven by the new scheduler instead of the interval timer.

## Acceptance Criteria

- [ ] Admins can define an arbitrary number of daily restock times via `config.yml`, in HH:mm format.
- [ ] Restocks fire at the correct in-game moment per world, independent of real-world elapsed time.
- [ ] Time skips (sleep, `/time set`, frozen daylight cycle) are handled per whatever rule the team decides on (see open questions) rather than silently dropping or double-firing restocks.
- [ ] Legacy `interval` mode continues to work unchanged for servers that don't opt into `schedule` mode.
- [ ] `/lobotomy debug` reflects the new schedule (next restock time/world).
- [ ] Config is validated on load/reload (rejects malformed times, warns on duplicate/near-duplicate entries).

---

## Open Questions

1. **Time format**: Is HH:mm (in-game clock, 06:00–29:59 style if we allow past-midnight wrap) the right format, or would you prefer raw tick values (0–24000)? Should we support both?
   Only daytime tick values should be supported!
2. **Scope of worlds**: Should the schedule apply to all worlds by default, or should it be configurable per-world (e.g. different restock times for an End trading hall vs. the Overworld base)?
   It should apply to all worlds!
3. **Sleep/time-skip behavior**: If players sleep through the night and skip past one or more scheduled restock times, should those restocks fire immediately on wake (catch-up), or simply be skipped until their next natural occurrence the following day?
   Fire immediately on wake up!
4. **`/time set` and admin time jumps**: Should crossed schedule boundaries fire retroactively, or be ignored until the next tick check?
   Fire retroactively!
5. **Disabled daylight cycle**: If `doDaylightCycle` is false and time never advances, should there be a fallback (e.g. a minimum real-time-based safety restock) so villagers don't get permanently stuck?
   This case is undefined behavior. It is expected to run in a day-night cycled server.
6. **Jitter**: Do you want per-scheduled-time jitter (`jitter-ticks`) at all, or should each configured time fire exactly, with no randomization, now that the schedule itself is already fully admin-controlled?
   It should be configured time fire exactly!
7. **Legacy mode**: Should `interval` mode be kept indefinitely as a supported alternative, or is this intended as a hard replacement with `interval` deprecated/removed in a future version?
   This feature requests leaves this question open. The goal is indeed to 
8. **Restock count/frequency limits**: Do you want any built-in guardrail on how many times per day can be configured (e.g. a soft warning above N entries), or fully unrestricted?
9. **Economy/demand decay**: Should price demand decay (separate from use-count restock) also move to this schedule, or remain on its current independent timing?
10. **Per-villager opt-out**: Should the existing "nobrain"/"alwaysbrain" naming convention get a third option to exclude specific villagers from the new scheduled restock (e.g. a villager that should never restock automatically)?
11. **Multi-time ordering/spacing**: Should the plugin enforce a minimum spacing between configured times (e.g. reject two times less than X ticks apart) to prevent misconfiguration, or leave that entirely up to the admin?