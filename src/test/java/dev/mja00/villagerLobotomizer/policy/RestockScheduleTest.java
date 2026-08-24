package dev.mja00.villagerLobotomizer.policy;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RestockScheduleTest {

    private static final long TICKS_PER_DAY = RestockSchedule.TICKS_PER_DAY;

    // ---- Parsing / validation ----

    @Test
    void parsesBoundaryDaytimeTimes() {
        RestockSchedule schedule = RestockSchedule.parse(List.of("06:00", "18:00"));

        assertTrue(schedule.warnings().isEmpty(), "unexpected warnings: " + schedule.warnings());
        assertEquals(2, schedule.size());
        assertEquals("06:00", RestockSchedule.formatTick(0L));
        assertEquals("18:00", RestockSchedule.formatTick(12000L));
    }

    @Test
    void mapsHhMmToExpectedDaytimeTick() {
        RestockSchedule schedule = RestockSchedule.parse(List.of("08:00", "10:30", "13:00"));

        assertTrue(schedule.warnings().isEmpty(), "unexpected warnings: " + schedule.warnings());
        assertEquals(3, schedule.size());

        // 08:00 -> 2000, 10:30 -> 4500, 13:00 -> 7000 (tick 0 == 06:00, 1000 ticks/hour)
        assertEquals("08:00", RestockSchedule.formatTick(2000L));
        assertEquals("10:30", RestockSchedule.formatTick(4500L));
        assertEquals("13:00", RestockSchedule.formatTick(7000L));

        // passId uses ">=" at the boundary, so landing exactly on a scheduled tick already counts
        // as crossed, and encodes the absolute tick (+1) of the latest point crossed so far today.
        assertEquals(2001L, schedule.passId(2000L));
        assertEquals(4501L, schedule.passId(4500L));
        assertEquals(7001L, schedule.passId(7000L));
    }

    @Test
    void rejectsNightTimeEntries() {
        RestockSchedule schedule = RestockSchedule.parse(List.of("20:00", "02:00"));

        assertTrue(schedule.isEmpty());
        assertEquals(2, schedule.warnings().size());
        for (String warning : schedule.warnings()) {
            assertTrue(warning.contains("06:00-18:00"), warning);
        }
    }

    @Test
    void rejectsMalformedAndOutOfRangeEntries() {
        RestockSchedule schedule = RestockSchedule.parse(java.util.Arrays.asList(
                "8:00",   // not zero-padded -> malformed
                "25:00",  // hour out of range
                "12:60",  // minute out of range
                "noon",   // garbage
                "",       // blank
                null      // null entry
        ));

        assertTrue(schedule.isEmpty());
        assertEquals(6, schedule.warnings().size());
    }

    @Test
    void acceptsValidEntriesAlongsideInvalidOnes() {
        RestockSchedule schedule = RestockSchedule.parse(List.of("08:00", "not-a-time", "22:00"));

        assertEquals(1, schedule.size());
        assertEquals(2, schedule.warnings().size());
    }

    @Test
    void warnsOnExactDuplicates() {
        RestockSchedule schedule = RestockSchedule.parse(List.of("08:00", "08:00"));

        assertEquals(1, schedule.size(), "duplicates collapse to a single schedule point");
        assertEquals(1, schedule.warnings().size());
        assertTrue(schedule.warnings().get(0).contains("Duplicate"));
    }

    @Test
    void warnsOnNearDuplicates() {
        RestockSchedule schedule = RestockSchedule.parse(List.of("08:00", "08:02"));

        assertEquals(2, schedule.size(), "near-duplicates are kept, not rejected");
        assertEquals(1, schedule.warnings().size());
        assertTrue(schedule.warnings().get(0).contains("ticks apart"));
    }

    @Test
    void doesNotWarnOnWellSpacedEntries() {
        RestockSchedule schedule = RestockSchedule.parse(List.of("08:00", "10:30", "13:00"));

        assertTrue(schedule.warnings().isEmpty());
    }

    @Test
    void emptyInputProducesEmptySchedule() {
        RestockSchedule schedule = RestockSchedule.parse(List.of());

        assertTrue(schedule.isEmpty());
        assertEquals(0, schedule.size());
        assertTrue(schedule.warnings().isEmpty());
        assertEquals(0L, schedule.passId(123456L));
        assertEquals(Long.MAX_VALUE, schedule.nextOccurrence(123456L));
    }

    // ---- passId monotonicity ----

    @Test
    void passIdDoesNotIncreaseBeforeFirstScheduledPoint() {
        RestockSchedule schedule = RestockSchedule.parse(List.of("08:00", "10:30", "13:00"));

        long beforeFirst = schedule.passId(1999L); // 07:59-ish, before 08:00 (tick 2000)
        long atFirst = schedule.passId(2000L);

        assertEquals(beforeFirst, schedule.passId(0L), "no points crossed yet at start of day");
        assertTrue(atFirst > beforeFirst, "crossing the first point must increase passId");
    }

    @Test
    void passIdIsStableAcrossMultipleChecksBetweenScheduledPoints() {
        RestockSchedule schedule = RestockSchedule.parse(List.of("08:00", "10:30", "13:00"));

        long afterFirst = schedule.passId(2500L);
        long stillAfterFirst = schedule.passId(3000L);

        assertEquals(afterFirst, stillAfterFirst, "no new point crossed between two checks -> same passId");
    }

    @Test
    void passIdCatchesUpOnLargeForwardJump() {
        // Simulates sleeping through the night / a forward /time set: two points crossed in one step.
        RestockSchedule schedule = RestockSchedule.parse(List.of("08:00", "10:30", "13:00"));

        long beforeAnyPoint = schedule.passId(500L);   // ~06:30, nothing crossed
        long afterJumpPastTwoPoints = schedule.passId(6000L); // ~12:00, past 08:00 and 10:30

        // passId encodes the absolute tick of the *latest* point crossed, not a per-point count, so
        // a multi-point jump reflects as one increase (to 10:30's tick), not a jump of "2".
        assertTrue(afterJumpPastTwoPoints > beforeAnyPoint,
                "jumping past two schedule points at once must be reflected, not silently dropped");
        assertEquals(4501L, afterJumpPastTwoPoints, "encodes 10:30 (tick 4500), the latest point crossed");
    }

    @Test
    void passIdAtDayRolloverDoesNotExceedPreviousDaysLastCrossedValueBeforeItsOwnFirstPoint() {
        RestockSchedule schedule = RestockSchedule.parse(List.of("08:00", "10:30", "13:00"));

        long endOfDayOne = schedule.passId(TICKS_PER_DAY - 1);       // last tick of day 0, all 3 points crossed
        long startOfDayTwo = schedule.passId(TICKS_PER_DAY);         // first tick of day 1, none crossed yet
        long afterFirstPointDayTwo = schedule.passId(TICKS_PER_DAY + 2000L);

        // Day two's passId resets to 0 until its own first point is crossed - it must never look
        // like a fresh opportunity relative to whatever was already consumed on day one.
        assertTrue(startOfDayTwo <= endOfDayOne,
                "day rollover with no points crossed yet on the new day must not look like a new opportunity");
        assertTrue(afterFirstPointDayTwo > endOfDayOne,
                "crossing day two's first point must exceed whatever was consumed on day one");
    }

    @Test
    void passIdComparisonRemainsValidWhenScheduleSizeChangesAcrossReload() {
        // Regression test for the QA-reported bug: the old day * size() + crossed encoding made a
        // stored passId incomparable with a freshly computed one whenever restock-schedule.times'
        // entry count changed between two RestockSchedule constructions (e.g. across a restart or
        // /lobotomy reload) - most damagingly when the count *shrank*, which could stall restocks
        // for many in-game days. The new encoding must be stable regardless of size() changing.
        RestockSchedule threePoints = RestockSchedule.parse(List.of("08:00", "10:30", "13:00"));
        long storedAfterAllThreeCrossed = threePoints.passId(TICKS_PER_DAY - 1); // end of day 0

        RestockSchedule oneShrunkPoint = RestockSchedule.parse(List.of("08:00")); // times shrunk to 1 entry
        long nextDaysCrossing = oneShrunkPoint.passId(TICKS_PER_DAY + 2000L); // day 1's 08:00

        assertTrue(nextDaysCrossing > storedAfterAllThreeCrossed,
                "a real later crossing under a smaller schedule must still exceed a value stored under the larger one");

        RestockSchedule fourGrownPoints = RestockSchedule.parse(List.of("08:00", "10:30", "13:00", "17:00"));
        long grownScheduleNextDay = fourGrownPoints.passId(TICKS_PER_DAY + 2000L); // day 1's 08:00

        assertTrue(grownScheduleNextDay > storedAfterAllThreeCrossed,
                "a real later crossing under a larger schedule must still exceed a value stored under the smaller one");
    }

    // ---- nextOccurrence ----

    @Test
    void nextOccurrenceFindsLaterPointSameDay() {
        RestockSchedule schedule = RestockSchedule.parse(List.of("08:00", "10:30", "13:00"));

        assertEquals(4500L, schedule.nextOccurrence(3000L));   // between 08:00 and 10:30 -> next is 10:30
        assertEquals(2000L, schedule.nextOccurrence(0L));      // start of day -> next is 08:00
    }

    @Test
    void nextOccurrenceWrapsToNextDayPastLastPoint() {
        RestockSchedule schedule = RestockSchedule.parse(List.of("08:00", "10:30", "13:00"));

        long afterLastPoint = 20000L; // well past 13:00 (tick 7000), still same day
        long expected = TICKS_PER_DAY + 2000L; // tomorrow's 08:00

        assertEquals(expected, schedule.nextOccurrence(afterLastPoint));
    }

    // ---- formatting ----

    @Test
    void formatTickRoundTripsHhMm() {
        for (String hhmm : List.of("06:00", "08:00", "10:30", "13:00", "17:59", "18:00")) {
            RestockSchedule single = RestockSchedule.parse(List.of(hhmm));
            assertFalse(single.isEmpty(), hhmm + " should parse");
        }
    }
}
