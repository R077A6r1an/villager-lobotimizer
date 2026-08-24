package dev.mja00.villagerLobotomizer.utils;

import dev.mja00.villagerLobotomizer.MockBukkitTestBase;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Villager;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MerchantRecipe;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.world.WorldMock;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VillagerUtilsTest extends MockBukkitTestBase {

    private static final NamespacedKey LAST_RESTOCK_KEY = NamespacedKey.fromString("vltest:last_restock_check");

    private Villager spawnVillager() {
        WorldMock world = server.addSimpleWorld("test");
        return world.spawn(new Location(world, 0, 64, 0), Villager.class);
    }

    @Test
    void getVillagerLevelMapsExperienceToLevelAtBoundaries() {
        Villager villager = spawnVillager();
        int[][] cases = {
                {0, 1}, {9, 1}, {10, 2}, {69, 2}, {70, 3}, {149, 3}, {150, 4}, {249, 4}, {250, 5}
        };
        for (int[] c : cases) {
            villager.setVillagerExperience(c[0]);
            assertEquals(c[1], VillagerUtils.getVillagerLevel(villager),
                    "experience " + c[0] + " should be level " + c[1]);
        }
    }

    @Test
    void professionStationMaterialsExcludesAirAndCoversEveryStation() {
        var stations = VillagerUtils.professionStationMaterials();
        assertFalse(stations.contains(Material.AIR), "AIR must not be a station material");
        // Every non-AIR station in the canonical map must be present
        for (Material station : VillagerUtils.PROFESSION_TO_STATION.values()) {
            if (station != Material.AIR) {
                assertTrue(stations.contains(station), station + " should be in the station set");
            }
        }
        // Sound map must cover the same professions as the station map
        assertEquals(VillagerUtils.PROFESSION_TO_STATION.keySet(), VillagerUtils.PROFESSION_TO_SOUND.keySet(),
                "station and sound maps should cover the same professions");
    }

    @Test
    void needsToRestockTrueOnlyWhenATradeHasBeenUsed() {
        Villager villager = spawnVillager();
        MerchantRecipe unused = new MerchantRecipe(new ItemStack(Material.EMERALD), 16);
        villager.setRecipes(List.of(unused));
        assertFalse(VillagerUtils.needsToRestock(villager), "no uses means no restock needed");

        MerchantRecipe used = new MerchantRecipe(new ItemStack(Material.EMERALD), 16);
        used.setUses(3);
        villager.setRecipes(List.of(used));
        assertTrue(VillagerUtils.needsToRestock(villager), "a used trade means restock needed");
    }

    @Test
    void isJobSiteNearbyDetectsMatchingStationWithinBox() {
        Villager villager = spawnVillager();
        villager.setProfession(Villager.Profession.FARMER); // station = COMPOSTER
        WorldMock world = (WorldMock) villager.getWorld();

        // No station nearby yet
        assertFalse(VillagerUtils.isJobSiteNearby(villager));

        // Place the composter one block away (within the 3x3x3 box)
        world.getBlockAt(1, 64, 0).setType(Material.COMPOSTER);
        assertTrue(VillagerUtils.isJobSiteNearby(villager));
    }

    @Test
    void isJobSiteNearbyFalseForProfessionWithoutStation() {
        Villager villager = spawnVillager();
        villager.setProfession(Villager.Profession.NONE); // station = AIR
        assertFalse(VillagerUtils.isJobSiteNearby(villager), "professions mapped to AIR have no job site");
    }

    @Test
    void shouldRestockResetsDailyCounterOnNewDay() {
        Villager villager = spawnVillager();
        WorldMock world = (WorldMock) villager.getWorld();

        // Record a check during day 0, with one restock already used today
        villager.getPersistentDataContainer().set(LAST_RESTOCK_KEY, PersistentDataType.LONG, 100L);
        villager.setRestocksToday(1);

        // Advance to day 1 (fullTime / 24000 increments)
        world.setFullTime(24_000L + 50L);
        VillagerUtils.shouldRestock(villager, LAST_RESTOCK_KEY);

        assertEquals(0, villager.getRestocksToday(), "crossing a day boundary resets the restock counter");
        assertEquals(24_050L, villager.getPersistentDataContainer()
                .getOrDefault(LAST_RESTOCK_KEY, PersistentDataType.LONG, 0L), "PDC updated to current full time");
    }

    @Test
    void defaultMaxRestocksPerDayMatchesTheImplicitIntervalModeCap() {
        Villager villager = spawnVillager();
        int cap = VillagerUtils.defaultMaxRestocksPerDay();

        villager.setRestocksToday(cap);
        assertFalse(VillagerUtils.allowedToRestock(villager), "the 1-arg overload's implicit cap must match the exposed constant");

        villager.setRestocksToday(cap - 1);
        assertTrue(VillagerUtils.allowedToRestock(villager));
    }

    @Test
    void allowedToRestockRespectsExplicitDailyCap() {
        Villager villager = spawnVillager();

        villager.setRestocksToday(2);
        assertFalse(VillagerUtils.allowedToRestock(villager, 2), "2-arg cap of 2 blocks a 3rd restock");
        assertTrue(VillagerUtils.allowedToRestock(villager, 3), "a schedule with 3 points allows a 3rd restock");

        villager.setRestocksToday(3);
        assertFalse(VillagerUtils.allowedToRestock(villager, 3));
    }

    @Test
    void shouldRestockWithExplicitCapResetsDailyCounterOnNewDay() {
        Villager villager = spawnVillager();
        WorldMock world = (WorldMock) villager.getWorld();

        villager.getPersistentDataContainer().set(LAST_RESTOCK_KEY, PersistentDataType.LONG, 100L);
        villager.setRestocksToday(3);

        world.setFullTime(24_000L + 50L);
        VillagerUtils.shouldRestock(villager, LAST_RESTOCK_KEY, 3);

        assertEquals(0, villager.getRestocksToday(), "crossing a day boundary resets the counter regardless of cap");
    }

    @Test
    void shouldRestockTreatsRealTickZeroCheckAsCheckedRatherThanNeverChecked() {
        Villager villager = spawnVillager();
        WorldMock world = (WorldMock) villager.getWorld();

        // A real check recorded at world tick 0 (the "never checked" sentinel is -1, not 0).
        villager.getPersistentDataContainer().set(LAST_RESTOCK_KEY, PersistentDataType.LONG, 0L);
        villager.setRestocksToday(2);

        // Still day 0: a real prior check at tick 0 must not be reset again just because it's
        // indistinguishable from "never checked" under the old 0L-default sentinel.
        world.setFullTime(50L);
        VillagerUtils.shouldRestock(villager, LAST_RESTOCK_KEY, 2);
        assertEquals(2, villager.getRestocksToday(), "a same-day recheck must not reset the counter");

        // Crossing into day 1 from that real tick-0 check must still reset normally.
        world.setFullTime(24_000L + 10L);
        VillagerUtils.shouldRestock(villager, LAST_RESTOCK_KEY, 2);
        assertEquals(0, villager.getRestocksToday(), "a day boundary crossed from a real tick-0 check still resets");
    }

    @Test
    void shouldRestockWithExplicitCapAllowsMoreThanTwoPerDay() {
        Villager villager = spawnVillager();
        MerchantRecipe used = new MerchantRecipe(new ItemStack(Material.EMERALD), 16);
        used.setUses(1);
        villager.setRecipes(List.of(used));

        villager.setRestocksToday(2);
        assertFalse(VillagerUtils.shouldRestock(villager, LAST_RESTOCK_KEY, 2),
                "the interval-mode cap of 2 would block this");
        assertTrue(VillagerUtils.shouldRestock(villager, LAST_RESTOCK_KEY, 3),
                "a 3-point schedule allows a 3rd restock");
    }
}
