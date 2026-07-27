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

    /** Nivela y despeja un solar a la cota dada: talla el terreno natural (y arboles) que sobresale,
     *  rellena los huecos por debajo y deja cesped. Para que un edificio quede A RAS y con el acceso
     *  despejado, no incrustado en una loma ni con la puerta tapada por un talud. */
    private void levelPad(int cx, int cz, int half, int floorY) {
        for (int dx = -half; dx <= half; dx++) {
            for (int dz = -half; dz <= half; dz++) {
                final int x = cx + dx;
                final int z = cz + dz;
                for (int y = floorY + 1; y <= floorY + 12; y++) {   // talla la loma/arbol por encima
                    final Block b = world.getBlockAt(x, y, z);
                    if (!b.getType().isAir() && TerrainPlanner.natural(b.getType())) {
                        b.setType(Material.AIR, false);
                    }
                }
                for (int y = floorY - 1; y >= floorY - 8; y--) {    // rellena el hueco por debajo
                    final Block b = world.getBlockAt(x, y, z);
                    if (b.getType().isAir() || b.isLiquid()) {
                        b.setType(Material.DIRT, false);
                    } else {
                        break;
                    }
                }
                final Block top = world.getBlockAt(x, floorY, z);
                if (top.getType().isAir() || TerrainPlanner.natural(top.getType())) {
                    top.setType(Material.GRASS_BLOCK, false);
                }
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
        // La TABERNA, el MERCADO y el GRANERO ya NO se levantan aqui: los construye el pueblo vivo
        // (SettlementModule) segun la POBLACION (granero desde el inicio, taberna>=4, mercado>=6).
        return new Location(world, cx + 3 + 0.5, floorY + 1, cz + 0.5);   // punto de reunion al lado
    }

    /** Plaza-mercado con puestos (toldos de colores) y barriles de genero. Nivela su solar. */
    public Location buildMarket(int cx, int cz, int floorY, String town) {
        levelPad(cx, cz, 5, floorY);
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
        placeWallSign(cx + 1, floorY + 2, cz - 3, BlockFace.NORTH, "§6Mercado", "§7de " + town);
        return new Location(world, cx + 0.5, floorY + 1, cz + 0.5);
    }

    /** GRANERO del pueblo (7x7): granja de almacenaje con barriles y heno, tejado de heno (granero)
     *  y cartel. El barril CENTRAL es donde cada oficio deposita su produccion. Nivela su solar. */
    public Location buildGranary(int cx, int cz, int floorY, String town) {
        final int half = 3;
        levelPad(cx, cz, half + 2, floorY);
        foundation(cx, cz, half, floorY, Material.SPRUCE_PLANKS, 6);
        // Muros de 3 de alto: esquinas de tronco, tablon; frente (sur) ABIERTO de par en par.
        for (int y = floorY + 1; y <= floorY + 3; y++) {
            for (int dx = -half; dx <= half; dx++) {
                for (int dz = -half; dz <= half; dz++) {
                    if (Math.abs(dx) != half && Math.abs(dz) != half) {
                        continue;
                    }
                    if (dz == half && Math.abs(dx) <= 1) {
                        continue;   // porton del granero (sur)
                    }
                    final boolean corner = Math.abs(dx) == half && Math.abs(dz) == half;
                    set(cx + dx, y, cz + dz, corner ? Material.SPRUCE_LOG : Material.SPRUCE_PLANKS);
                }
            }
        }
        // Tejado a dos aguas de HENO (aspecto de granero) con voladizo.
        for (int dx = -half - 1; dx <= half + 1; dx++) {
            for (int dz = -half - 1; dz <= half + 1; dz++) {
                set(cx + dx, floorY + 4, cz + dz, Material.HAY_BLOCK);
            }
        }
        for (int dx = -half + 1; dx <= half - 1; dx++) {
            for (int dz = -half + 1; dz <= half - 1; dz++) {
                set(cx + dx, floorY + 5, cz + dz, Material.HAY_BLOCK);
            }
        }
        // Interior: barriles de almacenaje por las paredes del fondo y los lados, y balas de heno.
        for (int dx = -half + 1; dx <= half - 1; dx++) {
            set(cx + dx, floorY + 1, cz - half + 1, Material.BARREL);   // fondo (norte)
            set(cx + dx, floorY + 2, cz - half + 1, Material.BARREL);
        }
        set(cx - half + 1, floorY + 1, cz, Material.HAY_BLOCK);
        set(cx + half - 1, floorY + 1, cz, Material.HAY_BLOCK);
        set(cx - half + 1, floorY + 1, cz + 1, Material.HAY_BLOCK);
        // Farol e iluminacion.
        set(cx, floorY + 3, cz, Material.LANTERN);
        // Barril CENTRAL de deposito (donde produce cada oficio).
        set(cx, floorY + 1, cz, Material.BARREL);
        // Cartel sobre el porton (sur).
        placeWallSign(cx + 2, floorY + 2, cz + half, BlockFace.SOUTH, "§6Granero", "§7de " + town);
        return new Location(world, cx + 0.5, floorY + 1, cz + 0.5);
    }

    /** Taberna GRANDE (9x9): salon de madera con barra, mesas con sillas, barriles y cristaleras.
     *  La puerta mira al OESTE (hacia la plaza). Nivela su solar. Devuelve su centro. */
    public Location buildTavern(int cx, int cz, int floorY, String town) {
        final int half = 4;
        levelPad(cx, cz, half + 2, floorY);   // solar a ras y despejado (no incrustada)
        foundation(cx, cz, half, floorY, Material.SPRUCE_PLANKS, 6);
        // Muros de 4 de alto: esquinas de tronco, paredes de tablon.
        for (int y = floorY + 1; y <= floorY + 4; y++) {
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
        // Cristaleras a media altura en los cuatro muros.
        for (int d = -2; d <= 2; d += 2) {
            set(cx + d, floorY + 2, cz - half, Material.GLASS_PANE);
            set(cx + d, floorY + 2, cz + half, Material.GLASS_PANE);
            set(cx - half, floorY + 2, cz + d, Material.GLASS_PANE);
            set(cx + half, floorY + 2, cz + d, Material.GLASS_PANE);
        }
        // Puerta al OESTE (hacia la plaza), hueco de 2.
        set(cx - half, floorY + 1, cz, Material.AIR);
        set(cx - half, floorY + 2, cz, Material.AIR);
        // Tejado: capa con voladizo + buhardilla ligera de losas.
        for (int dx = -half - 1; dx <= half + 1; dx++) {
            for (int dz = -half - 1; dz <= half + 1; dz++) {
                set(cx + dx, floorY + 5, cz + dz, Material.DARK_OAK_PLANKS);
            }
        }
        for (int dx = -half + 1; dx <= half - 1; dx++) {
            for (int dz = -half + 1; dz <= half - 1; dz++) {
                set(cx + dx, floorY + 6, cz + dz, Material.DARK_OAK_SLAB);
            }
        }
        // Linternas colgando del techo (iluminacion calida).
        for (final int[] p : new int[][] {{-2, -2}, {2, -2}, {-2, 2}, {2, 2}, {0, 0}}) {
            set(cx + p[0], floorY + 4, cz + p[1], Material.LANTERN);
        }
        // BARRA de madera a lo largo del muro este: mostrador (tablon + losa) con barriles y
        // destileria detras, y taburetes delante.
        // La barra se arrima al muro dejando un PASILLO libre detras: ahi se pone el TABERNERO
        // a servir (su puesto de trabajo es la taberna, no un edificio aparte).
        for (int dz = -2; dz <= 2; dz++) {
            set(cx + half - 3, floorY + 1, cz + dz, Material.SPRUCE_PLANKS);   // frente de la barra
            set(cx + half - 3, floorY + 2, cz + dz, Material.SPRUCE_SLAB);     // encimera
            set(cx + half - 2, floorY + 1, cz + dz, Material.AIR);             // pasillo del tabernero
            set(cx + half - 2, floorY + 2, cz + dz, Material.AIR);
            set(cx + half - 1, floorY + 1, cz + dz, Material.BARREL);          // estante tras la barra
        }
        set(cx + half - 1, floorY + 2, cz - 1, Material.BREWING_STAND);
        set(cx + half - 1, floorY + 2, cz + 1, Material.DECORATED_POT);
        for (int dz = -2; dz <= 2; dz += 2) {
            set(cx + half - 4, floorY + 1, cz + dz, Material.SPRUCE_FENCE);    // taburete
        }
        // MESAS con sillas repartidas por el salon (al otro lado de la barra).
        tavernTable(cx - 2, cz - 2, floorY);
        tavernTable(cx - 2, cz + 2, floorY);
        tavernTable(cx - 2, cz, floorY);
        // Barriles apilados en una esquina (almacen).
        set(cx - half + 1, floorY + 1, cz + half - 1, Material.BARREL);
        set(cx - half + 1, floorY + 2, cz + half - 1, Material.BARREL);
        set(cx - half + 1, floorY + 1, cz - half + 1, Material.BARREL);
        // Cartel "La Taberna" sobre la puerta (oeste).
        placeWallSign(cx - half - 1, floorY + 3, cz, BlockFace.WEST, "§6Taberna", "§7de " + town);
        return new Location(world, cx + 0.5, floorY + 1, cz + 0.5);
    }

    /** Una mesa de taberna: tablero (valla + placa) con dos sillas (escaleras) enfrentadas. */
    private void tavernTable(int tx, int tz, int floorY) {
        set(tx, floorY + 1, tz, Material.SPRUCE_FENCE);
        set(tx, floorY + 2, tz, Material.SPRUCE_PRESSURE_PLATE);
        setStair(tx - 1, floorY + 1, tz, BlockFace.EAST);
        setStair(tx + 1, floorY + 1, tz, BlockFace.WEST);
    }

    /** Coloca una escalera de abeto mirando a `facing` (silla). */
    private void setStair(int x, int y, int z, BlockFace facing) {
        final Block b = world.getBlockAt(x, y, z);
        b.setType(Material.SPRUCE_STAIRS, false);
        if (b.getBlockData() instanceof org.bukkit.block.data.type.Stairs st) {
            st.setFacing(facing);
            b.setBlockData(st, false);
        }
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

    /**
     * Vuelve a rotular un edificio civico YA construido, sin reconstruirlo. Hace falta porque los
     * civicos se levantan una sola vez: sin esto, un cartel mal puesto (p.ej. "Mercado de
     * Aetheria", con el nombre del mundo en vez del de la aldea) se quedaba ahi para siempre.
     */
    public void civicSign(String type, int cx, int cz, int floorY, String town) {
        switch (type) {
            case "granero" -> placeWallSign(cx + 2, floorY + 2, cz + 3, BlockFace.SOUTH,
                    "§6Granero", "§7de " + town);
            case "taberna" -> placeWallSign(cx - 5, floorY + 3, cz, BlockFace.WEST,
                    "§6Taberna", "§7de " + town);
            case "mercado" -> placeWallSign(cx + 1, floorY + 2, cz - 3, BlockFace.NORTH,
                    "§6Mercado", "§7de " + town);
            default -> { }
        }
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
