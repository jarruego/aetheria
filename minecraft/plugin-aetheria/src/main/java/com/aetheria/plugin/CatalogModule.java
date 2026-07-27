package com.aetheria.plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.bukkit.Bukkit;
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
 * Catalogo del mundo CREATIVO: una galeria rotulada con TODO lo que el plugin sabe construir.
 *
 * <p>#16 - Ya no es una muestra suelta: estan <b>todas las combinaciones del arquitecto</b>
 * (3 tamanos x 4 gamas de material x 3 estilos = 36 casas, con los mismos dados y paletas que
 * usa el arquitecto de verdad), <b>todas las plantillas VANILLA</b> importadas (las casas de
 * aldea de llanura del propio Minecraft), los puestos de trabajo de cada oficio y las
 * decoraciones. Cada muestra lleva su cartel delante: es el escaparate donde comprobar de un
 * vistazo como queda cada opcion antes de encargarla.
 *
 * <p>Se construye <b>por lotes</b> (una muestra por tick): son decenas de casas y levantarlas
 * de golpe al arrancar congelaria el servidor. Es idempotente y determinista (cota fija,
 * limpieza previa y semilla fija por celda), asi que al reiniciar sale exactamente igual.
 */
public final class CatalogModule {

    private static final int SPACING = 20;   // separacion entre muestras (caben casas grandes)
    private static final int PER_ROW = 6;
    private static final int CELL = 9;       // media anchura del solar que se limpia
    private static final long SEED = 20260727L;   // galeria estable entre reinicios

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
        this.baseZ = sp.getBlockZ() - 60;    // galeria al NORTE del spawn, en area despejada
        this.floorY = sp.getBlockY() - 1;    // cota FIJA (no trepa entre reinicios)

        final List<Runnable> jobs = new ArrayList<>();
        int i = 0;

        // 1) TODAS las combinaciones del arquitecto: tamano x material x estilo.
        for (final String style : ArchitectModule.STYLE_KEYS) {
            for (final String tier : ArchitectModule.TIER_KEYS) {
                for (int size = 1; size <= 3; size++) {
                    final int idx = i++;
                    final int sz = size;
                    jobs.add(() -> archHouse(idx, tier, style, sz));
                }
            }
        }
        // 2) Estructuras VANILLA importadas (las casas de aldea reales de Minecraft).
        for (final String key : VanillaStructures.plainsHouses()) {
            final int idx = i++;
            jobs.add(() -> vanillaHouse(idx, key));
        }
        // 3) Casas del pueblo vivo (las que se construyen solas a los colonos).
        final int hp = i++;
        jobs.add(() -> house(hp, "Casa de colono", "soltero (1 cama)", 2, 2, 1));
        final int hm = i++;
        jobs.add(() -> house(hm, "Casa de pareja", "casados (3 camas)", 3, 4, 3));
        // 4) Puestos de trabajo de cada oficio.
        final String[][] jobsList = {
            {"Huerto", "granjero", "farmer"}, {"Embarcadero", "pescador", "fisherman"},
            {"Aprisco", "pastor", "shepherd"}, {"Taller cantero", "cantero", "mason"},
            {"Biblioteca", "bibliotecario", "librarian"}, {"Herreria", "herrero", "toolsmith"},
            {"Carniceria", "carnicero", "butcher"}, {"Taller arquero", "arquero", "fletcher"},
        };
        for (final String[] w : jobsList) {
            final int idx = i++;
            jobs.add(() -> workplace(idx, w[0], w[1], w[2]));
        }
        // 5) Decoraciones del decorador.
        final String[][] decos = {
            {"Fuente", "", "fountain"}, {"Fuente grande", "", "bigfountain"},
            {"Jardin", "", "garden"}, {"Estatua", "", "statue"},
            {"Farola", "", "lamppost"}, {"Plataforma", "", "platform"},
        };
        for (final String[] d : decos) {
            final int idx = i++;
            jobs.add(() -> deco(idx, d[0], d[1], d[2]));
        }

        plugin.getLogger().info("[Aetheria] Catalogo del creativo: levantando " + jobs.size()
                + " muestras (una por tick, para no congelar el arranque).");
        runQueue(jobs);
    }

    /** Ejecuta la cola a razon de UNA muestra por tick: el arranque no se bloquea. */
    private void runQueue(List<Runnable> jobs) {
        final int[] n = {0};
        final int[] task = new int[1];
        task[0] = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (n[0] >= jobs.size()) {
                Bukkit.getScheduler().cancelTask(task[0]);
                plugin.getLogger().info("[Aetheria] Catalogo del creativo listo (" + jobs.size()
                        + " muestras con cartel).");
                return;
            }
            try {
                jobs.get(n[0]).run();
            } catch (Exception e) {   // una muestra rota no debe tumbar la galeria entera
                plugin.getLogger().warning("[Aetheria] muestra " + n[0] + " fallo: " + e.getMessage());
            }
            n[0]++;
        }, 20L, 1L).getTaskId();
    }

    private int[] cell(int i) {
        final int col = i % PER_ROW;
        final int row = i / PER_ROW;
        return new int[] {baseX + col * SPACING, baseZ + row * SPACING};
    }

    /** Limpia un cubo alrededor de la muestra y deja una base de cesped (rebuild limpio). */
    private void clearCell(int cx, int cz) {
        for (int dx = -CELL; dx <= CELL; dx++) {
            for (int dz = -CELL; dz <= CELL; dz++) {
                for (int y = floorY + 1; y <= floorY + 36; y++) {   // las torres son altas
                    final Block b = world.getBlockAt(cx + dx, y, cz + dz);
                    if (!b.getType().isAir()) {
                        b.setType(Material.AIR, false);
                    }
                }
                world.getBlockAt(cx + dx, floorY, cz + dz).setType(Material.GRASS_BLOCK, false);
            }
        }
    }

    /** Una casa TAL CUAL la construye el arquitecto para esa combinacion (mismos dados). */
    private void archHouse(int i, String tier, String style, int size) {
        final int[] c = cell(i);
        clearCell(c[0], c[1]);
        final Random rng = new Random(SEED + i);   // determinista: la galeria no cambia al reiniciar
        final int[] d = ArchitectModule.dims(size, style, rng);
        final Material[] pal = ArchitectModule.paletteFor(tier, style, rng);
        Blueprint.buildHouse(world, c[0], c[1], floorY, BlockFace.SOUTH, d[0], d[1],
                pal[0], pal[1], pal[2], pal[3], true, "Muestra");
        final String tam = size == 1 ? "pequena" : size == 2 ? "mediana" : "grande";
        sign(c[0], c[1] + CELL - 1, capitalize(style) + " " + tier,
                tam + ", " + d[1] + " pl.");
    }

    /** Una plantilla VANILLA concreta (casa de aldea real de Minecraft). */
    private void vanillaHouse(int i, String key) {
        final int[] c = cell(i);
        clearCell(c[0], c[1]);
        VanillaStructures.placeHouse(world, key, c[0], c[1], floorY);
        final String shortKey = key.substring(key.lastIndexOf('/') + 1).replace("plains_", "");
        sign(c[0], c[1] + CELL - 1, "Vanilla", shortKey.replace("_house_", " "));
    }

    private void house(int i, String l1, String l2, int hx, int hz, int beds) {
        final int[] c = cell(i);
        clearCell(c[0], c[1]);
        Blueprint.buildHouse(world, c[0], c[1], floorY, BlockFace.SOUTH, hx, hz, 1, false,
                Material.OAK_PLANKS, Material.SPRUCE_LOG, Material.DARK_OAK_PLANKS, Material.BRICKS,
                true, beds, "Muestra");
        sign(c[0], c[1] + CELL - 1, l1, l2);
    }

    private void workplace(int i, String l1, String l2, String profKey) {
        final int[] c = cell(i);
        clearCell(c[0], c[1]);
        Blueprint.workplaceShowcase(world, c[0], floorY, c[1], profKey);
        sign(c[0], c[1] + CELL - 1, l1, l2);
    }

    private void deco(int i, String l1, String l2, String name) {
        final int[] c = cell(i);
        clearCell(c[0], c[1]);
        Blueprint.placeAt(world, c[0], floorY, c[1], name);
        sign(c[0], c[1] + CELL - 1, l1, l2);
    }

    private static String capitalize(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
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
