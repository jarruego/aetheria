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
}
