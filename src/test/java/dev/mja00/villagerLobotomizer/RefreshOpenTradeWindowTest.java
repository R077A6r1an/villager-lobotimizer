package dev.mja00.villagerLobotomizer;

import org.bukkit.Location;
import org.bukkit.entity.Villager;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.entity.VillagerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * Covers {@code LobotomizeStorage.refreshOpenTradeWindow} (issue #6): {@code Merchant#setRecipes}
 * explicitly documents that it will not change the selected trades of a player currently trading
 * with the merchant, so {@code refreshTrades} re-opens the merchant for whoever is mid-trade after
 * every restock, rather than leaving them looking at stale offers until they close and reopen the
 * window themselves.
 * <p>
 * MockBukkit's {@code HumanEntity#openMerchant} is an unimplemented stub, so it cannot verify the
 * actual client-visible refresh this fix targets - only that the plugin-side dispatch logic
 * (no-op when nobody is trading, a scheduled reopen when somebody is) runs without throwing. The
 * real fix requires a manual {@code /runServer} pass to confirm the trade window visibly updates
 * (see {@code ImplementationPlan.md} §11.7).
 */
class RefreshOpenTradeWindowTest extends MockBukkitTestBase {

    private VillagerLobotomizer plugin;
    private WorldMock world;

    private Villager spawnVillager() {
        plugin = MockBukkit.load(VillagerLobotomizer.class);
        world = server.addSimpleWorld("test");
        return world.spawn(new Location(world, 0, 64, 0), Villager.class);
    }

    @Test
    void doesNothingWhenNobodyIsTrading() {
        Villager villager = spawnVillager();

        assertDoesNotThrow(() -> plugin.getStorage().refreshOpenTradeWindow(villager),
                "no trader means nothing to refresh");
    }

    @Test
    void schedulesAMerchantReopenWithoutThrowingWhenSomeoneIsTrading() {
        Villager villager = spawnVillager();
        PlayerMock trader = server.addPlayer();
        ((VillagerMock) villager).setTrader(trader);

        // The actual openMerchant() call happens inside a scheduled task (dispatched via the
        // trader's own EntityScheduler, per Folia thread-safety rules) rather than synchronously,
        // so this only verifies the dispatch itself doesn't throw - it never runs far enough to
        // reach MockBukkit's unimplemented openMerchant stub.
        assertDoesNotThrow(() -> plugin.getStorage().refreshOpenTradeWindow(villager),
                "dispatching the reopen must not throw even though a trade is in progress");
    }
}
