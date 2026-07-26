package com.aetheria.plugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

/**
 * NPC guias conversables: aldeanos junto a los portales con los que se puede hablar.
 *
 * <p>Modo charla inmersivo: al hacer clic derecho en un guia, el jugador entra en
 * conversacion; sus mensajes del chat van SOLO a ese NPC (no al chat publico) y el NPC
 * responde por la tuberia de 3 niveles del backend. Se sale diciendo "adios" o alejandose.
 */
public final class ConversationManager implements Listener {

    private static final String GUIDE_TAG = "aetheria_guide";
    private static final double LEAVE_DISTANCE_SQ = 36.0; // 6 bloques

    private record NpcInfo(String npcId, String name) {}

    private final AetheriaPlugin plugin;
    private final GatewayClient gateway;
    private final Map<UUID, NpcInfo> npcs = new ConcurrentHashMap<>();   // entidad -> info
    private final Map<UUID, UUID> talking = new ConcurrentHashMap<>();   // jugador -> entidad NPC

    public ConversationManager(AetheriaPlugin plugin, GatewayClient gateway) {
        this.plugin = plugin;
        this.gateway = gateway;
    }

    /** Elimina los guias existentes en el mundo (evita duplicados al reiniciar el plugin). */
    public void clearGuides(World world) {
        world.getEntities().stream()
                .filter(e -> e.getScoreboardTags().contains(GUIDE_TAG))
                .forEach(org.bukkit.entity.Entity::remove);
    }

    // Apariencias distintas: cada guia viste segun su bioma + profesion (deterministico).
    private static final Villager.Type[] TYPES = {
        Villager.Type.PLAINS, Villager.Type.DESERT, Villager.Type.SAVANNA,
        Villager.Type.JUNGLE, Villager.Type.TAIGA, Villager.Type.SNOW, Villager.Type.SWAMP,
    };
    private static final Villager.Profession[] PROFS = {
        Villager.Profession.ARMORER, Villager.Profession.LIBRARIAN, Villager.Profession.CARTOGRAPHER,
        Villager.Profession.CLERIC, Villager.Profession.MASON, Villager.Profession.TOOLSMITH,
        Villager.Profession.WEAPONSMITH, Villager.Profession.FLETCHER, Villager.Profession.SHEPHERD,
    };

    /** Crea un aldeano-guia conversable, quieto e invulnerable, en la ubicacion dada. */
    public Villager spawnGuide(Location loc, String npcId, String name) {
        final Villager v = (Villager) loc.getWorld().spawnEntity(loc, EntityType.VILLAGER);
        v.customName(Component.text(name));
        v.setCustomNameVisible(true);
        v.setAI(false);
        v.setGravity(false);
        v.setInvulnerable(true);
        v.setPersistent(true);
        v.setRemoveWhenFarAway(false);
        v.setSilent(true);
        v.setCollidable(false);
        v.addScoreboardTag(GUIDE_TAG);
        applyLook(v, npcId);
        npcs.put(v.getUniqueId(), new NpcInfo(npcId, name));
        return v;
    }

    /**
     * Registra una entidad ya existente como conversable (p.ej. un vecino con rutina de la
     * Fase 7). No cambia su IA ni su aspecto: solo la hace hablable por clic derecho.
     */
    public void registerConversable(org.bukkit.entity.Entity entity, String npcId, String name) {
        npcs.put(entity.getUniqueId(), new NpcInfo(npcId, name));
    }

    /** Da a cada guia una apariencia distinta (bioma + profesion) segun su clave. */
    private void applyLook(Villager v, String npcId) {
        final int h = npcId.hashCode() & 0x7fffffff;
        v.setVillagerType(TYPES[h % TYPES.length]);
        v.setProfession(PROFS[(h / TYPES.length) % PROFS.length]);
        v.setVillagerLevel(5);   // maestro: atuendo completo con distintivo
    }

    @EventHandler
    public void onInteract(PlayerInteractEntityEvent event) {
        final NpcInfo info = npcs.get(event.getRightClicked().getUniqueId());
        if (info == null) {
            return;
        }
        event.setCancelled(true);   // evita el GUI de comercio del aldeano
        final Player player = event.getPlayer();
        talking.put(player.getUniqueId(), event.getRightClicked().getUniqueId());
        player.sendMessage("§e[" + info.name() + "] §fHola, viajero. Preguntame lo que quieras.");
        player.sendMessage("§7(escribe en el chat; di §fadios§7 para terminar)");
    }

    @EventHandler
    public void onChat(AsyncChatEvent event) {
        final Player player = event.getPlayer();
        final UUID npcUuid = talking.get(player.getUniqueId());
        if (npcUuid == null) {
            return;   // no esta hablando con un NPC: chat normal
        }
        event.setCancelled(true);   // el mensaje no se emite al chat publico
        final NpcInfo info = npcs.get(npcUuid);
        final String msg = PlainTextComponentSerializer.plainText().serialize(event.message()).trim();

        if (info == null || msg.equalsIgnoreCase("adios") || msg.equalsIgnoreCase("adiós")
                || msg.equalsIgnoreCase("salir")) {
            talking.remove(player.getUniqueId());
            runSync(() -> player.sendMessage("§7Terminas la conversacion."));
            return;
        }

        runSync(() -> player.sendMessage("§7Tu: §f" + msg));
        gateway.conversation(info.npcId(), player.getUniqueId().toString(), msg)
                .whenComplete((json, err) -> runSync(() -> {
                    if (!player.isOnline()) {
                        return;
                    }
                    if (err != null) {
                        player.sendMessage("§c[" + info.name() + "] (no puedo responder ahora)");
                        return;
                    }
                    player.sendMessage("§a[" + info.name() + "] §f" + json.get("reply").getAsString());
                }));
    }

    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        final UUID npcUuid = talking.get(event.getPlayer().getUniqueId());
        if (npcUuid == null) {
            return;
        }
        final var npc = Bukkit.getEntity(npcUuid);
        final Player player = event.getPlayer();
        if (npc == null || !npc.getWorld().equals(player.getWorld())
                || npc.getLocation().distanceSquared(player.getLocation()) > LEAVE_DISTANCE_SQ) {
            talking.remove(player.getUniqueId());
            player.sendMessage("§7Te alejas y terminas la conversacion.");
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        talking.remove(event.getPlayer().getUniqueId());
    }

    private void runSync(Runnable task) {
        Bukkit.getScheduler().runTask(plugin, task);
    }
}
