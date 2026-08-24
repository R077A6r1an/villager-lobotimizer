package dev.mja00.villagerLobotomizer;

import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Villager;
import org.bukkit.entity.ZombieVillager;
import org.bukkit.event.entity.EntityTransformEvent;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.world.WorldMock;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Covers {@code LobotomizeStorage#bridgeRestockState} via {@code EntityListener#onTransform}.
 * MockBukkit cannot simulate real zombie infection/curing AI, so these tests fire
 * {@link EntityTransformEvent} directly through the registered listener, mirroring the villager
 * &lt;-&gt; zombie villager entity replacement Bukkit performs for both infection and curing.
 */
class EntityTransformBridgeTest extends MockBukkitTestBase {

    private VillagerLobotomizer plugin;
    private WorldMock world;

    private NamespacedKey key(String name) {
        return new NamespacedKey(plugin, name);
    }

    private void loadPlugin() {
        plugin = MockBukkit.load(VillagerLobotomizer.class);
        world = server.addSimpleWorld("test");
    }

    @AfterEach
    void stopStorage() {
        if (plugin != null) {
            plugin.getStorage().flush();
        }
    }

    @Test
    void infectionBridgesLobotomizationAndRestockStateToZombieVillager() {
        loadPlugin();

        Villager villager = world.spawn(new Location(world, 0, 64, 0), Villager.class);
        villager.getPersistentDataContainer().set(key("isLobotomized"), PersistentDataType.BYTE, (byte) 1);
        villager.getPersistentDataContainer().set(key("lastRestock"), PersistentDataType.LONG, 12345L);
        villager.getPersistentDataContainer().set(key("lastRestockCheckDayTime"), PersistentDataType.LONG, 500L);
        villager.getPersistentDataContainer().set(key("lastRestockPassId"), PersistentDataType.LONG, 7L);

        ZombieVillager zombie = world.spawn(new Location(world, 0, 64, 0), ZombieVillager.class);

        EntityTransformEvent event = new EntityTransformEvent(villager, List.<Entity>of(zombie),
                EntityTransformEvent.TransformReason.INFECTION);
        server.getPluginManager().callEvent(event);

        assertEquals((byte) 1, zombie.getPersistentDataContainer().get(key("isLobotomized"), PersistentDataType.BYTE));
        assertEquals(12345L, zombie.getPersistentDataContainer().get(key("lastRestock"), PersistentDataType.LONG));
        assertEquals(500L, zombie.getPersistentDataContainer().get(key("lastRestockCheckDayTime"), PersistentDataType.LONG));
        assertEquals(7L, zombie.getPersistentDataContainer().get(key("lastRestockPassId"), PersistentDataType.LONG));
    }

    @Test
    void curePreservesDailyRestockCapInsteadOfResettingIt() {
        loadPlugin();

        // Simulate a zombie villager carrying the plugin's state forward from a prior infection hop.
        ZombieVillager zombie = world.spawn(new Location(world, 0, 64, 0), ZombieVillager.class);
        zombie.getPersistentDataContainer().set(key("isLobotomized"), PersistentDataType.BYTE, (byte) 1);
        zombie.getPersistentDataContainer().set(key("lastRestockCheckDayTime"), PersistentDataType.LONG, 3000L);
        zombie.getPersistentDataContainer().set(key("lastRestockPassId"), PersistentDataType.LONG, 3L);

        Villager cured = world.spawn(new Location(world, 0, 64, 0), Villager.class);
        cured.setRestocksToday(3); // already at its daily cap (e.g. a 3-point schedule) when infected

        EntityTransformEvent event = new EntityTransformEvent(zombie, List.<Entity>of(cured),
                EntityTransformEvent.TransformReason.CURED);
        server.getPluginManager().callEvent(event);

        assertEquals((byte) 1, cured.getPersistentDataContainer().get(key("isLobotomized"), PersistentDataType.BYTE),
                "cured villager should carry the lobotomized marker rather than starting fresh");
        assertEquals(3L, cured.getPersistentDataContainer().get(key("lastRestockPassId"), PersistentDataType.LONG));

        // Without the bridge, lastRestockCheckDayTime would reset to "never checked", and this
        // call couldn't tell that no day boundary was crossed - restocksToday must stay at the
        // carried-over cap rather than being reset, otherwise a player could bypass the daily cap
        // by repeatedly infecting/curing a villager on demand.
        boolean allowed = dev.mja00.villagerLobotomizer.utils.VillagerUtils.shouldRestock(
                cured, key("lastRestockCheckDayTime"), 3);
        assertFalse(allowed, "the carried-over daily cap must still block a same-day restock");
        assertEquals(3, cured.getRestocksToday(), "restocksToday must not be reset by the bridge itself");
    }

    @Test
    void ignoresTransformReasonsOtherThanInfectionAndCure() {
        loadPlugin();

        Villager villager = world.spawn(new Location(world, 0, 64, 0), Villager.class);
        villager.getPersistentDataContainer().set(key("isLobotomized"), PersistentDataType.BYTE, (byte) 1);

        Villager other = world.spawn(new Location(world, 0, 64, 0), Villager.class);

        EntityTransformEvent event = new EntityTransformEvent(villager, List.<Entity>of(other),
                EntityTransformEvent.TransformReason.SHEARED);
        server.getPluginManager().callEvent(event);

        assertFalse(other.getPersistentDataContainer().has(key("isLobotomized"), PersistentDataType.BYTE),
                "unrelated transform reasons must not bridge state");
    }
}
