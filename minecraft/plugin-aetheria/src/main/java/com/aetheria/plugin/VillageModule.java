package com.aetheria.plugin;

import java.util.concurrent.ThreadLocalRandom;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Sign;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.type.Bed;
import org.bukkit.block.sign.Side;

import net.kyori.adventure.text.Component;

/**
 * Fase 7 (aldea fisica) - Construye una ALDEA REAL cerca del spawn del main: casas con
 * puerta, ventanas, cama y cartel; puestos de trabajo por oficio (granja con compostador,
 * puesto de guardia con campana); y una plaza con pozo. Todo lo levanta el PLUGIN de forma
 * determinista (nunca el LLM), y es idempotente: al reconstruir se sobrescriben las mismas
 * posiciones.
 *
 * <p>Ademas define los waypoints (casa/trabajo/plaza) que la rutina de NPC usa, de modo que
 * los vecinos viven y trabajan en edificios de verdad, no en puntos invisibles.
 */
public final class VillageModule {

    private final AetheriaPlugin plugin;
    private final World world;

    // Waypoints resultantes (centro de cada edificio, a nivel de pie), para la rutina.
    private Location naraHome;
    private Location naraWork;
    private Location polHome;
    private Location polWork;
    private Location mercaderHome;
    private Location mercaderWork;
    private Location plaza;
    private Location tavern;
    private int sx;
    private int sz;
    private int baseY;

    public VillageModule(AetheriaPlugin plugin, World world) {
        this.plugin = plugin;
        this.world = world;
    }

    /** Prepara el spawn del mundo main: busca un punto de bioma normal Y terreno LLANO en la huella
     *  del pueblo (spawn + franja hasta la plaza, al sur), y deja el spawn EXACTAMENTE A RAS DE
     *  SUELO. Asi la plaza y el portal (que se apoyan en {@code spawn.y - 1}) quedan a ras del
     *  terreno y no sobre un pedestal. Corre antes de construir portal y aldea. */
    public static void relocateSpawnToGoodBiome(AetheriaPlugin plugin, World world) {
        // IDEMPOTENTE: el spawn se prepara UNA SOLA VEZ (primer arranque del mundo). En reinicios
        // posteriores NO se toca; si no, cada reinicio podria moverlo a otro sitio y dejar plaza,
        // taberna y portal DUPLICADOS (los viejos huerfanos, con el portal sin cablear). El marcador
        // es village.txt (lo crea SettlementModule al fundar el pueblo); al reiniciar el mundo se
        // borra junto al resto de estado y el spawn se vuelve a preparar.
        if (new java.io.File(plugin.getDataFolder(), "village.txt").exists()) {
            return;
        }
        final org.bukkit.Location sp = world.getSpawnLocation();
        final int sx0 = sp.getBlockX();
        final int sz0 = sp.getBlockZ();
        int[] best = null;          // {x, z, baseY, spread}
        // Espiral: primero muy cerca (anillos de 8), luego lejos (de 100 en 100) si hace falta.
        final int[] steps = {0, 8, 16, 24, 32, 40, 48, 64, 100, 200, 300, 500, 800, 1200};
        for (final int r : steps) {
            final int puntos = r == 0 ? 1 : 8;
            for (int a = 0; a < puntos; a++) {
                final double ang = a * Math.PI / 4;
                final int x = sx0 + (int) Math.round(Math.cos(ang) * r);
                final int z = sz0 + (int) Math.round(Math.sin(ang) * r);
                final int[] eval = evalSpawnFootprint(world, x, z);   // {baseY, spread} o null
                if (eval == null) {
                    continue;
                }
                if (eval[1] <= 2) {                                   // llano y bioma normal: perfecto
                    setSpawnFlush(plugin, world, x, z, eval[0]);
                    return;
                }
                if (best == null || eval[1] < best[3]) {
                    best = new int[] {x, z, eval[0], eval[1]};
                }
            }
        }
        if (best != null) {
            setSpawnFlush(plugin, world, best[0], best[1], best[2]);
        } else {
            plugin.getLogger().info("[Aetheria] No encontre terreno normal y llano; el pueblo se "
                    + "queda donde esta.");
        }
    }

    private static void setSpawnFlush(AetheriaPlugin plugin, World world, int x, int z, int baseY) {
        world.setSpawnLocation(x, baseY + 1, z);   // a ras: baseY = suelo, jugador en baseY+1
        plugin.getLogger().info("[Aetheria] Spawn preparado (bioma normal, llano, a ras de suelo) en "
                + x + "," + (baseY + 1) + "," + z + ".");
    }

    /** Evalua la huella del pueblo (spawn + franja hasta la plaza, ~±6 x, de -2 a +26 en z) en un
     *  muestreo disperso: {cota base (minimo del suelo firme), desnivel} si TODO es bioma normal y
     *  terreno firme (sin agua/hielo); null si algo no cumple. */
    private static int[] evalSpawnFootprint(World world, int cx, int cz) {
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        for (int dx = -6; dx <= 6; dx += 3) {
            for (int dz = -2; dz <= 26; dz += 4) {
                final int x = cx + dx;
                final int z = cz + dz;
                final int gy = TerrainPlanner.groundY(world, x, z);
                if (!goodBiome(world.getBiome(x, gy, z))) {
                    return null;
                }
                if (TerrainPlanner.isLiquidOrIce(world, x, z)) {
                    return null;   // nada de agua/hielo en la huella del pueblo
                }
                min = Math.min(min, gy);
                max = Math.max(max, gy);
            }
        }
        return new int[] {min, max - min};
    }

    private static boolean goodBiome(org.bukkit.block.Biome biome) {
        final String n = biome.getKey().getKey();   // p.ej. "plains", "snowy_taiga", "desert"
        return !(n.contains("ocean") || n.contains("river") || n.contains("beach")
                || n.contains("frozen") || n.contains("snowy") || n.contains("ice")
                || n.contains("cold") || n.contains("desert") || n.contains("badlands")
                || n.contains("mushroom") || n.contains("deep") || n.contains("swamp"));
    }

    public Location naraHome() { return naraHome.clone(); }
    public Location naraWork() { return naraWork.clone(); }
    public Location polHome() { return polHome.clone(); }
    public Location polWork() { return polWork.clone(); }
    public Location mercaderHome() { return mercaderHome.clone(); }
    public Location mercaderWork() { return mercaderWork.clone(); }
    public Location plaza() { return plaza.clone(); }
    public Location tavern() { return tavern.clone(); }
    public int baseY() { return baseY; }
    public int spawnX() { return sx; }
    public int spawnZ() { return sz; }

    /** Centro de la casa nº i de expansion (rejilla de filas al sur del pueblo, 16 aparte). */
    public Location expansionSlot(int i) {
        final int perRow = 5;
        final int col = i % perRow;
        final int row = i / perRow;
        final int x = sx + (col - perRow / 2) * 16;
        final int z = sz + 44 + row * 16;
        return new Location(world, x, baseY, z);
    }

    /** Levanta la aldea al sur del spawn (detras del portal), en fila mirando al sur. */
    public void build() {
        final Location spawn = world.getSpawnLocation();
        final int sx = spawn.getBlockX();
        final int sz = spawn.getBlockZ();
        // Cota FIJA (nivel del spawn): estable entre reinicios. Usar getHighestBlockYAt haria
        // que la aldea "trepara" en cada arranque (el tejado anterior pasa a ser lo mas alto).
        final int baseY = spawn.getBlockY() - 1;
        this.sx = sx;
        this.sz = sz;
        this.baseY = baseY;

        // ARRANQUE MINIMO: solo una plaza con pozo como centro del pueblo. Todo lo demas
        // (casas, aldeanos, puestos de trabajo, mejoras civicas) lo hace crecer solo el
        // sistema de mundo vivo (SettlementModule), partiendo de dos aldeanos fundadores.
        clearArea(sx - 6, sx + 6, sz + 14, sz + 26, baseY + 1, baseY + 6);
        this.plaza = buildPlaza(sx, sz + 20, baseY);

        plugin.getLogger().info("[Aetheria] Centro del pueblo (plaza) listo; el pueblo crece solo.");
    }

    /** Camino de grava en linea recta a lo largo de Z (a un x fijo), sobre el suelo del pueblo. */
    private void path(int x, int floorY, int z0, int z1) {
        for (int z = z0; z <= z1; z++) {
            if (world.getBlockAt(x, floorY + 1, z).getType().isAir()) {
                set(x, floorY, z, Material.GRAVEL);
            }
        }
    }

    /** Camino de grava en linea recta a lo largo de X (a un z fijo). */
    private void path2(int cx, int floorY, int z, int dx0, int dx1) {
        for (int dx = dx0; dx <= dx1; dx++) {
            if (world.getBlockAt(cx + dx, floorY + 1, z).getType().isAir()) {
                set(cx + dx, floorY, z, Material.GRAVEL);
            }
        }
    }

    private void clearArea(int x0, int x1, int z0, int z1, int y0, int y1) {
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                for (int y = y0; y <= y1; y++) {
                    final Block b = world.getBlockAt(x, y, z);
                    if (!b.getType().isAir()) {
                        b.setType(Material.AIR, false);
                    }
                }
            }
        }
    }

    // ---------------- Edificios ----------------

    private Location buildHouse(int cx, int cz, int floorY, String name) {
        final int half = 2;
        foundation(cx, cz, half, floorY, Material.STONE_BRICKS, 5);

        // Muros (esquinas de tronco, paredes de tablon) de 3 de alto.
        for (int y = floorY + 1; y <= floorY + 3; y++) {
            for (int dx = -half; dx <= half; dx++) {
                for (int dz = -half; dz <= half; dz++) {
                    final boolean perimeter = Math.abs(dx) == half || Math.abs(dz) == half;
                    if (!perimeter) {
                        continue;
                    }
                    final boolean corner = Math.abs(dx) == half && Math.abs(dz) == half;
                    set(cx + dx, y, cz + dz, corner ? Material.SPRUCE_LOG : Material.OAK_PLANKS);
                }
            }
        }
        // Puerta abierta al frente (sur, +Z): hueco de 1x2.
        set(cx, floorY + 1, cz + half, Material.AIR);
        set(cx, floorY + 2, cz + half, Material.AIR);
        // Ventanas de cristal en los otros tres lados.
        set(cx - half, floorY + 2, cz, Material.GLASS_PANE);
        set(cx + half, floorY + 2, cz, Material.GLASS_PANE);
        set(cx, floorY + 2, cz - half, Material.GLASS_PANE);

        // Tejado solido con alero (7x7) un bloque por encima de los muros.
        for (int dx = -half - 1; dx <= half + 1; dx++) {
            for (int dz = -half - 1; dz <= half + 1; dz++) {
                set(cx + dx, floorY + 4, cz + dz, Material.DARK_OAK_PLANKS);
            }
        }

        // Interior: farol y cama.
        set(cx + 1, floorY + 1, cz - 1, Material.LANTERN);
        placeBed(cx - 1, floorY + 1, cz - 1, BlockFace.EAST);

        // Cartel con el nombre en la fachada, junto a la puerta.
        placeWallSign(cx + 1, floorY + 2, cz + half + 1, BlockFace.SOUTH, "Casa de", "§6" + name);

        return new Location(world, cx + 0.5, floorY + 1, cz + 0.5);
    }

    private Location buildFarm(int cx, int cz, int floorY) {
        foundation(cx, cz, 2, floorY, Material.DIRT, 4);
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                if (dx == 0 && dz == 0) {
                    set(cx, floorY, cz, Material.WATER);   // riego central
                    continue;
                }
                set(cx + dx, floorY, cz + dz, Material.FARMLAND);
                final Ageable wheat = (Ageable) Bukkit.createBlockData(Material.WHEAT);
                wheat.setAge(ThreadLocalRandom.current().nextInt(3, wheat.getMaximumAge() + 1));
                setData(cx + dx, floorY + 1, cz + dz, wheat);
            }
        }
        // Compostador (puesto del granjero) y farol en una esquina de trabajo.
        set(cx + 3, floorY, cz + 2, Material.COARSE_DIRT);
        set(cx + 3, floorY + 1, cz + 2, Material.COMPOSTER);
        set(cx - 3, floorY, cz - 2, Material.OAK_FENCE);
        set(cx - 3, floorY + 1, cz - 2, Material.LANTERN);

        return new Location(world, cx + 3 + 0.5, floorY + 1, cz + 2 + 0.5);
    }

    private Location buildGuardPost(int cx, int cz, int floorY) {
        foundation(cx, cz, 1, floorY, Material.STONE_BRICKS, 5);
        // Cuatro pilares de muro con tejado y campana en el centro.
        for (int dx = -1; dx <= 1; dx += 2) {
            for (int dz = -1; dz <= 1; dz += 2) {
                for (int y = floorY + 1; y <= floorY + 3; y++) {
                    set(cx + dx, y, cz + dz, Material.STONE_BRICK_WALL);
                }
            }
        }
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                set(cx + dx, floorY + 4, cz + dz, Material.STONE_BRICKS);
            }
        }
        set(cx, floorY + 1, cz, Material.BELL);            // campana del guardia
        set(cx + 1, floorY + 4, cz + 1, Material.LANTERN); // luz

        return new Location(world, cx + 0.5, floorY + 1, cz - 2 + 0.5);
    }

    /** Construye una plaza (pozo + campana) en CUALQUIER sitio, sobre el terreno. Para fundar
     *  aldeas nuevas lejos del spawn. Devuelve el centro. */
    public Location buildPlazaAt(int cx, int cz) {
        final int by = world.getHighestBlockYAt(cx, cz) - 1;
        clearArea(cx - 6, cx + 6, cz - 6, cz + 6, by + 1, by + 7);
        return buildPlaza(cx, cz, by);
    }

    Location buildPlaza(int cx, int cz, int floorY) {
        foundation(cx, cz, 3, floorY, Material.STONE_BRICKS, 5);
        // Pozo central: anillo de piedra con agua, postes y tejadillo.
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                set(cx + dx, floorY, cz + dz, Material.COBBLESTONE);
            }
        }
        set(cx, floorY, cz, Material.WATER);
        for (int dx = -1; dx <= 1; dx += 2) {
            for (int dz = -1; dz <= 1; dz += 2) {
                set(cx + dx, floorY + 1, cz + dz, Material.OAK_FENCE);
                set(cx + dx, floorY + 2, cz + dz, Material.OAK_FENCE);
            }
        }
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                set(cx + dx, floorY + 3, cz + dz, Material.COBBLESTONE_SLAB);
            }
        }
        // Faroles en las esquinas de la plaza.
        for (int dx = -3; dx <= 3; dx += 6) {
            for (int dz = -3; dz <= 3; dz += 6) {
                set(cx + dx, floorY + 1, cz + dz, Material.SEA_LANTERN);
            }
        }
        // Campana del pueblo (la tipica de las aldeas de Minecraft), sobre un soporte de piedra
        // al borde norte de la plaza (hacia el spawn/portal).
        set(cx, floorY + 1, cz - 3, Material.COBBLESTONE_WALL);
        set(cx, floorY + 2, cz - 3, Material.COBBLESTONE_WALL);
        set(cx, floorY + 3, cz - 3, Material.BELL);
        // Taberna del pueblo, al este de la plaza (donde los vecinos se reunen al atardecer). A la
        // misma cota que la plaza; se apoya a ras (su cimiento nivela). El punto de reunion del
        // pueblo (townCenter) queda en cx+3, asi que la taberna cae en townCenter + (8, 0).
        this.tavern = buildTavern(cx + 11, cz, floorY);
        return new Location(world, cx + 3 + 0.5, floorY + 1, cz + 0.5);   // punto de reunion al lado
    }

    /** Plaza-mercado con puestos (toldos de colores) y barriles de genero. */
    private Location buildMarket(int cx, int cz, int floorY) {
        foundation(cx, cz, 3, floorY, Material.SMOOTH_STONE, 4);
        final Material[] toldo = {Material.RED_WOOL, Material.BLUE_WOOL, Material.YELLOW_WOOL,
                Material.LIME_WOOL};
        final int[][] spots = {{-2, -2}, {2, -2}, {-2, 2}, {2, 2}};
        for (int i = 0; i < spots.length; i++) {
            final int ox = spots[i][0];
            final int oz = spots[i][1];
            set(cx + ox - 1, floorY + 1, cz + oz, Material.OAK_FENCE);
            set(cx + ox - 1, floorY + 2, cz + oz, Material.OAK_FENCE);
            set(cx + ox + 1, floorY + 1, cz + oz, Material.OAK_FENCE);
            set(cx + ox + 1, floorY + 2, cz + oz, Material.OAK_FENCE);
            for (int a = -1; a <= 1; a++) {
                set(cx + ox + a, floorY + 3, cz + oz, toldo[i]);   // toldo
            }
            set(cx + ox, floorY + 1, cz + oz, Material.BARREL);     // genero
        }
        // Iluminacion en las esquinas de la plaza-mercado.
        for (int dx = -3; dx <= 3; dx += 6) {
            for (int dz = -3; dz <= 3; dz += 6) {
                set(cx + dx, floorY + 1, cz + dz, Material.SEA_LANTERN);
            }
        }
        placeWallSign(cx + 1, floorY + 2, cz - 3, BlockFace.NORTH, "§6Mercado", "de Aetheria");
        return new Location(world, cx + 0.5, floorY + 1, cz + 0.5);
    }

    /** Taberna: casa de madera con barra, barriles y mesas. Devuelve su centro. */
    private Location buildTavern(int cx, int cz, int floorY) {
        final int half = 2;
        foundation(cx, cz, half, floorY, Material.SPRUCE_PLANKS, 5);
        for (int y = floorY + 1; y <= floorY + 3; y++) {
            for (int dx = -half; dx <= half; dx++) {
                for (int dz = -half; dz <= half; dz++) {
                    if (Math.abs(dx) != half && Math.abs(dz) != half) {
                        continue;
                    }
                    final boolean corner = Math.abs(dx) == half && Math.abs(dz) == half;
                    set(cx + dx, y, cz + dz, corner ? Material.SPRUCE_LOG : Material.SPRUCE_PLANKS);
                }
            }
        }
        set(cx, floorY + 1, cz + half, Material.AIR);
        set(cx, floorY + 2, cz + half, Material.AIR);
        set(cx - half, floorY + 2, cz, Material.GLASS_PANE);
        set(cx + half, floorY + 2, cz, Material.GLASS_PANE);
        for (int dx = -half - 1; dx <= half + 1; dx++) {
            for (int dz = -half - 1; dz <= half + 1; dz++) {
                set(cx + dx, floorY + 4, cz + dz, Material.DARK_OAK_PLANKS);
            }
        }
        // Barra y barriles al fondo; una mesa y un farol.
        set(cx - 1, floorY + 1, cz - 1, Material.BARREL);
        set(cx + 1, floorY + 1, cz - 1, Material.BARREL);
        set(cx, floorY + 1, cz - 1, Material.BREWING_STAND);
        set(cx - 1, floorY + 1, cz + 1, Material.OAK_FENCE);       // mesa
        set(cx - 1, floorY + 2, cz + 1, Material.OAK_PRESSURE_PLATE);
        set(cx + 1, floorY + 1, cz + 1, Material.LANTERN);
        placeWallSign(cx + 1, floorY + 2, cz + half + 1, BlockFace.SOUTH, "§6La", "§6Taberna");
        return new Location(world, cx + 0.5, floorY + 1, cz + 0.5);
    }

    // ---------------- Utilidades ----------------

    /** Aplana un cuadrado: suelo firme, aire encima y relleno de tierra debajo si flota. */
    private void foundation(int cx, int cz, int half, int floorY, Material floor, int clearHeight) {
        for (int dx = -half; dx <= half; dx++) {
            for (int dz = -half; dz <= half; dz++) {
                set(cx + dx, floorY, cz + dz, floor);
                for (int y = 1; y <= clearHeight; y++) {
                    set(cx + dx, floorY + y, cz + dz, Material.AIR);
                }
                for (int y = floorY - 1; y >= floorY - 8; y--) {
                    final Block b = world.getBlockAt(cx + dx, y, cz + dz);
                    if (b.getType().isAir() || b.isLiquid()) {
                        b.setType(Material.DIRT, false);
                    } else {
                        break;
                    }
                }
            }
        }
    }

    private void placeBed(int x, int y, int z, BlockFace facing) {
        final Bed foot = (Bed) Bukkit.createBlockData(Material.RED_BED);
        foot.setPart(Bed.Part.FOOT);
        foot.setFacing(facing);
        final Bed head = (Bed) Bukkit.createBlockData(Material.RED_BED);
        head.setPart(Bed.Part.HEAD);
        head.setFacing(facing);
        world.getBlockAt(x, y, z).setBlockData(foot, false);
        world.getBlockAt(x, y, z).getRelative(facing).setBlockData(head, false);
    }

    private void placeWallSign(int x, int y, int z, BlockFace facing, String l0, String l1) {
        final Block block = world.getBlockAt(x, y, z);
        block.setType(Material.OAK_WALL_SIGN, false);
        if (block.getBlockData() instanceof Directional dir) {
            dir.setFacing(facing);
            block.setBlockData(dir, false);
        }
        if (block.getState() instanceof Sign sign) {
            final var front = sign.getSide(Side.FRONT);
            front.line(1, Component.text(l0));
            front.line(2, Component.text(l1));
            sign.update(true);
        }
    }

    private void set(int x, int y, int z, Material m) {
        world.getBlockAt(x, y, z).setType(m, false);
    }

    private void setData(int x, int y, int z, org.bukkit.block.data.BlockData data) {
        world.getBlockAt(x, y, z).setBlockData(data, false);
    }
}
