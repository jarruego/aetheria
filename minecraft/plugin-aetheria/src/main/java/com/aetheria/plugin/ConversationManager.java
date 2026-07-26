package com.aetheria.plugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.inventory.EquipmentSlot;
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
    // NPC con rutina cuya IA hemos pausado por estar hablando (para reanudarla al terminar).
    private final java.util.Set<UUID> paused = ConcurrentHashMap.newKeySet();

    /** True si algun jugador esta hablando con este NPC (su rutina debe detenerlo). */
    public boolean isBusy(UUID npcId) {
        return talking.containsValue(npcId);
    }

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
        if (event.getHand() != EquipmentSlot.HAND) {
            return;   // el evento se dispara por ambas manos: solo atendemos la principal
        }
        final var entity = event.getRightClicked();
        final NpcInfo info = npcs.get(entity.getUniqueId());
        if (info == null) {
            return;
        }
        event.setCancelled(true);   // evita el GUI de comercio del aldeano
        final Player player = event.getPlayer();
        talking.put(player.getUniqueId(), entity.getUniqueId());
        // Si es un vecino con rutina, se DETIENE y te mira mientras dura la charla.
        if (entity instanceof Mob mob && mob.hasAI()) {
            mob.getPathfinder().stopPathfinding();
            mob.setAI(false);
            paused.add(entity.getUniqueId());
        }
        faceToward(entity, player);
        player.sendMessage("§e[" + info.name() + "] §fHola, viajero. Preguntame lo que quieras.");
        player.sendMessage("§7(escribe en el chat; di §fadios§7 para terminar)");
    }

    /** Gira al NPC para que mire al jugador (funciona aunque tenga la IA pausada). */
    private void faceToward(org.bukkit.entity.Entity npc, Player player) {
        final Location nl = npc.getLocation();
        final double dx = player.getLocation().getX() - nl.getX();
        final double dz = player.getLocation().getZ() - nl.getZ();
        nl.setYaw((float) Math.toDegrees(Math.atan2(-dx, dz)));
        nl.setPitch(0f);
        npc.teleport(nl);
    }

    /** Cierra la conversacion de un jugador y reanuda la rutina del NPC si la habiamos pausado. */
    private void endConversation(UUID playerUuid) {
        final UUID npcUuid = talking.remove(playerUuid);
        if (npcUuid != null && paused.remove(npcUuid) && Bukkit.getEntity(npcUuid) instanceof Mob mob) {
            mob.setAI(true);   // vuelve a su rutina diaria
        }
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
            runSync(() -> {
                endConversation(player.getUniqueId());
                player.sendMessage("§7Terminas la conversacion.");
            });
            return;
        }

        runSync(() -> player.sendMessage("§7Tu: §f" + msg));
        gateway.conversation(info.npcId(), player.getUniqueId().toString(), msg, info.name())
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
            endConversation(player.getUniqueId());
            player.sendMessage("§7Te alejas y terminas la conversacion.");
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        endConversation(event.getPlayer().getUniqueId());
    }

    private void runSync(Runnable task) {
        Bukkit.getScheduler().runTask(plugin, task);
    }
}
