package com.aetheria.plugin;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Villager;

import net.kyori.adventure.text.Component;

/**
 * Fase 7 - NPC vivos. Aldeanos "vecinos" con una RUTINA DIARIA por horario: trabajan de
 * dia, se reunen en la plaza al atardecer y se van a casa de noche. El movimiento se hace
 * por codigo (pathfinding), no por la IA del LLM: la IA sigue solo proponiendo planes.
 *
 * <p>Son ademas conversables (se registran en {@link ConversationManager}), asi que el
 * mundo empieza a sentirse habitado en lugar de tener guias estaticos junto a los portales.
 */
public final class NpcRoutineModule {

    private static final String WORKER_TAG = "aetheria_worker";
    private static final double ARRIVE_SQ = 4.0;   // 2 bloques: se considera "ha llegado"
    private static final double SPEED = 1.1;       // multiplicador de velocidad del aldeano
    private static final long PERIOD_TICKS = 10L;  // reevalua/reemite el camino 2 veces/seg

    /** Un vecino: su entidad, su persona y sus tres puntos de la jornada. */
    private static final class Worker {
        final String npcId;
        final String name;
        final Location home;
        final Location work;
        final Location plaza;
        Mob entity;
        Location last;      // ultima posicion vista (para detectar atascos)
        int stuck;          // ticks de rutina seguidos sin avanzar

        Worker(String npcId, String name, Location home, Location work, Location plaza) {
            this.npcId = npcId;
            this.name = name;
            this.home = home;
            this.work = work;
            this.plaza = plaza;
        }
    }

    private final AetheriaPlugin plugin;
    private final ConversationManager convo;
    private final World world;
    private final List<Worker> workers = new ArrayList<>();
    private int taskId = -1;

    public NpcRoutineModule(AetheriaPlugin plugin, ConversationManager convo, World world) {
        this.plugin = plugin;
        this.convo = convo;
        this.world = world;
    }

    /** Coloca a los vecinos alrededor del spawn y arranca el bucle de rutina. */
    public void start() {
        clearOld();
        final Location spawn = world.getSpawnLocation();
        // Puntos relativos al spawn: casa, trabajo y plaza. Distancias moderadas para que
        // sean alcanzables a pie sobre terreno generado (los muy lejanos se vuelven inaccesibles).
        workers.add(new Worker("vecina-nara", "Nara",
                offset(spawn, 6, 5), offset(spawn, 13, -3), offset(spawn, 2, 2)));
        workers.add(new Worker("vecino-pol", "Pol",
                offset(spawn, -6, 5), offset(spawn, -12, -4), offset(spawn, -2, 2)));

        for (final Worker w : workers) {
            w.entity = spawnWorker(w);
        }

        this.taskId = plugin.getServer().getScheduler().runTaskTimer(
                plugin, this::tick, PERIOD_TICKS, PERIOD_TICKS).getTaskId();
        plugin.getLogger().info("[Aetheria] Fase 7: " + workers.size()
                + " vecinos con rutina diaria en '" + world.getName() + "'.");
    }

    public void stop() {
        if (taskId != -1) {
            plugin.getServer().getScheduler().cancelTask(taskId);
            taskId = -1;
        }
    }

    private void clearOld() {
        world.getEntities().stream()
                .filter(e -> e.getScoreboardTags().contains(WORKER_TAG))
                .forEach(org.bukkit.entity.Entity::remove);
    }

    private Mob spawnWorker(Worker w) {
        final Villager v = (Villager) world.spawnEntity(w.home, EntityType.VILLAGER);
        v.customName(Component.text(w.name));
        v.setCustomNameVisible(true);
        v.setPersistent(true);
        v.setRemoveWhenFarAway(false);
        v.setInvulnerable(true);           // no queremos que un zombie termine con la rutina
        v.addScoreboardTag(WORKER_TAG);
        v.setVillagerLevel(3);
        convo.registerConversable(v, w.npcId, w.name);
        return v;
    }

    /**
     * Nucleo de la rutina: segun la hora del mundo, cada vecino se dirige a un punto.
     * 0-12000 = jornada laboral, 12000-13500 = reunion en la plaza, resto = a casa.
     */
    private void tick() {
        final long time = world.getTime();   // 0..24000 (0 = amanecer)
        for (final Worker w : workers) {
            if (w.entity == null || w.entity.isDead()) {
                w.entity = spawnWorker(w);    // resucita si algo lo elimino
                continue;
            }
            if (convo.isBusy(w.entity.getUniqueId())) {
                continue;                     // esta hablando: quieto (su IA esta pausada)
            }
            if (!w.entity.hasAI()) {
                w.entity.setAI(true);         // red de seguridad: reanuda si quedo pausado
            }
            final Location target;
            if (time < 12000L) {
                target = w.work;
            } else if (time < 13500L) {
                target = w.plaza;
            } else {
                target = w.home;
            }
            final Location at = w.entity.getLocation();
            if (!at.getWorld().equals(target.getWorld()) || at.distanceSquared(target) <= ARRIVE_SQ) {
                w.stuck = 0;
                w.last = at;
                continue;   // ha llegado (o no aplica): nada que hacer
            }
            w.entity.getPathfinder().moveTo(target, SPEED);

            // Anti-atasco: si esta PRACTICAMENTE congelado varios ciclos (terreno, cerebro del
            // aldeano peleando con el pathfinding...), se le teletransporta al destino y sigue.
            if (w.last != null && at.distanceSquared(w.last) < 0.01) {
                if (++w.stuck >= 6) {   // ~3 s sin moverse
                    w.entity.teleport(target);
                    w.stuck = 0;
                }
            } else {
                w.stuck = 0;
            }
            w.last = at;
        }
    }

    /** Punto a nivel de suelo desplazado (dx, dz) desde una base. */
    private static Location offset(Location base, double dx, double dz) {
        final Location loc = base.clone().add(dx, 0, dz);
        loc.setY(base.getWorld().getHighestBlockYAt(loc) + 1);
        return loc;
    }
}
