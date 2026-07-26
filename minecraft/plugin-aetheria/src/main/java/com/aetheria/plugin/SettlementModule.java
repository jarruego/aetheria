package com.aetheria.plugin;

import java.util.concurrent.ThreadLocalRandom;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.BlockFace;

/**
 * Pueblo VIVO: reconcilia el mundo fisico con la poblacion objetivo que fija la simulacion
 * (crece cuando el pueblo prospera, mengua cuando decae). Cuando la poblacion sube, construye
 * una casa nueva y llega un colono (con oficio); cuando baja, un colono emigra. Todo por
 * codigo (nunca el LLM), como las rutinas.
 */
public final class SettlementModule {

    private static final long PERIOD = 1200L;   // reconcilia cada 60 s (una casa por vez)
    private static final String[] NAMES = {"Bruno", "Lena", "Tobias", "Mila", "Ada", "Iker",
        "Noa", "Gala", "Hugo", "Vera", "Leo", "Sol", "Dario", "Enara", "Bruno", "Cloe"};
    private static final Material[][] COMBOS = {
        {Material.OAK_PLANKS, Material.SPRUCE_LOG, Material.DARK_OAK_PLANKS, Material.COBBLESTONE},
        {Material.STONE_BRICKS, Material.CHISELED_STONE_BRICKS, Material.COBBLESTONE, Material.MOSSY_STONE_BRICKS},
        {Material.BRICKS, Material.DEEPSLATE_TILES, Material.DARK_OAK_PLANKS, Material.POLISHED_BLACKSTONE},
        {Material.SPRUCE_PLANKS, Material.STRIPPED_SPRUCE_LOG, Material.DARK_OAK_PLANKS, Material.BRICKS},
    };

    private final AetheriaPlugin plugin;
    private final GatewayClient gateway;
    private final VillageModule village;
    private final NpcRoutineModule routines;
    private final World world;

    public SettlementModule(AetheriaPlugin plugin, GatewayClient gateway, VillageModule village,
            NpcRoutineModule routines, World world) {
        this.plugin = plugin;
        this.gateway = gateway;
        this.village = village;
        this.routines = routines;
        this.world = world;
    }

    public void start() {
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::reconcile, PERIOD, PERIOD);
        plugin.getLogger().info("[Aetheria] Pueblo vivo: reconciliando poblacion cada 60 s.");
    }

    private void reconcile() {
        gateway.getVillage().whenComplete((json, err) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (err != null || json == null) {
                return;
            }
            final int population = json.get("population").getAsInt();
            final int targetExtra = Math.max(0, population - 3);   // nucleo = 3 vecinos fijos
            final int current = routines.colonoCount();
            if (current < targetExtra) {
                grow(current);      // llega un colono (una casa nueva)
            } else if (current > targetExtra) {
                shrink();           // un colono emigra
            }
        }));
    }

    private void grow(int index) {
        final Location slot = village.expansionSlot(index);
        final int cx = slot.getBlockX();
        final int cz = slot.getBlockZ();
        final int fy = village.baseY();
        final var rng = ThreadLocalRandom.current();
        final Material[] pal = COMBOS[rng.nextInt(COMBOS.length)];
        final int floors = 1 + rng.nextInt(2);
        // Desbroce del hueco (arboles/colinas) para que la casa no quede enterrada.
        for (int x = cx - 6; x <= cx + 6; x++) {
            for (int z = cz - 6; z <= cz + 6; z++) {
                for (int y = fy + 1; y <= fy + 16; y++) {
                    if (!world.getBlockAt(x, y, z).getType().isAir()) {
                        world.getBlockAt(x, y, z).setType(Material.AIR, false);
                    }
                }
            }
        }
        // Casa pequena mirando al pueblo (la puerta hacia el norte).
        Blueprint.buildHouse(world, cx, cz, fy, BlockFace.NORTH, 3, floors, pal[0], pal[1], pal[2], pal[3], true);

        final String name = NAMES[rng.nextInt(NAMES.length)];
        final Location home = new Location(world, cx + 0.5, fy + 1, cz + 0.5);
        final Location workspot = new Location(world, cx + 0.5, fy + 1, cz - 6.5);   // va al pueblo de dia
        routines.addColono("colono", name, home, workspot);

        Bukkit.getOnlinePlayers().forEach(p -> p.sendMessage(
                "§a[Pueblo] §f" + name + " §7se ha instalado en una casa nueva del pueblo."));
        plugin.getLogger().info("[Aetheria] Pueblo vivo: +1 colono (" + name + ").");
    }

    private void shrink() {
        final String name = routines.removeNewestColono();
        if (name != null) {
            Bukkit.getOnlinePlayers().forEach(p -> p.sendMessage(
                    "§7[Pueblo] " + name + " ha hecho las maletas y ha emigrado a otra tierra."));
            plugin.getLogger().info("[Aetheria] Pueblo vivo: -1 colono (" + name + " emigra).");
        }
    }
}
