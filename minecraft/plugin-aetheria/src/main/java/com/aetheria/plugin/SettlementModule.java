package com.aetheria.plugin;

import java.util.concurrent.ThreadLocalRandom;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Villager;

/**
 * Pueblo VIVO: reconcilia el mundo fisico con la poblacion objetivo de la simulacion (crece
 * cuando prospera, mengua cuando decae). Al crecer NIVELA el terreno, construye una casa
 * (con el nombre del colono en el cartel y un rasgo segun su oficio), la conecta con un
 * camino al pueblo, y llega un colono con su rutina. Al decaer, un colono emigra.
 */
public final class SettlementModule {

    private static final long PERIOD = 1200L;   // reconcilia cada 60 s (una casa por vez)
    private static final String[] NAMES = {"Bruno", "Lena", "Tobias", "Mila", "Ada", "Iker",
        "Noa", "Gala", "Hugo", "Vera", "Leo", "Sol", "Dario", "Enara", "Cloe", "Nil"};
    private static final Villager.Profession[] PROFS = {Villager.Profession.FARMER,
        Villager.Profession.FISHERMAN, Villager.Profession.SHEPHERD, Villager.Profession.MASON,
        Villager.Profession.LIBRARIAN, Villager.Profession.TOOLSMITH, Villager.Profession.BUTCHER,
        Villager.Profession.FLETCHER};
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
        // Red de caminos: avenida este-oeste y prolongacion de la calle mayor hacia el barrio.
        final int sx = village.spawnX();
        final int sz = village.spawnZ();
        road(sx - 40, sx + 40, sz + 38, sz + 38);
        road(sx, sx, sz + 34, sz + 38);
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::reconcile, PERIOD, PERIOD);
        plugin.getLogger().info("[Aetheria] Pueblo vivo: reconciliando poblacion cada 60 s.");
    }

    private void reconcile() {
        gateway.getVillage().whenComplete((json, err) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (err != null || json == null) {
                return;
            }
            final int population = json.get("population").getAsInt();
            final int targetExtra = Math.max(0, population - 3);
            final int current = routines.colonoCount();
            if (current < targetExtra) {
                grow(current);
            } else if (current > targetExtra) {
                shrink();
            }
        }));
    }

    private void grow(int index) {
        final Location slot = village.expansionSlot(index);
        final int cx = slot.getBlockX();
        final int cz = slot.getBlockZ();
        final int fy = village.baseY();
        final var rng = ThreadLocalRandom.current();
        final String name = NAMES[rng.nextInt(NAMES.length)];
        final Villager.Profession prof = PROFS[rng.nextInt(PROFS.length)];
        final Material[] pal = COMBOS[rng.nextInt(COMBOS.length)];
        final int floors = 1 + rng.nextInt(2);

        yard(cx, cz, fy);                                   // nivela patio (suelo + desbroce)
        Blueprint.buildHouse(world, cx, cz, fy, BlockFace.NORTH, 3, floors,
                pal[0], pal[1], pal[2], pal[3], true, name);
        professionFeature(cx, cz, fy, prof, rng);          // rasgo del oficio
        road(cx, cx, village.spawnZ() + 38, cz - 4);       // camino a la avenida

        final Location home = new Location(world, cx + 0.5, fy + 1, cz + 0.5);
        routines.addColono("colono", name, home, village.plaza(), prof);
        Bukkit.getOnlinePlayers().forEach(p -> p.sendMessage(
                "§a[Pueblo] §f" + name + " §7(" + oficio(prof) + ") se ha instalado en el pueblo."));
        plugin.getLogger().info("[Aetheria] Pueblo vivo: +1 colono (" + name + ", " + prof + ").");
    }

    private void shrink() {
        final String name = routines.removeNewestColono();
        if (name != null) {
            Bukkit.getOnlinePlayers().forEach(p -> p.sendMessage(
                    "§7[Pueblo] " + name + " ha hecho las maletas y ha emigrado a otra tierra."));
            plugin.getLogger().info("[Aetheria] Pueblo vivo: -1 colono (" + name + " emigra).");
        }
    }

    /** Nivela un patio 13x13 al nivel del pueblo: suelo de cesped, relleno abajo y desbroce arriba. */
    private void yard(int cx, int cz, int fy) {
        for (int dx = -6; dx <= 6; dx++) {
            for (int dz = -6; dz <= 6; dz++) {
                for (int y = fy - 1; y >= fy - 7; y--) {
                    final var b = world.getBlockAt(cx + dx, y, cz + dz);
                    if (b.getType().isAir() || b.isLiquid()) {
                        b.setType(Material.DIRT, false);
                    } else {
                        break;
                    }
                }
                world.getBlockAt(cx + dx, fy, cz + dz).setType(Material.GRASS_BLOCK, false);
                for (int y = fy + 1; y <= fy + 16; y++) {
                    if (!world.getBlockAt(cx + dx, y, cz + dz).getType().isAir()) {
                        world.getBlockAt(cx + dx, y, cz + dz).setType(Material.AIR, false);
                    }
                }
            }
        }
    }

    /** Camino de grava recto (nivelado) entre dos puntos, al nivel del pueblo. */
    private void road(int x0, int x1, int z0, int z1) {
        final int fy = village.baseY();
        final int xa = Math.min(x0, x1);
        final int xb = Math.max(x0, x1);
        final int za = Math.min(z0, z1);
        final int zb = Math.max(z0, z1);
        for (int x = xa; x <= xb; x++) {
            for (int z = za; z <= zb; z++) {
                for (int y = fy - 1; y >= fy - 7; y--) {
                    final var b = world.getBlockAt(x, y, z);
                    if (b.getType().isAir() || b.isLiquid()) {
                        b.setType(Material.DIRT, false);
                    } else {
                        break;
                    }
                }
                world.getBlockAt(x, fy, z).setType(Material.GRAVEL, false);
                for (int y = fy + 1; y <= fy + 4; y++) {
                    if (!world.getBlockAt(x, y, z).getType().isAir()) {
                        world.getBlockAt(x, y, z).setType(Material.AIR, false);
                    }
                }
            }
        }
    }

    /** Un pequeno rasgo junto a la casa segun el oficio (al este). Profession ya no es enum. */
    private void professionFeature(int cx, int cz, int fy, Villager.Profession prof, java.util.Random rng) {
        final int ex = cx + 6;
        if (prof == Villager.Profession.FARMER) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dz == 0) {
                        world.getBlockAt(ex, fy, cz).setType(Material.WATER, false);
                    } else {
                        world.getBlockAt(ex + dx, fy, cz + dz).setType(Material.FARMLAND, false);
                        world.getBlockAt(ex + dx, fy + 1, cz + dz).setType(Material.WHEAT, false);
                    }
                }
            }
            world.getBlockAt(ex + 2, fy + 1, cz).setType(Material.COMPOSTER, false);
        } else if (prof == Villager.Profession.FISHERMAN) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    world.getBlockAt(ex + dx, fy, cz + dz).setType(Material.WATER, false);
                }
            }
            world.getBlockAt(ex, fy + 1, cz - 2).setType(Material.BARREL, false);
        } else if (prof == Villager.Profession.SHEPHERD) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    final boolean edge = Math.abs(dx) == 1 || Math.abs(dz) == 1;
                    world.getBlockAt(ex + dx, fy + 1, cz + dz)
                            .setType(edge ? Material.OAK_FENCE : Material.WHITE_WOOL, false);
                }
            }
        } else if (prof == Villager.Profession.MASON) {
            world.getBlockAt(ex, fy + 1, cz).setType(Material.STONECUTTER, false);
            world.getBlockAt(ex + 1, fy + 1, cz).setType(Material.STONE, false);
            world.getBlockAt(ex + 1, fy + 2, cz).setType(Material.CHISELED_STONE_BRICKS, false);
        } else if (prof == Villager.Profession.LIBRARIAN) {
            world.getBlockAt(ex, fy + 1, cz).setType(Material.LECTERN, false);
            world.getBlockAt(ex + 1, fy + 1, cz).setType(Material.BOOKSHELF, false);
            world.getBlockAt(ex + 1, fy + 2, cz).setType(Material.BOOKSHELF, false);
        } else if (prof == Villager.Profession.TOOLSMITH) {
            world.getBlockAt(ex, fy + 1, cz).setType(Material.ANVIL, false);
            world.getBlockAt(ex + 1, fy + 1, cz).setType(Material.GRINDSTONE, false);
            world.getBlockAt(ex - 1, fy + 1, cz).setType(Material.FURNACE, false);
        } else if (prof == Villager.Profession.BUTCHER) {
            world.getBlockAt(ex, fy + 1, cz).setType(Material.SMOKER, false);
            world.getBlockAt(ex + 1, fy + 1, cz).setType(Material.BARREL, false);
        } else if (prof == Villager.Profession.FLETCHER) {
            world.getBlockAt(ex, fy + 1, cz).setType(Material.FLETCHING_TABLE, false);
            world.getBlockAt(ex + 1, fy + 1, cz).setType(Material.HAY_BLOCK, false);
        } else {
            world.getBlockAt(ex, fy + 1, cz).setType(Material.OAK_FENCE, false);
            world.getBlockAt(ex, fy + 2, cz).setType(Material.LANTERN, false);
        }
    }

    private static String oficio(Villager.Profession p) {
        if (p == Villager.Profession.FARMER) return "granjero";
        if (p == Villager.Profession.FISHERMAN) return "pescador";
        if (p == Villager.Profession.SHEPHERD) return "pastor";
        if (p == Villager.Profession.MASON) return "cantero";
        if (p == Villager.Profession.LIBRARIAN) return "bibliotecario";
        if (p == Villager.Profession.TOOLSMITH) return "herrero";
        if (p == Villager.Profession.BUTCHER) return "carnicero";
        if (p == Villager.Profession.FLETCHER) return "arquero";
        return "vecino";
    }
}
