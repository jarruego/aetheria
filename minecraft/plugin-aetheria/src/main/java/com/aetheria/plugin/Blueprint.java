package com.aetheria.plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;

/**
 * Catalogo ACOTADO de estructuras que el plugin sabe colocar. Debe reflejar la lista
 * blanca de blueprints del validador del backend (defensa en profundidad en ambos lados).
 *
 * <p>Las estructuras son pequenas y deterministas (nada de esquematicos arbitrarios),
 * para que el efecto sobre el mundo este siempre acotado.
 */
public final class Blueprint {

    /** Un bloque relativo al origen del blueprint. */
    private record Block(int dx, int dy, int dz, Material material) {}

    private static final Map<String, List<Block>> CATALOG = Map.of(
            "platform", platform(),
            "fountain", fountain());

    private Blueprint() {}

    public static boolean exists(String name) {
        return CATALOG.containsKey(name);
    }

    /**
     * Coloca el blueprint {@code name} unos bloques por delante del jugador.
     *
     * @return numero de bloques colocados, o -1 si el blueprint no existe.
     */
    public static int place(Player player, String name) {
        final List<Block> blocks = CATALOG.get(name);
        if (blocks == null) {
            return -1;
        }
        final World world = player.getWorld();
        final BlockFace facing = player.getFacing();
        final Location origin = player.getLocation().getBlock().getLocation()
                .add(facing.getModX() * 2.0, 0, facing.getModZ() * 2.0);

        for (Block b : blocks) {
            world.getBlockAt(
                    origin.getBlockX() + b.dx(),
                    origin.getBlockY() + b.dy(),
                    origin.getBlockZ() + b.dz()).setType(b.material());
        }
        return blocks.size();
    }

    private static List<Block> platform() {
        final List<Block> blocks = new ArrayList<>();
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                blocks.add(new Block(dx, 0, dz, Material.STONE_BRICKS));
            }
        }
        return blocks;
    }

    private static List<Block> fountain() {
        final List<Block> blocks = new ArrayList<>();
        // Base 3x3 de cuarzo.
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                blocks.add(new Block(dx, 0, dz, Material.QUARTZ_BLOCK));
            }
        }
        // Perimetro de cuarzo a la altura 1 (contiene el agua) y agua en el centro.
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                final boolean edge = (dx != 0 || dz != 0);
                blocks.add(new Block(dx, 1, dz, edge ? Material.QUARTZ_BLOCK : Material.WATER));
            }
        }
        return blocks;
    }
}
