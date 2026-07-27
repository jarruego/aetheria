package com.aetheria.plugin;

import org.bukkit.entity.Player;

/**
 * Catalogo de ESQUEMATICOS apoyado en FAWE/WorldEdit. En vez de enlazar con la API de WorldEdit
 * en tiempo de compilacion (sus dependencias no siempre estan disponibles al construir), este
 * modulo DESPACHA los comandos de WorldEdit en nombre del jugador. Asi el plugin no depende de
 * ninguna clase de WorldEdit: si FAWE no esta, simplemente no se activa.
 *
 * <p>El catalogo es la carpeta de esquematicos de FAWE (plugins/FastAsyncWorldEdit/schematics/).
 * Se pega con //schematic load + //paste, y se guarda una seleccion con //copy + //schematic save.
 */
public final class SchematicModule {

    private final AetheriaPlugin plugin;

    public SchematicModule(AetheriaPlugin plugin) {
        this.plugin = plugin;
    }

    /** Pega un esquematico del catalogo en la posicion del jugador. */
    public void paste(Player player, String name) {
        player.sendMessage("§7[Esquematico] cargando y pegando §f" + name + "§7...");
        if (!player.performCommand("/schematic load " + name)) {
            player.sendMessage("§cNo pude cargar '" + name + "'. Mira §f/aetheria schem list§c.");
            return;
        }
        player.performCommand("/paste");
    }

    /** Guarda la SELECCION actual del jugador (varita de WorldEdit) como esquematico del catalogo. */
    public void save(Player player, String name) {
        player.sendMessage("§7[Esquematico] guardando tu seleccion como §f" + name + "§7...");
        if (!player.performCommand("/copy")) {
            player.sendMessage("§cPrimero selecciona una zona con la varita de WorldEdit (//wand).");
            return;
        }
        player.performCommand("/schematic save " + name);
    }

    /** Lista los esquematicos del catalogo (los muestra WorldEdit en el chat). */
    public void list(Player player) {
        player.performCommand("/schematic list");
    }

    /** Vacia (pone AIRE) una caja del mundo via FAWE por reflexion. Para limpiar antes de pegar. */
    public boolean fillAir(org.bukkit.World bukkitWorld, int minX, int minY, int minZ,
            int maxX, int maxY, int maxZ) {
        try {
            final Class<?> adapter = Class.forName("com.sk89q.worldedit.bukkit.BukkitAdapter");
            final Object weWorld =
                    adapter.getMethod("adapt", org.bukkit.World.class).invoke(null, bukkitWorld);
            final Class<?> weCls = Class.forName("com.sk89q.worldedit.WorldEdit");
            final Object we = weCls.getMethod("getInstance").invoke(null);
            final Class<?> weWorldCls = Class.forName("com.sk89q.worldedit.world.World");
            final Object editSession = weCls.getMethod("newEditSession", weWorldCls).invoke(we, weWorld);
            final Class<?> bv3 = Class.forName("com.sk89q.worldedit.math.BlockVector3");
            final Object min = bv3.getMethod("at", int.class, int.class, int.class)
                    .invoke(null, minX, minY, minZ);
            final Object max = bv3.getMethod("at", int.class, int.class, int.class)
                    .invoke(null, maxX, maxY, maxZ);
            final Class<?> cuboid = Class.forName("com.sk89q.worldedit.regions.CuboidRegion");
            final Object region = cuboid.getConstructor(weWorldCls, bv3, bv3)
                    .newInstance(weWorld, min, max);
            final Class<?> blockTypes = Class.forName("com.sk89q.worldedit.world.block.BlockTypes");
            final Object airType = blockTypes.getField("AIR").get(null);
            final Object airState = airType.getClass().getMethod("getDefaultState").invoke(airType);
            final Class<?> regionCls = Class.forName("com.sk89q.worldedit.regions.Region");
            final Class<?> patternCls = Class.forName("com.sk89q.worldedit.function.pattern.Pattern");
            editSession.getClass().getMethod("setBlocks", regionCls, patternCls)
                    .invoke(editSession, region, airState);
            if (editSession instanceof AutoCloseable ac) {
                ac.close();
            }
            return true;
        } catch (Throwable t) {
            plugin.getLogger().warning("[Aetheria] fillAir: " + t);
            return false;
        }
    }

    /** Carga el clipboard de un .schem (via WorldEdit por reflexion). Interfaces publicas. */
    private Object loadClipboard(java.io.File file) throws Exception {
        final Class<?> formats =
                Class.forName("com.sk89q.worldedit.extent.clipboard.io.ClipboardFormats");
        final Object format = formats.getMethod("findByFile", java.io.File.class).invoke(null, file);
        if (format == null) {
            return null;
        }
        // format/reader son clases NO publicas (constantes de enum): invocar por la INTERFAZ.
        final Class<?> formatIface =
                Class.forName("com.sk89q.worldedit.extent.clipboard.io.ClipboardFormat");
        final Class<?> readerIface =
                Class.forName("com.sk89q.worldedit.extent.clipboard.io.ClipboardReader");
        try (java.io.InputStream in = new java.io.FileInputStream(file)) {
            final Object reader = formatIface
                    .getMethod("getReader", java.io.InputStream.class).invoke(format, in);
            final Object clip = readerIface.getMethod("read").invoke(reader);
            if (reader instanceof AutoCloseable ac) {
                ac.close();
            }
            return clip;
        }
    }

    /** {ancho(x), largo(z)} de un esquematico, o null si falla. */
    public int[] dimensions(java.io.File file) {
        try {
            final Object clip = loadClipboard(file);
            if (clip == null) {
                return null;
            }
            final Class<?> clipIface = Class.forName("com.sk89q.worldedit.extent.clipboard.Clipboard");
            final Object dim = clipIface.getMethod("getDimensions").invoke(clip);
            final Class<?> bv3 = Class.forName("com.sk89q.worldedit.math.BlockVector3");
            final int wx = (int) bv3.getMethod("getX").invoke(dim);
            final int lz = (int) bv3.getMethod("getZ").invoke(dim);
            return new int[] {wx, lz};
        } catch (Throwable t) {
            plugin.getLogger().warning("[Aetheria] dimensions " + file.getName() + ": " + t);
            return null;
        }
    }

    /** Pega un clipboard poniendo su ORIGEN en (x,y,z). */
    private void pasteClip(org.bukkit.World bukkitWorld, Object clipboard, int x, int y, int z)
            throws Exception {
        final Class<?> adapter = Class.forName("com.sk89q.worldedit.bukkit.BukkitAdapter");
        final Object weWorld =
                adapter.getMethod("adapt", org.bukkit.World.class).invoke(null, bukkitWorld);
        final Class<?> weCls = Class.forName("com.sk89q.worldedit.WorldEdit");
        final Object we = weCls.getMethod("getInstance").invoke(null);
        final Class<?> weWorldCls = Class.forName("com.sk89q.worldedit.world.World");
        final Object editSession = weCls.getMethod("newEditSession", weWorldCls).invoke(we, weWorld);
        final Class<?> bv3 = Class.forName("com.sk89q.worldedit.math.BlockVector3");
        final Object to = bv3.getMethod("at", int.class, int.class, int.class).invoke(null, x, y, z);
        final Class<?> holderCls = Class.forName("com.sk89q.worldedit.session.ClipboardHolder");
        final Class<?> clipboardCls = Class.forName("com.sk89q.worldedit.extent.clipboard.Clipboard");
        final Object holder = holderCls.getConstructor(clipboardCls).newInstance(clipboard);
        final Class<?> extentCls = Class.forName("com.sk89q.worldedit.extent.Extent");
        Object pb = holderCls.getMethod("createPaste", extentCls).invoke(holder, editSession);
        pb = pb.getClass().getMethod("to", bv3).invoke(pb, to);
        pb = pb.getClass().getMethod("ignoreAirBlocks", boolean.class).invoke(pb, true);
        final Object operation = pb.getClass().getMethod("build").invoke(pb);
        final Class<?> ops = Class.forName("com.sk89q.worldedit.function.operation.Operations");
        final Class<?> opCls = Class.forName("com.sk89q.worldedit.function.operation.Operation");
        ops.getMethod("complete", opCls).invoke(null, operation);
        if (editSession instanceof AutoCloseable ac) {
            ac.close();
        }
    }

    /**
     * Pega un .schem con su ESQUINA MINIMA en (leftX, groundY, frontZ), sea cual sea su origen
     * interno (asi se pueden alinear en fila). Devuelve {ancho(x), largo(z)} o null si falla.
     */
    public int[] pasteAligned(org.bukkit.World bukkitWorld, java.io.File file,
            int leftX, int groundY, int frontZ) {
        try {
            final Object clip = loadClipboard(file);
            if (clip == null) {
                return null;
            }
            final Class<?> clipIface = Class.forName("com.sk89q.worldedit.extent.clipboard.Clipboard");
            final Class<?> regionIface = Class.forName("com.sk89q.worldedit.regions.Region");
            final Class<?> bv3 = Class.forName("com.sk89q.worldedit.math.BlockVector3");
            final Object origin = clipIface.getMethod("getOrigin").invoke(clip);
            final Object region = clipIface.getMethod("getRegion").invoke(clip);
            final Object min = regionIface.getMethod("getMinimumPoint").invoke(region);
            final Object dim = clipIface.getMethod("getDimensions").invoke(clip);
            final int ox = (int) bv3.getMethod("getX").invoke(origin);
            final int oy = (int) bv3.getMethod("getY").invoke(origin);
            final int oz = (int) bv3.getMethod("getZ").invoke(origin);
            final int mnx = (int) bv3.getMethod("getX").invoke(min);
            final int mny = (int) bv3.getMethod("getY").invoke(min);
            final int mnz = (int) bv3.getMethod("getZ").invoke(min);
            final int width = (int) bv3.getMethod("getX").invoke(dim);
            final int length = (int) bv3.getMethod("getZ").invoke(dim);
            // Nivela el terreno bajo el esquematico (columna a columna) y clava pilotes si cae
            // sobre agua/hielo — el .schem en si no trae cimentacion.
            TerrainPlanner.prepare(bukkitWorld, leftX, frontZ, leftX + width - 1, frontZ + length - 1,
                    groundY, org.bukkit.Material.COBBLESTONE);
            // paste .to(origen); para que la esquina min caiga en left/ground/front: to = left + (origen - min)
            pasteClip(bukkitWorld, clip, leftX + (ox - mnx), groundY + (oy - mny), frontZ + (oz - mnz));
            return new int[] {width, length};
        } catch (Throwable t) {
            plugin.getLogger().warning("[Aetheria] pasteAligned " + file.getName() + ": " + t);
            return null;
        }
    }
}
