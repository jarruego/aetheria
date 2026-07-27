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
        Location home;
        Location work;
        final Location plaza;
        final Location town;   // centro de SU aldea (para pasear por su pueblo, no por otro)
        Mob entity;
        Location last;      // ultima posicion vista (para detectar atascos)
        int stuck;          // ticks de rutina seguidos sin avanzar
        long lastRemark;    // ultima vez que solto un comentario curioso
        Location wander;    // destino de paseo actual (o null si esta trabajando)
        long wanderUntil;   // hasta cuando dura el paseo
        String prof = "vecino";   // oficio (para que hable de LO SUYO, no todos lo mismo)

        Worker(String npcId, String name, Location home, Location work, Location plaza, Location town) {
            this.npcId = npcId;
            this.name = name;
            this.home = home;
            this.work = work;
            this.plaza = plaza;
            this.town = town;
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

    /** Arranca el bucle de rutina. Ya no hay vecinos "base": todos los aldeanos son colonos
     *  que el SettlementModule da de alta (dos fundadores al empezar, y crece desde ahi). */
    public void start() {
        clearOld();
        this.taskId = plugin.getServer().getScheduler().runTaskTimer(
                plugin, this::tick, PERIOD_TICKS, PERIOD_TICKS).getTaskId();
        plugin.getLogger().info("[Aetheria] Fase 7: rutina de aldeanos activa en '"
                + world.getName() + "' (los aldeanos los crea el pueblo vivo).");
    }

    /** Reasigna casa y puesto de un aldeano (p.ej. al casarse y mudarse a una casa nueva). */
    public void setHomeWork(String name, Location home, Location work) {
        for (final Worker w : workers) {
            if (w.name.equals(name)) {
                w.home = home;
                w.work = work;
                return;
            }
        }
    }

    public void stop() {
        if (taskId != -1) {
            plugin.getServer().getScheduler().cancelTask(taskId);
            taskId = -1;
        }
    }

    private static final int BASE = 0;   // ya no hay vecinos fijos: todos son colonos

    /** Cuantos colonos hay ahora mismo. */
    public int colonoCount() {
        return workers.size();
    }

    /** Da de alta un colono nuevo con su oficio, que vive y trabaja donde se indica, y se reune
     *  y pasea en SU aldea (townCenter). */
    public void addColono(String npcId, String name, Location home, Location work,
            Villager.Profession prof, Location townCenter) {
        final Worker w = new Worker(npcId, name, home, work, plazaSpot(townCenter), townCenter);
        w.entity = spawnWorker(w);
        w.prof = profWord(prof);
        if (w.entity instanceof Villager v) {
            v.setProfession(prof);
        }
        workers.add(w);
    }

    /** Punto de reunion en un ANILLO alrededor de la plaza de su aldea (uno distinto por vecino)
     *  para que al atardecer no se apilen todos en la misma casilla y se crucen los nombres. */
    private Location plazaSpot(Location center) {
        final double ang = workers.size() * 2.399963;   // angulo aureo: reparte bien en el anillo
        final double r = 3 + (workers.size() % 3);
        return center.clone().add(Math.cos(ang) * r, 0, Math.sin(ang) * r);
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
                target = workOrWander(w);   // trabaja, y de vez en cuando pasea por el pueblo
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

    /**
     * De dia el vecino trabaja, pero a ratos se va a PASEAR por el pueblo (o a explorar un poco
     * mas lejos) y luego vuelve. Da sensacion de vida en vez de estar clavado en el puesto.
     */
    private Location workOrWander(Worker w) {
        final long now = System.currentTimeMillis();
        if (w.wander != null && now < w.wanderUntil) {
            // ¿ya llego al punto de paseo? entonces que descanse ahi hasta que acabe el paseo.
            if (w.entity.getLocation().distanceSquared(w.wander) <= ARRIVE_SQ) {
                return w.entity.getLocation();
            }
            return w.wander;
        }
        if (java.util.concurrent.ThreadLocalRandom.current().nextInt(1000) < 7) {
            final var rng = java.util.concurrent.ThreadLocalRandom.current();
            final Location p = w.town != null ? w.town : village.plaza();
            final double ang = rng.nextDouble() * Math.PI * 2;
            final int dist = 6 + rng.nextInt(rng.nextInt(100) < 20 ? 34 : 16);   // a veces explora lejos
            final int wx = (int) Math.round(p.getX() + Math.cos(ang) * dist);
            final int wz = (int) Math.round(p.getZ() + Math.sin(ang) * dist);
            final int wy = world.getHighestBlockYAt(wx, wz) + 1;
            w.wander = new Location(world, wx + 0.5, wy, wz + 0.5);
            w.wanderUntil = now + (12 + rng.nextInt(20)) * 1000L;
            return w.wander;
        }
        w.wander = null;
        return w.work;
    }

    // Frases generales, no atadas a oficio ni hora. Ultimo recurso / relleno.
    private static final String[] GENERIC = {
        "Si necesitas algo, pregunta por la plaza.",
        "Cada dia llega mas gente al pueblo.",
        "Dicen en la taberna que el pueblo prospera.",
        "¿Has probado a vender en el mercado? Da buenas monedas.",
        "He oido que alguien se ha construido una casa nueva.",
    };

    // Frases segun el MOMENTO del dia (el mismo vecino habla distinto de manana o de noche).
    private static final String[] AL_ALBA = {
        "Madrugar cansa, pero el trabajo no se hace solo.",
        "Buen dia para trabajar, ¿no crees?",
        "Aun huele a rocio. Me gusta esta hora.",
    };
    private static final String[] DE_DIA = {
        "Ando liado, pero siempre hay un momento para saludar.",
        "El sol aprieta; se trabaja mejor con calma.",
    };
    private static final String[] AL_ATARDECER = {
        "Voy cerrando; a esta hora se recoge uno hacia la plaza.",
        "Menudo dia. Toca charlar un rato antes de casa.",
        "Cuidado de noche, que salen cosas por los caminos.",
    };
    private static final String[] DE_NOCHE = {
        "¿Aun por aqui? Yo me voy ya a dormir.",
        "De noche mejor bajo techo, hazme caso.",
    };

    // Frases propias de CADA oficio (para que no todos digan lo mismo). Clave = profWord(...).
    private static final java.util.Map<String, String[]> POR_OFICIO = java.util.Map.of(
        "granjero", new String[] {
            "Los cultivos van creciendo poco a poco.",
            "Si riegas a tiempo, la cosecha responde.",
            "Este ano la tierra viene generosa." },
        "herrero", new String[] {
            "El yunque no descansa; siempre falta una herramienta.",
            "Con buen hierro, buena hoja. Asi de simple.",
            "¿Se te ha roto algo? Puedo echarle un ojo." },
        "pescador", new String[] {
            "El agua esta buena hoy; los peces pican.",
            "Paciencia y sedal, ese es el secreto." },
        "bibliotecario", new String[] {
            "Tengo un libro para casi todo, si sabes buscar.",
            "El saber pesa menos que el oro y vale mas." },
        "carnicero", new String[] {
            "Carne fresca cada manana, no lo dudes.",
            "El ganado da trabajo, pero llena la despensa." },
        "clerigo", new String[] {
            "Que la fortuna te acompane, viajero.",
            "Hay dias oscuros, pero el pueblo aguanta unido." },
        "cartografo", new String[] {
            "Cada camino nuevo hay que ponerlo en el mapa.",
            "¿Perdido? Dime a donde vas y te oriento." },
        "guardia", new String[] {
            "Tranquilo, aqui vigilo yo. No pasa nada raro.",
            "De noche redoblo la ronda; nunca se sabe." });

    private static String[] pool(String[]... groups) {
        final java.util.List<String> all = new java.util.ArrayList<>();
        for (final String[] g : groups) {
            if (g != null) {
                java.util.Collections.addAll(all, g);
            }
        }
        return all.toArray(new String[0]);
    }

    /** Si hay un jugador cerca, el vecino suelta un comentario curioso (con cooldown). El
     *  comentario mezcla lo propio de SU oficio con lo propio de la HORA del dia. */
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
                final long time = world.getTime();
                final String[] hora = time < 2000L ? AL_ALBA
                        : time < 11000L ? DE_DIA
                        : time < 13500L ? AL_ATARDECER
                        : DE_NOCHE;
                final String[] oficio = POR_OFICIO.get(w.prof);
                final String[] mix = pool(oficio, hora, GENERIC);
                final String line = mix[java.util.concurrent.ThreadLocalRandom.current().nextInt(mix.length)];
                p.sendMessage("§e[" + w.name + "] §7" + line);
                return;
            }
        }
    }

    /** Nombre corto del oficio en castellano (clave para las frases). */
    private static String profWord(Villager.Profession prof) {
        if (prof == Villager.Profession.FARMER) {
            return "granjero";
        } else if (prof == Villager.Profession.WEAPONSMITH || prof == Villager.Profession.TOOLSMITH
                || prof == Villager.Profession.ARMORER) {
            return "herrero";
        } else if (prof == Villager.Profession.FISHERMAN) {
            return "pescador";
        } else if (prof == Villager.Profession.LIBRARIAN) {
            return "bibliotecario";
        } else if (prof == Villager.Profession.BUTCHER) {
            return "carnicero";
        } else if (prof == Villager.Profession.CLERIC) {
            return "clerigo";
        } else if (prof == Villager.Profession.CARTOGRAPHER) {
            return "cartografo";
        } else if (prof == Villager.Profession.NITWIT || prof == Villager.Profession.NONE) {
            return "guardia";
        }
        return "vecino";
    }
}
