package com.aetheria.plugin;

import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;

/**
 * Validador y preparador de TERRENO compartido por todos los caminos de construccion (aldea
 * autonoma, arquitecto, decorador, blueprint por chat y esquematicos). Centraliza la logica que
 * antes solo tenia bien SettlementModule: altura real de suelo por columna, deteccion de
 * agua/hielo, nivelado COLUMNA A COLUMNA con material coherente y CIMENTACION SOBRE PILOTES para
 * agua/hielo (en vez de un tapon de tierra o de rechazar).
 *
 * <p>Modelo tomado del generador de estructuras del propio Minecraft (heightmap + "beard"), con
 * una mejora: sobre agua/hielo se apoya en pilotes hasta el fondo real, dejando el agua visible.
 */
public final class TerrainPlanner {

    private TerrainPlanner() {
    }

    /** Altura del volumen que se despeja sobre el suelo (para talar arboles que atraviesen). */
    private static final int CLEAR_HEIGHT = 16;

    /** True si el material es NATURAL (se puede talar/allanar): aire, agua, tierra, roca, arena,
     *  grava, arboles/hojas, vegetacion, mineral, nieve, hielo... FALSE si es algo construido. */
    public static boolean natural(Material m) {
        if (m.isAir() || m == Material.WATER || m == Material.LAVA) {
            return true;
        }
        if (Tag.LOGS.isTagged(m) || Tag.LEAVES.isTagged(m) || Tag.SAPLINGS.isTagged(m)
                || Tag.DIRT.isTagged(m) || Tag.SAND.isTagged(m) || Tag.FLOWERS.isTagged(m)) {
            return true;
        }
        final String n = m.name();
        if (n.endsWith("_ORE") || n.contains("MUSHROOM")) {
            return true;
        }
        return switch (m) {
            case STONE, GRANITE, DIORITE, ANDESITE, DEEPSLATE, TUFF, CALCITE, GRAVEL, CLAY,
                 SANDSTONE, RED_SANDSTONE, SNOW, SNOW_BLOCK, POWDER_SNOW, ICE, PACKED_ICE, BLUE_ICE,
                 FROSTED_ICE, SHORT_GRASS, TALL_GRASS, FERN, LARGE_FERN, DEAD_BUSH, VINE, GLOW_LICHEN,
                 MOSS_BLOCK, MOSS_CARPET, DIRT_PATH, GRASS_BLOCK, SWEET_BERRY_BUSH, SUGAR_CANE,
                 LILY_PAD, CACTUS, PUMPKIN, MELON, BAMBOO, COBWEB -> true;
            default -> false;
        };
    }

    private static boolean isTree(Material m) {
        return Tag.LOGS.isTagged(m) || Tag.LEAVES.isTagged(m) || m.name().contains("MUSHROOM_BLOCK");
    }

    private static boolean isIce(Material m) {
        return m == Material.ICE || m == Material.PACKED_ICE || m == Material.BLUE_ICE
                || m == Material.FROSTED_ICE;
    }

    /** Altura del SUELO real (ignora hojas/troncos/vegetacion), escaneando hacia abajo. */
    public static int groundY(World world, int x, int z) {
        int y = world.getHighestBlockYAt(x, z);
        for (int i = 0; i < 40 && y > world.getMinHeight() + 1; i++, y--) {
            final Material m = world.getBlockAt(x, y, z).getType();
            if (m.isSolid() && !isTree(m)) {
                return y;
            }
        }
        return y;
    }

    /** Fondo SOLIDO real por debajo de agua Y hielo (para clavar pilotes hasta el lecho). */
    public static int bedGroundY(World world, int x, int z) {
        int y = world.getHighestBlockYAt(x, z);
        for (int i = 0; i < 64 && y > world.getMinHeight() + 1; i++, y--) {
            final Material m = world.getBlockAt(x, y, z).getType();
            if (m.isSolid() && !isTree(m) && !isIce(m)) {
                return y;
            }
        }
        return y;
    }

    /** True si la columna es agua o hielo en su superficie (necesita pilote, no relleno). */
    public static boolean isLiquidOrIce(World world, int x, int z) {
        final int gy = groundY(world, x, z);
        final Block g = world.getBlockAt(x, gy, z);
        if (g.isLiquid() || isIce(g.getType())) {
            return true;
        }
        for (int y = gy + 1; y <= gy + 4; y++) {
            if (world.getBlockAt(x, y, z).isLiquid()) {
                return true;
            }
        }
        return false;
    }

    /** Cota media del suelo FIRME (no agua/hielo) de la huella: el mejor nivel para el suelo. */
    public static int meanFirmY(World world, int minX, int minZ, int maxX, int maxZ) {
        long sum = 0;
        int n = 0;
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                if (!isLiquidOrIce(world, x, z)) {
                    sum += groundY(world, x, z);
                    n++;
                }
            }
        }
        return n > 0 ? Math.round(sum / (float) n) : groundY(world, (minX + maxX) / 2, (minZ + maxZ) / 2);
    }

    /**
     * Prepara la huella para un suelo a {@code floorY}: nivela COLUMNA A COLUMNA la tierra firme
     * (rellena con material coherente si esta baja, talla si sobresale) y clava PILOTES en las
     * columnas de agua/hielo (esquinas + perimetro cada ~4) bajando hasta el lecho real, dejando
     * el agua visible. NO coloca el suelo del edificio (eso lo hace el constructor).
     */
    public static void prepare(World world, int minX, int minZ, int maxX, int maxZ, int floorY,
            Material postMaterial) {
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                // 0) DESPEJA EL VOLUMEN del edificio: tala arboles y quita vegetacion/nieve por
                //    encima del suelo. Sin esto, un tronco o unas hojas quedaban ATRAVESANDO la
                //    construccion (se "construia sobre arboles"). Solo lo natural; nunca lo puesto.
                for (int y = floorY + 1; y <= floorY + CLEAR_HEIGHT; y++) {
                    final Block b = world.getBlockAt(x, y, z);
                    final Material m = b.getType();
                    if (!m.isAir() && !b.isLiquid() && natural(m)) {
                        b.setType(Material.AIR, false);
                    }
                }
                if (isLiquidOrIce(world, x, z)) {
                    final boolean edge = x == minX || x == maxX || z == minZ || z == maxZ;
                    final boolean support = (x - minX) % 4 == 0 && (z - minZ) % 4 == 0;
                    if (edge || support) {           // pilote solo en puntos de apoyo
                        final int bed = bedGroundY(world, x, z);
                        for (int y = bed + 1; y < floorY; y++) {
                            world.getBlockAt(x, y, z).setType(postMaterial, false);
                        }
                    }
                    continue;   // el resto de la columna se deja (agua visible bajo el edificio)
                }
                // Tierra firme: rellena con material COHERENTE (su propio subsuelo) o talla.
                final int gy = groundY(world, x, z);
                final Material fill = coherentFill(world, x, gy, z);
                for (int y = gy + 1; y < floorY; y++) {          // rellena si el suelo esta bajo
                    final Block b = world.getBlockAt(x, y, z);
                    if (b.getType().isAir() || b.isLiquid()) {
                        b.setType(fill, false);
                    }
                }
                for (int y = floorY; y <= gy; y++) {             // talla si sobresale dentro
                    final Block b = world.getBlockAt(x, y, z);
                    if (natural(b.getType()) && !b.isLiquid()) {
                        b.setType(Material.AIR, false);
                    }
                }
            }
        }
    }

    /** Material de relleno coherente con la columna: su propio subsuelo (o tierra por defecto). */
    private static Material coherentFill(World world, int x, int gy, int z) {
        final Material sub = world.getBlockAt(x, gy, z).getType();
        if (sub == Material.GRASS_BLOCK || sub == Material.DIRT || sub == Material.COARSE_DIRT
                || sub == Material.PODZOL || sub == Material.MYCELIUM) {
            return Material.DIRT;
        }
        if (sub == Material.SAND || sub == Material.RED_SAND) {
            return sub;
        }
        if (sub == Material.SANDSTONE || sub == Material.RED_SANDSTONE) {
            return sub;
        }
        if (sub == Material.STONE || sub == Material.GRANITE || sub == Material.DIORITE
                || sub == Material.ANDESITE || sub == Material.DEEPSLATE || sub == Material.GRAVEL) {
            return sub == Material.GRAVEL ? Material.DIRT : Material.STONE;
        }
        return Material.DIRT;
    }
}
