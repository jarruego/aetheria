package com.aetheria.plugin;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Villager;

import net.kyori.adventure.text.Component;

/**
 * Conserje del lobby: UN solo personaje que ronda la sala, con su nombre sobre la cabeza y
 * oficio acorde (bibliotecario/informador). Es conversable y su persona (en el orchestrator)
 * conoce TODO lo que se puede hacer en el server, para orientar a quien llega.
 *
 * <p>Se mueve con pathfinding por codigo (como los vecinos del pueblo), con rescate
 * anti-atasco; se detiene si alguien habla con el (la conversacion pausa su IA).
 */
public final class LobbyGuideModule {

    private static final String TAG = "aetheria_lobby_npc";
    private static final String NPC_ID = "conserje-lobby";
    private static final String NAME = "§bAeon §7el Conserje";
    private static final long PERIOD = 2L;        // se mueve 10 veces por segundo (paseo fluido)
    private static final double STEP = 0.02;      // radianes por paso: una vuelta cada ~16 s

    private final AetheriaPlugin plugin;
    private final ConversationManager convo;
    private final World world;
    private final Location center;
    private final int radius;

    private Villager npc;
    private double angle;

    /** Ronda en circulos de radio 3 (para el lobby). */
    public LobbyGuideModule(AetheriaPlugin plugin, ConversationManager convo, Location center) {
        this(plugin, convo, center, 3);
    }

    /** Ronda en circulos del radio dado alrededor del punto indicado. */
    public LobbyGuideModule(AetheriaPlugin plugin, ConversationManager convo, Location center,
            int radius) {
        this.plugin = plugin;
        this.convo = convo;
        this.world = center.getWorld();
        this.center = center;
        this.radius = Math.max(1, radius);
    }

    public void start() {
        clearOld();
        this.npc = spawn();
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, PERIOD, PERIOD);
        plugin.getLogger().info("Conserje '" + NAME + "' rondando en circulo (radio " + radius + ").");
    }

    private void clearOld() {
        world.getEntities().stream()
                .filter(e -> e.getScoreboardTags().contains(TAG))
                .forEach(org.bukkit.entity.Entity::remove);
    }

    private Villager spawn() {
        final Villager v = (Villager) world.spawnEntity(at(0), EntityType.VILLAGER);
        v.customName(Component.text(NAME));
        v.setCustomNameVisible(true);         // nombre sobre la cabeza
        v.setPersistent(true);
        v.setRemoveWhenFarAway(false);
        v.setInvulnerable(true);
        v.setProfession(Villager.Profession.LIBRARIAN);   // informador del lobby
        v.setVillagerType(Villager.Type.PLAINS);
        v.setVillagerLevel(5);
        // SIN IA: el cerebro vanilla del aldeano tira de el hacia sus POI y lo sacaba de la
        // ronda (se quedaba yendo y viniendo en una esquina, peleado con el pathfinding).
        // Aqui el paseo lo lleva el plugin: un circulo limpio alrededor del punto.
        v.setAI(false);
        v.addScoreboardTag(TAG);
        convo.registerConversable(v, NPC_ID, "Aeon");
        return v;
    }

    private void tick() {
        if (npc == null || npc.isDead()) {
            npc = spawn();
            return;
        }
        if (convo.isBusy(npc.getUniqueId())) {
            return;   // atendiendo a alguien: se para a hablar
        }
        if (npc.hasAI()) {
            npc.setAI(false);
        }
        angle += STEP;
        if (angle > Math.PI * 2) {
            angle -= Math.PI * 2;
        }
        npc.teleport(at(angle));
    }

    /** Punto del circulo para ese angulo, MIRANDO hacia donde camina (yaw tangente). */
    private Location at(double a) {
        final Location loc = new Location(world,
                center.getX() + Math.cos(a) * radius,
                center.getY(),
                center.getZ() + Math.sin(a) * radius);
        loc.setYaw((float) Math.toDegrees(a));
        return loc;
    }

}
