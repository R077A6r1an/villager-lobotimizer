package dev.mja00.villagerLobotomizer.policy;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure decision logic for tick-scheduled restocks ("restock-schedule.mode: schedule"). Parses a
 * list of HH:mm config entries into in-game daytime ticks and answers "has a new restock
 * opportunity occurred" without touching any live Bukkit object, so it can be driven directly by
 * {@code World.getFullTime()} and unit tested without MockBukkit.
 *
 * <p>Tick-of-day 0 corresponds to 06:00 and 12000 to 18:00, mirroring
 * {@code World.getFullTime() % TICKS_PER_DAY} (the same full-time basis
 * {@code VillagerUtils.shouldRestock} already uses for day-rollover detection, rather than the
 * doc's {@code World.getTime()} — the two are equivalent modulo 24000 but full-time is what the
 * rest of this codebase already keys off of).
 */
public final class RestockSchedule {

    /** In-game ticks per Minecraft day. */
    public static final long TICKS_PER_DAY = 24000L;

    /** Daytime window: 06:00 (tick 0) through 18:00 (tick 12000), inclusive. */
    public static final long MAX_DAYTIME_TICK = 12000L;

    /** Ticks per in-game hour (TICKS_PER_DAY / 24). */
    private static final long TICKS_PER_HOUR = 1000L;

    /** Two schedule points closer together than this (in ticks) trigger a near-duplicate warning. */
    private static final long NEAR_DUPLICATE_THRESHOLD_TICKS = 100L;

    private static final Pattern HH_MM = Pattern.compile("^([0-9]{2}):([0-9]{2})$");

    /** Sorted, deduplicated, ascending daytime ticks (each in [0, MAX_DAYTIME_TICK]). */
    private final long[] ticks;
    private final List<String> warnings;

    private RestockSchedule(long[] ticks, List<String> warnings) {
        this.ticks = ticks;
        this.warnings = List.copyOf(warnings);
    }

    /**
     * Parses raw {@code restock-schedule.times} config entries into a schedule.
     *
     * <p>Each entry must be a 24h {@code HH:mm} string mapping to an in-game daytime tick (06:00
     * through 18:00 inclusive); malformed entries or entries falling outside that window are
     * dropped and reported via {@link #warnings()} rather than throwing. Duplicate or
     * near-duplicate entries (within {@value #NEAR_DUPLICATE_THRESHOLD_TICKS} ticks of each other)
     * are kept but also reported as a warning, per the project's "warn, don't reject" guardrail.
     *
     * @param times raw config entries, e.g. {@code ["08:00", "10:30", "13:00"]}
     * @return a schedule containing every entry that parsed and validated successfully
     */
    public static RestockSchedule parse(List<String> times) {
        List<String> warnings = new ArrayList<>();
        List<Long> accepted = new ArrayList<>();

        if (times != null) {
            for (String raw : times) {
                Long tick = parseEntry(raw, warnings);
                if (tick != null) {
                    accepted.add(tick);
                }
            }
        }

        accepted.sort(Long::compareTo);
        warnDuplicates(accepted, warnings);

        long[] deduped = accepted.stream().distinct().mapToLong(Long::longValue).toArray();
        return new RestockSchedule(deduped, warnings);
    }

    /**
     * Parses and validates a single {@code HH:mm} entry.
     *
     * @return the entry's daytime tick, or {@code null} (with a warning appended) if it is
     *         malformed or falls outside the 06:00-18:00 window
     */
    private static Long parseEntry(String raw, List<String> warnings) {
        if (raw == null || raw.isBlank()) {
            warnings.add("Ignoring blank restock-schedule.times entry.");
            return null;
        }

        String trimmed = raw.trim();
        Matcher matcher = HH_MM.matcher(trimmed);
        if (!matcher.matches()) {
            warnings.add("Ignoring malformed restock-schedule.times entry \"" + raw + "\" (expected HH:mm).");
            return null;
        }

        int hour;
        int minute;
        try {
            hour = Integer.parseInt(matcher.group(1));
            minute = Integer.parseInt(matcher.group(2));
        } catch (NumberFormatException e) {
            warnings.add("Ignoring malformed restock-schedule.times entry \"" + raw + "\" (expected HH:mm).");
            return null;
        }

        if (hour < 0 || hour > 23 || minute < 0 || minute > 59) {
            warnings.add("Ignoring out-of-range restock-schedule.times entry \"" + raw + "\" (expected 00:00-23:59).");
            return null;
        }

        long hoursFrom6am = ((hour - 6) % 24 + 24) % 24;
        long tick = hoursFrom6am * TICKS_PER_HOUR + Math.round(minute * (TICKS_PER_HOUR / 60.0));

        if (tick < 0 || tick > MAX_DAYTIME_TICK) {
            warnings.add("Ignoring restock-schedule.times entry \"" + raw
                    + "\" - only daytime times (06:00-18:00) are supported.");
            return null;
        }

        return tick;
    }

    /** Appends a warning for each pair of sorted ticks closer than the near-duplicate threshold. */
    private static void warnDuplicates(List<Long> sortedTicks, List<String> warnings) {
        for (int i = 1; i < sortedTicks.size(); i++) {
            long previous = sortedTicks.get(i - 1);
            long current = sortedTicks.get(i);
            long gap = current - previous;
            if (gap == 0) {
                warnings.add("Duplicate restock-schedule.times entry at " + formatTick(current) + ".");
            } else if (gap < NEAR_DUPLICATE_THRESHOLD_TICKS) {
                warnings.add("restock-schedule.times entries " + formatTick(previous) + " and " + formatTick(current)
                        + " are only " + gap + " ticks apart.");
            }
        }
    }

    /**
     * @return the number of distinct accepted schedule points; used as the daily restock cap in
     *         schedule mode (in place of the hardcoded 2-per-day cap used by interval mode)
     */
    public int size() {
        return this.ticks.length;
    }

    /** @return {@code true} if no entry parsed and validated successfully */
    public boolean isEmpty() {
        return this.ticks.length == 0;
    }

    /** @return every warning collected while parsing, in encounter order */
    public List<String> warnings() {
        return this.warnings;
    }

    /**
     * Computes a value that increases every time a new scheduled restock point is crossed, so that
     * callers can compare a previously stored value against a fresh call for the same world to
     * cheaply answer "has a new restock opportunity occurred since I last restocked" via plain
     * {@code >} — including catch-up after a large forward jump in {@code fullTime} (sleeping
     * through the night, {@code /time set} moving forward), since a jump past several points in one
     * step still reflects only the single latest one, not a per-point count.
     *
     * <p>The returned value is the absolute {@code fullTime} tick of the latest configured point
     * crossed so far today (offset by {@code +1} so it's never {@code 0}, which is reserved for "no
     * point has been crossed since the schedule's day started" / the PDC default for "never
     * restocked"), or {@code 0} if none has been crossed yet today or the schedule is empty.
     * Deliberately <strong>not</strong> encoded as {@code day * size() + crossedCount}: that earlier
     * encoding baked the schedule's entry count into the value, so a stored value became
     * incomparable with a freshly computed one the moment {@code restock-schedule.times} gained or
     * lost an entry between two {@link RestockSchedule} constructions (e.g. across a server restart
     * or {@code /lobotomy reload}) — silently stalling restocks for however long it took the new
     * count's sequence to numerically catch back up. Keying off each point's own absolute tick
     * instead is unaffected by how many <em>other</em> points are configured, so it stays valid
     * across any edit to {@code times} without needing to invalidate or migrate a stored value.
     *
     * @param fullTime the world's current {@code World.getFullTime()}
     * @return {@code 0} if the schedule is empty or no point has been crossed yet today, otherwise
     *         the absolute tick of the latest point crossed so far today, plus one
     */
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

    /**
     * Finds the next scheduled restock point strictly after {@code fullTime}, for display purposes
     * (e.g. {@code /lobotomy debug}).
     *
     * @param fullTime the world's current {@code World.getFullTime()}
     * @return the absolute full-time tick of the next occurrence, or {@code Long.MAX_VALUE} if the
     *         schedule is empty
     */
    public long nextOccurrence(long fullTime) {
        if (isEmpty()) {
            return Long.MAX_VALUE;
        }
        long day = Math.floorDiv(fullTime, TICKS_PER_DAY);
        long tickOfDay = Math.floorMod(fullTime, TICKS_PER_DAY);
        long dayStart = day * TICKS_PER_DAY;

        for (long tick : this.ticks) {
            if (tick > tickOfDay) {
                return dayStart + tick;
            }
        }
        return dayStart + TICKS_PER_DAY + this.ticks[0];
    }

    /**
     * Formats an in-game daytime tick (0-12000) back to a 24h {@code HH:mm} clock time, for
     * warnings and future debug display.
     */
    public static String formatTick(long daytimeTick) {
        long totalMinutes = Math.round(daytimeTick / (TICKS_PER_HOUR / 60.0));
        long hour = (6 + totalMinutes / 60) % 24;
        long minute = totalMinutes % 60;
        return String.format(Locale.ROOT, "%02d:%02d", hour, minute);
    }
}
