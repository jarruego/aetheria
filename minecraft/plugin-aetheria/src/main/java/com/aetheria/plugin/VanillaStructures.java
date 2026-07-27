package com.aetheria.plugin;

import java.util.concurrent.ThreadLocalRandom;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;

/**
 * Coloca ESTRUCTURAS VANILLA reales (casas de aldea de llanura) en el mundo. En vez de recrear el
 * estilo a mano, usa las plantillas .nbt que trae el propio servidor (las mismas que genera una
 * aldea natural), mediante el comando {@code /place template minecraft:village/plains/...} — un
 * mecanismo estable y sin dependencias de WorldEdit.
 *
 * <p>Nivela el terreno bajo la casa (via {@link TerrainPlanner}), la coloca, y LIMPIA los bloques
 * de andamiaje que trae la plantilla (jigsaw, structure_block, structure_void) para que no queden a
 * la vista. No incluye entidades: los aldeanos los pone nuestro propio sistema, no la plantilla.
 */
public final class VanillaStructures {

    private VanillaStructures() {
    }

    /** Casas de aldea de LLANURA del vanilla (claves del registro del servidor). */
    private static final String[] PLAINS_HOUSES = {
        "village/plains/houses/plains_small_house_1",
        "village/plains/houses/plains_small_house_2",
        "village/plains/houses/plains_small_house_3",
        "village/plains/houses/plains_small_house_4",
        "village/plains/houses/plains_small_house_5",
        "village/plains/houses/plains_small_house_6",
        "village/plains/houses/plains_small_house_7",
        "village/plains/houses/plains_small_house_8",
        "village/plains/houses/plains_medium_house_1",
        "village/plains/houses/plains_medium_house_2",
        "village/plains/houses/plains_big_house_1",
    };

    // Caja generosa alrededor del punto de colocacion (las casas de llanura caben de sobra en
    // ±8 en horizontal). Se usa para nivelar, limpiar el andamiaje y registrar el anti-solape.
    private static final int PAD = 8;
    private static final int UP = 14;

    /** ¿Esta disponible el sistema de estructuras? (siempre en Paper; se deja por claridad). */
    public static boolean available() {
        return Bukkit.getStructureManager() != null;
    }

    /**
     * Coloca una casa de aldea vanilla al AZAR centrada en (cx, cz), con el suelo en floorY.
     * Devuelve la clave colocada, o null si algo falla.
     */
    public static String placeRandomHouse(World world, int cx, int cz, int floorY) {
        final String key = PLAINS_HOUSES[ThreadLocalRandom.current().nextInt(PLAINS_HOUSES.length)];
        // 1) Nivela y despeja un solar amplio a la cota del suelo (talla lomas/arboles, rellena
        //    huecos): la casa vanilla se apoya a ras y con sus patas cortas de cimiento.
        TerrainPlanner.prepare(world, cx - PAD, cz - PAD, cx + PAD, cz + PAD, floorY,
                Material.COBBLESTONE);
        // 2) La plantilla se coloca por su ESQUINA minima; para centrarla, se resta media caja.
        final int ox = cx - 4;
        final int oz = cz - 4;
        final boolean ok = Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                "place template minecraft:" + key + " " + ox + " " + floorY + " " + oz);
        if (!ok) {
            return null;
        }
        // 3) Limpia el andamiaje de la plantilla (jigsaw / structure_block / structure_void) para
        //    que no quede a la vista.
        cleanup(world, cx, cz, floorY);
        return key;
    }

    /** Sustituye por AIRE los bloques de andamiaje de estructura en la caja de la casa. */
    private static void cleanup(World world, int cx, int cz, int floorY) {
        for (int x = cx - PAD; x <= cx + PAD; x++) {
            for (int z = cz - PAD; z <= cz + PAD; z++) {
                for (int y = floorY - 1; y <= floorY + UP; y++) {
                    final Block b = world.getBlockAt(x, y, z);
                    final Material m = b.getType();
                    if (m == Material.JIGSAW || m == Material.STRUCTURE_BLOCK
                            || m == Material.STRUCTURE_VOID) {
                        b.setType(Material.AIR, false);
                    }
                }
            }
        }
    }

    /** Caja 3D (para el registro anti-solape) de una casa colocada en (cx, cz, floorY). */
    public static int[] region(int cx, int cz, int floorY) {
        return new int[] {cx - PAD, floorY - 2, cz - PAD, cx + PAD, floorY + UP, cz + PAD};
    }
}
