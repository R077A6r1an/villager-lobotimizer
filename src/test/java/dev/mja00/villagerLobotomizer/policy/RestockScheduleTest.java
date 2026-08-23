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
        // as crossed: at 08:00 one point has fired, at 10:30 two, at 13:00 all three (wrapping
        // back to a %3 remainder of 0).
        assertEquals(1, schedule.passId(2000L) % 3);
        assertEquals(2, schedule.passId(4500L) % 3);
        assertEquals(0, schedule.passId(7000L) % 3);
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

        assertEquals(2, afterJumpPastTwoPoints - beforeAnyPoint,
                "jumping past two schedule points at once must be reflected, not silently dropped");
    }

    @Test
    void passIdIsNonDecreasingAcrossADayRollover() {
        RestockSchedule schedule = RestockSchedule.parse(List.of("08:00", "10:30", "13:00"));

        long endOfDayOne = schedule.passId(TICKS_PER_DAY - 1);       // last tick of day 0, all 3 points crossed
        long startOfDayTwo = schedule.passId(TICKS_PER_DAY);         // first tick of day 1, none crossed yet
        long afterFirstPointDayTwo = schedule.passId(TICKS_PER_DAY + 2000L);

        assertEquals(endOfDayOne, startOfDayTwo, "day rollover with no points crossed yet must not lose progress");
        assertTrue(afterFirstPointDayTwo > startOfDayTwo, "crossing day two's first point must increase passId");
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
