package com.aetheria.plugin;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Villager;

import net.kyori.adventure.text.Component;

/**
 * Conserje: UN solo personaje que ronda su zona, con su nombre sobre la cabeza y oficio acorde
 * (bibliotecario/informador). Es conversable y su persona (en el orchestrator) conoce TODO lo que
 * se puede hacer en el server, para orientar a quien llega.
 *
 * <p>Camina DE VERDAD (pathfinding del propio aldeano) por un octogono alrededor de su punto,
 * con rescate anti-atasco; se detiene si alguien habla con el (la conversacion pausa su IA).
 * Ademas esta ACOTADO: si el cerebro vanilla del aldeano tira de el hacia sus POI y se aleja mas
 * de {@link #LEASH} bloques del centro, vuelve a la ronda por el punto mas cercano (no rebota
 * contra el limite, que era lo que le hacia ir y venir en una esquina).
 */
public final class LobbyGuideModule {

    private static final String TAG = "aetheria_lobby_npc";
    private static final String NPC_ID = "conserje-lobby";
    private static final String NAME = "§bAeon §7el Conserje";
    private static final double ARRIVE_SQ = 4.0;   // 2 bloques: se da por llegado y sigue la ronda
    private static final double SPEED = 0.8;
    private static final long PERIOD = 40L;
    /** Nunca se aleja mas de esto del centro de su ronda. */
    private static final double LEASH = 5.0;

    private final AetheriaPlugin plugin;
    private final ConversationManager convo;
    private final World world;
    private final Location center;
    // Puntos de ronda (relativos al centro): un octogono, que da un paseo mas natural que un
    // simple ir y venir entre dos puntos.
    private final int[][] patrol;

    private Villager npc;
    private int target = 0;
    private Location last;
    private int stuck;

    /** Ronda en un octogono de radio 3 (cabe de sobra dentro de la correa de 5). */
    public LobbyGuideModule(AetheriaPlugin plugin, ConversationManager convo, Location center) {
        this(plugin, convo, center, 3);
    }

    public LobbyGuideModule(AetheriaPlugin plugin, ConversationManager convo, Location center,
            int radius) {
        this.plugin = plugin;
        this.convo = convo;
        this.world = center.getWorld();
        this.center = center;
        final int r = Math.max(1, radius);
        this.patrol = new int[][] {
            {r, 0}, {r, r}, {0, r}, {-r, r}, {-r, 0}, {-r, -r}, {0, -r}, {r, -r},
        };
    }

    public void start() {
        clearOld();
        this.npc = spawn();
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, PERIOD, PERIOD);
        plugin.getLogger().info("Conserje '" + NAME + "' rondando (a menos de " + (int) LEASH
                + " bloques de su punto).");
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
        v.setProfession(Villager.Profession.LIBRARIAN);   // informador
        v.setVillagerType(Villager.Type.PLAINS);
        v.setVillagerLevel(5);
        v.addScoreboardTag(TAG);
        convo.registerConversable(v, NPC_ID, "Aeon");
        DisguiseModule.humanize(v, "m", "Aeon", "concierge");   // aspecto humano (si hay plugin)
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
        final Location here = npc.getLocation();
        // CORREA: si se ha ido de su zona, vuelve y RETOMA la ronda por el punto mas cercano
        // (no por el que tocaba), para no quedarse rebotando contra el limite.
        if (here.distanceSquared(center) > LEASH * LEASH) {
            target = nextFrom(here);
            npc.teleport(at(patrol[target]));
            stuck = 0;
            last = null;
            return;
        }
        final Location dest = at(patrol[target]);
        if (here.distanceSquared(dest) <= ARRIVE_SQ) {
            target = (target + 1) % patrol.length;   // siguiente punto de la ronda
            stuck = 0;
            last = here;
            return;
        }
        npc.getPathfinder().moveTo(dest, SPEED);
        // Anti-atasco: si lleva varios ciclos practicamente congelado, se le lleva al destino.
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

    /** El punto de ronda SIGUIENTE al mas cercano a donde esta (para seguir avanzando). */
    private int nextFrom(Location here) {
        int best = 0;
        double bestD = Double.MAX_VALUE;
        for (int i = 0; i < patrol.length; i++) {
            final double d = here.distanceSquared(at(patrol[i]));
            if (d < bestD) {
                bestD = d;
                best = i;
            }
        }
        return (best + 1) % patrol.length;
    }

    private Location at(int[] off) {
        return new Location(world, center.getX() + off[0], center.getBlockY(), center.getZ() + off[1]);
    }
}
