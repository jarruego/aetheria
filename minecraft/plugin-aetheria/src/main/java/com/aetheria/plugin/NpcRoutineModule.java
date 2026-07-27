package com.aetheria.plugin;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
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
    private static final String SONG_TAG = "aetheria_song";
    private static final String[] SONGS = {
        "§e♪ Ronda la jarra, la la la ♪",
        "§e♪ Bebe y canta, la noche es larga ♪",
        "§e♪ Ay, tabernero, sirve otra mas ♪",
        "§e♪ El pueblo prospera, brindemos hoy ♪",
        "§e♪ Tralala, que suene el laud ♪",
        "§e♪ A la salud del alcalde... y de todos ♪",
    };
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
        long lastSong;      // ultima vez que canto en la taberna
        long lastGossip;    // ultima vez que conto (u oyo) un cotilleo del pueblo
        Location wander;    // destino de paseo actual (o null si esta trabajando)
        long wanderUntil;   // hasta cuando dura el paseo
        Location bed;       // cama de su casa (para dormir de noche); se busca una vez
        boolean bedSearched;
        String prof = "vecino";   // oficio en castellano (para que hable de LO SUYO)
        String profKey = "";      // oficio en INGLES (clave de la skin de disfraz)
        String gender = "m";      // sexo (para la skin humana del disfraz)
        boolean stayAtWork;       // el tabernero no se va de paseo: se queda sirviendo

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
                w.bed = null;            // nueva casa: se vuelve a buscar su cama
                w.bedSearched = false;
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
        addColono(npcId, name, home, work, prof, townCenter, "m");
    }

    public void addColono(String npcId, String name, Location home, Location work,
            Villager.Profession prof, Location townCenter, String gender) {
        final Worker w = new Worker(npcId, name, home, work, plazaSpot(townCenter), townCenter);
        w.gender = gender;
        w.prof = profWord(prof);
        w.profKey = prof.name().toLowerCase(java.util.Locale.ROOT);   // clave de skin (ingles)
        w.entity = spawnWorker(w);   // ya con prof/profKey/gender puestos, para el disfraz
        if (w.entity instanceof Villager v) {
            v.setProfession(prof);
        }
        workers.add(w);
    }

    // --- Aldeas que YA tienen taberna (hasta entonces, la vida social es en la plaza) ---

    private final java.util.Set<String> taverns = new java.util.HashSet<>();
    private static final String[] SIN_TABERNA = {
        "§7Que aburrido... ojala hubiera una taberna.",
        "§7Se echa de menos un sitio donde tomar algo al caer el sol.",
        "§7Aqui no hay ni donde sentarse con una jarra.",
        "§7Si el pueblo creciera, tendriamos taberna.",
        "§7Otra tarde de charla en la plaza, como siempre...",
    };

    /** Marca que esa aldea YA tiene taberna: a partir de ahora sus vecinos se reunen alli. */
    public void setTavern(Location townCenter) {
        if (townCenter != null) {
            taverns.add(townCenter.getBlockX() + "," + townCenter.getBlockZ());
        }
    }

    private boolean hasTavern(Worker w) {
        return w.town != null
                && taverns.contains(w.town.getBlockX() + "," + w.town.getBlockZ());
    }

    /** Sin taberna, al atardecer los vecinos se juntan en la plaza y se quejan de que no hay. */
    private void maybeBored(Worker w) {
        if (w.entity == null) {
            return;
        }
        final long now = System.currentTimeMillis();
        if (now - w.lastSong < 20000L
                || java.util.concurrent.ThreadLocalRandom.current().nextInt(100) >= 8) {
            return;
        }
        boolean audience = false;
        for (final Player p : world.getPlayers()) {
            if (p.getLocation().distanceSquared(w.entity.getLocation()) <= 576) {
                audience = true;
                break;
            }
        }
        if (!audience) {
            return;   // si no hay quien lo oiga, no se gasta nada
        }
        w.lastSong = now;
        bubble(w.entity.getLocation().clone().add(0, 2.3, 0),
                SIN_TABERNA[java.util.concurrent.ThreadLocalRandom.current().nextInt(
                        SIN_TABERNA.length)], 80L);
    }

    /** La entidad de un colono por su nombre (la usa el modulo de trabajo fisico para saber
     *  DONDE esta trabajando ahora mismo). Null si no existe o esta descargado. */
    public org.bukkit.entity.Mob entityOf(String name) {
        for (final Worker w : workers) {
            if (w.name.equals(name)) {
                return w.entity;
            }
        }
        return null;
    }

    // --- COTILLEO: las noticias del pueblo corren de boca en boca ---

    private static final int GOSSIP_MAX = 10;
    private static final long GOSSIP_COOLDOWN_MS = 60_000L;
    private static final String[] GOSSIP_OPEN = {
        "¿Te has enterado?", "Dicen por ahi que", "Me ha contado un vecino:", "Lo comentan en la plaza:",
        "No se lo cuentes a nadie, pero", "Se dice en la taberna:",
    };
    private static final String[] GOSSIP_REPLY = {
        "Vaya, no tenia ni idea.", "Algo habia oido yo tambien.", "¡No me digas!",
        "Cosas del pueblo...", "Pues mira tu por donde.", "Ya somos dos que lo sabemos.",
    };
    private final java.util.ArrayDeque<String> gossip = new java.util.ArrayDeque<>();

    /** Anota una NOTICIA del pueblo (boda, nacimiento, muerte, obra, cosecha). Los vecinos la
     *  repetiran entre ellos cuando coincidan, si hay algun jugador cerca para oirlo. */
    public void pushGossip(String news) {
        if (news == null || news.isBlank()) {
            return;
        }
        gossip.addFirst(news.length() > 90 ? news.substring(0, 88) + "..." : news);
        while (gossip.size() > GOSSIP_MAX) {
            gossip.removeLast();
        }
    }

    /** Texto flotante sobre la cabeza de alguien unos segundos (canciones y cotilleos). */
    private void bubble(Location above, String text, long ticks) {
        final TextDisplay td = world.spawn(above, TextDisplay.class, t -> {
            t.text(Component.text(text));
            t.setBillboard(Display.Billboard.CENTER);
            t.setSeeThrough(true);
            t.addScoreboardTag(SONG_TAG);
        });
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (td.isValid()) {
                td.remove();
            }
        }, ticks);
    }

    /**
     * Dos vecinos que coinciden cerca se cuentan las novedades del pueblo (con cooldown y solo
     * si hay un jugador a la escucha: si no hay nadie, no se gasta nada). Uno suelta la noticia
     * y el otro responde: se ve como dos bocadillos encadenados.
     */
    private void maybeGossip(Worker w) {
        if (gossip.isEmpty() || w.entity == null) {
            return;
        }
        final long now = System.currentTimeMillis();
        if (now - w.lastGossip < GOSSIP_COOLDOWN_MS
                || java.util.concurrent.ThreadLocalRandom.current().nextInt(100) >= 4) {
            return;
        }
        final Location at = w.entity.getLocation();
        boolean audience = false;
        for (final Player p : world.getPlayers()) {
            if (p.getLocation().distanceSquared(at) <= 576) {   // 24 bloques: hay quien lo oiga
                audience = true;
                break;
            }
        }
        if (!audience) {
            return;
        }
        Worker other = null;
        for (final Worker o : workers) {
            if (o != w && o.entity != null && !o.entity.isDead()
                    && o.entity.getWorld().equals(at.getWorld())
                    && o.entity.getLocation().distanceSquared(at) <= 36) {   // a 6 bloques
                other = o;
                break;
            }
        }
        if (other == null) {
            return;
        }
        final var rng = java.util.concurrent.ThreadLocalRandom.current();
        w.lastGossip = now;
        other.lastGossip = now;
        final String news = gossip.stream().skip(rng.nextInt(gossip.size())).findFirst().orElse(null);
        if (news == null) {
            return;
        }
        final String line = GOSSIP_OPEN[rng.nextInt(GOSSIP_OPEN.length)] + " " + news;
        bubble(at.clone().add(0, 2.3, 0), "§f" + line, 100L);
        final Worker listener = other;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (listener.entity != null && !listener.entity.isDead()) {
                bubble(listener.entity.getLocation().clone().add(0, 2.3, 0),
                        "§7" + GOSSIP_REPLY[rng.nextInt(GOSSIP_REPLY.length)], 70L);
            }
        }, 45L);
        for (final Player p : world.getPlayers()) {
            if (p.getLocation().distanceSquared(at) <= 576) {
                p.sendMessage("§e[" + w.name + "] §7" + line);
            }
        }
    }

    /** Si el vecino esta DENTRO de la taberna (al atardecer), de vez en cuando "canta": aparece un
     *  texto flotante sobre su cabeza unos segundos. Da vida a la taberna llena. */
    private void maybeSing(Worker w) {
        if (w.town == null || w.entity == null) {
            return;
        }
        final long now = System.currentTimeMillis();
        if (now - w.lastSong < 10000L) {
            return;
        }
        // Solo si esta en/junto a la taberna (townCenter + ~9 al este).
        final Location at = w.entity.getLocation();
        final double dx = at.getX() - (w.town.getX() + 9);
        final double dz = at.getZ() - w.town.getZ();
        if (dx * dx + dz * dz > 49) {
            return;
        }
        if (java.util.concurrent.ThreadLocalRandom.current().nextInt(100) >= 15) {
            return;
        }
        w.lastSong = now;
        final String line = SONGS[java.util.concurrent.ThreadLocalRandom.current().nextInt(SONGS.length)];
        bubble(at.clone().add(0, 2.3, 0), line, 90L);   // ~4,5 s y desaparece
    }

    /** La CAMA de la casa del vecino: se busca UNA vez un bloque de cama junto a su casa. Ir a la
     *  cama de noche (y quedarse quieto) es lo que deja que el aldeano se acueste. */
    private Location bedOf(Worker w) {
        if (!w.bedSearched) {
            w.bedSearched = true;
            w.bed = findBed(w.home);
        }
        return w.bed != null ? w.bed : w.home;
    }

    private Location findBed(Location home) {
        if (home == null) {
            return null;
        }
        final int hx = home.getBlockX();
        final int hy = home.getBlockY();
        final int hz = home.getBlockZ();
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -4; dx <= 4; dx++) {
                for (int dz = -4; dz <= 4; dz++) {
                    if (org.bukkit.Tag.BEDS.isTagged(
                            world.getBlockAt(hx + dx, hy + dy, hz + dz).getType())) {
                        return new Location(world, hx + dx + 0.5, hy + dy, hz + dz + 0.5);
                    }
                }
            }
        }
        return null;
    }

    /** Un aldeano jubilado ya no ejerce oficio (profesion NONE): el pueblo le da el relevo. */
    private boolean isRetired(Worker w) {
        return w.entity instanceof Villager v && v.getProfession() == Villager.Profession.NONE;
    }

    /** Los jubilados hacen vida en la plaza (con un poco de paseo), no en un puesto de trabajo. */
    private Location plazaLoiter(Worker w) {
        if (java.util.concurrent.ThreadLocalRandom.current().nextInt(1000) < 5) {
            return workOrWander(w);   // de vez en cuando dan una vuelta por el pueblo
        }
        final int h = w.name.hashCode();
        final double r = 2 + (h & 3);
        final double ang = (h >> 2) * 0.7;
        final Location c = w.town != null ? w.town : w.plaza;
        return c.clone().add(Math.cos(ang) * r, 0, Math.sin(ang) * r);
    }

    /** Punto de reunion al ATARDECER: delante de la taberna de su aldea (townCenter + ~8 al este,
     *  donde la construye VillageModule.buildPlaza), con una pequena dispersion estable por vecino
     *  para que no se apilen todos en la misma casilla ni se crucen los nombres. */
    private Location tavernSpot(Worker w) {
        if (w.town == null) {
            return w.plaza;   // sin aldea localizada: a la plaza, como antes
        }
        final int h = w.name.hashCode();
        final double ox = 7 + (h & 3);            // 7..10 al este (frente a la taberna)
        final double oz = ((h >> 2) & 3) - 1.5;   // -1.5..+1.5 a los lados
        return w.town.clone().add(ox, 0, oz);
    }

    /** Punto de reunion en un ANILLO alrededor de la plaza de su aldea (uno distinto por vecino)
     *  para que al atardecer no se apilen todos en la misma casilla y se crucen los nombres. */
    private Location plazaSpot(Location center) {
        final double ang = workers.size() * 2.399963;   // angulo aureo: reparte bien en el anillo
        final double r = 3 + (workers.size() % 3);
        return center.clone().add(Math.cos(ang) * r, 0, Math.sin(ang) * r);
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

    /** Marca a un vecino como "clavado a su puesto" (el tabernero se queda en la barra). */
    public void setStayAtWork(String name, boolean stay) {
        for (final Worker w : workers) {
            if (w.name.equals(name)) {
                w.stayAtWork = stay;
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
                    DisguiseModule.humanize(ex, w.gender, w.name, w.profKey);   // aspecto humano
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
        DisguiseModule.humanize(v, w.gender, w.name, w.profKey);   // aspecto humano (skin de oficio)
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
            maybeGossip(w);                   // y, si coincide con otro vecino, se cuentan lo ultimo
            final Location target;
            if (time < 12000L) {
                // Los JUBILADOS (sin oficio) no trabajan: pasan el dia en la plaza y pasean.
                target = isRetired(w) ? plazaLoiter(w) : workOrWander(w);
            } else if (time < 13500L) {
                // ATARDECER: a la taberna SOLO si la aldea ya la tiene construida; si no, la vida
                // social es en la plaza (y se quejan de que hace falta una taberna).
                if (hasTavern(w)) {
                    target = tavernSpot(w);
                    maybeSing(w);           // y, si esta dentro, a veces canta (texto flotante)
                } else {
                    target = w.plaza;
                    maybeBored(w);
                }
            } else {
                target = bedOf(w);          // NOCHE: a la CAMA de su casa (a dormir)
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
        if (w.stayAtWork) {
            return w.work;   // el tabernero no abandona la barra durante la jornada
        }
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
            "De noche redoblo la ronda; nunca se sabe." },
        "tabernero", new String[] {
            "¿Te sirvo algo? La casa invita a la primera.",
            "Aqui se entera uno de todo lo que pasa en el pueblo.",
            "Por la noche esto se llena; ya lo veras." });

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
        } else if (prof == Villager.Profession.LEATHERWORKER) {
            return "tabernero";
        } else if (prof == Villager.Profession.NITWIT || prof == Villager.Profession.NONE) {
            return "guardia";
        }
        return "vecino";
    }
}
