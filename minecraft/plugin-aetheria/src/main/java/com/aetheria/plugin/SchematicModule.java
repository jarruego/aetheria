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

    /**
     * Pega un .schem en (x,y,z) del mundo usando la API de WorldEdit por REFLEXION (FAWE la
     * aporta en runtime, sin dependencia de compilacion). Sirve desde consola/RCON, sin jugador.
     * Devuelve true si se pego.
     */
    public boolean pasteAt(org.bukkit.World bukkitWorld, java.io.File file, int x, int y, int z) {
        try {
            final Class<?> formats =
                    Class.forName("com.sk89q.worldedit.extent.clipboard.io.ClipboardFormats");
            final Object format = formats.getMethod("findByFile", java.io.File.class).invoke(null, file);
            if (format == null) {
                return false;
            }
            // Ojo: format/reader son clases NO publicas (constantes de enum). Hay que invocar sus
            // metodos a traves de la INTERFAZ (publica), no de getClass() (clase concreta).
            final Class<?> formatIface =
                    Class.forName("com.sk89q.worldedit.extent.clipboard.io.ClipboardFormat");
            final Class<?> readerIface =
                    Class.forName("com.sk89q.worldedit.extent.clipboard.io.ClipboardReader");
            final Object clipboard;
            try (java.io.InputStream in = new java.io.FileInputStream(file)) {
                final Object reader = formatIface
                        .getMethod("getReader", java.io.InputStream.class).invoke(format, in);
                clipboard = readerIface.getMethod("read").invoke(reader);
                if (reader instanceof AutoCloseable ac) {
                    ac.close();
                }
            }
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
            final Class<?> clipboardCls =
                    Class.forName("com.sk89q.worldedit.extent.clipboard.Clipboard");
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
            return true;
        } catch (Throwable t) {
            plugin.getLogger().warning("[Aetheria] pasteAt " + file.getName() + ": " + t);
            return false;
        }
    }
}
