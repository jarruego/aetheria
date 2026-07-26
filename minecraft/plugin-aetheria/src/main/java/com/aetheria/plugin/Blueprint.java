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
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.type.Bed;
import org.bukkit.block.data.type.Door;
import org.bukkit.block.data.type.Ladder;
import org.bukkit.block.data.type.Stairs;
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
            "fountain", fountain(),
            "garden", garden(),
            "lamppost", lamppost(),
            "statue", statue(),
            "bigfountain", bigfountain());

    private Blueprint() {}

    public static boolean exists(String name) {
        return "house".equals(name) || CATALOG.containsKey(name);
    }

    /**
     * Caja (minX,minY,minZ,maxX,maxY,maxZ) que ABARCA lo que colocaria {@code place}/
     * {@code buildHouse} para este jugador AHORA. Se usa para fotografiar el terreno antes de
     * construir (para poder deshacer). Debe calcular el mismo origen que los constructores.
     */
    public static int[] buildRegion(Player player, String blueprint, int half) {
        final BlockFace f = player.getFacing();
        final int px = player.getLocation().getBlockX();
        final int pz = player.getLocation().getBlockZ();
        if ("house".equals(blueprint)) {
            final int cx = px + f.getModX() * (half + 2);
            final int cz = pz + f.getModZ() * (half + 2);
            final int fy = player.getWorld().getHighestBlockYAt(cx, cz);
            return new int[] {cx - half - 1, fy - 8, cz - half - 1, cx + half + 1, fy + 5, cz + half + 1};
        }
        // Decoraciones del catalogo: origen a 2 bloques por delante (ver place()).
        final int ox = px + f.getModX() * 2;
        final int oz = pz + f.getModZ() * 2;
        final int oy = player.getLocation().getBlockY();
        return new int[] {ox - 2, oy - 2, oz - 2, ox + 2, oy + 4, oz + 2};
    }

    /**
     * Coloca el blueprint {@code name} unos bloques por delante del jugador.
     *
     * @return numero de bloques colocados, o -1 si el blueprint no existe.
     */
    public static int place(Player player, String name) {
        if ("house".equals(name)) {
            // Casa por defecto (mediana, 2 plantas, madera) para el servicio de una linea.
            final BlockFace f = player.getFacing();
            final int cx = player.getLocation().getBlockX() + f.getModX() * 5;
            final int cz = player.getLocation().getBlockZ() + f.getModZ() * 5;
            final int fy = player.getWorld().getHighestBlockYAt(cx, cz);
            return buildHouse(player.getWorld(), cx, cz, fy, f.getOppositeFace(), 3, 2,
                    Material.OAK_PLANKS, Material.SPRUCE_LOG, Material.DARK_OAK_PLANKS,
                    Material.BRICKS, true);
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

    /** Caja que abarca una casa (para fotografiar el terreno antes de construir). */
    public static int[] houseRegion(int cx, int cz, int floorY, int half, int floors) {
        return new int[] {cx - half - 1, floorY - 8, cz - half - 1,
                cx + half + 1, floorY + floors * 4 + 3, cz + half + 1};
    }

    /**
     * Construye una CASA a medida en (cx,cz) con la planta baja en floorY y la puerta en el
     * lado {@code door}. Multiplanta con forjados, escalera de mano, TERRAZA con barandilla,
     * puerta y ventanas de verdad, chimenea, mobiliario por estancias y un toque unico.
     */
    public static int buildHouse(World world, int cx, int cz, int floorY, BlockFace door,
            int half, int floors, Material wall, Material corner, Material roof, Material accent,
            boolean furniture) {
        final int fh = 4;                          // altura por planta (3 muro + 1 forjado)
        final int topY = floorY + floors * fh;     // nivel de la terraza
        int n = 0;

        // Cimentacion: suelo firme, hueco despejado y relleno inferior para que no flote.
        for (int dx = -half; dx <= half; dx++) {
            for (int dz = -half; dz <= half; dz++) {
                set(world, cx + dx, floorY, cz + dz, Material.STONE_BRICKS);
                n++;
                for (int dy = 1; dy <= floors * fh + 3; dy++) {
                    set(world, cx + dx, floorY + dy, cz + dz, Material.AIR);
                }
                for (int dy = floorY - 1; dy >= floorY - 8; dy--) {
                    final var b = world.getBlockAt(cx + dx, dy, cz + dz);
                    if (b.getType().isAir() || b.isLiquid()) {
                        b.setType(Material.DIRT, false);
                    } else {
                        break;
                    }
                }
            }
        }

        // Plantas: muros (con zocalo de acento), forjados y ventanas.
        for (int fl = 0; fl < floors; fl++) {
            final int by = floorY + fl * fh;
            for (int y = by + 1; y <= by + fh - 1; y++) {
                for (int dx = -half; dx <= half; dx++) {
                    for (int dz = -half; dz <= half; dz++) {
                        if (Math.abs(dx) != half && Math.abs(dz) != half) {
                            continue;
                        }
                        final boolean isCorner = Math.abs(dx) == half && Math.abs(dz) == half;
                        set(world, cx + dx, y, cz + dz, isCorner ? corner : (y == by + 1 ? accent : wall));
                        n++;
                    }
                }
            }
            if (fl < floors - 1) {   // forjado de la planta de arriba (la ultima lleva terraza)
                for (int dx = -half + 1; dx <= half - 1; dx++) {
                    for (int dz = -half + 1; dz <= half - 1; dz++) {
                        set(world, cx + dx, by + fh, cz + dz, Material.SPRUCE_PLANKS);
                    }
                }
            }
            windows(world, cx, cz, by + 2, half, door);
        }

        // Puerta de verdad en la planta baja + escalon y faroles de entrada.
        final int dgx = cx + door.getModX() * half;
        final int dgz = cz + door.getModZ() * half;
        set(world, dgx, floorY + 1, dgz, Material.AIR);
        set(world, dgx, floorY + 2, dgz, Material.AIR);
        placeDoor(world, dgx, floorY + 1, dgz, door.getOppositeFace());
        placeStair(world, dgx + door.getModX(), floorY, dgz + door.getModZ(), door);
        set(world, dgx + door.getModZ(), floorY + 2, dgz + door.getModX(), Material.LANTERN);
        set(world, dgx - door.getModZ(), floorY + 2, dgz - door.getModX(), Material.LANTERN);

        // Escalera de mano entre plantas + huecos en los forjados (esquina trasera-interior).
        if (floors > 1) {
            final int lx = cx - half + 1;
            final int lz = cz - half + 1;
            for (int y = floorY + 1; y < topY; y++) {
                placeLadder(world, lx, y, lz, BlockFace.SOUTH);
            }
            for (int fl = 1; fl <= floors; fl++) {
                set(world, lx, floorY + fl * fh, lz, Material.AIR);
                set(world, lx, floorY + fl * fh - 1, lz, Material.AIR);
                placeLadder(world, lx, floorY + fl * fh, lz, BlockFace.SOUTH);
            }
        }

        // Terraza: suelo, barandilla de valla y un farol.
        for (int dx = -half; dx <= half; dx++) {
            for (int dz = -half; dz <= half; dz++) {
                set(world, cx + dx, topY, cz + dz, roof);
                n++;
                if ((Math.abs(dx) == half || Math.abs(dz) == half) && floors > 1) {
                    set(world, cx + dx, topY + 1, cz + dz, Material.OAK_FENCE);
                }
            }
        }
        if (floors > 1) {
            set(world, cx + half - 1, topY + 1, cz - half + 2, Material.LANTERN);
            set(world, cx - half + 1, topY, cz - half + 1, Material.AIR);   // salida de la escalera
        }

        // Chimenea de ladrillo (toque unico: el lado depende de un seed simple).
        final int chx = ((cx * 31 + cz) & 1) == 0 ? cx + half - 1 : cx - half + 1;
        for (int y = floorY + 1; y <= topY + 1; y++) {
            set(world, chx, y, cz + half - 1, Material.BRICKS);
        }
        set(world, chx, topY + 2, cz + half - 1, Material.CAMPFIRE);

        // Mobiliario por estancias.
        set(world, cx + 1, floorY + 1, cz + 1, Material.LANTERN);   // luz de la planta baja
        if (furniture) {
            set(world, cx - half + 1, floorY + 1, cz - half + 1, Material.CRAFTING_TABLE);
            set(world, cx - half + 1, floorY + 1, cz - half + 2, Material.FURNACE);
            set(world, cx + half - 1, floorY + 1, cz - half + 1, Material.CHEST);
            set(world, cx + half - 1, floorY + 1, cz + half - 1, Material.OAK_FENCE);      // mesa
            set(world, cx + half - 1, floorY + 2, cz + half - 1, Material.OAK_PRESSURE_PLATE);
            final int bedY = floorY + (floors - 1) * fh + 1;                                // dormitorio arriba
            placeBed(world, cx, bedY, cz + half - 1, BlockFace.NORTH);
            set(world, cx - half + 1, bedY, cz + half - 1, Material.LANTERN);
        }

        // Cartel en la fachada, junto a la puerta.
        final int sx = door.getModX() != 0 ? 0 : 1;
        final int sz = door.getModZ() != 0 ? 0 : 1;
        placeSign(world.getBlockAt(dgx + sx + door.getModX(), floorY + 2, dgz + sz + door.getModZ()),
                door, "Tu casa");
        return n;
    }

    private static void set(World w, int x, int y, int z, Material m) {
        w.getBlockAt(x, y, z).setType(m, false);
    }

    private static void windows(World w, int cx, int cz, int y, int half, BlockFace door) {
        for (final BlockFace s : new BlockFace[] {BlockFace.NORTH, BlockFace.SOUTH,
                BlockFace.EAST, BlockFace.WEST}) {
            if (s == door) {
                continue;
            }
            final int wx = cx + s.getModX() * half;
            final int wz = cz + s.getModZ() * half;
            set(w, wx, y, wz, Material.GLASS_PANE);
            if (half >= 3) {   // fachadas grandes: una ventana mas a cada lado
                final int ox = s.getModX() != 0 ? 0 : 1;
                final int oz = s.getModZ() != 0 ? 0 : 1;
                set(w, wx + ox, y, wz + oz, Material.GLASS_PANE);
                set(w, wx - ox, y, wz - oz, Material.GLASS_PANE);
            }
        }
    }

    private static void placeDoor(World w, int x, int y, int z, BlockFace facing) {
        final Door lower = (Door) Bukkit.createBlockData(Material.OAK_DOOR);
        lower.setFacing(facing);
        lower.setHalf(Bisected.Half.BOTTOM);
        final Door upper = (Door) Bukkit.createBlockData(Material.OAK_DOOR);
        upper.setFacing(facing);
        upper.setHalf(Bisected.Half.TOP);
        w.getBlockAt(x, y, z).setBlockData(lower, false);
        w.getBlockAt(x, y + 1, z).setBlockData(upper, false);
    }

    private static void placeLadder(World w, int x, int y, int z, BlockFace facing) {
        final Ladder l = (Ladder) Bukkit.createBlockData(Material.LADDER);
        l.setFacing(facing);
        w.getBlockAt(x, y, z).setBlockData(l, false);
    }

    private static void placeStair(World w, int x, int y, int z, BlockFace facing) {
        final Stairs s = (Stairs) Bukkit.createBlockData(Material.STONE_BRICK_STAIRS);
        s.setFacing(facing);
        w.getBlockAt(x, y, z).setBlockData(s, false);
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

    private static List<Block> garden() {
        final List<Block> b = new ArrayList<>();
        final Material[] flores = {Material.POPPY, Material.DANDELION, Material.BLUE_ORCHID,
                Material.ALLIUM, Material.OXEYE_DAISY, Material.CORNFLOWER};
        int f = 0;
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                b.add(new Block(dx, 0, dz, Material.GRASS_BLOCK));
                final boolean edge = Math.abs(dx) == 2 || Math.abs(dz) == 2;
                if (edge) {
                    b.add(new Block(dx, 1, dz, Material.OAK_FENCE));   // cerca
                } else {
                    b.add(new Block(dx, 1, dz, flores[(f++) % flores.length]));  // flores
                }
            }
        }
        b.add(new Block(-2, 2, -2, Material.LANTERN));   // farolillo en una esquina
        return b;
    }

    private static List<Block> lamppost() {
        final List<Block> b = new ArrayList<>();
        b.add(new Block(0, 0, 0, Material.COBBLESTONE));
        b.add(new Block(0, 1, 0, Material.OAK_FENCE));
        b.add(new Block(0, 2, 0, Material.OAK_FENCE));
        b.add(new Block(0, 3, 0, Material.LANTERN));
        return b;
    }

    private static List<Block> statue() {
        final List<Block> b = new ArrayList<>();
        b.add(new Block(0, 0, 0, Material.CHISELED_STONE_BRICKS));   // pedestal
        b.add(new Block(0, 1, 0, Material.QUARTZ_PILLAR));           // piernas
        b.add(new Block(0, 2, 0, Material.QUARTZ_BLOCK));            // torso
        b.add(new Block(1, 2, 0, Material.QUARTZ_SLAB));             // brazos
        b.add(new Block(-1, 2, 0, Material.QUARTZ_SLAB));
        b.add(new Block(0, 3, 0, Material.QUARTZ_BLOCK));            // cabeza
        return b;
    }

    private static List<Block> bigfountain() {
        final List<Block> b = new ArrayList<>();
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                b.add(new Block(dx, 0, dz, Material.QUARTZ_BLOCK));               // base 5x5
                final boolean rim = Math.abs(dx) == 2 || Math.abs(dz) == 2;
                if (rim) {
                    b.add(new Block(dx, 1, dz, Material.QUARTZ_BLOCK));           // borde exterior
                } else if (dx == 0 && dz == 0) {
                    b.add(new Block(dx, 1, dz, Material.QUARTZ_PILLAR));          // pilar central
                    b.add(new Block(dx, 2, dz, Material.WATER));                  // agua arriba
                } else {
                    b.add(new Block(dx, 1, dz, Material.WATER));                  // estanque
                }
            }
        }
        return b;
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
