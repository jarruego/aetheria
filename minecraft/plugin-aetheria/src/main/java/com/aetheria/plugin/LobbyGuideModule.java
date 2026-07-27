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
    private static final double ARRIVE_SQ = 2.0;
    private static final double SPEED = 0.8;
    private static final long PERIOD = 40L;

    private final AetheriaPlugin plugin;
    private final ConversationManager convo;
    private final World world;
    private final Location center;
    // Puntos de ronda (relativos al centro). En el lobby es un cuadrado pequeno; junto al spawn de
    // main es MAS AMPLIO para que Aeon pasee de verdad y no quede encerrado en el cuadro del portal.
    private final int[][] patrol;

    private Villager npc;
    private int target = 0;
    private Location last;
    private int stuck;

    /** Ronda en un cuadrado de radio 3 (para el lobby). */
    public LobbyGuideModule(AetheriaPlugin plugin, ConversationManager convo, Location center) {
        this(plugin, convo, center, 3);
    }

    /** Ronda en un cuadrado del radio dado (usa mas radio junto al spawn de main). */
    public LobbyGuideModule(AetheriaPlugin plugin, ConversationManager convo, Location center,
            int radius) {
        this.plugin = plugin;
        this.convo = convo;
        this.world = center.getWorld();
        this.center = center;
        final int r = radius;
        // Un octogono aproximado: da un paseo mas natural que un simple cuadrado.
        this.patrol = new int[][] {
            {r, 0}, {r, r}, {0, r}, {-r, r}, {-r, 0}, {-r, -r}, {0, -r}, {r, -r},
        };
    }

    public void start() {
        clearOld();
        this.npc = spawn();
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, PERIOD, PERIOD);
        plugin.getLogger().info("Lobby: conserje '" + NAME + "' rondando la sala.");
    }

    private void clearOld() {
        world.getEntities().stream()
                .filter(e -> e.getScoreboardTags().contains(TAG))
                .forEach(org.bukkit.entity.Entity::remove);
    }

    private Villager spawn() {
        final Villager v = (Villager) world.spawnEntity(at(patrol[0]), EntityType.VILLAGER);
        v.customName(Component.text(NAME));
        v.setCustomNameVisible(true);         // nombre sobre la cabeza
        v.setPersistent(true);
        v.setRemoveWhenFarAway(false);
        v.setInvulnerable(true);
        v.setProfession(Villager.Profession.LIBRARIAN);   // informador del lobby
        v.setVillagerType(Villager.Type.PLAINS);
        v.setVillagerLevel(5);
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
            return;   // atendiendo a alguien: quieto
        }
        if (!npc.hasAI()) {
            npc.setAI(true);
        }
        final Location dest = at(patrol[target]);
        final Location here = npc.getLocation();
        if (here.distanceSquared(dest) <= ARRIVE_SQ) {
            target = (target + 1) % patrol.length;   // siguiente punto de ronda
            stuck = 0;
            last = here;
            return;
        }
        npc.getPathfinder().moveTo(dest, SPEED);
        if (last != null && here.distanceSquared(last) < 0.01) {
            if (++stuck >= 4) {
                npc.teleport(dest);
                stuck = 0;
            }
        } else {
            stuck = 0;
        }
        last = here;
    }

    private Location at(int[] off) {
        return new Location(world, center.getX() + off[0], center.getBlockY(), center.getZ() + off[1]);
    }
}
