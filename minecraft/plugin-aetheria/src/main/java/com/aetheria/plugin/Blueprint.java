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
                    Material.BRICKS, true, player.getName());
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
                cx + half + 1, floorY + floors * 4 + half + 2, cz + half + 1};
    }

    private static final Material[] FLOWERS = {Material.POPPY, Material.DANDELION,
            Material.BLUE_ORCHID, Material.ALLIUM, Material.OXEYE_DAISY, Material.CORNFLOWER};
    private static final Material[] DOORS = {Material.OAK_DOOR, Material.SPRUCE_DOOR,
            Material.DARK_OAK_DOOR, Material.BIRCH_DOOR, Material.ACACIA_DOOR};

    /**
     * Construye una CASA a medida en (cx,cz) con la planta baja en floorY y la puerta en el
     * lado {@code door}. Multiplanta y ANCHA segun `half`, con forjados, escalera, tejado
     * (terraza o a dos aguas), puerta y ventanas de verdad, chimenea y mobiliario. Cada casa
     * es DISTINTA: se eligen al azar el tejado, la puerta, las ventanas, la entrada y detalles.
     */
    public static int buildHouse(World world, int cx, int cz, int floorY, BlockFace door,
            int half, int floors, Material wall, Material corner, Material roof, Material accent,
            boolean furniture, String ownerName) {
        final java.util.Random rng = new java.util.Random();
        final int fh = 4;                          // altura por planta (3 muro + 1 forjado)
        final int topY = floorY + floors * fh;     // nivel del tejado
        // --- Variedad aleatoria ---
        final Material doorMat = DOORS[rng.nextInt(DOORS.length)];
        final boolean pitched = rng.nextBoolean();     // tejado a dos aguas (true) o terraza (false)
        final int winStyle = rng.nextInt(3);           // 0 cristal, 1 contraventanas, 2 jardineras
        final boolean cornice = rng.nextBoolean();     // cornisa de acento bajo el tejado
        final boolean chimney = rng.nextBoolean() || floors >= 3;
        final int entrance = rng.nextInt(3);           // 0 marquesina, 1 faroles, 2 porche
        int n = 0;

        // Cimentacion: suelo firme, hueco despejado y relleno inferior para que no flote.
        for (int dx = -half; dx <= half; dx++) {
            for (int dz = -half; dz <= half; dz++) {
                set(world, cx + dx, floorY, cz + dz, Material.STONE_BRICKS);
                n++;
                for (int dy = 1; dy <= floors * fh + half + 2; dy++) {
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

        // Plantas: muros (zocalo + cornisa de acento), forjados y ventanas con estilo.
        for (int fl = 0; fl < floors; fl++) {
            final int by = floorY + fl * fh;
            for (int y = by + 1; y <= by + fh - 1; y++) {
                for (int dx = -half; dx <= half; dx++) {
                    for (int dz = -half; dz <= half; dz++) {
                        if (Math.abs(dx) != half && Math.abs(dz) != half) {
                            continue;
                        }
                        final boolean isCorner = Math.abs(dx) == half && Math.abs(dz) == half;
                        final boolean band = y == by + 1 || (cornice && y == by + fh - 1);
                        set(world, cx + dx, y, cz + dz, isCorner ? corner : (band ? accent : wall));
                        n++;
                    }
                }
            }
            if (fl < floors - 1) {   // forjado + banda perimetral (muro continuo -> apoyo escalera)
                for (int dx = -half; dx <= half; dx++) {
                    for (int dz = -half; dz <= half; dz++) {
                        final boolean edge = Math.abs(dx) == half || Math.abs(dz) == half;
                        final boolean corn = Math.abs(dx) == half && Math.abs(dz) == half;
                        set(world, cx + dx, by + fh, cz + dz,
                                edge ? (corn ? corner : wall) : Material.SPRUCE_PLANKS);
                    }
                }
            }
            windows(world, cx, cz, by, half, door, winStyle, accent, rng);
        }

        // Puerta de verdad + entrada aleatoria.
        final int dgx = cx + door.getModX() * half;
        final int dgz = cz + door.getModZ() * half;
        set(world, dgx, floorY + 1, dgz, Material.AIR);
        set(world, dgx, floorY + 2, dgz, Material.AIR);
        placeDoor(world, dgx, floorY + 1, dgz, door.getOppositeFace(), doorMat);
        placeStair(world, dgx + door.getModX(), floorY, dgz + door.getModZ(), door);
        entrance(world, dgx, floorY, dgz, door, entrance);

        // Tejado: a dos aguas (escalonado) o terraza con barandilla.
        if (pitched) {
            for (int layer = 0; layer <= half; layer++) {
                final int r = half - layer;
                for (int dx = -r; dx <= r; dx++) {
                    for (int dz = -r; dz <= r; dz++) {
                        if (Math.max(Math.abs(dx), Math.abs(dz)) == r || layer == half) {
                            set(world, cx + dx, topY + layer, cz + dz, roof);
                            n++;
                        }
                    }
                }
            }
        } else {
            for (int dx = -half; dx <= half; dx++) {
                for (int dz = -half; dz <= half; dz++) {
                    set(world, cx + dx, topY, cz + dz, roof);
                    n++;
                    if (Math.abs(dx) == half || Math.abs(dz) == half) {
                        set(world, cx + dx, topY + 1, cz + dz, Material.OAK_FENCE);   // barandilla
                    }
                }
            }
            if (floors > 1) {
                set(world, cx - half + 1, topY, cz - half + 1, Material.AIR);   // salida escalera
                set(world, cx + half - 1, topY + 1, cz - half + 2, Material.LANTERN);
                set(world, cx, topY + 1, cz, FLOWERS[rng.nextInt(FLOWERS.length)]);
            }
        }

        // Chimenea (posicion y material al azar).
        if (chimney) {
            final int chx = rng.nextBoolean() ? cx + half - 1 : cx - half + 1;
            final Material brick = rng.nextBoolean() ? Material.BRICKS : Material.COBBLESTONE;
            for (int y = floorY + 1; y <= topY + half; y++) {
                set(world, chx, y, cz + half - 1, brick);
            }
            set(world, chx, topY + half + 1, cz + half - 1, Material.CAMPFIRE);
        }

        // Estancias: tabiques con puerta y mobiliario por habitacion, planta a planta.
        for (int fl = 0; fl < floors; fl++) {
            furnishFloor(world, cx, cz, floorY + fl * fh, half, fl, door, wall, furniture);
        }

        // Escalera de mano AL FINAL: columna continua (nada la corta) con salida arriba.
        if (floors > 1) {
            final int lx = cx - half + 1;
            final int lz = cz - half + 1;
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
     * Divide una planta en DOS habitaciones con un tabique PERPENDICULAR a la puerta (con
     * hueco de paso en el centro) y las amuebla: planta baja = salon (junto a la puerta) +
     * cocina; plantas altas = estudio + dormitorio. El eje se adapta a la orientacion.
     */
    private static void furnishFloor(World w, int cx, int cz, int by, int half, int fl,
            BlockFace door, Material wall, boolean furniture) {
        final int inner = half - 1;
        final int ax = door.getModX();
        final int az = door.getModZ();
        final int px = ax != 0 ? 0 : 1;   // el tabique corre perpendicular a la puerta
        final int pz = az != 0 ? 0 : 1;

        // Tabique con hueco de paso en el centro.
        for (int d = -inner; d <= inner; d++) {
            if (d == 0) {
                continue;
            }
            for (int y = by + 1; y <= by + 3; y++) {
                set(w, cx + px * d, y, cz + pz * d, wall);
            }
        }

        // Centros de las dos habitaciones: "cerca" (hacia la puerta) y "lejos".
        final int nx = cx + ax * (inner - 1);
        final int nz = cz + az * (inner - 1);
        final int fx = cx - ax * (inner - 1);
        final int fz = cz - az * (inner - 1);
        set(w, nx, by + 3, nz, Material.LANTERN);   // farol colgante en cada habitacion
        set(w, fx, by + 3, fz, Material.LANTERN);
        if (!furniture) {
            return;
        }

        if (fl == 0) {
            // Salon junto a la puerta: mesa con dos sillas y una libreria.
            set(w, nx, by + 1, nz, Material.OAK_FENCE);
            set(w, nx, by + 2, nz, Material.OAK_PRESSURE_PLATE);
            placeStairSeat(w, nx + px, by + 1, nz + pz, faceFrom(-px, -pz));
            placeStairSeat(w, nx - px, by + 1, nz - pz, faceFrom(px, pz));
            set(w, nx + px, by + 1, nz + pz + 0, Material.BOOKSHELF);
            // Cocina al fondo.
            set(w, fx + px, by + 1, fz + pz, Material.CRAFTING_TABLE);
            set(w, fx, by + 1, fz, Material.FURNACE);
            set(w, fx - px, by + 1, fz - pz, Material.SMOKER);
            set(w, fx + px, by + 1, fz - pz, Material.BARREL);
        } else {
            // Estudio junto a la escalera; dormitorio al fondo.
            set(w, nx + px, by + 1, nz + pz, Material.BOOKSHELF);
            set(w, nx - px, by + 1, nz - pz, Material.CRAFTING_TABLE);
            placeBedAt(w, fx, by + 1, fz, door);          // cabecera hacia el centro
            set(w, fx + px, by + 1, fz + pz, Material.CHEST);
            set(w, fx - px, by + 1, fz - pz, Material.CHEST);
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

    private static void windows(World w, int cx, int cz, int by, int half, BlockFace door,
            int style, Material accent, java.util.Random rng) {
        final int y = by + 2;
        for (final BlockFace s : new BlockFace[] {BlockFace.NORTH, BlockFace.SOUTH,
                BlockFace.EAST, BlockFace.WEST}) {
            if (s == door) {
                continue;
            }
            final int ox = s.getModX() != 0 ? 0 : 1;   // a lo largo del muro
            final int oz = s.getModZ() != 0 ? 0 : 1;
            final int base = half >= 3 ? 1 : 0;         // fachadas grandes: varias ventanas
            for (int k = -base; k <= base; k++) {
                final int wx = cx + s.getModX() * half + ox * k;
                final int wz = cz + s.getModZ() * half + oz * k;
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
            sign.getSide(Side.FRONT).line(2, Component.text("§6" + ownerName));
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
