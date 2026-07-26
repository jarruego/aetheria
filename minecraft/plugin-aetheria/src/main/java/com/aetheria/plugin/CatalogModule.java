package com.aetheria.plugin;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Sign;
import org.bukkit.block.data.Rotatable;
import org.bukkit.block.sign.Side;

import net.kyori.adventure.text.Component;

/**
 * Catalogo del mundo CREATIVO: una galeria con una muestra de cada estructura que el plugin
 * sabe construir (casas por estado, puestos de trabajo tematicos y decoraciones), cada una con
 * un cartel delante que dice lo que es. Es un escaparate de "lo que tenemos", validable de un
 * vistazo. Todo lo levanta el plugin de forma determinista e IDEMPOTENTE (cota fija + limpieza
 * previa), asi que al reiniciar se reconstruye en el mismo sitio sin "trepar".
 *
 * <p>El dia que anadamos esquematicos reales (WorldEdit/FAWE), cada .schem del catalogo se
 * pegaria aqui con la misma idea: una fila de muestras rotuladas.
 */
public final class CatalogModule {

    private static final int SPACING = 16;   // separacion entre muestras
    private static final int PER_ROW = 4;

    private final AetheriaPlugin plugin;
    private final World world;
    private int baseX;
    private int baseZ;
    private int floorY;

    public CatalogModule(AetheriaPlugin plugin, World world) {
        this.plugin = plugin;
        this.world = world;
    }

    public void build() {
        final Location sp = world.getSpawnLocation();
        this.baseX = sp.getBlockX() - 24;
        this.baseZ = sp.getBlockZ() - 52;    // galeria al NORTE del spawn, en area despejada
        this.floorY = sp.getBlockY() - 1;    // cota FIJA (no trepa entre reinicios)

        int i = 0;
        i = house(i, "Casa pequena", "soltero (1 cama)", 3, 3, 1);
        i = house(i, "Casa mediana", "pareja (3 camas)", 4, 4, 3);
        i = workplace(i, "Huerto", "granjero", "farmer");
        i = workplace(i, "Embarcadero", "pescador", "fisherman");
        i = workplace(i, "Aprisco", "pastor", "shepherd");
        i = workplace(i, "Taller cantero", "cantero", "mason");
        i = workplace(i, "Biblioteca", "bibliotecario", "librarian");
        i = workplace(i, "Herreria", "herrero", "toolsmith");
        i = workplace(i, "Carniceria", "carnicero", "butcher");
        i = workplace(i, "Taller arquero", "arquero", "fletcher");
        i = deco(i, "Fuente", "", "fountain");
        i = deco(i, "Fuente grande", "", "bigfountain");
        i = deco(i, "Jardin", "", "garden");
        i = deco(i, "Estatua", "", "statue");
        i = deco(i, "Farola", "", "lamppost");
        i = deco(i, "Plataforma", "", "platform");

        plugin.getLogger().info("[Aetheria] Catalogo del creativo: " + i + " muestras con cartel.");
    }

    private int[] cell(int i) {
        final int col = i % PER_ROW;
        final int row = i / PER_ROW;
        return new int[] {baseX + col * SPACING, baseZ + row * SPACING};
    }

    /** Limpia un cubo alrededor de la muestra y deja una base de cesped (rebuild limpio). */
    private void clearCell(int cx, int cz) {
        for (int dx = -6; dx <= 6; dx++) {
            for (int dz = -6; dz <= 6; dz++) {
                for (int y = floorY + 1; y <= floorY + 20; y++) {
                    final Block b = world.getBlockAt(cx + dx, y, cz + dz);
                    if (!b.getType().isAir()) {
                        b.setType(Material.AIR, false);
                    }
                }
                world.getBlockAt(cx + dx, floorY, cz + dz).setType(Material.GRASS_BLOCK, false);
            }
        }
    }

    private int house(int i, String l1, String l2, int hx, int hz, int beds) {
        final int[] c = cell(i);
        clearCell(c[0], c[1]);
        Blueprint.buildHouse(world, c[0], c[1], floorY, BlockFace.SOUTH, hx, hz, 1, false,
                Material.OAK_PLANKS, Material.SPRUCE_LOG, Material.DARK_OAK_PLANKS, Material.BRICKS,
                true, beds, "Muestra");
        sign(c[0], c[1] + 6, l1, l2);
        return i + 1;
    }

    private int workplace(int i, String l1, String l2, String profKey) {
        final int[] c = cell(i);
        clearCell(c[0], c[1]);
        Blueprint.workplaceShowcase(world, c[0], floorY, c[1], profKey);
        sign(c[0], c[1] + 6, l1, l2);
        return i + 1;
    }

    private int deco(int i, String l1, String l2, String name) {
        final int[] c = cell(i);
        clearCell(c[0], c[1]);
        Blueprint.placeAt(world, c[0], floorY, c[1], name);
        sign(c[0], c[1] + 6, l1, l2);
        return i + 1;
    }

    /** Cartel de pie (sobre el cesped) mirando al sur, hacia quien recorre la galeria. */
    private void sign(int x, int z, String l1, String l2) {
        final Block b = world.getBlockAt(x, floorY + 1, z);
        b.setType(Material.OAK_SIGN, false);
        if (b.getBlockData() instanceof Rotatable rot) {
            rot.setRotation(BlockFace.SOUTH);
            b.setBlockData(rot, false);
        }
        if (b.getState() instanceof Sign s) {
            s.getSide(Side.FRONT).line(1, Component.text("§6" + l1));
            if (l2 != null && !l2.isEmpty()) {
                s.getSide(Side.FRONT).line(2, Component.text("§7" + l2));
            }
            s.update(true);
        }
    }
}
