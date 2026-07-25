package com.aetheria.plugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Villager;

/**
 * Gestiona los NPC como ENTIDADES normales del mundo (no jugadores), tal como pide la
 * vision. Hoy usa Villagers persistentes; el movimiento se hace por codigo (pathfinder).
 *
 * <p>Registro en memoria: clave -> UUID de la entidad. Simple y suficiente para empezar;
 * la persistencia entre reinicios se abordara mas adelante.
 */
public final class NpcManager {

    private final Map<String, UUID> npcs = new ConcurrentHashMap<>();

    /** Crea (o recoloca) el NPC con la clave dada en la ubicacion indicada. */
    public Villager spawn(String key, Location location) {
        remove(key);
        final Villager villager = (Villager) location.getWorld()
                .spawnEntity(location, EntityType.VILLAGER);
        villager.customName(net.kyori.adventure.text.Component.text(key));
        villager.setCustomNameVisible(true);
        villager.setPersistent(true);
        villager.setRemoveWhenFarAway(false);
        npcs.put(key, villager.getUniqueId());
        return villager;
    }

    /** Devuelve la entidad NPC viva con esa clave, o null si no existe. */
    public Entity get(String key) {
        final UUID id = npcs.get(key);
        if (id == null) {
            return null;
        }
        return Bukkit.getEntity(id);
    }

    /** Elimina el NPC con esa clave (si existe). */
    public boolean remove(String key) {
        final UUID id = npcs.remove(key);
        if (id == null) {
            return false;
        }
        final Entity entity = Bukkit.getEntity(id);
        if (entity != null) {
            entity.remove();
            return true;
        }
        return false;
    }
}
