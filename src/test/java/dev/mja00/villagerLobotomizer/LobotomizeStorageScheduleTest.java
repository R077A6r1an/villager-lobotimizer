package dev.mja00.villagerLobotomizer;

import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.LogRecord;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers {@code restock-schedule} config parsing in {@link LobotomizeStorage}'s constructor — the
 * data source behind the schedule-aware restock gate in {@code refreshTrades} and the
 * {@code /lobotomy debug} "next scheduled restock" line.
 */
class LobotomizeStorageScheduleTest extends MockBukkitTestBase {

    private VillagerLobotomizer plugin;
    private final List<LogRecord> logs = new ArrayList<>();

    private LobotomizeStorage storageWithSchedule(String mode, List<String> times) {
        plugin = MockBukkit.load(VillagerLobotomizer.class);
        plugin.getConfig().set("restock-schedule.mode", mode);
        plugin.getConfig().set("restock-schedule.times", times);
        plugin.saveConfig();
        plugin.getLogger().addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                logs.add(record);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        });
        return new LobotomizeStorage(plugin);
    }

    private boolean loggedInactiveModeReminder() {
        return logs.stream().anyMatch(r -> r.getMessage() != null
                && r.getMessage().contains("restock-schedule.mode is \"interval\""));
    }

    @Test
    void defaultsToIntervalModeInactive() {
        plugin = MockBukkit.load(VillagerLobotomizer.class);
        LobotomizeStorage storage = plugin.getStorage();

        assertFalse(storage.isScheduleModeActive(), "shipped default config.yml uses mode: interval");
    }

    @Test
    void scheduleModeActiveWithValidTimes() {
        LobotomizeStorage storage = storageWithSchedule("schedule", List.of("08:00", "10:30", "13:00"));

        assertTrue(storage.isScheduleModeActive());
        assertEquals(3, storage.getRestockSchedule().size());
    }

    @Test
    void scheduleModeFallsBackToIntervalWhenTimesAreAllInvalid() {
        LobotomizeStorage storage = storageWithSchedule("schedule", List.of("22:00", "not-a-time"));

        assertFalse(storage.isScheduleModeActive(),
                "an empty parsed schedule must fail safe to interval-mode behavior, not silently never restock");
        assertTrue(storage.getRestockSchedule().isEmpty());
    }

    @Test
    void scheduleModeFallsBackToIntervalWhenTimesAreEmpty() {
        LobotomizeStorage storage = storageWithSchedule("schedule", List.of());

        assertFalse(storage.isScheduleModeActive());
    }

    @Test
    void modeIsCaseInsensitive() {
        LobotomizeStorage storage = storageWithSchedule("SCHEDULE", List.of("08:00"));

        assertTrue(storage.isScheduleModeActive());
    }

    // --- issue #3: since config.yml's own clarifying comment can never reach an already-migrated
    // server (CommentPreservingYamlMigrator always prefers an admin's existing comment for a
    // key/section already present in their file), the reminder is logged at runtime instead. ---

    @Test
    void logsReminderWhenTimesAreConfiguredButModeIsInterval() {
        storageWithSchedule("interval", List.of("08:00", "10:30", "13:00"));

        assertTrue(loggedInactiveModeReminder(),
                "populated times under mode: interval is exactly the confusing state issue #3 covers");
    }

    @Test
    void doesNotLogReminderWhenTimesAreEmpty() {
        storageWithSchedule("interval", List.of());

        assertFalse(loggedInactiveModeReminder(), "nothing configured, nothing to be confused about");
    }

    @Test
    void doesNotLogReminderWhenModeIsSchedule() {
        storageWithSchedule("schedule", List.of("08:00"));

        assertFalse(loggedInactiveModeReminder(), "schedule mode is already active, times already take effect");
    }
}
