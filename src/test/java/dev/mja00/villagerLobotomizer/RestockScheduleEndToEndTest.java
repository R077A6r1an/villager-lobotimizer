package dev.mja00.villagerLobotomizer;

import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Villager;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MerchantRecipe;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.exception.UnimplementedOperationException;
import org.mockbukkit.mockbukkit.world.WorldMock;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end coverage of {@code mode: schedule} restocking: drives a real
 * {@link LobotomizeStorage} instance through its actual per-villager
 * {@code EntityScheduler} task (via MockBukkit's tick-driven scheduler mock), rather than calling
 * the private {@code refreshTrades}/{@code processVillager} methods directly.
 */
class RestockScheduleEndToEndTest extends MockBukkitTestBase {

    private static final int CHECK_INTERVAL_TICKS = 5;

    private VillagerLobotomizer plugin;
    private WorldMock world;

    // performTicks() actually executes each villager's recurring runAtFixedRate task, unlike other
    // tests in this suite that only ever schedule it. Cancel it before MockBukkitTestBase's own
    // @AfterEach tears down the mock server, or the still-live repeating task destabilizes MockBukkit's
    // teardown/setup and silently skips subsequent tests in this class.
    @AfterEach
    void stopStorage() {
        if (plugin != null) {
            plugin.getStorage().flush();
        }
    }

    /** Loads the plugin with schedule mode configured and a short check-interval for fast ticking. */
    private void loadPluginWithSchedule(List<String> times) {
        plugin = MockBukkit.load(VillagerLobotomizer.class);
        plugin.getConfig().set("restock-schedule.mode", "schedule");
        plugin.getConfig().set("restock-schedule.times", times);
        plugin.getConfig().set("check-interval", CHECK_INTERVAL_TICKS);
        plugin.getConfig().set("inactive-check-interval", CHECK_INTERVAL_TICKS);
        plugin.saveConfig();
        // reloadPluginState() re-reads config.yml from disk and rebuilds LobotomizeStorage from it;
        // it returns the tracked villager count, or a negative number on failure.
        assertTrue(plugin.reloadPluginState() >= 0, "precondition: reload must succeed");

        world = server.addSimpleWorld("test");
        // processVillager() bails out early on an unloaded chunk; MockBukkit doesn't mark a chunk
        // loaded just because an entity was spawned in it.
        world.loadChunk(0, 0);
    }

    /** A villager lobotomized via the "nobrain" name, with a used trade and a nearby job site. */
    private Villager spawnDueVillager(Location location) {
        Villager villager = world.spawn(location, Villager.class);
        villager.customName(Component.text("nobrain"));
        villager.setProfession(Villager.Profession.FARMER); // station = COMPOSTER

        world.getBlockAt(location.getBlockX() + 1, location.getBlockY(), location.getBlockZ())
                .setType(Material.COMPOSTER);

        MerchantRecipe used = new MerchantRecipe(new ItemStack(Material.EMERALD), 16);
        used.setUses(3);
        villager.setRecipes(List.of(used));

        return villager;
    }

    private boolean tradesWereReset(Villager villager) {
        return villager.getRecipes().stream().allMatch(recipe -> recipe.getUses() == 0);
    }

    /**
     * Advances the mock scheduler one tick at a time. {@code LobotomizeStorage.refreshTrades}
     * unconditionally calls {@code Villager#updateDemand()} right after a successful restock (after
     * resetting recipe uses and incrementing {@code restocksToday}, both of which MockBukkit's
     * VillagerMock does support) — MockBukkit doesn't implement that method yet and signals it via
     * {@link UnimplementedOperationException}, which is itself a JUnit {@code TestAbortedException}.
     * The state we assert on has already been mutated by the time that call happens, so the exception
     * is swallowed rather than one tick at a time via {@code performTicks} aborting the whole batch.
     * <p>
     * A second MockBukkit gap: {@code BukkitSchedulerMock.processTasks} only re-registers a repeating
     * task for its next run <em>after</em> the runnable returns normally — a runnable that throws
     * (as above) permanently drops the repeating task, unlike real Bukkit/Folia, which logs the error
     * but keeps the task alive. Re-tracking the villager works around that so later {@code tick()}
     * calls in the same test still observe further checks — via {@code removeVillager} then
     * {@code addVillager} rather than a second {@code addVillager} alone, since MockBukkit's
     * {@code PaperScheduledTask} (what {@code Entity#getScheduler()} hands back) unconditionally
     * throws the same exception from {@code cancel()}/{@code isCancelled()}, which
     * {@code scheduleVillagerTask} calls directly on any still-tracked previous task.
     * {@code removeVillager} cancels through {@code safeCancel}, which already swallows that.
     * <p>
     * {@code removeVillager} itself, if the villager was already inactive, queues a real "wake it
     * back up" task on the entity scheduler for the very next tick (mirroring real un-lobotomize
     * behavior) — before {@code addVillager} re-lobotomizes it synchronously afterward, since the
     * persisted marker hasn't been cleared yet at that point. Stopping this call's loop as soon as
     * one exception is handled (rather than continuing to spend the rest of its tick budget) avoids
     * ever executing that queued wake-up within the same call and undoing the re-lobotomization; a
     * test that needs to observe more checks after a restock issues a separate {@code tick()} call.
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

    @Test
    void villagerDoesNotRestockBeforeScheduledTime() {
        loadPluginWithSchedule(List.of("08:00")); // tick 2000
        world.setFullTime(1000L); // 07:00 - before the scheduled point

        Villager villager = spawnDueVillager(new Location(world, 0, 64, 0));
        plugin.getStorage().addVillager(villager);

        tick(villager, CHECK_INTERVAL_TICKS + 1);

        assertFalse(tradesWereReset(villager), "no schedule point has fired yet");
        assertEquals(0, villager.getRestocksToday());
    }

    @Test
    void villagerRestocksOnceScheduledTimeHasPassed() {
        loadPluginWithSchedule(List.of("08:00")); // tick 2000
        world.setFullTime(1000L); // 07:00 - before the scheduled point

        Villager villager = spawnDueVillager(new Location(world, 0, 64, 0));
        plugin.getStorage().addVillager(villager);

        // First check: no restock opportunity yet, so the active -> inactive transition completes
        // cleanly and the villager becomes lobotomized before we ever exercise a restock.
        tick(villager, CHECK_INTERVAL_TICKS + 1);
        assertFalse(villager.isAware(), "precondition: villager should be lobotomized after the first check");
        assertEquals(0, villager.getRestocksToday(), "precondition: no restock opportunity yet");

        // Now the scheduled point has passed; the next check (villager already inactive) restocks it.
        world.setFullTime(3000L); // 09:00
        tick(villager, CHECK_INTERVAL_TICKS);

        assertTrue(tradesWereReset(villager), "the 08:00 point already fired, villager should restock");
        assertEquals(1, villager.getRestocksToday());
        assertFalse(villager.isAware(), "still lobotomized");
        // passId encodes the absolute tick (+1) of the latest point crossed, not a per-day count -
        // 08:00 is tick 2000, so the stored value is 2001.
        assertEquals(2001L, villager.getPersistentDataContainer()
                .get(new org.bukkit.NamespacedKey(plugin, "lastRestockPassId"), PersistentDataType.LONG));
    }

    @Test
    void villagerDoesNotRestockAgainWithoutANewScheduledPoint() {
        loadPluginWithSchedule(List.of("08:00")); // a single schedule point -> daily cap of 1
        world.setFullTime(3000L);

        Villager villager = spawnDueVillager(new Location(world, 0, 64, 0));
        plugin.getStorage().addVillager(villager);
        tick(villager, CHECK_INTERVAL_TICKS + 1);
        assertEquals(1, villager.getRestocksToday(), "precondition: first restock happened");

        // Use up a trade again without any new schedule point firing (still day 0, tick 3000).
        MerchantRecipe usedAgain = new MerchantRecipe(new ItemStack(Material.EMERALD), 16);
        usedAgain.setUses(2);
        villager.setRecipes(List.of(usedAgain));

        tick(villager, CHECK_INTERVAL_TICKS);

        assertFalse(tradesWereReset(villager), "no new schedule point crossed, so no second restock");
        assertEquals(1, villager.getRestocksToday(), "the single configured time caps restocks at 1/day");
    }

    @Test
    void villagerCatchesUpAfterSleepingPastMultipleScheduledPoints() {
        loadPluginWithSchedule(List.of("08:00", "10:30")); // ticks 2000 and 4500
        world.setFullTime(500L); // 06:30 - before either point

        Villager villager = spawnDueVillager(new Location(world, 0, 64, 0));
        plugin.getStorage().addVillager(villager);
        tick(villager, CHECK_INTERVAL_TICKS + 1);
        assertEquals(0, villager.getRestocksToday(), "precondition: nothing fired before either point");

        // Simulate sleeping through the night skipping past both configured points in one jump.
        world.setFullTime(6000L); // 12:00 - past both 08:00 and 10:30
        tick(villager, CHECK_INTERVAL_TICKS);

        assertTrue(tradesWereReset(villager), "catch-up must fire rather than silently drop the missed restocks");
        assertEquals(1, villager.getRestocksToday(),
                "one restock action clears all due trades at once, it does not fire once per skipped point");
    }

    @Test
    void restockPersistsAcrossReconstructedStorageViaPdc() {
        loadPluginWithSchedule(List.of("08:00"));
        world.setFullTime(3000L);

        Villager villager = spawnDueVillager(new Location(world, 0, 64, 0));
        plugin.getStorage().addVillager(villager);
        tick(villager, CHECK_INTERVAL_TICKS + 1);
        assertEquals(1, villager.getRestocksToday());

        long storedPassId = villager.getPersistentDataContainer()
                .get(new org.bukkit.NamespacedKey(plugin, "lastRestockPassId"), PersistentDataType.LONG);
        assertEquals(plugin.getStorage().getRestockSchedule().passId(world.getFullTime()), storedPassId,
                "the PDC marker should record the exact pass id consumed, for correct gating after a reload");
        assertEquals(storedPassId, plugin.getStorage().getLastRestockPassId(villager),
                "the /lobotomy debug accessor must read back the same value written during the restock");
    }

    @Test
    void getLastRestockPassIdDefaultsToZeroBeforeAnyScheduleModeRestock() {
        loadPluginWithSchedule(List.of("08:00"));
        world.setFullTime(500L); // before the scheduled point

        Villager villager = spawnDueVillager(new Location(world, 0, 64, 0));
        plugin.getStorage().addVillager(villager);

        assertEquals(0L, plugin.getStorage().getLastRestockPassId(villager),
                "a villager that has never restocked under schedule mode has no PDC value yet");
    }

    @Test
    void restockKeepsWorkingAfterTimesListShrinksAcrossReload() {
        // Regression test for the QA-reported "inconsistent restocks after a restart" bug: the
        // villager's lastRestockPassId is stored under the 3-point schedule below, then
        // restock-schedule.times is edited down to a single entry and the plugin is reloaded -
        // exactly what an admin does when tuning restock-schedule.times and restarting/reloading
        // to apply it. Restocking must not stall afterwards.
        loadPluginWithSchedule(List.of("08:00", "10:30", "13:00")); // size 3
        world.setFullTime(8000L); // past all three points (13:00 = tick 7000)

        Villager villager = spawnDueVillager(new Location(world, 0, 64, 0));
        plugin.getStorage().addVillager(villager);
        tick(villager, CHECK_INTERVAL_TICKS + 1);
        assertEquals(1, villager.getRestocksToday(), "precondition: first restock consumed under the 3-point schedule");

        // Simulate editing restock-schedule.times down to one entry, then restarting/reloading.
        plugin.getConfig().set("restock-schedule.times", List.of("08:00"));
        plugin.saveConfig();
        assertTrue(plugin.reloadPluginState() >= 0, "reload with a shrunk schedule must succeed");

        // Use up a trade again and advance to the next day's (now the only) scheduled point.
        MerchantRecipe usedAgain = new MerchantRecipe(new ItemStack(Material.EMERALD), 16);
        usedAgain.setUses(2);
        villager.setRecipes(List.of(usedAgain));
        world.setFullTime(24_000L + 2000L); // day 1's 08:00

        tick(villager, CHECK_INTERVAL_TICKS + 1);

        assertTrue(tradesWereReset(villager),
                "shrinking restock-schedule.times across a reload must not stall restocking");
        assertEquals(1, villager.getRestocksToday(), "day rollover resets the counter before the new restock");
    }

    @Test
    void villagerRestocksAtEachOfThreeScheduledPointsIndividually() {
        // Regression/coverage test for issue #5: unlike villagerCatchesUpAfterSleepingPastMultipleScheduledPoints
        // (one combined jump past several points at once), this drives three separate, individually
        // triggered checks across one in-game day, marking a trade used again between each, so each
        // configured point fires its own distinct restock rather than being collapsed into one.
        loadPluginWithSchedule(List.of("08:00", "10:30", "13:00")); // ticks 2000, 4500, 7000
        world.setFullTime(500L); // 06:30 - before any configured point

        Villager villager = spawnDueVillager(new Location(world, 0, 64, 0));
        plugin.getStorage().addVillager(villager);
        tick(villager, CHECK_INTERVAL_TICKS + 1);
        assertEquals(0, villager.getRestocksToday(), "precondition: nothing fired before the first point");

        // 08:00
        world.setFullTime(2000L);
        tick(villager, CHECK_INTERVAL_TICKS);
        assertTrue(tradesWereReset(villager), "08:00 should restock individually");
        assertEquals(1, villager.getRestocksToday());

        // Use a trade up again, then advance to 10:30.
        MerchantRecipe usedAgain1 = new MerchantRecipe(new ItemStack(Material.EMERALD), 16);
        usedAgain1.setUses(2);
        villager.setRecipes(List.of(usedAgain1));
        world.setFullTime(4500L);
        tick(villager, CHECK_INTERVAL_TICKS);
        assertTrue(tradesWereReset(villager), "10:30 should restock individually, not just 08:00");
        assertEquals(2, villager.getRestocksToday());

        // Use a trade up again, then advance to 13:00.
        MerchantRecipe usedAgain2 = new MerchantRecipe(new ItemStack(Material.EMERALD), 16);
        usedAgain2.setUses(2);
        villager.setRecipes(List.of(usedAgain2));
        world.setFullTime(7000L);
        tick(villager, CHECK_INTERVAL_TICKS);
        assertTrue(tradesWereReset(villager), "13:00 should restock individually, completing all three points");
        assertEquals(3, villager.getRestocksToday(), "all three configured times fired, each via its own check");
    }
}
