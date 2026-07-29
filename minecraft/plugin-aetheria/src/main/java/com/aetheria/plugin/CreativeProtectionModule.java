package com.aetheria.plugin;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;

import net.kyori.adventure.text.Component;

/**
 * PROTECCION DEL CREATIVO: todo lo que COLOCA un jugador queda registrado como SUYO, y solo ese
 * mismo jugador (o un op/admin) puede romperlo. Asi nadie destruye las construcciones de otro.
 *
 * <p>Solo se protege lo que un jugador ha puesto: el terreno natural del mundo (sin dueno) se puede
 * romper libremente. La propiedad se guarda en creative-owners.txt (posicion empaquetada -> uuid).
 */
public final class CreativeProtectionModule implements Listener {

    private final AetheriaPlugin plugin;
    private final World world;
    private final File file;
    private final Map<Long, UUID> owners = new HashMap<>();
    private boolean dirty;

    public CreativeProtectionModule(AetheriaPlugin plugin, World world) {
        this.plugin = plugin;
        this.world = world;
        this.file = new File(plugin.getDataFolder(), "creative-owners.txt");
        load();
        // Guardado periodico (cada 60 s) solo si hubo cambios: no se escribe el fichero por cada bloque.
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (dirty) {
                save();
                dirty = false;
            }
        }, 1200L, 1200L);
    }

    /** Clave de posicion de bloque (formato vanilla: x 26 bits, z 26 bits, y 12 bits). */
    private static long key(Block b) {
        return ((long) (b.getX() & 0x3FFFFFF) << 38) | ((long) (b.getZ() & 0x3FFFFFF) << 12)
                | (b.getY() & 0xFFF);
    }

    @EventHandler
    public void onPlace(BlockPlaceEvent e) {
        if (!e.getBlock().getWorld().equals(world)) {
            return;
        }
        owners.put(key(e.getBlock()), e.getPlayer().getUniqueId());
        dirty = true;
    }

    @EventHandler
    public void onBreak(BlockBreakEvent e) {
        if (!e.getBlock().getWorld().equals(world)) {
            return;
        }
        final long k = key(e.getBlock());
        final UUID owner = owners.get(k);
        final Player p = e.getPlayer();
        if (owner != null && !owner.equals(p.getUniqueId())
                && !p.isOp() && !p.hasPermission("aetheria.creative.admin")) {
            e.setCancelled(true);
            p.sendActionBar(Component.text("§cEso lo construyo otro jugador; no puedes romperlo."));
            return;
        }
        if (owner != null) {
            owners.remove(k);   // se rompio legitimamente: deja de estar protegido
            dirty = true;
        }
    }

    // Explosiones (TNT/creeper): nunca destruyen lo que tiene dueno (un jugador no borra lo de otro
    // con TNT). Los bloques con dueno se sacan de la lista de la explosion.
    @EventHandler
    public void onEntityExplode(EntityExplodeEvent e) {
        if (e.getEntity().getWorld().equals(world)) {
            e.blockList().removeIf(b -> owners.containsKey(key(b)));
        }
    }

    @EventHandler
    public void onBlockExplode(BlockExplodeEvent e) {
        e.blockList().removeIf(b -> owners.containsKey(key(b)));
    }

    private void load() {
        if (!file.exists()) {
            return;
        }
        try (BufferedReader r = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = r.readLine()) != null) {
                final int i = line.indexOf(';');
                if (i > 0) {
                    try {
                        owners.put(Long.parseLong(line.substring(0, i)),
                                UUID.fromString(line.substring(i + 1)));
                    } catch (IllegalArgumentException ignored) {
                        // linea corrupta: se ignora
                    }
                }
            }
        } catch (Exception ex) {
            plugin.getLogger().warning("[Aetheria] no pude cargar duenos del creativo: " + ex.getMessage());
        }
        plugin.getLogger().info("[Aetheria] Proteccion del creativo: " + owners.size()
                + " bloques con dueno.");
    }

    private void save() {
        try (FileWriter w = new FileWriter(file, false)) {
            for (final Map.Entry<Long, UUID> e : owners.entrySet()) {
                w.write(e.getKey() + ";" + e.getValue() + "\n");
            }
        } catch (Exception ex) {
            plugin.getLogger().warning("[Aetheria] no pude guardar duenos del creativo: " + ex.getMessage());
        }
    }
}
