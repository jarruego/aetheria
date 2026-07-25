package com.aetheria.plugin;

import java.io.File;
import java.io.IOException;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

/**
 * Gestiona la "casa" de cada jugador (una posicion guardada por UUID).
 *
 * <p>Persistencia v1: fichero local del plugin (homes.yml). Cuando exista el camino de
 * escritura a la base de datos (que economia y parcelas tambien necesitan), las casas
 * migraran a Supabase; por eso el acceso queda aislado en esta clase.
 */
public final class HomeManager {

    private final AetheriaPlugin plugin;
    private final File file;
    private final YamlConfiguration cfg;

    public HomeManager(AetheriaPlugin plugin) {
        this.plugin = plugin;
        if (!plugin.getDataFolder().exists()) {
            plugin.getDataFolder().mkdirs();
        }
        this.file = new File(plugin.getDataFolder(), "homes.yml");
        this.cfg = YamlConfiguration.loadConfiguration(file);
    }

    public void setHome(Player player) {
        final Location l = player.getLocation();
        final String base = player.getUniqueId().toString();
        cfg.set(base + ".world", l.getWorld().getName());
        cfg.set(base + ".x", l.getX());
        cfg.set(base + ".y", l.getY());
        cfg.set(base + ".z", l.getZ());
        cfg.set(base + ".yaw", (double) l.getYaw());
        cfg.set(base + ".pitch", (double) l.getPitch());
        save();
    }

    /** Devuelve la casa del jugador, o null si no tiene o su mundo no existe aqui. */
    public Location getHome(Player player) {
        final String base = player.getUniqueId().toString();
        if (!cfg.contains(base)) {
            return null;
        }
        final World world = Bukkit.getWorld(cfg.getString(base + ".world", ""));
        if (world == null) {
            return null;
        }
        return new Location(
                world,
                cfg.getDouble(base + ".x"),
                cfg.getDouble(base + ".y"),
                cfg.getDouble(base + ".z"),
                (float) cfg.getDouble(base + ".yaw"),
                (float) cfg.getDouble(base + ".pitch"));
    }

    private void save() {
        try {
            cfg.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("No se pudo guardar homes.yml: " + e.getMessage());
        }
    }
}
