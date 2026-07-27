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
            return new int[] {cx - half - 1, fy - 8, cz - half - 1, cx + half + 1, fy + 14, cz + half + 1};
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
            final int fy = TerrainPlanner.meanFirmY(player.getWorld(), cx - 3, cz - 3, cx + 3, cz + 3);
            return buildHouse(player.getWorld(), cx, cz, fy, f.getOppositeFace(), 3, 2,
                    Material.OAK_PLANKS, Material.SPRUCE_LOG, Material.DARK_OAK_PLANKS,
                    Material.BRICKS, true, player.getName());
        }
        final List<Block> blocks = CATALOG.get(name);
        if (blocks == null) {
            return -1;
        }
        final World world = player.getWorld();
        final BlockFace facing = player.getFacing();
        final int ox = player.getLocation().getBlockX() + facing.getModX() * 2;
        final int oz = player.getLocation().getBlockZ() + facing.getModZ() * 2;
        final int oy = TerrainPlanner.groundY(world, ox, oz);   // sobre el SUELO real, no la Y del jugador
        TerrainPlanner.prepare(world, ox - 3, oz - 3, ox + 3, oz + 3, oy, Material.COBBLESTONE);

        for (Block b : blocks) {
            world.getBlockAt(ox + b.dx(), oy + b.dy(), oz + b.dz()).setType(b.material());
        }
        return blocks.size();
    }

    private static void wpFence(World w, int cx, int gy, int cz, int r) {
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                if (Math.abs(dx) != r && Math.abs(dz) != r) {
                    continue;
                }
                set(w, cx + dx, gy + 1, cz + dz,
                        (dx == -r && dz == 0) ? Material.OAK_FENCE_GATE : Material.OAK_FENCE);
            }
        }
    }

    private static void wpCanopy(World w, int cx, int gy, int cz, Material roof) {
        for (int dx = -1; dx <= 1; dx += 2) {
            for (int dz = -1; dz <= 1; dz += 2) {
                set(w, cx + dx, gy + 1, cz + dz, Material.OAK_FENCE);
                set(w, cx + dx, gy + 2, cz + dz, Material.OAK_FENCE);
            }
        }
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                set(w, cx + dx, gy + 3, cz + dz, roof);
            }
        }
    }

    /**
     * Puesto de trabajo TEMATICO para el catalogo (mundo creativo): construye directamente en
     * (wx,gy,wz) la estructura del oficio. {@code profKey} en minusculas (farmer, fisherman...).
     */
    public static void workplaceShowcase(World w, int wx, int gy, int wz, String profKey) {
        switch (profKey) {
            case "farmer" -> {
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dz == 0) {
                            set(w, wx, gy, wz, Material.WATER);
                        } else {
                            set(w, wx + dx, gy, wz + dz, Material.FARMLAND);
                            set(w, wx + dx, gy + 1, wz + dz,
                                    ((dx + dz) & 1) == 0 ? Material.WHEAT : Material.CARROTS);
                        }
                    }
                }
                set(w, wx + 2, gy + 1, wz, Material.COMPOSTER);
                set(w, wx - 2, gy + 1, wz + 1, Material.HAY_BLOCK);
                set(w, wx - 2, gy + 2, wz + 1, Material.CARVED_PUMPKIN);
                wpFence(w, wx, gy, wz, 2);
            }
            case "fisherman" -> {
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        set(w, wx + dx, gy, wz + dz, Material.WATER);
                    }
                }
                set(w, wx, gy + 1, wz, Material.LILY_PAD);
                for (int dz = -1; dz <= 1; dz++) {
                    set(w, wx + 2, gy + 1, wz + dz, Material.OAK_PLANKS);
                }
                set(w, wx + 2, gy + 2, wz + 1, Material.OAK_FENCE);
                set(w, wx + 2, gy + 3, wz + 1, Material.LANTERN);
                set(w, wx + 2, gy + 2, wz - 1, Material.BARREL);
                set(w, wx - 2, gy + 1, wz, Material.BARREL);
            }
            case "shepherd" -> {
                wpFence(w, wx, gy, wz, 2);
                set(w, wx - 1, gy + 1, wz - 1, Material.WHITE_WOOL);
                set(w, wx - 1, gy + 2, wz - 1, Material.WHITE_WOOL);
                set(w, wx + 1, gy + 1, wz + 1, Material.BLACK_WOOL);
                set(w, wx + 1, gy + 1, wz - 1, Material.HAY_BLOCK);
                set(w, wx, gy + 1, wz, Material.SHORT_GRASS);
            }
            case "mason" -> {
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        set(w, wx + dx, gy, wz + dz, Material.STONE_BRICKS);
                    }
                }
                set(w, wx, gy + 1, wz, Material.STONECUTTER);
                set(w, wx + 1, gy + 1, wz, Material.CHISELED_STONE_BRICKS);
                set(w, wx + 1, gy + 2, wz, Material.STONE_BRICK_WALL);
                set(w, wx - 1, gy + 1, wz - 1, Material.POLISHED_ANDESITE);
                set(w, wx - 1, gy + 1, wz + 1, Material.STONE_BRICK_STAIRS);
                set(w, wx - 1, gy + 2, wz + 1, Material.STONE_BRICKS);
            }
            case "librarian" -> {
                wpCanopy(w, wx, gy, wz, Material.OAK_SLAB);
                set(w, wx, gy + 1, wz, Material.LECTERN);
                set(w, wx + 1, gy + 1, wz, Material.BOOKSHELF);
                set(w, wx - 1, gy + 1, wz, Material.BOOKSHELF);
                set(w, wx + 1, gy + 2, wz, Material.BOOKSHELF);
                set(w, wx - 1, gy + 2, wz, Material.BOOKSHELF);
                set(w, wx, gy + 1, wz - 1, Material.LANTERN);
            }
            case "toolsmith" -> {
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        set(w, wx + dx, gy, wz + dz, Material.COBBLESTONE);
                    }
                }
                wpCanopy(w, wx, gy, wz, Material.STONE_BRICK_SLAB);
                set(w, wx - 1, gy + 1, wz, Material.FURNACE);
                set(w, wx, gy + 1, wz, Material.BLAST_FURNACE);
                set(w, wx + 1, gy + 1, wz, Material.FURNACE);
                set(w, wx + 1, gy + 1, wz - 1, Material.ANVIL);
                set(w, wx - 1, gy + 1, wz - 1, Material.GRINDSTONE);
                set(w, wx, gy + 1, wz + 1, Material.CAMPFIRE);
            }
            case "butcher" -> {
                wpCanopy(w, wx, gy, wz, Material.RED_WOOL);
                set(w, wx - 1, gy + 1, wz, Material.SMOKER);
                set(w, wx + 1, gy + 1, wz, Material.BARREL);
                for (int dx = -1; dx <= 1; dx++) {
                    set(w, wx + dx, gy + 1, wz + 1, Material.OAK_FENCE);
                    set(w, wx + dx, gy + 2, wz + 1, Material.OAK_SLAB);
                }
            }
            case "fletcher" -> {
                set(w, wx, gy + 1, wz, Material.FLETCHING_TABLE);
                set(w, wx + 1, gy + 1, wz, Material.HAY_BLOCK);
                for (int dx = -1; dx <= 1; dx++) {
                    set(w, wx + dx, gy + 1, wz - 2, Material.WHITE_WOOL);
                    set(w, wx + dx, gy + 3, wz - 2, Material.WHITE_WOOL);
                }
                set(w, wx, gy + 2, wz - 2, Material.RED_WOOL);
                set(w, wx - 1, gy + 2, wz - 2, Material.WHITE_WOOL);
                set(w, wx + 1, gy + 2, wz - 2, Material.WHITE_WOOL);
            }
            default -> {
                wpCanopy(w, wx, gy, wz, Material.OAK_SLAB);
                set(w, wx, gy + 1, wz, Material.BARREL);
                set(w, wx, gy + 2, wz, Material.LANTERN);
            }
        }
    }

    /** Coloca una decoracion del catalogo con su ORIGEN en (ox,oy,oz). Devuelve nº de bloques o -1. */
    public static int placeAt(World world, int ox, int oy, int oz, String name) {
        final List<Block> blocks = CATALOG.get(name);
        if (blocks == null) {
            return -1;
        }
        for (final Block b : blocks) {
            world.getBlockAt(ox + b.dx(), oy + b.dy(), oz + b.dz()).setType(b.material(), false);
        }
        return blocks.size();
    }

    /** Caja que abarca una casa (para fotografiar el terreno antes de construir). */
    public static int[] houseRegion(int cx, int cz, int floorY, int half, int floors) {
        return new int[] {cx - half - 1, floorY - 8, cz - half - 1,
                cx + half + 1, floorY + floors * 5 + half + 2, cz + half + 1};
    }

    private static final Material[] FLOWERS = {Material.POPPY, Material.DANDELION,
            Material.BLUE_ORCHID, Material.ALLIUM, Material.OXEYE_DAISY, Material.CORNFLOWER};
    private static final Material[] DOORS = {Material.OAK_DOOR, Material.SPRUCE_DOOR,
            Material.DARK_OAK_DOOR, Material.BIRCH_DOOR, Material.ACACIA_DOOR};

    /** Casa CUADRADA (compatibilidad): delega en la version rectangular sin retranqueo. */
    public static int buildHouse(World world, int cx, int cz, int floorY, BlockFace door,
            int half, int floors, Material wall, Material corner, Material roof, Material accent,
            boolean furniture, String ownerName) {
        return buildHouse(world, cx, cz, floorY, door, half, half, floors, false,
                wall, corner, roof, accent, furniture, -1, ownerName);
    }

    /**
     * Construye una CASA a medida en (cx,cz), planta baja en floorY, puerta en {@code door}.
     * Ahora RECTANGULAR (halfX x halfZ, no siempre cuadrada), con posible RETRANQUEO
     * ({@code setback}: la planta alta mas estrecha que la baja, dejando una terraza), forjados,
     * escalera, tejado (a dos aguas o terraza), ventanas, chimenea y DOS habitaciones de
     * distinto tamano (el tabique se desplaza). Cada casa es distinta.
     */
    public static int buildHouse(World world, int cx, int cz, int floorY, BlockFace door,
            int halfX, int halfZ, int floors, boolean setback, Material wall, Material corner,
            Material roof, Material accent, boolean furniture, int beds, String ownerName) {
        final java.util.Random rng = new java.util.Random();
        final int fh = 5;                          // altura por planta (4 muro + 1 forjado): techos altos
        final int topY = floorY + floors * fh;     // nivel del tejado
        final int topShrink = (setback && floors > 1) ? 1 : 0;   // planta alta mas estrecha
        final int rtx = halfX - topShrink;         // medias de la planta superior (y del tejado)
        final int rtz = halfZ - topShrink;
        final int maxH = Math.max(halfX, halfZ);
        // --- Variedad aleatoria ---
        final Material doorMat = DOORS[rng.nextInt(DOORS.length)];
        final boolean pitched = rng.nextBoolean();     // tejado a dos aguas (true) o terraza (false)
        final int winStyle = rng.nextInt(3);           // 0 cristal, 1 contraventanas, 2 jardineras
        final boolean cornice = rng.nextBoolean();     // cornisa de acento bajo el tejado
        final boolean chimney = rng.nextBoolean() || floors >= 3;
        final int entrance = rng.nextInt(3);           // 0 marquesina, 1 faroles, 2 porche
        final int split = rng.nextInt(3) - 1;          // desplaza el tabique -> habitaciones desiguales
        int n = 0;

        // Cimentacion: nivela el terreno COLUMNA A COLUMNA (relleno coherente en tierra firme,
        // PILOTES en columnas de agua/hielo) y luego pone el suelo firme y despeja el hueco.
        TerrainPlanner.prepare(world, cx - halfX, cz - halfZ, cx + halfX, cz + halfZ, floorY, corner);
        for (int dx = -halfX; dx <= halfX; dx++) {
            for (int dz = -halfZ; dz <= halfZ; dz++) {
                set(world, cx + dx, floorY, cz + dz, Material.STONE_BRICKS);
                n++;
                for (int dy = 1; dy <= floors * fh + maxH + 2; dy++) {
                    set(world, cx + dx, floorY + dy, cz + dz, Material.AIR);
                }
            }
        }

        // Plantas: muros (zocalo + cornisa de acento), forjados y ventanas con estilo.
        for (int fl = 0; fl < floors; fl++) {
            final int shrink = (setback && fl == floors - 1 && floors > 1) ? 1 : 0;
            final int hx = halfX - shrink;
            final int hz = halfZ - shrink;
            final int by = floorY + fl * fh;
            for (int y = by + 1; y <= by + fh - 1; y++) {
                for (int dx = -hx; dx <= hx; dx++) {
                    for (int dz = -hz; dz <= hz; dz++) {
                        if (Math.abs(dx) != hx && Math.abs(dz) != hz) {
                            continue;
                        }
                        final boolean isCorner = Math.abs(dx) == hx && Math.abs(dz) == hz;
                        final boolean band = y == by + 1 || (cornice && y == by + fh - 1);
                        set(world, cx + dx, y, cz + dz, isCorner ? corner : (band ? accent : wall));
                        n++;
                    }
                }
            }
            if (fl < floors - 1) {   // forjado + banda perimetral (muro continuo -> apoyo escalera)
                final int nextShrink = (setback && fl + 1 == floors - 1) ? 1 : 0;
                final int nhx = halfX - nextShrink;
                final int nhz = halfZ - nextShrink;
                for (int dx = -hx; dx <= hx; dx++) {
                    for (int dz = -hz; dz <= hz; dz++) {
                        final boolean edge = Math.abs(dx) == hx || Math.abs(dz) == hz;
                        final boolean corn = Math.abs(dx) == hx && Math.abs(dz) == hz;
                        set(world, cx + dx, by + fh, cz + dz,
                                edge ? (corn ? corner : wall) : Material.SPRUCE_PLANKS);
                    }
                }
                if (nextShrink > 0) {   // terraza: barandilla en el borde que sobresale
                    for (int dx = -hx; dx <= hx; dx++) {
                        for (int dz = -hz; dz <= hz; dz++) {
                            final boolean outer = Math.abs(dx) == hx || Math.abs(dz) == hz;
                            final boolean underUpper = Math.abs(dx) <= nhx && Math.abs(dz) <= nhz;
                            if (outer && !underUpper) {
                                set(world, cx + dx, by + fh + 1, cz + dz, Material.OAK_FENCE);
                            }
                        }
                    }
                }
            }
            windows(world, cx, cz, by, hx, hz, door, winStyle, accent, rng);
        }

        // Puerta de verdad + entrada aleatoria (siempre en la planta baja, tamano completo).
        final int dgx = cx + door.getModX() * halfX;
        final int dgz = cz + door.getModZ() * halfZ;
        set(world, dgx, floorY + 1, dgz, Material.AIR);
        set(world, dgx, floorY + 2, dgz, Material.AIR);
        placeDoor(world, dgx, floorY + 1, dgz, door.getOppositeFace(), doorMat);
        placeStair(world, dgx + door.getModX(), floorY, dgz + door.getModZ(), door);
        entrance(world, dgx, floorY, dgz, door, entrance);

        // Tejado sobre la planta superior (rtx x rtz): a dos aguas (hip) o terraza con barandilla.
        if (pitched) {
            final int maxLayer = Math.min(rtx, rtz);
            for (int layer = 0; layer <= maxLayer; layer++) {
                final int rx = rtx - layer;
                final int rz = rtz - layer;
                for (int dx = -rx; dx <= rx; dx++) {
                    for (int dz = -rz; dz <= rz; dz++) {
                        if (Math.abs(dx) == rx || Math.abs(dz) == rz || layer == maxLayer) {
                            set(world, cx + dx, topY + layer, cz + dz, roof);
                            n++;
                        }
                    }
                }
            }
        } else {
            for (int dx = -rtx; dx <= rtx; dx++) {
                for (int dz = -rtz; dz <= rtz; dz++) {
                    set(world, cx + dx, topY, cz + dz, roof);
                    n++;
                    if (Math.abs(dx) == rtx || Math.abs(dz) == rtz) {
                        set(world, cx + dx, topY + 1, cz + dz, Material.OAK_FENCE);   // barandilla
                    }
                }
            }
            if (floors > 1) {
                set(world, cx - rtx + 1, topY, cz - rtz + 1, Material.AIR);   // salida escalera
                set(world, cx + rtx - 1, topY + 1, cz - rtz + 2, Material.LANTERN);
                set(world, cx, topY + 1, cz, FLOWERS[rng.nextInt(FLOWERS.length)]);
            }
        }

        // Chimenea (posicion y material al azar).
        if (chimney) {
            final int chx = rng.nextBoolean() ? cx + halfX - 1 : cx - halfX + 1;
            final Material brick = rng.nextBoolean() ? Material.BRICKS : Material.COBBLESTONE;
            for (int y = floorY + 1; y <= topY + maxH; y++) {
                set(world, chx, y, cz + halfZ - 1, brick);
            }
            set(world, chx, topY + maxH + 1, cz + halfZ - 1, Material.CAMPFIRE);
        }

        // Estancias. Casa de ALDEANO (beds>=1): habitacion diafana con UNA CAMA POR MIEMBRO
        // contra la pared del fondo + una mesita; siempre transitable. Casa a medida del
        // jugador (beds<0): salon/cocina/dormitorio por planta como antes.
        if (beds >= 4) {
            // Casa de MATRIMONIO: dos habitaciones (delante/detras de un tabique) con dos camas
            // cada una.
            furnishCouple(world, cx, cz, floorY, halfX, halfZ, door);
        } else if (beds >= 1) {
            furnishVillager(world, cx, cz, floorY, halfX, halfZ, door, beds);
        } else if (beds == 0) {
            // Cascaron VACIO (para edificios de oficio: el interior lo pone quien lo llama).
            set(world, cx, floorY + 4, cz, Material.LANTERN);   // solo una luz colgante
        } else {
            for (int fl = 0; fl < floors; fl++) {
                final int shrink = (setback && fl == floors - 1 && floors > 1) ? 1 : 0;
                furnishFloor(world, cx, cz, floorY + fl * fh, halfX - shrink, halfZ - shrink,
                        fl, door, wall, furniture, split);
            }
        }

        // Escalera de mano AL FINAL: columna continua (nada la corta) con salida arriba.
        if (floors > 1) {
            final int lx = cx - rtx + 1;
            final int lz = cz - rtz + 1;
            for (int y = floorY + 1; y <= topY - 1; y++) {
                placeLadder(world, lx, y, lz, BlockFace.SOUTH);
            }
            set(world, lx, topY, lz, Material.AIR);   // hueco de salida a la terraza/desvan
        }

        final int sgx = door.getModX() != 0 ? 0 : 1;
        final int sgz = door.getModZ() != 0 ? 0 : 1;
        placeSign(world.getBlockAt(dgx + sgx + door.getModX(), floorY + 2, dgz + sgz + door.getModZ()),
                door, ownerName);
        return n;
    }

    private static void set(World w, int x, int y, int z, Material m) {
        w.getBlockAt(x, y, z).setType(m, false);
    }

    /** Pequena marquesina, faroles o porche sobre la puerta (variedad de entrada). */
    private static void entrance(World w, int dgx, int floorY, int dgz, BlockFace door, int style) {
        final int ox = door.getModX();
        final int oz = door.getModZ();
        if (style == 0) {          // marquesina: losa sobre la puerta
            set(w, dgx + ox, floorY + 3, dgz + oz, Material.DARK_OAK_SLAB);
        } else if (style == 1) {   // faroles a los lados
            set(w, dgx + oz, floorY + 2, dgz + ox, Material.LANTERN);
            set(w, dgx - oz, floorY + 2, dgz - ox, Material.LANTERN);
        } else {                   // porche: postes de valla y farol
            set(w, dgx + ox + oz, floorY + 1, dgz + oz + ox, Material.OAK_FENCE);
            set(w, dgx + ox - oz, floorY + 1, dgz + oz - ox, Material.OAK_FENCE);
            set(w, dgx + ox, floorY + 3, dgz + oz, Material.LANTERN);
        }
    }

    /**
     * Amueblado de casa de ALDEANO: una sola habitacion diafana (sin tabiques) con UNA CAMA
     * POR MIEMBRO contra la pared del fondo (cabecera al centro, accesible), un farol y un par
     * de enseres junto a la pared de la puerta. Siempre transitable: el aldeano/jugador nunca
     * queda atrapado.
     */
    private static void furnishVillager(World w, int cx, int cz, int floorY, int halfX, int halfZ,
            BlockFace door, int beds) {
        final int by = floorY;
        final int ax = door.getModX();
        final int az = door.getModZ();
        final int px = ax != 0 ? 0 : 1;
        final int pz = az != 0 ? 0 : 1;
        final int depth = (ax != 0 ? halfX : halfZ) - 1;   // interior a lo largo del eje puerta
        final int width = (px != 0 ? halfX : halfZ) - 1;   // interior perpendicular
        set(w, cx, by + 4, cz, Material.LANTERN);           // luz colgante del techo
        final int footX = cx - ax * depth;                  // pared del fondo (opuesta a la puerta)
        final int footZ = cz - az * depth;
        for (final int off : bedOffsets(beds, width)) {
            placeBedAt(w, footX + px * off, by + 1, footZ + pz * off, door);  // cabecera hacia el centro
        }
        final int nx = cx + ax * (depth - 1);               // junto a la pared de la puerta
        final int nz = cz + az * (depth - 1);
        set(w, nx + px * width, by + 1, nz + pz * width, Material.CHEST);
        set(w, nx - px * width, by + 1, nz - pz * width, Material.CRAFTING_TABLE);
    }

    /** Casa de MATRIMONIO: un tabique perpendicular a la puerta divide el interior en DOS
     *  habitaciones (delante y detras), con DOS camas en cada una. El pasillo central (o=0) queda
     *  libre para no atrapar a nadie (los aldeanos protegen su casa: debe ser transitable). */
    private static void furnishCouple(World w, int cx, int cz, int floorY, int halfX, int halfZ,
            BlockFace door) {
        final int by = floorY;
        final int ax = door.getModX();
        final int az = door.getModZ();
        final int px = ax != 0 ? 0 : 1;
        final int pz = az != 0 ? 0 : 1;
        final int depth = (ax != 0 ? halfX : halfZ) - 1;   // mitad interior a lo largo del eje puerta
        final int width = (px != 0 ? halfX : halfZ) - 1;   // mitad interior perpendicular
        // Tabique por el centro (d=0), perpendicular a la puerta, con PASO en o=0.
        for (int o = -width; o <= width; o++) {
            if (o == 0) {
                continue;
            }
            set(w, cx + px * o, by + 1, cz + pz * o, Material.OAK_PLANKS);
            set(w, cx + px * o, by + 2, cz + pz * o, Material.OAK_PLANKS);
        }
        // Habitacion de ATRAS: 2 camas contra el muro del fondo (cabecera hacia el centro).
        final int footX = cx - ax * depth;
        final int footZ = cz - az * depth;
        placeBedAt(w, footX + px, by + 1, footZ + pz, door);
        placeBedAt(w, footX - px, by + 1, footZ - pz, door);
        set(w, cx - ax * depth, by + 4, cz - az * depth, Material.LANTERN);
        // Habitacion de DELANTE: 2 camas contra los muros laterales (cabecera hacia el centro).
        final int fx = cx + ax;
        final int fz = cz + az;
        placeBedAt(w, fx + px * width, by + 1, fz + pz * width, faceFrom(-px, -pz));
        placeBedAt(w, fx - px * width, by + 1, fz - pz * width, faceFrom(px, pz));
        set(w, cx + ax * depth, by + 4, cz + az * depth, Material.LANTERN);
        // Un cofre en una esquina del fondo (fuera del paso y de las camas).
        set(w, footX + px * width, by + 1, footZ + pz * width, Material.CHEST);
    }

    /** Posiciones (eje perpendicular) para repartir {@code beds} camas a lo largo del muro. */
    private static int[] bedOffsets(int beds, int width) {
        final int s = Math.min(2, Math.max(1, width));
        if (beds <= 1) {
            return new int[] {0};
        }
        if (beds == 2) {
            return new int[] {-s, s};
        }
        return new int[] {-s, 0, s};
    }

    /**
     * Divide una planta en DOS habitaciones (de distinto tamano: el tabique se desplaza con
     * {@code split}) con un tabique PERPENDICULAR a la puerta y hueco de paso, y las amuebla.
     * El mobiliario va SIEMPRE contra los muros y nunca rodea una casilla: como los aldeanos
     * protegen su casa (no se puede romper), el interior debe ser SIEMPRE transitable para que
     * el jugador no quede atrapado (p.ej. al levantarse de una cama). El eje se adapta a la
     * orientacion; {@code hx}/{@code hz} son las medias de esta planta (menor si hay retranqueo).
     */
    private static void furnishFloor(World w, int cx, int cz, int by, int hx, int hz, int fl,
            BlockFace door, Material wall, boolean furniture, int split) {
        final int ax = door.getModX();
        final int az = door.getModZ();
        final int px = ax != 0 ? 0 : 1;   // el tabique corre perpendicular a la puerta
        final int pz = az != 0 ? 0 : 1;
        final int innerA = (ax != 0 ? hx : hz) - 1;   // profundidad (eje de la puerta)
        final int innerC = (px != 0 ? hx : hz) - 1;   // ancho del tabique (eje cruzado)
        // Desplaza el tabique para que las habitaciones sean desiguales, sin dejarlas minusculas.
        int sp = split;
        final int lim = Math.max(0, innerA - 2);
        sp = Math.max(-lim, Math.min(lim, sp));

        // Tabique (hasta el techo) con hueco de paso en el centro -> siempre hay salida.
        for (int d = -innerC; d <= innerC; d++) {
            if (d == 0) {
                continue;
            }
            for (int y = by + 1; y <= by + 4; y++) {
                set(w, cx + ax * sp + px * d, y, cz + az * sp + pz * d, wall);
            }
        }

        // Centros de las dos habitaciones: "cerca" (hacia la puerta) y "lejos".
        final int nx = cx + ax * (innerA - 1);
        final int nz = cz + az * (innerA - 1);
        final int fx = cx - ax * (innerA - 1);
        final int fz = cz - az * (innerA - 1);
        set(w, nx, by + 4, nz, Material.LANTERN);   // farol colgante en cada habitacion
        set(w, fx, by + 4, fz, Material.LANTERN);
        if (!furniture) {
            return;
        }

        if (fl == 0) {
            // Salon junto a la puerta: mesa con dos sillas (nada bloquea el paso a la puerta).
            set(w, nx, by + 1, nz, Material.OAK_FENCE);
            set(w, nx, by + 2, nz, Material.OAK_PRESSURE_PLATE);
            placeStairSeat(w, nx + px, by + 1, nz + pz, faceFrom(-px, -pz));
            placeStairSeat(w, nx - px, by + 1, nz - pz, faceFrom(px, pz));
            // Cocina al fondo: electrodomesticos EN FILA contra el muro (no rodean al jugador).
            set(w, fx - ax, by + 1, fz - az, Material.FURNACE);
            set(w, fx - ax + px, by + 1, fz - az + pz, Material.CRAFTING_TABLE);
            set(w, fx - ax - px, by + 1, fz - az - pz, Material.SMOKER);
        } else {
            // Estudio junto a la escalera; dormitorio al fondo con la cama accesible por ambos lados.
            set(w, nx - ax, by + 1, nz - az, Material.BOOKSHELF);
            set(w, nx - ax + px, by + 1, nz - az + pz, Material.CRAFTING_TABLE);
            placeBedAt(w, fx, by + 1, fz, door);          // pie al fondo, cabecera hacia el centro
            set(w, fx - ax - px, by + 1, fz - az - pz, Material.CHEST);   // cofre en la esquina
        }
    }

    private static BlockFace faceFrom(int mx, int mz) {
        if (mx > 0) {
            return BlockFace.EAST;
        }
        if (mx < 0) {
            return BlockFace.WEST;
        }
        return mz > 0 ? BlockFace.SOUTH : BlockFace.NORTH;
    }

    private static void placeStairSeat(World w, int x, int y, int z, BlockFace facing) {
        final Stairs s = (Stairs) Bukkit.createBlockData(Material.OAK_STAIRS);
        s.setFacing(facing);
        w.getBlockAt(x, y, z).setBlockData(s, false);
    }

    private static void placeBedAt(World w, int x, int y, int z, BlockFace facing) {
        final Bed foot = (Bed) Bukkit.createBlockData(Material.RED_BED);
        foot.setPart(Bed.Part.FOOT);
        foot.setFacing(facing);
        final Bed head = (Bed) Bukkit.createBlockData(Material.RED_BED);
        head.setPart(Bed.Part.HEAD);
        head.setFacing(facing);
        w.getBlockAt(x, y, z).setBlockData(foot, false);
        w.getBlockAt(x + facing.getModX(), y, z + facing.getModZ()).setBlockData(head, false);
    }

    private static void windows(World w, int cx, int cz, int by, int hx, int hz, BlockFace door,
            int style, Material accent, java.util.Random rng) {
        final int y = by + 2;
        for (final BlockFace s : new BlockFace[] {BlockFace.NORTH, BlockFace.SOUTH,
                BlockFace.EAST, BlockFace.WEST}) {
            if (s == door) {
                continue;
            }
            final int ox = s.getModX() != 0 ? 0 : 1;   // a lo largo del muro
            final int oz = s.getModZ() != 0 ? 0 : 1;
            final int wallHalf = s.getModX() != 0 ? hz : hx;   // media a lo largo de este muro
            final int base = wallHalf >= 3 ? 1 : 0;     // fachadas grandes: varias ventanas
            for (int k = -base; k <= base; k++) {
                final int wx = cx + s.getModX() * hx + ox * k;
                final int wz = cz + s.getModZ() * hz + oz * k;
                set(w, wx, y, wz, style == 0 ? Material.GLASS : Material.GLASS_PANE);
                if (style == 1) {   // contraventanas de acento a los lados
                    set(w, wx + ox, y, wz + oz, accent);
                    set(w, wx - ox, y, wz - oz, accent);
                } else if (style == 2) {   // jardinera con flor bajo la ventana
                    set(w, wx + s.getModX(), y - 1, wz + s.getModZ(), Material.SPRUCE_TRAPDOOR);
                    set(w, wx, y - 1, wz, FLOWERS[rng.nextInt(FLOWERS.length)]);
                }
            }
        }
    }

    private static void placeDoor(World w, int x, int y, int z, BlockFace facing, Material mat) {
        final Door lower = (Door) Bukkit.createBlockData(mat);
        lower.setFacing(facing);
        lower.setHalf(Bisected.Half.BOTTOM);
        final Door upper = (Door) Bukkit.createBlockData(mat);
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

    private static void placeSign(org.bukkit.block.Block block, BlockFace facing, String ownerName) {
        block.setType(Material.OAK_WALL_SIGN, false);
        if (block.getBlockData() instanceof Directional dir) {
            dir.setFacing(facing);
            block.setBlockData(dir, false);
        }
        if (block.getState() instanceof Sign sign) {
            sign.getSide(Side.FRONT).line(1, Component.text("Casa de"));
            if (ownerName.contains(" y ")) {
                // Matrimonio: "Nombre1 Ap1 y Nombre2 Ap2" -> los DOS nombres de pila, en dos lineas
                // (antes solo salia uno porque se cortaba en el primer espacio).
                final String[] pair = ownerName.split(" y ", 2);
                sign.getSide(Side.FRONT).line(2, Component.text("§6" + given(pair[0]) + " y"));
                sign.getSide(Side.FRONT).line(3, Component.text("§6" + given(pair[1])));
            } else {
                // Soltero: solo el nombre de pila (el apellido no cabe en una linea de cartel).
                sign.getSide(Side.FRONT).line(2, Component.text("§6" + given(ownerName)));
            }
            sign.update(true);
        }
    }

    /** Nombre de pila (primer token) de un "Nombre Apellido". */
    private static String given(String full) {
        final String t = full.trim();
        final int sp = t.indexOf(' ');
        return sp > 0 ? t.substring(0, sp) : t;
    }

    /** Reescribe el cartel de una casa ya construida (para marcarla EN VENTA o con nuevo dueno).
     *  Busca el unico cartel en la huella de la casa y le pone las dos lineas. Devuelve true si lo
     *  encontro. Se usa cuando una casa cambia de estado sin reconstruirla. */
    public static boolean setHouseSign(World world, int cx, int cz, int floorY, int floors,
            String l1, String l2) {
        for (int dx = -5; dx <= 5; dx++) {
            for (int dz = -5; dz <= 5; dz++) {
                for (int y = floorY; y <= floorY + floors * 6 + 3; y++) {
                    if (world.getBlockAt(cx + dx, y, cz + dz).getState() instanceof Sign sign) {
                        sign.getSide(Side.FRONT).line(1, Component.text(l1));
                        sign.getSide(Side.FRONT).line(2, Component.text(l2));
                        sign.update(true);
                        return true;
                    }
                }
            }
        }
        return false;
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
