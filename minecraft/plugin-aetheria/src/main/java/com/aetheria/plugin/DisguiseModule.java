package com.aetheria.plugin;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;

/**
 * Registro de los NPC que deben verse HUMANOS (skin). El disfraz real lo aplica
 * {@link HumanSkinListener} (packetevents): intercepta el paquete de aparicion del aldeano y lo
 * reescribe a JUGADOR con skin. Aqui solo se anota QUE entidades son NPC y con que sexo.
 *
 * <p>Dependencia BLANDA de packetevents: si el plugin no esta, {@link #available()} es false, no se
 * registra el listener y los NPC se quedan de aldeanos (nunca rompe nada).
 */
public final class DisguiseModule {

    private DisguiseModule() {
    }

    // UUID del NPC -> sexo, -> nombre real, -> oficio (clave en ingles). Y entityId -> UUID.
    private static final java.util.Map<UUID, String> BY_UUID = new ConcurrentHashMap<>();
    private static final java.util.Map<UUID, String> NAME_BY_UUID = new ConcurrentHashMap<>();
    private static final java.util.Map<UUID, String> PROF_BY_UUID = new ConcurrentHashMap<>();
    private static final java.util.Map<Integer, UUID> BY_ENTITY = new ConcurrentHashMap<>();

    public static boolean available() {
        return Bukkit.getPluginManager().getPlugin("packetevents") != null;
    }

    /** Marca a un NPC para que se vea humano (skin de OFICIO si la hay, si no por sexo). */
    public static void humanize(Entity npc, String gender, String name) {
        humanize(npc, gender, name, null);
    }

    public static void humanize(Entity npc, String gender, String name, String profKey) {
        if (npc != null) {
            BY_UUID.put(npc.getUniqueId(), (gender == null || gender.isEmpty()) ? "m" : gender);
            if (name != null && !name.isEmpty()) {
                NAME_BY_UUID.put(npc.getUniqueId(), name);
            }
            if (profKey != null && !profKey.isEmpty()) {
                PROF_BY_UUID.put(npc.getUniqueId(), profKey);
            }
        }
    }

    /** Sexo del NPC con ese UUID, o null si no es NPC nuestro. */
    public static String genderOf(UUID uuid) {
        return BY_UUID.get(uuid);
    }

    /** Oficio (clave en ingles) del NPC, o null. */
    public static String profOf(UUID uuid) {
        return PROF_BY_UUID.get(uuid);
    }

    /** Nombre real del NPC (para mostrarlo encima como nombre de jugador), o null. */
    public static String nameOf(UUID uuid) {
        return NAME_BY_UUID.get(uuid);
    }

    public static void trackEntity(int entityId, UUID uuid) {
        BY_ENTITY.put(entityId, uuid);
    }

    public static boolean isDisguised(int entityId) {
        return BY_ENTITY.containsKey(entityId);
    }
}
