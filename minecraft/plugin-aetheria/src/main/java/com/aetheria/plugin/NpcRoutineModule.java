package com.aetheria.plugin;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

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
        long lastRemark;    // ultima vez que solto un comentario curioso

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
    private final VillageModule village;
    private final List<Worker> workers = new ArrayList<>();
    private int taskId = -1;

    public NpcRoutineModule(AetheriaPlugin plugin, ConversationManager convo, World world,
            VillageModule village) {
        this.plugin = plugin;
        this.convo = convo;
        this.world = world;
        this.village = village;
    }

    /** Coloca a los vecinos en su casa/puesto de la aldea y arranca el bucle de rutina. */
    public void start() {
        clearOld();
        // Cada vecino vive y trabaja en edificios REALES de la aldea (VillageModule).
        workers.add(new Worker("vecina-nara", "Nara",
                village.naraHome(), village.naraWork(), village.plaza()));
        workers.add(new Worker("vecino-pol", "Pol",
                village.polHome(), village.polWork(), village.plaza()));
        workers.add(new Worker("vecina-sella", "Sella",
                village.mercaderHome(), village.mercaderWork(), village.plaza()));

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

    private static final int BASE = 3;   // Nara, Pol y Sella son fijos

    /** Cuantos colonos (vecinos extra al nucleo) hay ahora mismo. */
    public int colonoCount() {
        return Math.max(0, workers.size() - BASE);
    }

    /** Da de alta un colono nuevo con su oficio, que vive y trabaja donde se indica. */
    public void addColono(String npcId, String name, Location home, Location work,
            Villager.Profession prof) {
        final Worker w = new Worker(npcId, name, home, work, village.plaza());
        w.entity = spawnWorker(w);
        if (w.entity instanceof Villager v) {
            v.setProfession(prof);
        }
        workers.add(w);
    }

    /** Da de baja al colono mas reciente (emigra). Nunca toca al nucleo. Devuelve su nombre. */
    public String removeNewestColono() {
        if (workers.size() <= BASE) {
            return null;
        }
        final Worker w = workers.remove(workers.size() - 1);
        if (w.entity != null) {
            w.entity.remove();
        }
        return w.name;
    }

    /** Da de baja a un colono concreto por su nombre (p.ej. al morir). */
    public boolean removeColono(String name) {
        for (int i = workers.size() - 1; i >= BASE; i--) {
            if (workers.get(i).name.equals(name)) {
                final Worker w = workers.remove(i);
                if (w.entity != null) {
                    w.entity.remove();
                }
                return true;
            }
        }
        return false;
    }

    /** Jubila a un colono: pierde el oficio y su nombre pasa a "(jubilado)". */
    public void retire(String name) {
        for (final Worker w : workers) {
            if (w.name.equals(name) && w.entity instanceof Villager v) {
                v.setProfession(Villager.Profession.NONE);
                v.customName(Component.text("§7" + name + " §8(jubilado)"));
                return;
            }
        }
    }

    /** Cambia el oficio de un colono (p.ej. al heredar el puesto de un fallecido). */
    public void setProfession(String name, Villager.Profession prof) {
        for (final Worker w : workers) {
            if (w.name.equals(name) && w.entity instanceof Villager v) {
                v.setProfession(prof);
                return;
            }
        }
    }

    private void clearOld() {
        world.getEntities().stream()
                .filter(e -> e.getScoreboardTags().contains(WORKER_TAG))
                .forEach(org.bukkit.entity.Entity::remove);
    }

    // Biomas -> colores/estilo de atuendo distintos. El oficio (Profession) pone ademas el
    // distintivo del trabajo; asi cada aldeano viste diferente y acorde a lo que hace.
    private static final Villager.Type[] TYPES = {
        Villager.Type.PLAINS, Villager.Type.TAIGA, Villager.Type.SNOW, Villager.Type.SAVANNA,
        Villager.Type.DESERT, Villager.Type.JUNGLE, Villager.Type.SWAMP,
    };

    private Mob spawnWorker(Worker w) {
        // Reutiliza un aldeano ya existente con ese nombre (evita CLONES al recargar chunks o
        // reiniciar: no volvemos a generar uno si el mundo ya guardo al original).
        for (final org.bukkit.entity.Entity e : world.getEntities()) {
            if (e instanceof Villager ex && e.getScoreboardTags().contains(WORKER_TAG)
                    && ex.customName() != null) {
                final String pn = PlainTextComponentSerializer.plainText().serialize(ex.customName());
                if (pn.equals(w.name) || pn.startsWith(w.name + " ")) {
                    convo.registerConversable(ex, w.npcId, w.name);
                    return ex;
                }
            }
        }
        final Villager v = (Villager) world.spawnEntity(w.home, EntityType.VILLAGER);
        v.customName(Component.text(w.name));
        v.setCustomNameVisible(true);
        v.setPersistent(true);
        v.setRemoveWhenFarAway(false);
        v.setInvulnerable(true);           // no queremos que un zombie termine con la rutina
        v.addScoreboardTag(WORKER_TAG);
        final int h = w.name.hashCode() & 0x7fffffff;
        v.setVillagerType(TYPES[h % TYPES.length]);   // ropaje variado segun el nombre
        v.setVillagerLevel(5);             // maestro: muestra el distintivo del oficio
        convo.registerConversable(v, w.npcId, w.name);
        return v;
    }

    /**
     * Barrida anti-clones: elimina cualquier aldeano etiquetado que el plugin ya no rastrea
     * (restos de un reinicio con la casa en un chunk descargado, o de una recarga de chunk).
     * Con nombres unicos, todo villager que no sea uno de los nuestros es un duplicado.
     */
    public void dedupe() {
        for (final org.bukkit.entity.Entity e : world.getEntities()) {
            if (!e.getScoreboardTags().contains(WORKER_TAG)) {
                continue;
            }
            boolean tracked = false;
            for (final Worker w : workers) {
                if (e == w.entity) {
                    tracked = true;
                    break;
                }
            }
            if (!tracked) {
                e.remove();
            }
        }
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
            maybeRemark(w);                   // comentario curioso si hay alguien cerca
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

    private static final String[] REMARKS = {
        "Buen dia para trabajar, ¿no crees?",
        "Dicen en la taberna que el pueblo prospera.",
        "Cuidado de noche, que salen cosas por los caminos.",
        "¿Ya conoces a Sella, la del mercado?",
        "He oido que alguien se ha construido una casa nueva.",
        "Si necesitas algo, pregunta por la plaza.",
        "El herrero anda muy ocupado estos dias.",
        "Los cultivos van creciendo poco a poco.",
        "Bienvenido, viajero. Ponte comodo.",
        "Cada dia llega mas gente al pueblo.",
        "Trabajar de dia, descansar de noche: asi es la vida aqui.",
        "¿Has probado a vender en el mercado? Da buenas monedas.",
    };

    /** Si hay un jugador cerca, el vecino suelta un comentario curioso (con cooldown). */
    private void maybeRemark(Worker w) {
        final long now = System.currentTimeMillis();
        if (now - w.lastRemark < 30000L) {
            return;
        }
        if (java.util.concurrent.ThreadLocalRandom.current().nextInt(100) >= 5) {
            return;   // ~1 comentario cada pocos segundos como mucho
        }
        for (final Player p : w.entity.getWorld().getPlayers()) {
            if (p.getLocation().distanceSquared(w.entity.getLocation()) <= 25) {   // 5 bloques
                w.lastRemark = now;
                final String line = REMARKS[java.util.concurrent.ThreadLocalRandom.current().nextInt(REMARKS.length)];
                p.sendMessage("§e[" + w.name + "] §7" + line);
                return;
            }
        }
    }
}
