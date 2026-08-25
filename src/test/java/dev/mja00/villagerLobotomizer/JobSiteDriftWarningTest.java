package dev.mja00.villagerLobotomizer;

import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Villager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.exception.UnimplementedOperationException;
import org.mockbukkit.mockbukkit.world.WorldMock;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers {@code LobotomizeStorage.refreshTrades}'s one-time "no job site nearby" warning (issue
 * #1) - a lobotomized villager missing its workstation (most commonly after a zombify/cure round
 * trip moved it elsewhere) should log a single admin-visible {@code WARNING}, not spam one every
 * check-interval tick, and should be able to warn again after a separate, later drift episode.
 * <p>
 * Deliberately does not test any automatic repositioning - there is none by design (see
 * {@code ImplementationPlan.md} §11.4): the fix is placing a matching workstation block near the
 * villager, which {@code isJobSiteNearby}'s existing plain material scan already picks up on the
 * villager's very next check with no plugin change needed.
 */
class JobSiteDriftWarningTest extends MockBukkitTestBase {

    private static final int CHECK_INTERVAL_TICKS = 5;

    private VillagerLobotomizer plugin;
    private WorldMock world;
    private final List<LogRecord> warnings = new ArrayList<>();

    // Mirrors RestockScheduleEndToEndTest's teardown: a still-live repeating villager task
    // destabilizes MockBukkit's teardown/setup for later tests if not cancelled first.
    @AfterEach
    void stopStorage() {
        if (plugin != null) {
            plugin.getStorage().flush();
        }
    }

    private void loadPlugin() {
        plugin = MockBukkit.load(VillagerLobotomizer.class);
        plugin.getConfig().set("check-interval", CHECK_INTERVAL_TICKS);
        plugin.getConfig().set("inactive-check-interval", CHECK_INTERVAL_TICKS);
        plugin.saveConfig();
        assertTrue(plugin.reloadPluginState() >= 0, "precondition: reload must succeed");

        plugin.getLogger().addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (record.getLevel() == Level.WARNING) {
                    warnings.add(record);
                }
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        });

        world = server.addSimpleWorld("test");
        // processVillager() bails out early on an unloaded chunk; MockBukkit doesn't mark a chunk
        // loaded just because an entity was spawned in it.
        world.loadChunk(0, 0);
    }

    /** A villager forced lobotomized via the "nobrain" name, with no workstation placed nearby. */
    private Villager spawnLobotomizedFarmerWithoutJobSite(Location location) {
        Villager villager = world.spawn(location, Villager.class);
        villager.customName(Component.text("nobrain"));
        villager.setProfession(Villager.Profession.FARMER); // station = COMPOSTER
        return villager;
    }

    /**
     * Advances the mock scheduler one tick at a time. See RestockScheduleEndToEndTest's own
     * {@code tick()} for why {@code UnimplementedOperationException} is handled by re-tracking the
     * villager rather than letting it abort the remaining ticks - the same MockBukkit gaps
     * (unimplemented {@code Villager#updateDemand()}, scheduled-task re-registration only on a
     * normal return) apply here since this test drives the same real per-villager task.
     */
    private void tick(Villager villager, int ticks) {
        for (int i = 0; i < ticks; i++) {
            try {
                server.getScheduler().performOneTick();
            } catch (UnimplementedOperationException ignored) {
                plugin.getStorage().removeVillager(villager);
                plugin.getStorage().addVillager(villager);
                return;
            }
        }
    }

    private long jobSiteWarningCount() {
        return warnings.stream()
                .filter(r -> r.getMessage() != null && r.getMessage().contains("no job site"))
                .count();
    }

    @Test
    void warnsOnceWhenLobotomizedVillagerHasNoJobSiteNearby() {
        loadPlugin();
        Villager villager = spawnLobotomizedFarmerWithoutJobSite(new Location(world, 0, 64, 0));
        plugin.getStorage().addVillager(villager);

        // First check lobotomizes it (via "nobrain") and hits the missing-job-site path; further
        // checks must not add another warning for the same, still-unresolved drift.
        tick(villager, CHECK_INTERVAL_TICKS + 1);
        tick(villager, CHECK_INTERVAL_TICKS);
        tick(villager, CHECK_INTERVAL_TICKS);

        assertEquals(1, jobSiteWarningCount(), "should warn exactly once, not every check-interval tick");
    }

    @Test
    void warningClearsOnRecoveryAndCanFireAgainForANewDrift() {
        loadPlugin();
        Villager villager = spawnLobotomizedFarmerWithoutJobSite(new Location(world, 0, 64, 0));
        plugin.getStorage().addVillager(villager);

        tick(villager, CHECK_INTERVAL_TICKS + 1);
        assertEquals(1, jobSiteWarningCount(), "precondition: warned once while job site missing");

        // Place the workstation - isJobSiteNearby is a plain material scan, so this alone recovers
        // the villager with no other plugin action needed.
        world.getBlockAt(1, 64, 0).setType(Material.COMPOSTER);
        tick(villager, CHECK_INTERVAL_TICKS);

        // Remove it again - a fresh, separate drift episode should warn once more.
        world.getBlockAt(1, 64, 0).setType(Material.AIR);
        tick(villager, CHECK_INTERVAL_TICKS);

        assertEquals(2, jobSiteWarningCount(), "a new drift episode after recovery should warn again");
    }

    @Test
    void neverWarnsForAProfessionWithNoWorkstation() {
        loadPlugin();
        Villager villager = world.spawn(new Location(world, 0, 64, 0), Villager.class);
        villager.customName(Component.text("nobrain"));
        villager.setProfession(Villager.Profession.NITWIT); // station = AIR, isJobSiteNearby always false
        plugin.getStorage().addVillager(villager);

        tick(villager, CHECK_INTERVAL_TICKS + 1);
        tick(villager, CHECK_INTERVAL_TICKS);

        assertEquals(0, jobSiteWarningCount(),
                "a nitwit has no workstation by design - isJobSiteNearby being false is expected, not a drift to warn about");
    }
}
