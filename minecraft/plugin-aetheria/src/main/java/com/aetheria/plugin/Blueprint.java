package com.aetheria.plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Sign;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.type.Bed;
import org.bukkit.block.sign.Side;
import org.bukkit.entity.Player;

import net.kyori.adventure.text.Component;

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
        return "house".equals(name) || CATALOG.containsKey(name);
    }

    /**
     * Coloca el blueprint {@code name} unos bloques por delante del jugador.
     *
     * @return numero de bloques colocados, o -1 si el blueprint no existe.
     */
    public static int place(Player player, String name) {
        if ("house".equals(name)) {
            // Casa por defecto (mediana, madera, amueblada) para el servicio de una linea.
            return buildHouse(player, 2, Material.OAK_PLANKS, Material.SPRUCE_LOG,
                    Material.DARK_OAK_PLANKS, true);
        }
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

    /**
     * Construye una CASA PARAMETRICA unos bloques por delante del jugador, sobre cimentacion
     * aplanada: tamano (half), materiales de muro/esquina/tejado y mobiliario opcional.
     * Puerta del lado del jugador, ventanas, y cartel en la fachada.
     */
    public static int buildHouse(Player player, int half, Material wall, Material corner,
            Material roof, boolean furniture) {
        final World world = player.getWorld();
        final BlockFace facing = player.getFacing();
        // Centro de la casa, por delante de donde mira el jugador (segun su tamano).
        final int cx = player.getLocation().getBlockX() + facing.getModX() * (half + 2);
        final int cz = player.getLocation().getBlockZ() + facing.getModZ() * (half + 2);
        final int fy = world.getHighestBlockYAt(cx, cz);
        final BlockFace door = facing.getOppositeFace();   // la puerta mira al jugador
        final int wallH = 3;
        int n = 0;

        // Cimentacion: suelo, hueco interior despejado y relleno inferior.
        for (int dx = -half; dx <= half; dx++) {
            for (int dz = -half; dz <= half; dz++) {
                world.getBlockAt(cx + dx, fy, cz + dz).setType(Material.STONE_BRICKS, false);
                n++;
                for (int dy = 1; dy <= 5; dy++) {
                    world.getBlockAt(cx + dx, fy + dy, cz + dz).setType(Material.AIR, false);
                }
                for (int dy = fy - 1; dy >= fy - 6; dy--) {
                    final var b = world.getBlockAt(cx + dx, dy, cz + dz);
                    if (b.getType().isAir() || b.isLiquid()) {
                        b.setType(Material.DIRT, false);
                    } else {
                        break;
                    }
                }
            }
        }
        // Muros: esquinas de un material y paredes de otro.
        for (int y = fy + 1; y <= fy + wallH; y++) {
            for (int dx = -half; dx <= half; dx++) {
                for (int dz = -half; dz <= half; dz++) {
                    if (Math.abs(dx) != half && Math.abs(dz) != half) {
                        continue;
                    }
                    final boolean isCorner = Math.abs(dx) == half && Math.abs(dz) == half;
                    world.getBlockAt(cx + dx, y, cz + dz).setType(isCorner ? corner : wall, false);
                    n++;
                }
            }
        }
        // Tejado con alero.
        for (int dx = -half - 1; dx <= half + 1; dx++) {
            for (int dz = -half - 1; dz <= half + 1; dz++) {
                world.getBlockAt(cx + dx, fy + wallH + 1, cz + dz).setType(roof, false);
                n++;
            }
        }
        // Puerta (hueco de 1x2) del lado del jugador.
        final int dgx = cx + door.getModX() * half;
        final int dgz = cz + door.getModZ() * half;
        world.getBlockAt(dgx, fy + 1, dgz).setType(Material.AIR, false);
        world.getBlockAt(dgx, fy + 2, dgz).setType(Material.AIR, false);
        // Ventanas: en los otros lados, a media altura.
        for (final BlockFace side : new BlockFace[] {BlockFace.NORTH, BlockFace.SOUTH,
                BlockFace.EAST, BlockFace.WEST}) {
            if (side == door) {
                continue;
            }
            world.getBlockAt(cx + side.getModX() * half, fy + 2, cz + side.getModZ() * half)
                    .setType(Material.GLASS_PANE, false);
        }
        // Iluminacion siempre; mobiliario solo si se ha pagado.
        world.getBlockAt(cx + 1, fy + 1, cz + 1).setType(Material.LANTERN, false);
        if (furniture) {
            placeBed(world, cx, fy + 1, cz, BlockFace.EAST);
            world.getBlockAt(cx - 1, fy + 1, cz + 1).setType(Material.CRAFTING_TABLE, false);
            world.getBlockAt(cx + 1, fy + 1, cz - 1).setType(Material.CHEST, false);
            world.getBlockAt(cx, fy + 1, cz + 1).setType(Material.FURNACE, false);
        }
        // Cartel en la fachada, junto a la puerta (sobre un bloque solido de la pared).
        final int px = door.getModX() != 0 ? 0 : 1;   // desplazamiento a lo largo de la pared
        final int pz = door.getModZ() != 0 ? 0 : 1;
        placeSign(world.getBlockAt(dgx + px + door.getModX(), fy + 2, dgz + pz + door.getModZ()),
                door, "Tu casa");
        return n;
    }

    private static void placeBed(World world, int x, int y, int z, BlockFace facing) {
        final Bed foot = (Bed) Bukkit.createBlockData(Material.RED_BED);
        foot.setPart(Bed.Part.FOOT);
        foot.setFacing(facing);
        final Bed head = (Bed) Bukkit.createBlockData(Material.RED_BED);
        head.setPart(Bed.Part.HEAD);
        head.setFacing(facing);
        world.getBlockAt(x - 1, y, z - 1).setBlockData(foot, false);
        world.getBlockAt(x - 1, y, z - 1).getRelative(facing).setBlockData(head, false);
    }

    private static void placeSign(org.bukkit.block.Block block, BlockFace facing, String text) {
        block.setType(Material.OAK_WALL_SIGN, false);
        if (block.getBlockData() instanceof Directional dir) {
            dir.setFacing(facing);
            block.setBlockData(dir, false);
        }
        if (block.getState() instanceof Sign sign) {
            sign.getSide(Side.FRONT).line(1, Component.text(text));
            sign.update(true);
        }
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
