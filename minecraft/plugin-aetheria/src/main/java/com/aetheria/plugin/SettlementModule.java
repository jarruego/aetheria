package com.aetheria.plugin;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Display;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerMoveEvent;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;

/**
 * Pueblo VIVO: reconcilia el mundo fisico con la poblacion objetivo de la simulacion (crece
 * cuando prospera, mengua cuando decae). Al crecer NIVELA el terreno, construye una casa
 * (con el nombre del colono en el cartel y un rasgo segun su oficio), la conecta con un
 * camino al pueblo, y llega un colono con su rutina. Al decaer, un colono emigra.
 */
public final class SettlementModule implements Listener {

    private static final long PERIOD = 1200L;   // reconcilia cada 60 s (una casa por vez)
    private static final String[] MALE_NAMES = {
        "Alejandro", "Alvaro", "Adrian", "Aitor", "Alberto", "Alfonso", "Andres", "Angel",
        "Antonio", "Arnau", "Asier", "Bruno", "Carlos", "Cesar", "Cristian", "Dario", "David",
        "Diego", "Domingo", "Eduardo", "Emilio", "Enrique", "Ernesto", "Esteban", "Fabian",
        "Felipe", "Fernando", "Francisco", "Gabriel", "Gael", "German", "Gonzalo", "Guillermo",
        "Hector", "Hugo", "Ignacio", "Iker", "Ismael", "Ivan", "Jaime", "Javier", "Jesus",
        "Joaquin", "Jorge", "Jose", "Juan", "Julian", "Julio", "Leo", "Lorenzo", "Lucas", "Luis",
        "Manuel", "Marc", "Marcos", "Mario", "Martin", "Mateo", "Matias", "Miguel", "Nacho",
        "Nicolas", "Nil", "Noe", "Oscar", "Oriol", "Pablo", "Pau", "Pedro", "Pol", "Rafael",
        "Ramon", "Raul", "Ricardo", "Roberto", "Rodrigo", "Ruben", "Salvador", "Samuel",
        "Santiago", "Saul", "Sergio", "Simon", "Tomas", "Unai", "Vicente", "Victor", "Xavier",
        "Aaron", "Abel", "Adan", "Alan", "Alonso", "Anton", "Bautista", "Benito", "Bernardo",
        "Biel", "Teo", "Marco"};
    private static final String[] FEMALE_NAMES = {
        "Adriana", "Alba", "Alejandra", "Alicia", "Alma", "Amaia", "Amelia", "Ana", "Andrea",
        "Angela", "Aitana", "Ainhoa", "Aurora", "Beatriz", "Berta", "Blanca", "Carla", "Carlota",
        "Carmen", "Carolina", "Catalina", "Cecilia", "Celia", "Clara", "Claudia", "Cloe",
        "Cristina", "Daniela", "Diana", "Dolores", "Elena", "Elisa", "Elsa", "Emma", "Enara",
        "Esther", "Eva", "Fatima", "Gabriela", "Gala", "Gema", "Gloria", "Greta", "Helena",
        "Ines", "Irene", "Iris", "Isabel", "Jimena", "Judith", "Julia", "Laia", "Lara", "Laura",
        "Leire", "Leonor", "Lidia", "Lorena", "Lucia", "Luisa", "Luz", "Maite", "Malena",
        "Manuela", "Marcela", "Margarita", "Maria", "Marina", "Marta", "Martina", "Mireia",
        "Miriam", "Monica", "Nadia", "Naia", "Natalia", "Nayara", "Nerea", "Noa", "Noelia",
        "Nora", "Nuria", "Olga", "Olivia", "Paloma", "Patricia", "Paula", "Pilar", "Raquel",
        "Rebeca", "Rocio", "Rosa", "Sara", "Sofia", "Sol", "Sonia", "Teresa", "Vega", "Vera",
        "Victoria", "Violeta", "Yaiza", "Zoe"};
    private static final Villager.Profession[] PROFS = {Villager.Profession.FARMER,
        Villager.Profession.FISHERMAN, Villager.Profession.SHEPHERD, Villager.Profession.MASON,
        Villager.Profession.LIBRARIAN, Villager.Profession.TOOLSMITH, Villager.Profession.BUTCHER,
        Villager.Profession.FLETCHER};
    private static final String[] SURNAMES = {
        "Rivas", "Soto", "Vega", "Prado", "Campos", "Robles", "Herrero", "Bravo", "Nieto",
        "Ramos", "Castro", "Vidal", "Marin", "Mora", "Pardo", "Rojas", "Serrano", "Lozano",
        "Ibarra", "Cuesta", "Aguado", "Fuentes", "Molina", "Vargas", "Otero", "Blanco", "Crespo",
        "Gil", "Solano", "Ferrer", "Duran", "Escudero", "Pineda", "Valle", "Guerra", "Palacios"};
    private static final Material[] FLOWERS = {Material.POPPY, Material.DANDELION,
        Material.BLUE_ORCHID, Material.ALLIUM, Material.OXEYE_DAISY, Material.CORNFLOWER};
    private static final Material[][] COMBOS = {
        {Material.OAK_PLANKS, Material.SPRUCE_LOG, Material.DARK_OAK_PLANKS, Material.COBBLESTONE},
        {Material.STONE_BRICKS, Material.CHISELED_STONE_BRICKS, Material.COBBLESTONE, Material.MOSSY_STONE_BRICKS},
        {Material.BRICKS, Material.DEEPSLATE_TILES, Material.DARK_OAK_PLANKS, Material.POLISHED_BLACKSTONE},
        {Material.SPRUCE_PLANKS, Material.STRIPPED_SPRUCE_LOG, Material.DARK_OAK_PLANKS, Material.BRICKS},
    };

    private final AetheriaPlugin plugin;
    private final GatewayClient gateway;
    private final VillageModule village;
    private final NpcRoutineModule routines;
    private final ConversationManager convo;
    private final World world;
    private int farmRadius = 2;   // los cultivos del pueblo se amplian con el tiempo
    private int civic = 0;        // mejoras civicas de la plaza ya construidas (persistido)

    private static final String BABY_TAG = "aetheria_baby";
    private static final long GROW_MS = 6 * 60 * 1000L;   // un bebe tarda ~6 min en hacerse adulto
    private static final double YEARS_PER_DAY = 2.0;       // envejecen 2 anos por dia real
    private static final long DAY_MS = 86_400_000L;
    private static final int WORK_AGE = 16;
    private static final int RETIRE_AGE = 65;

    /** Un nino del pueblo creciendo: su bebe, su nombre, su padre/madre y cuando se hara adulto. */
    private static final class Child {
        final Villager baby;
        final String name;
        final String parent;
        final String gender;
        final int vid;
        final String surname;
        final long matureAt;

        Child(Villager baby, String name, String parent, String gender, int vid, String surname,
                long matureAt) {
            this.baby = baby;
            this.name = name;
            this.parent = parent;
            this.gender = gender;
            this.vid = vid;
            this.surname = surname;
            this.matureAt = matureAt;
        }
    }

    /** Un colono adulto con su edad (envejece), oficio, casa, padre/madre y estado de jubilacion. */
    private static final class Colono {
        String name;
        String profKey;
        int x;
        int y;
        int z;
        long bornMillis;
        double initialAge;
        int deathAge;
        String parent;
        boolean retired;
        int floors = 1;      // plantas de su casa (para saber que region ocupa)
        String spouse;       // nombre del conyuge, o null si esta soltero/a
        String gender = "m"; // "m" o "f" (dos hombres no tienen hijos biologicos)
        int vid;             // aldea a la que pertenece (indice en towns)
        String surname = ""; // apellido (los hijos heredan el de su familia)

        double age(long now) {
            return initialAge + (now - bornMillis) * YEARS_PER_DAY / DAY_MS;
        }

        String toLine() {
            return name + ";" + profKey + ";" + x + ";" + y + ";" + z + ";" + bornMillis + ";"
                    + initialAge + ";" + deathAge + ";" + (parent == null ? "" : parent) + ";"
                    + retired + ";" + floors + ";" + (spouse == null ? "" : spouse) + ";" + gender
                    + ";" + vid + ";" + surname;
        }
    }

    private final List<Child> children = new ArrayList<>();
    private final List<int[]> placed = new ArrayList<>();   // (x,z) de las casas ya colocadas
    private final List<Colono> colonos = new ArrayList<>(); // colonos adultos (con edad), persistidos
    private final File dataFile;
    private final File civicFile;
    private final File nameFile;   // village.txt: una linea por aldea "nombre;cx;cz;baseY"
    private final File buildingsFile;   // buildings.txt: "vid;profKey;cx;cz;baseY" por edificio
    private final File vacantsFile;   // vacants.txt: "x;y;z;floors" casas en venta (sin dueno)
    // Casas EN VENTA (sin propietario): al casarse, las dos casitas de los novios no se demuelen,
    // quedan vacantes y se reasignan a los proximos colonos (asi no aparecen/desaparecen por magia).
    private final List<int[]> vacants = new ArrayList<>();   // {x, y, z, floors}
    private final java.util.Map<java.util.UUID, Integer> inTown = new java.util.HashMap<>();

    private static final int PER_TOWN = 8;   // al llenarse, una pareja funda otra aldea lejos
    private static final String[] TOWN_NAMES = {"Rocavieja", "Valverde", "Fuenteclara", "Montenar",
        "Rivablanca", "Espinar", "Robledo", "Vallehondo", "Penaflor", "Aldealba", "Sotobravo",
        "Villalce", "Olmedal", "Riofrio", "Costaluna", "Miralbosque", "Pradoverde", "Encinar",
        "Torrelaguna", "Valdehielo", "Montalbo", "Fuentesauco", "Castroverde", "Puebla Nueva"};

    /** Una aldea del mundo: su nombre y el centro de su plaza. Aetheria es el mundo; cada aldea
     *  tiene nombre propio. */
    private static final class Town {
        final String name;
        final int cx;
        final int cz;
        final int baseY;
        Town(String name, int cx, int cz, int baseY) {
            this.name = name;
            this.cx = cx;
            this.cz = cz;
            this.baseY = baseY;
        }
    }

    private final List<Town> towns = new ArrayList<>();
    private final java.util.Map<Integer, String> alcaldes = new java.util.HashMap<>();  // vid -> alcalde

    /** Un EDIFICIO de oficio del pueblo (mercado, biblioteca, herreria...). Es PERMANENTE: no
     *  se derriba al morir su aldeano; lo hereda otro del mismo oficio o espera a que llegue uno. */
    private static final class Building {
        final int vid;
        final String profKey;
        final int cx;
        final int cz;
        final int baseY;
        Building(int vid, String profKey, int cx, int cz, int baseY) {
            this.vid = vid;
            this.profKey = profKey;
            this.cx = cx;
            this.cz = cz;
            this.baseY = baseY;
        }
    }

    private final List<Building> buildings = new ArrayList<>();

    /** Altura del SUELO real (ignora hojas, troncos y plantas), escaneando hacia abajo. */
    private int groundY(int x, int z) {
        int y = world.getHighestBlockYAt(x, z);
        for (int i = 0; i < 40 && y > world.getMinHeight() + 1; i++, y--) {
            final Material m = world.getBlockAt(x, y, z).getType();
            final String n = m.name();
            final boolean tree = n.contains("LEAVES") || n.contains("LOG") || n.contains("_WOOD")
                    || n.contains("MUSHROOM_BLOCK");
            if (m.isSolid() && !tree) {
                return y;
            }
        }
        return y;
    }

    /** True si (x,z) esta demasiado cerca de otra casa o del centro de la aldea. */
    private boolean tooClose(Location center, int x, int z) {
        if (Math.hypot(x - center.getX(), z - center.getZ()) < 9) {   // pegado a la plaza, pero sin pisarla
            return true;
        }
        for (final int[] p : placed) {
            if (Math.hypot(x - p[0], z - p[1]) < 12) {   // compacto: casas/edificios juntos, sin solaparse
                return true;
            }
        }
        return false;
    }

    /**
     * True si el bloque es NATURAL (se puede talar/allanar sin problema): aire, agua, tierra,
     * roca, grava, arena, arboles/hojas, vegetacion, mineral... Si es FALSE, es algo puesto por
     * alguien (madera trabajada, ladrillo, cristal, cofre...) y NO se debe romper para construir.
     */
    private static boolean natural(Material m) {
        if (m.isAir() || m == Material.WATER || m == Material.LAVA) {
            return true;
        }
        if (Tag.LOGS.isTagged(m) || Tag.LEAVES.isTagged(m) || Tag.SAPLINGS.isTagged(m)
                || Tag.DIRT.isTagged(m) || Tag.SAND.isTagged(m) || Tag.FLOWERS.isTagged(m)) {
            return true;
        }
        final String n = m.name();
        if (n.endsWith("_ORE") || n.contains("MUSHROOM")) {
            return true;
        }
        return switch (m) {
            case STONE, GRANITE, DIORITE, ANDESITE, DEEPSLATE, TUFF, CALCITE, GRAVEL, CLAY,
                 SANDSTONE, RED_SANDSTONE, SNOW, SNOW_BLOCK, POWDER_SNOW, ICE, PACKED_ICE, BLUE_ICE,
                 SHORT_GRASS, TALL_GRASS, FERN, LARGE_FERN, DEAD_BUSH, VINE, GLOW_LICHEN, MOSS_BLOCK,
                 MOSS_CARPET, DIRT_PATH, GRASS_BLOCK, SWEET_BERRY_BUSH, SUGAR_CANE, LILY_PAD,
                 CACTUS, PUMPKIN, MELON, BAMBOO, COBWEB -> true;
            default -> false;
        };
    }

    /**
     * Busca el sitio VALIDO (llano, sin agua/hielo, sin pisar lo construido) MAS CERCANO a la
     * plaza: escanea en ANILLOS hacia fuera desde el centro y se queda con el primero que valga,
     * asi el pueblo crece compacto desde la plaza. Devuelve {cx,cz,fy} o null.
     */
    private int[] findBuildSpot(Location center, int index) {
        final var rng = ThreadLocalRandom.current();
        final int px = center.getBlockX();
        final int pz = center.getBlockZ();
        int[] best = null;
        int bestFlat = Integer.MAX_VALUE;
        for (int dist = 10; dist <= 110; dist += 3) {
            final int angles = Math.max(10, dist / 2);   // mas angulos cuanto mayor el anillo
            for (int a = 0; a < angles; a++) {
                final double ang = a * (Math.PI * 2 / angles) + rng.nextDouble() * 0.25;
                final int cx = px + (int) Math.round(Math.cos(ang) * dist);
                final int cz = pz + (int) Math.round(Math.sin(ang) * dist);
                if (tooClose(center, cx, cz)) {
                    continue;
                }
                final int[] eval = evaluateSpot(cx, cz);   // {fy, flat} o null si invalido
                if (eval == null) {
                    continue;
                }
                // La aldea debe quedar COHESIONADA: nada de casas en un valle o un cerro lejano a
                // otra altura. Solo terreno a una cota parecida a la de la plaza (evita edificios
                // "hundidos" en una hondonada cercana aunque esa hondonada sea llana).
                if (Math.abs(eval[0] - center.getBlockY()) > 6) {
                    continue;
                }
                if (eval[1] <= 2) {
                    return new int[] {cx, cz, eval[0]};   // llano y CERCANO: perfecto
                }
                if (eval[1] < bestFlat) {                 // reserva el mas llano por si no hay perfecto
                    bestFlat = eval[1];
                    best = new int[] {cx, cz, eval[0]};
                }
            }
        }
        return best;
    }

    /** Evalua una posicion: {fy (cota mas baja), irregularidad} si es tierra firme LLANA sin
     *  construir; null si hay agua/hielo, algo construido, o el desnivel es grande (barranco,
     *  cueva, cuesta) en su huella (±5). */
    private int[] evaluateSpot(int cx, int cz) {
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        for (int dx = -5; dx <= 5; dx++) {
            for (int dz = -5; dz <= 5; dz++) {
                final int gy = groundY(cx + dx, cz + dz);
                final Block g = world.getBlockAt(cx + dx, gy, cz + dz);
                if (g.isLiquid()) {
                    return null;
                }
                final Material gm = g.getType();
                if (gm == Material.ICE || gm == Material.PACKED_ICE || gm == Material.BLUE_ICE
                        || gm == Material.FROSTED_ICE) {
                    return null;   // no sobre hielo
                }
                for (int y = gy + 1; y <= gy + 4; y++) {
                    if (world.getBlockAt(cx + dx, y, cz + dz).isLiquid()) {
                        return null;   // agua adyacente por encima del fondo
                    }
                }
                if (!natural(gm)) {
                    return null;   // suelo construido por alguien
                }
                for (int y = gy + 1; y <= gy + 7; y++) {
                    final Material above = world.getBlockAt(cx + dx, y, cz + dz).getType();
                    if (!above.isAir() && !natural(above)) {
                        return null;   // construccion encima
                    }
                }
                if (dx >= -4 && dx <= 4 && dz >= -4 && dz <= 4) {
                    min = Math.min(min, gy);
                    max = Math.max(max, gy);
                }
            }
        }
        // Rechaza huellas con mucho desnivel (barranco/cueva/cuesta): construir ahi obligaria a
        // tallar/rellenar un cortado enorme (fue lo que hundio un edificio a y=40). Solo terreno
        // razonablemente llano; si no hay, no se construye este ciclo y se reintenta luego.
        if (max - min > 4) {
            return null;
        }
        // El suelo se pone en la cota MAS BAJA de la huella: asi se TALLA el poco terreno que
        // sobresale (casa encajada en el relieve) en vez de RELLENAR con tierra por debajo (que
        // dejaba las casas elevadas sobre un "pegote"). Sin agua/hielo en la huella (ya rechazado).
        return new int[] {min, max - min};
    }

    public SettlementModule(AetheriaPlugin plugin, GatewayClient gateway, VillageModule village,
            NpcRoutineModule routines, ConversationManager convo, World world) {
        this.plugin = plugin;
        this.gateway = gateway;
        this.village = village;
        this.routines = routines;
        this.convo = convo;
        this.world = world;
        plugin.getDataFolder().mkdirs();
        this.dataFile = new File(plugin.getDataFolder(), "colonos.txt");
        this.civicFile = new File(plugin.getDataFolder(), "civic.txt");
        this.nameFile = new File(plugin.getDataFolder(), "village.txt");
        this.buildingsFile = new File(plugin.getDataFolder(), "buildings.txt");
        this.vacantsFile = new File(plugin.getDataFolder(), "vacants.txt");
    }

    public void start() {
        world.getEntities().stream()   // limpia bebes huerfanos y paneles viejos de sesiones anteriores
                .filter(e -> e.getScoreboardTags().contains(BABY_TAG)
                        || e.getScoreboardTags().contains(PANEL_TAG))
                .forEach(org.bukkit.entity.Entity::remove);
        final boolean fresh = !dataFile.exists();
        loadTowns();       // las aldeas (nombre + centro) ANTES que los colonos y edificios
        loadBuildings();   // los edificios de oficio (permanentes)
        loadVacants();     // casas en venta que quedaron de bodas anteriores
        load();            // reaparecen los colonos ya existentes en sus casas (sin reconstruir)
        loadCivic();
        if (fresh && colonos.isEmpty()) {
            // Mundo NUEVO: dos fundadores, un hombre y una mujer (asi pueden formar una familia),
            // cada uno con su casa pequena y su puesto.
            final var rng = ThreadLocalRandom.current();
            growAdult(0, 0, freshName("m", rng), randomSurname(rng), "m", 22 + rng.nextInt(30), "");
            growAdult(0, 1, freshName("f", rng), randomSurname(rng), "f", 22 + rng.nextInt(30), "");
        }
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::reconcile, PERIOD, PERIOD);
        plugin.getLogger().info("[Aetheria] Pueblo vivo: reconciliando poblacion cada 60 s ("
                + colonos.size() + " colonos cargados).");
    }

    /** Reaparece a los colonos guardados en sus casas (los bloques ya persisten en el mundo). */
    private void load() {
        if (!dataFile.exists()) {
            return;
        }
        final var rng = ThreadLocalRandom.current();
        boolean renamed = false;
        try (BufferedReader r = new BufferedReader(new FileReader(dataFile))) {
            String line;
            while ((line = r.readLine()) != null) {
                final String[] f = line.split(";", -1);
                if (f.length < 5) {
                    continue;
                }
                final Colono c = new Colono();
                c.name = f[0];
                c.profKey = f[1];
                c.x = Integer.parseInt(f[2]);
                c.y = Integer.parseInt(f[3]);
                c.z = Integer.parseInt(f[4]);
                if (f.length >= 10) {
                    c.bornMillis = Long.parseLong(f[5]);
                    c.initialAge = Double.parseDouble(f[6]);
                    c.deathAge = Integer.parseInt(f[7]);
                    c.parent = f[8];
                    c.retired = Boolean.parseBoolean(f[9]);
                    c.floors = f.length >= 11 ? Integer.parseInt(f[10]) : 1;
                    c.spouse = f.length >= 12 && !f[11].isEmpty() ? f[11] : null;
                    c.gender = f.length >= 13 && !f[12].isEmpty() ? f[12] : randGender(rng);
                    c.vid = f.length >= 14 && !f[13].isEmpty() ? Integer.parseInt(f[13]) : 0;
                    c.surname = f.length >= 15 && !f[14].isEmpty() ? f[14] : "";
                } else {   // formato antiguo: se le asigna una edad plausible
                    c.bornMillis = System.currentTimeMillis();
                    c.initialAge = 20 + rng.nextInt(40);
                    c.deathAge = randomDeathAge(rng);
                    c.parent = "";
                    c.gender = randGender(rng);
                }
                // Corrige nombres duplicados heredados de versiones antiguas (p.ej. tres "Tobias").
                for (final Colono other : colonos) {
                    if (other.name.equals(c.name)) {
                        c.name = freshName(c.gender, rng);
                        renamed = true;
                        break;
                    }
                }
                routines.addColono("colono", c.name, new Location(world, c.x + 0.5, c.y, c.z + 0.5),
                        ensureBuilding(c.vid, profFromKey(c.profKey)), profFromKey(c.profKey),
                        townCenter(c.vid));
                if (c.retired) {
                    routines.retire(c.name);
                }
                placed.add(new int[] {c.x, c.z});
                colonos.add(c);
            }
        } catch (Exception e) {   // nunca hacemos caer el plugin por esto
            plugin.getLogger().warning("[Aetheria] no pude cargar colonos: " + e.getMessage());
        }
        if (renamed) {
            save();   // persiste los nombres ya diferenciados
        }
    }

    private void save() {
        try (FileWriter w = new FileWriter(dataFile, false)) {
            for (final Colono c : colonos) {
                w.write(c.toLine() + "\n");
            }
        } catch (Exception e) {
            plugin.getLogger().warning("[Aetheria] no pude guardar colonos: " + e.getMessage());
        }
    }

    private static String profKey(Villager.Profession p) {
        return p.getKey().getKey();
    }

    /** El oficio que MENOS tiene la aldea, para que se equilibre (no 6 del mismo y ninguno de otro). */
    private Villager.Profession neededProfession(int vid, java.util.Random rng) {
        final int[] counts = new int[PROFS.length];
        for (final Colono c : colonos) {
            if (c.vid != vid) {
                continue;
            }
            for (int i = 0; i < PROFS.length; i++) {
                if (PROFS[i].getKey().getKey().equals(c.profKey)) {
                    counts[i]++;
                    break;
                }
            }
        }
        int min = Integer.MAX_VALUE;
        for (final int n : counts) {
            min = Math.min(min, n);
        }
        final List<Villager.Profession> cand = new ArrayList<>();
        for (int i = 0; i < PROFS.length; i++) {
            if (counts[i] == min) {
                cand.add(PROFS[i]);
            }
        }
        return cand.get(rng.nextInt(cand.size()));
    }

    private static Villager.Profession profFromKey(String key) {
        for (final Villager.Profession p : PROFS) {
            if (p.getKey().getKey().equals(key)) {
                return p;
            }
        }
        return Villager.Profession.FARMER;
    }

    /** Un nombre del sexo dado que NO este ya en uso por otro colono o nino. */
    private String freshName(String gender, java.util.Random rng) {
        final java.util.Set<String> used = new java.util.HashSet<>();
        for (final Colono c : colonos) {
            used.add(c.name);
        }
        for (final Child ch : children) {
            used.add(ch.name);
        }
        final String[] pool = "f".equals(gender) ? FEMALE_NAMES : MALE_NAMES;
        final List<String> free = new ArrayList<>();
        for (final String n : pool) {
            if (!used.contains(n)) {
                free.add(n);
            }
        }
        if (!free.isEmpty()) {
            return free.get(rng.nextInt(free.size()));
        }
        // Todos en uso: genera una variante unica ("Tobias II", "Tobias III"...).
        final String base = pool[rng.nextInt(pool.length)];
        for (int i = 2; ; i++) {
            final String cand = base + " " + roman(i);
            if (!used.contains(cand)) {
                return cand;
            }
        }
    }

    private static String randGender(java.util.Random rng) {
        return rng.nextBoolean() ? "m" : "f";
    }

    private static String randomSurname(java.util.Random rng) {
        return SURNAMES[rng.nextInt(SURNAMES.length)];
    }

    private static String roman(int n) {
        final String[] r = {"", "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X"};
        return n < r.length ? r[n] : String.valueOf(n);
    }

    private void reconcile() {
        gateway.getVillage().whenComplete((json, err) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (err != null || json == null) {
                return;
            }
            routines.dedupe();  // borra aldeanos-clon que hayan quedado de reinicios/recargas
            ageAndDeath();      // envejecen; a los 65 se jubilan; de muy mayores mueren (lento)
            matureChildren();   // los ninos que ya han crecido se mudan a su casa
            maybeMarry();       // dos solteros pueden casarse y mudarse a una casa mediana nueva
            repairHouses();     // mantenimiento: seca las casas que se hayan inundado
            updateBios();       // refresca su ficha (edad/oficio/familia) para que hablen de si

            // Todos los aldeanos son colonos (no hay vecinos "base"): el objetivo es la poblacion.
            final int target = Math.max(2, json.get("population").getAsInt());
            final int adults = colonos.size();
            final int have = adults + children.size();
            if (have < target) {
                final var rng = ThreadLocalRandom.current();
                // ¿A que aldea va el nuevo? La primera con sitio; si todas estan llenas, se FUNDA
                // una nueva lejos (una pareja parte a prosperar a otra zona).
                final int vid = assignTown();
                final int enAldea = countInTown(vid);
                // Los dos primeros de cada aldea son de distinto sexo (para formar familia).
                if (enAldea < 2 || target - have >= 2) {
                    final String g = enAldea == 1 ? oppositeOfSole(vid) : randGender(rng);
                    growAdult(vid, colonos.size(), freshName(g, rng), randomSurname(rng), g,
                            20 + rng.nextInt(40), "");
                } else if (!bearChild()) {
                    final String g = randGender(rng);   // sin pareja fertil, llega un inmigrante
                    growAdult(vid, colonos.size(), freshName(g, rng), randomSurname(rng), g,
                            20 + rng.nextInt(40), "");
                }
            } else if (have > target) {
                if (!children.isEmpty()) {
                    final Child c = children.remove(children.size() - 1);
                    if (c.baby != null) {
                        c.baby.remove();
                    }
                } else {
                    shrink();
                }
            }
            final String level = json.has("level") ? json.get("level").getAsString() : "estable";
            worldWork(level);   // los NPC mejoran el mundo (amplian cultivos) con el tiempo
            townLife();         // alcalde de cada aldea + granero donde los oficios producen
        }));
    }

    /** Nace un nino de una PAREJA fertil (un hombre y una mujer, casados). Dos personas del
     *  mismo sexo no tienen hijos biologicos. Devuelve true si hubo nacimiento. */
    private boolean bearChild() {
        final var rng = ThreadLocalRandom.current();
        // Madres posibles: mujer no jubilada, casada con un hombre no jubilado.
        final List<Colono> mothers = new ArrayList<>();
        for (final Colono c : colonos) {
            if (c.retired || !"f".equals(c.gender) || c.spouse == null) {
                continue;
            }
            final Colono sp = findColono(c.spouse);
            if (sp != null && "m".equals(sp.gender) && !sp.retired) {
                mothers.add(c);
            }
        }
        if (mothers.isEmpty()) {
            return false;   // no hay pareja fertil ahora mismo: que venga un inmigrante
        }
        final Colono mother = mothers.get(rng.nextInt(mothers.size()));
        final Colono father = findColono(mother.spouse);
        final String gender = randGender(rng);
        final String name = freshName(gender, rng);
        // Aparece junto a la casa de su familia.
        final Location base = new Location(world, mother.x + 0.5, mother.y, mother.z + 2.5);
        final Location at = base.clone().add(rng.nextInt(3) - 1, 0, rng.nextInt(3) - 1);
        final Villager baby = (Villager) world.spawnEntity(at, EntityType.VILLAGER);
        baby.setBaby();
        baby.customName(net.kyori.adventure.text.Component.text("§b" + name + " §7(nino)"));
        baby.setCustomNameVisible(true);
        baby.setPersistent(true);
        baby.setRemoveWhenFarAway(false);
        baby.setInvulnerable(true);
        baby.addScoreboardTag(BABY_TAG);
        convo.registerConversable(baby, "nino", name);   // se puede hablar con los ninos
        final String hijo = "f".equals(gender) ? "hija" : "hijo";
        final String of = ", " + hijo + " de " + father.name + " y " + mother.name;
        convo.setBio(name, "Eres " + name + ", un nino pequeno del pueblo de Aetheria" + of
                + ". Todavia no trabajas; hablas con la inocencia de un nino.");
        final String famSurname = (father != null && !father.surname.isEmpty())
                ? father.surname : mother.surname;   // hereda el apellido de la familia
        children.add(new Child(baby, name, mother.name, gender, mother.vid, famSurname,
                System.currentTimeMillis() + GROW_MS));
        Bukkit.getOnlinePlayers().forEach(p -> p.sendMessage(
                "§d[Pueblo] §fHa nacido §b" + name + "§f" + of + "."));
        gateway.postEvent("nacimiento", "Nace " + name + of + ".");
        plugin.getLogger().info("[Aetheria] Pueblo vivo: nace un nino (" + name + ").");
        return true;
    }

    /** Al llegar a la edad de trabajar (16), el nino se hace adulto con casa y oficio propios. */
    private void matureChildren() {
        final long now = System.currentTimeMillis();
        final Iterator<Child> it = children.iterator();
        while (it.hasNext()) {
            final Child c = it.next();
            if (now < c.matureAt) {
                continue;
            }
            it.remove();
            if (c.baby != null) {
                c.baby.remove();
            }
            growAdult(c.vid, colonos.size(), c.name, c.surname, c.gender, WORK_AGE, c.parent);
            Bukkit.getOnlinePlayers().forEach(p -> p.sendMessage(
                    "§a[Pueblo] §b" + c.name + " §7ha crecido y se ha mudado a su propia casa."));
        }
    }

    /** Los granjeros amplian los cultivos del pueblo cuando prospera (solo sobre suelo libre). */
    private void worldWork(String level) {
        if (!level.equals("prospero") && !level.equals("floreciente")) {
            return;
        }
        maybeCivic(level);   // la prosperidad tambien mejora la plaza (no solo el dinero)
        if (farmRadius >= 7 || ThreadLocalRandom.current().nextInt(100) >= 45) {
            return;
        }
        farmRadius++;
        final int fx = village.spawnX() + 14;
        final int fz = village.spawnZ() + 22;
        final int fy = village.baseY();
        final int r = farmRadius;
        int planted = 0;
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                if (Math.max(Math.abs(dx), Math.abs(dz)) != r) {
                    continue;   // solo el anillo nuevo
                }
                final var ground = world.getBlockAt(fx + dx, fy, fz + dz);
                final var above = world.getBlockAt(fx + dx, fy + 1, fz + dz);
                // No romper construcciones: solo sobre cesped/tierra con aire encima.
                if (above.getType().isAir()
                        && (ground.getType() == Material.GRASS_BLOCK || ground.getType() == Material.DIRT)) {
                    ground.setType(Material.FARMLAND, false);
                    above.setType(Material.WHEAT, false);
                    planted++;
                }
            }
        }
        if (planted > 0) {
            Bukkit.getOnlinePlayers().forEach(p -> p.sendMessage(
                    "§7[Pueblo] Los granjeros han ampliado los cultivos del pueblo."));
        }
    }

    // Mejoras civicas de la plaza, en orden: (dx, dz, tipo) respecto al centro de la plaza.
    // tipo: 0 farola, 1 jardin, 2 banco, 3 puesto de mercado.
    private static final int[][] CIVIC = {
        {5, 5, 0}, {-5, 5, 0}, {5, -5, 0}, {-5, -5, 0},        // faroles en las esquinas
        {8, 0, 1}, {-8, 0, 1}, {0, 8, 1}, {0, -8, 1},          // jardines en los lados
        {9, 6, 3}, {-9, 6, 3},                                  // dos puestos de mercado
        {7, -7, 2}, {-7, -7, 2}, {7, 7, 2}, {-7, 7, 2},        // bancos
        {11, 0, 0}, {-11, 0, 0}, {0, 11, 0}, {0, -11, 0},      // mas faroles (caminos)
    };

    /** Cuando el pueblo prospera, mejora fisicamente la plaza (no solo el dinero). */
    private void maybeCivic(String level) {
        if (civic >= CIVIC.length) {
            return;
        }
        final int chance = level.equals("floreciente") ? 45 : 22;
        if (ThreadLocalRandom.current().nextInt(100) >= chance) {
            return;
        }
        final int[] u = CIVIC[civic];
        final Location plaza = village.plaza();
        buildCivic(plaza.getBlockX() + u[0], plaza.getBlockZ() + u[1], u[2]);
        civic++;
        saveCivic();
        final String quien = tradesman(Villager.Profession.MASON, "El cantero del pueblo");
        Bukkit.getOnlinePlayers().forEach(p -> p.sendMessage(
                "§7[Pueblo] La prosperidad se nota: se ha mejorado la plaza."));
        gateway.postEvent("mejora", quien + " ha embellecido la plaza del pueblo.");
    }

    private void buildCivic(int x, int z, int type) {
        final int gy = groundY(x, z);
        switch (type) {
            case 0 -> {   // farola
                world.getBlockAt(x, gy, z).setType(Material.COBBLESTONE, false);
                world.getBlockAt(x, gy + 1, z).setType(Material.OAK_FENCE, false);
                world.getBlockAt(x, gy + 2, z).setType(Material.OAK_FENCE, false);
                world.getBlockAt(x, gy + 3, z).setType(Material.LANTERN, false);
            }
            case 1 -> {   // jardin 3x3 con cerca y flores
                int f = 0;
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        world.getBlockAt(x + dx, gy, z + dz).setType(Material.GRASS_BLOCK, false);
                        final boolean edge = Math.abs(dx) == 1 || Math.abs(dz) == 1;
                        world.getBlockAt(x + dx, gy + 1, z + dz).setType(
                                edge ? Material.OAK_FENCE : FLOWERS[(f++) % FLOWERS.length], false);
                    }
                }
                world.getBlockAt(x, gy + 2, z).setType(Material.LANTERN, false);
            }
            case 2 -> {   // banco (dos escalones mirando al centro) con farol
                world.getBlockAt(x, gy + 1, z).setType(Material.OAK_STAIRS, false);
                world.getBlockAt(x + 1, gy + 1, z).setType(Material.OAK_STAIRS, false);
                world.getBlockAt(x - 1, gy + 1, z).setType(Material.OAK_FENCE, false);
                world.getBlockAt(x - 1, gy + 2, z).setType(Material.LANTERN, false);
            }
            default -> { // puesto de mercado: mostrador con toldo y barril
                world.getBlockAt(x, gy + 1, z).setType(Material.OAK_FENCE, false);
                world.getBlockAt(x + 1, gy + 1, z).setType(Material.OAK_FENCE, false);
                world.getBlockAt(x, gy + 2, z).setType(Material.OAK_SLAB, false);
                world.getBlockAt(x + 1, gy + 2, z).setType(Material.OAK_SLAB, false);
                world.getBlockAt(x, gy + 3, z).setType(Material.RED_WOOL, false);
                world.getBlockAt(x + 1, gy + 3, z).setType(Material.WHITE_WOOL, false);
                world.getBlockAt(x, gy + 1, z + 1).setType(Material.BARREL, false);
            }
        }
    }

    private void loadCivic() {
        if (!civicFile.exists()) {
            return;
        }
        try (BufferedReader r = new BufferedReader(new FileReader(civicFile))) {
            final String line = r.readLine();
            if (line != null && !line.isBlank()) {
                civic = Math.max(0, Math.min(CIVIC.length, Integer.parseInt(line.trim())));
            }
        } catch (Exception e) {
            plugin.getLogger().warning("[Aetheria] no pude cargar civic: " + e.getMessage());
        }
    }

    private void saveCivic() {
        try (FileWriter w = new FileWriter(civicFile, false)) {
            w.write(Integer.toString(civic));
        } catch (Exception e) {
            plugin.getLogger().warning("[Aetheria] no pude guardar civic: " + e.getMessage());
        }
    }

    private void growAdult(int vid, int index, String given, String surname, String gender,
            double initialAge, String parent) {
        final Location center = townCenter(vid);
        final String name = surname.isEmpty() ? given : given + " " + surname;   // "Nombre Apellido"
        final var rng = ThreadLocalRandom.current();
        final Villager.Profession prof = neededProfession(vid, rng);   // el oficio que falta en la aldea
        final int cx;
        final int cz;
        final int fy;
        // Primero: ¿hay una casa EN VENTA cerca? El colono se muda a ella (no se construye nada,
        // solo cambia el cartel a su nombre). Asi se reaprovechan las casas de los que se casaron.
        final int[] vac = claimVacant(center);
        if (vac != null) {
            cx = vac[0];
            fy = vac[1] - 1;
            cz = vac[2];
            Blueprint.setHouseSign(world, cx, cz, fy, vac[3], "Casa de", "§6" + given);
        } else {
            final int[] spot = findBuildSpot(center, index);
            if (spot == null) {
                return;   // no encontro sitio libre ni casa en venta; lo reintenta el proximo ciclo
            }
            cx = spot[0];
            cz = spot[1];
            fy = spot[2];
            final Material[] pal = COMBOS[rng.nextInt(COMBOS.length)];
            // Un aldeano SOLTERO vive en una casa MUY PEQUENA (una sola cama). Al casarse se le
            // construye una mediana (ver maybeMarry).
            final int halfX = 2;
            final int halfZ = rng.nextInt(100) < 35 ? 3 : 2;   // 5x5 o 5x7, modesta
            final BlockFace door = towardPlaza(center, cx, cz); // la puerta mira a la plaza
            prepareTerrain(cx, cz, fy);                        // tala arboles + nivela al suelo real
            Blueprint.buildHouse(world, cx, cz, fy, door, halfX, halfZ, 1, false,
                    pal[0], pal[1], pal[2], pal[3], true, 1, name);   // 1 cama (soltero)
            deflood(cx, fy, cz, 1);                            // por si algo de agua se colo
            pathTo(cx, cz, center);                            // sendero hacia la plaza
            placed.add(new int[] {cx, cz});
            plugin.buildRegistry().add(new int[] {cx - halfX - 1, fy - 2, cz - halfZ - 1,
                    cx + halfX + 1, fy + 14, cz + halfZ + 1});   // anti-solape con el jugador
        }
        // Trabaja en el EDIFICIO de su oficio (mercado/biblioteca/herreria...), compartido y
        // permanente; se levanta si su aldea aun no tiene uno de ese oficio.
        final Location workspot = ensureBuilding(vid, prof);

        final Colono c = new Colono();
        c.name = name;
        c.profKey = profKey(prof);
        c.x = cx;
        c.y = fy + 1;
        c.z = cz;
        c.bornMillis = System.currentTimeMillis();
        c.initialAge = initialAge;
        c.deathAge = randomDeathAge(rng);
        c.parent = parent;
        c.floors = 1;
        c.gender = gender;
        c.vid = vid;
        c.surname = surname;
        colonos.add(c);
        save();

        final Location home = new Location(world, cx + 0.5, fy + 1, cz + 0.5);
        routines.addColono("colono", name, home, workspot, prof, center);
        final String pueblo = towns.get(Math.max(0, Math.min(vid, towns.size() - 1))).name;
        Bukkit.getOnlinePlayers().forEach(p -> p.sendMessage(
                "§a[Pueblo] §f" + name + " §7(" + oficio(prof) + ") se ha instalado en §f" + pueblo + "§7."));
        plugin.getLogger().info("[Aetheria] Pueblo vivo: +1 colono (" + name + ", " + prof
                + ") en aldea " + vid + ".");
    }

    private boolean compatible(Colono a, Colono b) {
        if (a == b || a.vid != b.vid) {
            return false;   // se casan dentro de la misma aldea
        }
        if (a.name.equals(b.parent) || b.name.equals(a.parent)) {
            return false;   // padre/madre - hijo/a
        }
        if (a.parent != null && !a.parent.isEmpty() && a.parent.equals(b.parent)) {
            return false;   // hermanos
        }
        return true;
    }

    /**
     * Casa a dos solteros compatibles: construye una casa MEDIANA NUEVA para los dos (con tres
     * camas: la pareja y un futuro hijo/a), DEMUELE sus dos casas pequenas y los muda alli.
     */
    private void maybeMarry() {
        final var rng = ThreadLocalRandom.current();
        final List<Colono> singles = new ArrayList<>();
        for (final Colono c : colonos) {
            if (c.spouse == null && !c.retired) {
                singles.add(c);
            }
        }
        if (singles.size() < 2 || rng.nextInt(100) >= 40) {
            return;   // no siempre hay solteros, ni siempre se casan
        }
        java.util.Collections.shuffle(singles, rng);
        Colono a = null;
        Colono b = null;
        // Preferencia por pareja de DISTINTO sexo (lo habitual); si no hay, se permite del mismo.
        for (int pass = 0; pass < 2 && a == null; pass++) {
            for (int i = 0; i < singles.size() && a == null; i++) {
                for (int j = i + 1; j < singles.size(); j++) {
                    final Colono ci = singles.get(i);
                    final Colono cj = singles.get(j);
                    if (!compatible(ci, cj)) {
                        continue;
                    }
                    if (pass == 0 && ci.gender.equals(cj.gender)) {
                        continue;   // primera pasada: solo distinto sexo
                    }
                    a = ci;
                    b = cj;
                    break;
                }
            }
        }
        if (a == null) {
            return;
        }
        final Location center = townCenter(a.vid);
        final int[] spot = findBuildSpot(center, colonos.size() + 2);
        if (spot == null) {
            return;   // sin sitio libre ahora; se reintenta el proximo ciclo
        }
        final int cx = spot[0];
        final int cz = spot[1];
        final int fy = spot[2];
        final Material[] pal = COMBOS[rng.nextInt(COMBOS.length)];
        final int halfX = 3;
        final int halfZ = rng.nextInt(100) < 40 ? 4 : 3;   // MEDIANA (algo mayor que la de soltero)
        final BlockFace door = towardPlaza(center, cx, cz);
        prepareTerrain(cx, cz, fy);
        Blueprint.buildHouse(world, cx, cz, fy, door, halfX, halfZ, 1, false,
                pal[0], pal[1], pal[2], pal[3], true, 3, a.name + " y " + b.name);   // 3 camas
        deflood(cx, fy, cz, 1);                                          // por si se colo agua
        pathTo(cx, cz, center);
        final Location workA = ensureBuilding(a.vid, profFromKey(a.profKey));
        final Location workB = ensureBuilding(b.vid, profFromKey(b.profKey));

        // Sus dos casitas NO se demuelen: quedan EN VENTA (sin dueno) y se reasignaran a los
        // proximos colonos. Asi el pueblo no hace aparecer/desaparecer casas por arte de magia.
        vacate(a);
        vacate(b);

        a.x = cx;  a.y = fy + 1;  a.z = cz;  a.floors = 1;  a.spouse = b.name;
        b.x = cx;  b.y = fy + 1;  b.z = cz;  b.floors = 1;  b.spouse = a.name;
        placed.add(new int[] {cx, cz});
        save();

        // Casa compartida (con destinos ligeramente distintos para no apilarse), pero cada uno
        // trabaja en el edificio de SU oficio.
        routines.setHomeWork(a.name, new Location(world, cx + 1.0, fy + 1, cz + 0.5), workA);
        routines.setHomeWork(b.name, new Location(world, cx, fy + 1, cz + 1.5), workB);

        final String msg = a.name + " y " + b.name
                + " se han casado y se han mudado juntos a una casa nueva.";
        gateway.postEvent("boda", msg);
        Bukkit.getOnlinePlayers().forEach(p -> p.sendMessage("§d[Pueblo] §f" + msg));
        plugin.getLogger().info("[Aetheria] Pueblo vivo: boda (" + a.name + " + " + b.name + ").");
    }

    private Colono findColono(String name) {
        if (name == null) {
            return null;
        }
        for (final Colono c : colonos) {
            if (c.name.equals(name)) {
                return c;
            }
        }
        return null;
    }

    /** Envejecimiento LENTO: a los 65 se jubilan; de muy mayores mueren (libera espacio en BD). */
    private void ageAndDeath() {
        final long now = System.currentTimeMillis();
        final Iterator<Colono> it = colonos.iterator();
        boolean changed = false;
        while (it.hasNext()) {
            final Colono c = it.next();
            final double age = c.age(now);
            if (age >= c.deathAge) {
                it.remove();
                routines.removeColono(c.name);
                changed = true;
                final String oficioDelDifunto = oficio(profFromKey(c.profKey));
                final Colono heir = pickSuccessor(c);
                String relevo = null;
                final String successor;
                if (heir != null) {
                    final String antes = oficio(profFromKey(heir.profKey));
                    heir.profKey = c.profKey;   // cambia de oficio para cubrir la vacante
                    routines.setProfession(heir.name, profFromKey(c.profKey));
                    routines.setHomeWork(heir.name,     // pasa a trabajar en el edificio del oficio
                            new Location(world, heir.x + 0.5, heir.y, heir.z + 0.5),
                            ensureBuilding(heir.vid, profFromKey(c.profKey)));
                    successor = heir.name;
                    if (!antes.equals(oficioDelDifunto)) {
                        relevo = heir.name + ", que era " + antes + ", se hace " + oficioDelDifunto
                                + " para cubrir la vacante de " + c.name + ".";
                    }
                } else {
                    successor = "nadie de momento";
                }
                final String family = livingChildren(c);
                final String msg = String.format(
                        "Muere %s, %s, a los %d anos.%s Le releva %s.",
                        c.name, oficioDelDifunto, (int) age,
                        family.isEmpty() ? "" : " Le sobreviven " + family + ".", successor);
                gateway.postEvent("obituario", msg);
                Bukkit.getOnlinePlayers().forEach(p -> p.sendMessage("§8[Pueblo] §7" + msg));
                if (relevo != null) {
                    gateway.postEvent("relevo", relevo);   // cambio de oficio para cubrir la baja
                }
                convo.clearBio(c.name);
                final Colono widow = findColono(c.spouse);
                if (widow != null) {
                    widow.spouse = null;   // enviuda y conserva la casa comun
                } else {
                    demolish(c);           // vivia solo: su casa se derriba y queda un solar libre
                }
            } else if (age >= RETIRE_AGE && !c.retired) {
                c.retired = true;
                changed = true;
                routines.retire(c.name);
                final String msg = c.name + " se jubila de " + oficio(profFromKey(c.profKey)) + ".";
                gateway.postEvent("jubilacion", msg);
                Bukkit.getOnlinePlayers().forEach(p -> p.sendMessage("§7[Pueblo] " + msg));
            }
        }
        if (changed) {
            save();
        }
    }

    /** Hereda el oficio del fallecido: preferentemente un HIJO/A suyo; si no, el vecino mas joven
     *  de su misma aldea. */
    private Colono pickSuccessor(Colono dead) {
        Colono child = null;
        Colono best = null;
        for (final Colono c : colonos) {
            if (c == dead || c.retired || c.vid != dead.vid) {
                continue;
            }
            if (dead.name.equals(c.parent) && (child == null || c.initialAge < child.initialAge)) {
                child = c;
            }
            if (best == null || c.initialAge < best.initialAge) {
                best = c;
            }
        }
        return child != null ? child : best;
    }

    private void loadBuildings() {
        buildings.clear();
        if (!buildingsFile.exists()) {
            return;
        }
        try (BufferedReader r = new BufferedReader(new FileReader(buildingsFile))) {
            String line;
            while ((line = r.readLine()) != null) {
                final String[] f = line.split(";", -1);
                if (f.length >= 5) {
                    final int cx = Integer.parseInt(f[2]);
                    final int cz = Integer.parseInt(f[3]);
                    buildings.add(new Building(Integer.parseInt(f[0]), f[1], cx, cz,
                            Integer.parseInt(f[4])));
                    placed.add(new int[] {cx, cz});   // que las casas no se planten encima
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("[Aetheria] no pude cargar edificios: " + e.getMessage());
        }
    }

    private void saveBuildings() {
        try (FileWriter w = new FileWriter(buildingsFile, false)) {
            for (final Building b : buildings) {
                w.write(b.vid + ";" + b.profKey + ";" + b.cx + ";" + b.cz + ";" + b.baseY + "\n");
            }
        } catch (Exception e) {
            plugin.getLogger().warning("[Aetheria] no pude guardar edificios: " + e.getMessage());
        }
    }

    private void loadVacants() {
        vacants.clear();
        if (!vacantsFile.exists()) {
            return;
        }
        try (BufferedReader r = new BufferedReader(new FileReader(vacantsFile))) {
            String line;
            while ((line = r.readLine()) != null) {
                final String[] f = line.split(";", -1);
                if (f.length >= 4) {
                    vacants.add(new int[] {Integer.parseInt(f[0]), Integer.parseInt(f[1]),
                            Integer.parseInt(f[2]), Integer.parseInt(f[3])});
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("[Aetheria] no pude cargar casas en venta: " + e.getMessage());
        }
    }

    private void saveVacants() {
        try (FileWriter w = new FileWriter(vacantsFile, false)) {
            for (final int[] v : vacants) {
                w.write(v[0] + ";" + v[1] + ";" + v[2] + ";" + v[3] + "\n");
            }
        } catch (Exception e) {
            plugin.getLogger().warning("[Aetheria] no pude guardar casas en venta: " + e.getMessage());
        }
    }

    /** Al casarse: la casita del colono NO se demuele; queda EN VENTA (sin dueno) y su cartel lo
     *  anuncia. Se reasignara a un futuro colono (asi no desaparece por arte de magia). */
    private void vacate(Colono c) {
        Blueprint.setHouseSign(world, c.x, c.z, c.y - 1, c.floors, "§eEn venta", "§7(libre)");
        vacants.add(new int[] {c.x, c.y, c.z, c.floors});
        saveVacants();
    }

    /** Toma la casa EN VENTA mas cercana al centro de la aldea (si hay alguna a mano) para un
     *  colono nuevo, en vez de construir una de cero. Devuelve {x,y,z,floors} o null. */
    private int[] claimVacant(Location center) {
        int bestIdx = -1;
        double bestDist = Double.MAX_VALUE;
        for (int i = 0; i < vacants.size(); i++) {
            final int[] v = vacants.get(i);
            final double d = Math.hypot(v[0] - center.getX(), v[2] - center.getZ());
            if (d < bestDist) {
                bestDist = d;
                bestIdx = i;
            }
        }
        if (bestIdx < 0 || bestDist > 140) {   // solo si hay una vacante razonablemente cerca
            return null;
        }
        final int[] v = vacants.remove(bestIdx);
        saveVacants();
        return v;
    }

    /** Punto de trabajo del EDIFICIO del oficio en esa aldea; lo levanta si aun no existe.
     *  Los edificios son PERMANENTES: no se derriban al morir su aldeano (los hereda otro). */
    private Location ensureBuilding(int vid, Villager.Profession prof) {
        final String key = profKey(prof);
        for (final Building b : buildings) {
            if (b.vid == vid && b.profKey.equals(key)) {
                return new Location(world, b.cx + 0.5, b.baseY + 1, b.cz + 0.5);
            }
        }
        final int[] spot = findBuildSpot(townCenter(vid), colonos.size() + buildings.size());
        if (spot == null) {
            return townCenter(vid);   // sin sitio ahora: trabaja en la plaza de momento
        }
        final int cx = spot[0];
        final int cz = spot[1];
        final int fy = spot[2];
        final BlockFace door = towardPlaza(townCenter(vid), cx, cz);
        prepareTerrain(cx, cz, fy);
        // Generador PROPIO de puestos de trabajo (no un cascaron de casa): cada oficio tiene su
        // diseno con elementos de Minecraft acordes (forja con fraguas y yunque, huerto, biblioteca...).
        Blueprint.workplaceShowcase(world, cx, fy, cz, key);
        tradeSign(cx, fy, cz, prof, door);
        deflood(cx, fy, cz, 1);
        pathTo(cx, cz, townCenter(vid));
        placed.add(new int[] {cx, cz});
        plugin.buildRegistry().add(new int[] {cx - 5, fy - 2, cz - 5, cx + 5, fy + 14, cz + 5});
        buildings.add(new Building(vid, key, cx, cz, fy));
        saveBuildings();
        gateway.postEvent("edificio", "El pueblo levanta " + buildingName(prof) + " en "
                + towns.get(Math.max(0, Math.min(vid, towns.size() - 1))).name + ".");
        return new Location(world, cx + 0.5, fy + 1, cz + 0.5);
    }

    /** Cartel del oficio delante del puesto de trabajo (se pone una sola vez, no trepa). */
    private void tradeSign(int cx, int fy, int cz, Villager.Profession prof, BlockFace door) {
        final int sx = cx + door.getModX() * 3;
        final int sz = cz + door.getModZ() * 3;
        final int gy = groundY(sx, sz);
        world.getBlockAt(sx, gy + 1, sz).setType(Material.OAK_FENCE, false);
        final Block b = world.getBlockAt(sx, gy + 2, sz);
        b.setType(Material.OAK_SIGN, false);
        if (b.getBlockData() instanceof org.bukkit.block.data.Rotatable rot) {
            rot.setRotation(door.getOppositeFace());
            b.setBlockData(rot, false);
        }
        if (b.getState() instanceof org.bukkit.block.Sign s) {
            s.getSide(org.bukkit.block.sign.Side.FRONT).line(1, Component.text("§6" + tradeLabel(prof)));
            s.update(true);
        }
    }

    private static String tradeLabel(Villager.Profession p) {
        return buildingName(p).replaceFirst("^una? ", "");
    }

    private static String buildingName(Villager.Profession p) {
        if (p == Villager.Profession.FARMER) return "una Granja";
        if (p == Villager.Profession.FISHERMAN) return "una Pescaderia";
        if (p == Villager.Profession.SHEPHERD) return "un Corral";
        if (p == Villager.Profession.MASON) return "una Canteria";
        if (p == Villager.Profession.LIBRARIAN) return "una Biblioteca";
        if (p == Villager.Profession.TOOLSMITH) return "una Herreria";
        if (p == Villager.Profession.BUTCHER) return "una Carniceria";
        if (p == Villager.Profession.FLETCHER) return "un Taller de arquero";
        return "un Taller";
    }

    private static Material[] buildingPalette(Villager.Profession p) {
        if (p == Villager.Profession.TOOLSMITH || p == Villager.Profession.MASON) {
            return new Material[] {Material.STONE_BRICKS, Material.DEEPSLATE_BRICKS,
                Material.DARK_OAK_PLANKS, Material.COBBLESTONE};
        }
        if (p == Villager.Profession.LIBRARIAN) {
            return new Material[] {Material.OAK_PLANKS, Material.OAK_LOG, Material.DARK_OAK_PLANKS,
                Material.BOOKSHELF};
        }
        if (p == Villager.Profession.BUTCHER || p == Villager.Profession.FARMER) {
            return new Material[] {Material.STRIPPED_OAK_WOOD, Material.OAK_LOG, Material.DARK_OAK_PLANKS,
                Material.BRICKS};
        }
        return new Material[] {Material.SPRUCE_PLANKS, Material.SPRUCE_LOG, Material.DARK_OAK_PLANKS,
            Material.STONE_BRICKS};
    }

    /** Coloca dentro del edificio los enseres tipicos del oficio (contra la pared del fondo). */
    private void buildingInterior(int cx, int cz, int fy, Villager.Profession prof, BlockFace door) {
        final int ax = door.getModX();
        final int az = door.getModZ();
        final int px = ax != 0 ? 0 : 1;
        final int pz = az != 0 ? 0 : 1;
        final int bx = cx - ax * 2;   // centro de la pared del fondo
        final int bz = cz - az * 2;
        final int y = fy + 1;
        if (prof == Villager.Profession.LIBRARIAN) {
            for (int d = -1; d <= 1; d++) {
                put(bx + px * d, y, bz + pz * d, Material.BOOKSHELF);
                put(bx + px * d, y + 1, bz + pz * d, Material.BOOKSHELF);
            }
            put(cx, y, cz, Material.LECTERN);
        } else if (prof == Villager.Profession.TOOLSMITH) {
            put(bx, y, bz, Material.BLAST_FURNACE);
            put(bx + px, y, bz + pz, Material.FURNACE);
            put(bx - px, y, bz - pz, Material.SMITHING_TABLE);
            put(cx + px, y, cz + pz, Material.ANVIL);
            put(cx - px, y, cz - pz, Material.GRINDSTONE);
        } else if (prof == Villager.Profession.MASON) {
            put(bx, y, bz, Material.STONECUTTER);
            put(bx + px, y, bz + pz, Material.CHISELED_STONE_BRICKS);
            put(bx - px, y, bz - pz, Material.STONE);
        } else if (prof == Villager.Profession.FARMER) {
            put(bx, y, bz, Material.COMPOSTER);
            put(bx + px, y, bz + pz, Material.BARREL);
            put(bx - px, y, bz - pz, Material.HAY_BLOCK);
        } else if (prof == Villager.Profession.FISHERMAN) {
            put(bx, y, bz, Material.BARREL);
            put(bx + px, y, bz + pz, Material.BARREL);
            put(bx - px, y, bz - pz, Material.BARREL);
        } else if (prof == Villager.Profession.SHEPHERD) {
            put(bx, y, bz, Material.LOOM);
            put(bx + px, y, bz + pz, Material.WHITE_WOOL);
            put(bx - px, y, bz - pz, Material.HAY_BLOCK);
        } else if (prof == Villager.Profession.BUTCHER) {
            put(bx, y, bz, Material.SMOKER);
            put(bx + px, y, bz + pz, Material.BARREL);
            put(cx, y, cz, Material.CAMPFIRE);
        } else if (prof == Villager.Profession.FLETCHER) {
            put(bx, y, bz, Material.FLETCHING_TABLE);
            put(bx + px, y, bz + pz, Material.BARREL);
            put(bx - px, y, bz - pz, Material.HAY_BLOCK);
        } else {
            put(bx, y, bz, Material.BARREL);
        }
    }

    /** True si el bloque forma parte de un edificio de oficio (para protegerlo, nunca se rompe). */
    private boolean buildingAt(Block b) {
        for (final Building bd : buildings) {
            final int fy = bd.baseY;
            if (b.getX() >= bd.cx - 5 && b.getX() <= bd.cx + 5 && b.getZ() >= bd.cz - 5
                    && b.getZ() <= bd.cz + 5 && b.getY() >= fy && b.getY() <= fy + 12) {
                return true;
            }
        }
        return false;
    }

    /**
     * Vida civica de cada aldea: un ALCALDE (el vecino mas veterano) con su cartel en la plaza,
     * y un GRANERO donde cada oficio deposita algo de su produccion (la economia se vuelve
     * tangible: al abrir el barril ves trigo, lana, hierro... segun quien trabaje en el pueblo).
     */
    private void townLife() {
        final long now = System.currentTimeMillis();
        for (int vid = 0; vid < towns.size(); vid++) {
            final Town t = towns.get(vid);
            Colono alc = null;
            for (final Colono c : colonos) {
                if (c.vid == vid && !c.retired && (alc == null || c.age(now) > alc.age(now))) {
                    alc = c;
                }
            }
            final String alcalde = alc != null ? alc.name : "";
            infoPanel(vid, t, alcalde);
            final String prev = alcaldes.get(vid);
            if (!alcalde.isEmpty() && !alcalde.equals(prev)) {
                if (prev != null) {
                    gateway.postEvent("gobierno", alcalde + " toma el cargo de alcalde de " + t.name + ".");
                }
                alcaldes.put(vid, alcalde);
            }
            produceInto(vid, t);
        }
    }

    private static final String PANEL_TAG = "aetheria_panel";

    /** Panel HOLOGRAFICO de color (Text Display) flotando sobre la plaza: nombre del pueblo,
     *  alcalde, habitantes y prosperidad — como una pantalla de informacion, sin carteles. */
    private void infoPanel(int vid, Town t, String alcalde) {
        final int gy = t.baseY;   // cota fija de la plaza (nada de groundY: no debe "trepar")
        // Limpia posibles carteles/poste viejos apilados (bug anterior) sobre el punto del cartel.
        for (int yy = gy; yy <= gy + 20; yy++) {
            final Material m = world.getBlockAt(t.cx, yy, t.cz - 4).getType();
            if (m == Material.OAK_SIGN || m == Material.OAK_WALL_SIGN || m == Material.OAK_FENCE) {
                world.getBlockAt(t.cx, yy, t.cz - 4).setType(Material.AIR, false);
            }
        }
        final Location loc = new Location(world, t.cx + 0.5, gy + 3.4, t.cz + 0.5);
        final String tag = PANEL_TAG + "_" + vid;
        TextDisplay panel = null;
        for (final org.bukkit.entity.Entity e : world.getNearbyEntities(loc, 10, 10, 10)) {
            if (e instanceof TextDisplay td && e.getScoreboardTags().contains(tag)) {
                panel = td;
                break;
            }
        }
        if (panel == null) {
            panel = (TextDisplay) world.spawnEntity(loc, EntityType.TEXT_DISPLAY);
            panel.addScoreboardTag(PANEL_TAG);
            panel.addScoreboardTag(tag);
            panel.setBillboard(Display.Billboard.CENTER);   // siempre mira al jugador
            panel.setSeeThrough(false);
            panel.setPersistent(true);
            panel.setBackgroundColor(Color.fromARGB(190, 15, 15, 25));
            panel.setAlignment(TextDisplay.TextAlignment.CENTER);
        }
        final int hab = countInTown(vid);
        panel.text(Component.text(
                "§6§l" + t.name + "\n"
                + "§r§8· pueblo de Aetheria ·\n \n"
                + "§7Alcalde: §e" + (alcalde.isEmpty() ? "sin nombrar" : alcalde) + "\n"
                + "§7Habitantes: §a" + hab));
        panel.teleport(loc);
    }

    /** Cada oficio deposita 1 unidad de su produccion en el granero (barril) de su aldea. */
    private void produceInto(int vid, Town t) {
        final int bx = t.cx + 3;
        final int bz = t.cz;
        // Cota FIJA (suelo de la plaza), NO groundY: si no, el barril se ve a si mismo como suelo
        // y cada ciclo se planta otro encima -> pila vertical de barriles.
        final org.bukkit.block.Block bb = world.getBlockAt(bx, t.baseY + 1, bz);
        if (bb.getType() != Material.BARREL) {
            bb.setType(Material.BARREL, false);
        }
        if (!(bb.getState() instanceof org.bukkit.block.Container container)) {
            return;
        }
        final org.bukkit.inventory.Inventory inv = container.getInventory();
        for (final Colono c : colonos) {
            if (c.vid == vid && !c.retired) {
                inv.addItem(new org.bukkit.inventory.ItemStack(tradeGood(c.profKey), 1));
            }
        }
    }

    private static Material tradeGood(String profKey) {
        return switch (profKey) {
            case "farmer" -> Material.WHEAT;
            case "fisherman" -> Material.COD;
            case "shepherd" -> Material.WHITE_WOOL;
            case "mason" -> Material.STONE;
            case "butcher" -> Material.BEEF;
            case "librarian" -> Material.BOOK;
            case "toolsmith" -> Material.IRON_INGOT;
            case "fletcher" -> Material.ARROW;
            default -> Material.EMERALD;
        };
    }

    /** Refresca la ficha (edad, oficio, familia) de cada colono para que hable de si mismo. */
    private void updateBios() {
        final long now = System.currentTimeMillis();
        for (final Colono c : colonos) {
            final int age = (int) c.age(now);
            final String job = c.retired
                    ? "jubilado (antes fue " + oficio(profFromKey(c.profKey)) + ")"
                    : oficio(profFromKey(c.profKey));
            final boolean fem = "f".equals(c.gender);
            final StringBuilder fam = new StringBuilder();
            if (c.spouse != null && !c.spouse.isEmpty()) {
                fam.append(fem ? " Estas casada con " : " Estas casado con ").append(c.spouse).append(".");
            }
            if (c.parent != null && !c.parent.isEmpty()) {
                fam.append(" Tu padre o madre es ").append(c.parent).append(".");
            }
            final String kids = livingChildren(c);   // "sus hijos X, Y" o ""
            if (!kids.isEmpty()) {
                fam.append(" Tus hijos son ").append(kids.replace("sus hijos ", "")).append(".");
            }
            if (c.name.equals(alcaldes.get(c.vid)) && c.vid < towns.size()) {
                fam.append(" Eres el ALCALDE de ").append(towns.get(c.vid).name)
                        .append("; hablas con orgullo de tu pueblo.");
            }
            final String bio = "Eres " + c.name + ", " + (fem ? "vecina" : "vecino")
                    + " del pueblo de Aetheria. Tienes " + age
                    + " anos y tu oficio es " + job + "." + fam
                    + " Si te preguntan, habla con naturalidad de tu edad, tu trabajo y tu familia.";
            convo.setBio(c.name, bio);
        }
    }

    /** Nombres de los hijos vivos de un colono (para el obituario). */
    private String livingChildren(Colono parent) {
        final StringBuilder sb = new StringBuilder();
        for (final Colono c : colonos) {
            if (parent.name.equals(c.parent)) {
                if (sb.length() > 0) {
                    sb.append(", ");
                }
                sb.append(c.name);
            }
        }
        return sb.length() > 0 ? "sus hijos " + sb : "";
    }

    private BlockFace towardPlaza(Location plaza, int cx, int cz) {
        final int dx = plaza.getBlockX() - cx;
        final int dz = plaza.getBlockZ() - cz;
        if (Math.abs(dx) >= Math.abs(dz)) {
            return dx >= 0 ? BlockFace.EAST : BlockFace.WEST;
        }
        return dz >= 0 ? BlockFace.SOUTH : BlockFace.NORTH;
    }

    /** Tala arboles del hueco y nivela SUAVE (poco) el terreno al nivel del suelo real. */
    private void prepareTerrain(int cx, int cz, int fy) {
        for (int dx = -6; dx <= 6; dx++) {
            for (int dz = -6; dz <= 6; dz++) {
                final int x = cx + dx;
                final int z = cz + dz;
                for (int y = fy + 1; y <= fy + 14; y++) {   // despeja/tala SOLO lo natural
                    final Material mm = world.getBlockAt(x, y, z).getType();
                    if (!mm.isAir() && natural(mm)) {
                        world.getBlockAt(x, y, z).setType(Material.AIR, false);
                    }
                }
                final int gy = groundY(x, z);
                if (gy < fy) {                               // rellena lo que falta (poco)
                    for (int y = gy + 1; y <= fy; y++) {
                        world.getBlockAt(x, y, z).setType(Material.DIRT, false);
                    }
                }
                final Material top = world.getBlockAt(x, fy, z).getType();
                if (top.isAir() || natural(top)) {          // no piso un suelo construido
                    world.getBlockAt(x, fy, z).setType(Material.GRASS_BLOCK, false);
                }
            }
        }
    }

    /** Sendero (dirt path) que sigue el relieve desde la casa hacia la plaza. */
    private void pathTo(int cx, int cz, Location plaza) {
        int x = cx;
        int z = cz;
        final int tx = plaza.getBlockX();
        final int tz = plaza.getBlockZ();
        for (int guard = 0; (x != tx || z != tz) && guard < 130; guard++) {
            if (Math.abs(tx - x) >= Math.abs(tz - z)) {
                x += Integer.signum(tx - x);
            } else {
                z += Integer.signum(tz - z);
            }
            final int gy = groundY(x, z);
            final Material below = world.getBlockAt(x, gy, z).getType();
            if (below == Material.WATER || below == Material.LAVA) {
                continue;
            }
            if (below == Material.GRASS_BLOCK || below == Material.DIRT || below == Material.STONE
                    || below == Material.GRAVEL || below == Material.COARSE_DIRT) {
                world.getBlockAt(x, gy, z).setType(Material.DIRT_PATH, false);
            }
            world.getBlockAt(x, gy + 1, z).setType(Material.AIR, false);
            world.getBlockAt(x, gy + 2, z).setType(Material.AIR, false);
        }
    }

    private void shrink() {
        final String name = routines.removeNewestColono();
        if (name != null) {
            convo.clearBio(name);
            if (!colonos.isEmpty()) {
                final Colono gone = colonos.remove(colonos.size() - 1);
                final Colono widow = findColono(gone.spouse);
                if (widow != null) {
                    widow.spouse = null;   // su pareja se queda con la casa
                } else {
                    demolish(gone);        // al emigrar (vivia solo), su casa se derriba
                }
            }
            save();
            Bukkit.getOnlinePlayers().forEach(p -> p.sendMessage(
                    "§7[Pueblo] " + name + " ha hecho las maletas y ha emigrado a otra tierra."));
            plugin.getLogger().info("[Aetheria] Pueblo vivo: -1 colono (" + name + " emigra).");
        }
    }


    /** Edad de muerte: 65..110, concentrada en 80-90 (media de dos uniformes, pico ~87). */
    private static int randomDeathAge(java.util.Random rng) {
        return 65 + (rng.nextInt(46) + rng.nextInt(46)) / 2;
    }

    /** El colono cuya casa contiene ese bloque (o null). Para proteger casas de aldeano. */
    private Colono ownerAt(Block b) {
        for (final Colono c : colonos) {
            final int fy = c.y - 1;
            if (b.getX() >= c.x - 6 && b.getX() <= c.x + 7 && b.getZ() >= c.z - 6 && b.getZ() <= c.z + 6
                    && b.getY() >= fy && b.getY() <= fy + c.floors * 6 + 8) {
                return c;
            }
        }
        return null;
    }

    /** True si el bloque esta en el NUCLEO de alguna aldea (plaza: pozo, campana, civico). Se
     *  calcula por proximidad a los centros de aldea, asi que se actualiza al fundar aldeas nuevas
     *  o al crecer (no es un recuadro fijo del spawn). */
    private boolean inVillageCore(Block b) {
        for (final Town t : towns) {
            if (Math.abs(b.getY() - t.baseY) <= 14
                    && Math.hypot(b.getX() - (t.cx + 0.5), b.getZ() - (t.cz + 0.5)) <= 13) {
                return true;
            }
        }
        return false;
    }

    /** Bloque de TERRENO natural del suelo (se puede recolectar aunque este junto a una casa):
     *  tierra, piedra y sus variantes, grava, arena, arcilla, nieve y minerales. NO incluye
     *  madera/tablon/ladrillo/cristal (eso es la casa) para que no se pueda vandalizar. */
    private static boolean terrain(Material m) {
        if (m.name().endsWith("_ORE")) {
            return true;
        }
        return switch (m) {
            case DIRT, GRASS_BLOCK, COARSE_DIRT, PODZOL, ROOTED_DIRT, MUD, DIRT_PATH, MYCELIUM,
                 STONE, GRANITE, DIORITE, ANDESITE, DEEPSLATE, TUFF, CALCITE, GRAVEL, CLAY,
                 SAND, RED_SAND, SANDSTONE, RED_SANDSTONE, SNOW, SNOW_BLOCK, MOSS_BLOCK -> true;
            default -> false;
        };
    }

    private boolean protect(Player player, Block b) {
        if (terrain(b.getType())) {
            return false;   // recolectar tierra/piedra/arena cerca de una casa esta permitido
        }
        final Colono c = ownerAt(b);
        if (c != null) {
            player.sendMessage("§cEsta es la casa de §f" + c.name + "§c. Todavia vive en la aldea: "
                    + "no puedes destruir ni coger nada suyo.");
            return true;
        }
        if (buildingAt(b)) {
            player.sendMessage("§cEsto es un edificio del pueblo (donde trabajan los aldeanos). "
                    + "No puedes destruirlo.");
            return true;
        }
        if (inVillageCore(b)) {
            player.sendMessage("§cEsto pertenece al pueblo de Aetheria. No puedes tocarlo.");
            return true;
        }
        return false;
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (protect(e.getPlayer(), e.getBlock())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (protect(e.getPlayer(), e.getBlock())) {
            e.setCancelled(true);
        }
    }

    /** Creepers/TNT no destruyen casas de aldeano ni el nucleo del pueblo (se quitan de la lista
     *  de bloques a volar). Asi no hay que reconstruir: sencillamente no se rompen. */
    @EventHandler(ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent e) {
        e.blockList().removeIf(b -> ownerAt(b) != null || buildingAt(b) || inVillageCore(b));
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) {
        e.blockList().removeIf(b -> ownerAt(b) != null || buildingAt(b) || inVillageCore(b));
    }

    /** Al ENTRAR en la zona de una aldea, aparece SU nombre en pantalla dando la bienvenida. */
    @EventHandler
    public void onMove(PlayerMoveEvent e) {
        if (e.getTo() == null || (e.getFrom().getBlockX() == e.getTo().getBlockX()
                && e.getFrom().getBlockZ() == e.getTo().getBlockZ())) {
            return;   // solo al cambiar de bloque horizontal (barato)
        }
        final Player p = e.getPlayer();
        if (!p.getWorld().equals(world)) {
            return;
        }
        int near = -1;
        double bestD = 49;
        for (int i = 0; i < towns.size(); i++) {
            final Town t = towns.get(i);
            final double d = Math.hypot(p.getX() - (t.cx + 0.5), p.getZ() - (t.cz + 0.5));
            if (d <= 48 && d < bestD) {
                bestD = d;
                near = i;
            }
        }
        final Integer was = inTown.get(p.getUniqueId());
        if (near >= 0 && (was == null || was != near)) {
            inTown.put(p.getUniqueId(), near);
            p.showTitle(Title.title(
                    Component.text("§6" + towns.get(near).name),
                    Component.text("§7Un pueblo de Aetheria"),
                    Title.Times.times(java.time.Duration.ofMillis(400),
                            java.time.Duration.ofSeconds(3), java.time.Duration.ofMillis(900))));
        } else if (near < 0 && was != null) {
            inTown.remove(p.getUniqueId());
        }
    }

    private Location townCenter(int vid) {
        final Town t = towns.get(Math.max(0, Math.min(vid, towns.size() - 1)));
        return new Location(world, t.cx + 0.5, t.baseY + 1, t.cz + 0.5);
    }

    private int countInTown(int vid) {
        int n = 0;
        for (final Colono c : colonos) {
            if (c.vid == vid) {
                n++;
            }
        }
        return n;
    }

    private String oppositeOfSole(int vid) {
        for (final Colono c : colonos) {
            if (c.vid == vid) {
                return "f".equals(c.gender) ? "m" : "f";
            }
        }
        return "m";
    }

    /** La primera aldea con sitio; si todas estan llenas, FUNDA una nueva lejos y devuelve su id. */
    private int assignTown() {
        for (int i = 0; i < towns.size(); i++) {
            if (countInTown(i) < PER_TOWN) {
                return i;
            }
        }
        return foundNewTown();
    }

    /** Funda una aldea NUEVA lejos de todas (fuera de vista), sobre tierra firme. Devuelve su id. */
    private int foundNewTown() {
        final var rng = ThreadLocalRandom.current();
        final Town origin = towns.get(0);
        int bcx = 0;
        int bcz = 0;
        boolean found = false;
        for (int t = 0; t < 40 && !found; t++) {
            final double ang = rng.nextDouble() * Math.PI * 2;
            final int dist = 220 + rng.nextInt(180);   // 220-400 bloques: bien lejos
            final int cx = origin.cx + (int) Math.round(Math.cos(ang) * dist);
            final int cz = origin.cz + (int) Math.round(Math.sin(ang) * dist);
            boolean far = true;
            for (final Town tw : towns) {
                if (Math.hypot(cx - tw.cx, cz - tw.cz) < 180) {
                    far = false;
                    break;
                }
            }
            if (!far) {
                continue;
            }
            final int gy = groundY(cx, cz);
            if (world.getBlockAt(cx, gy, cz).isLiquid() || world.getBlockAt(cx, gy + 1, cz).isLiquid()) {
                continue;   // agua: no fundar ahi
            }
            bcx = cx;
            bcz = cz;
            found = true;
        }
        if (!found) {
            return 0;   // no encontro sitio; el nuevo se queda en la aldea principal
        }
        String name = null;
        final java.util.Set<String> used = new java.util.HashSet<>();
        for (final Town tw : towns) {
            used.add(tw.name);
        }
        for (final String n : TOWN_NAMES) {
            if (!used.contains(n)) {
                name = n;
                break;
            }
        }
        if (name == null) {
            name = "Aldea " + (towns.size() + 1);
        }
        final Location plaza = village.buildPlazaAt(bcx, bcz);
        towns.add(new Town(name, plaza.getBlockX(), plaza.getBlockZ(), plaza.getBlockY() - 1));
        saveTowns();
        final String msg = "Unos colonos parten a fundar una nueva aldea, " + name + ", lejos de aqui.";
        gateway.postEvent("fundacion", msg);
        Bukkit.getOnlinePlayers().forEach(pl -> pl.sendMessage("§d[Mundo] §f" + msg));
        plugin.getLogger().info("[Aetheria] Nueva aldea fundada: " + name + " en " + bcx + "," + bcz);
        return towns.size() - 1;
    }

    private void loadTowns() {
        towns.clear();
        try {
            if (nameFile.exists()) {
                try (BufferedReader r = new BufferedReader(new FileReader(nameFile))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        final String[] f = line.split(";", -1);
                        if (f.length >= 4 && !f[0].isBlank()) {
                            towns.add(new Town(f[0], Integer.parseInt(f[1]),
                                    Integer.parseInt(f[2]), Integer.parseInt(f[3])));
                        }
                    }
                }
            }
        } catch (Exception ex) {
            plugin.getLogger().warning("[Aetheria] no pude cargar aldeas: " + ex.getMessage());
        }
        if (towns.isEmpty()) {
            final Location plaza = village.plaza();
            final String name = TOWN_NAMES[ThreadLocalRandom.current().nextInt(TOWN_NAMES.length)];
            towns.add(new Town(name, plaza.getBlockX(), plaza.getBlockZ(), village.baseY()));
            saveTowns();
        }
    }

    private void saveTowns() {
        try (FileWriter w = new FileWriter(nameFile, false)) {
            for (final Town t : towns) {
                w.write(t.name + ";" + t.cx + ";" + t.cz + ";" + t.baseY + "\n");
            }
        } catch (Exception ex) {
            plugin.getLogger().warning("[Aetheria] no pude guardar aldeas: " + ex.getMessage());
        }
    }

    /** Seca una casa: quita el agua/lava que se haya colado dentro (±3 del centro, sin tocar
     *  el estanque del pescador ni el huerto, que estan en el puesto al este). */
    private int deflood(int cx, int fy, int cz, int floors) {
        int dried = 0;
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                for (int y = fy + 1; y <= fy + floors * 6 + 2; y++) {
                    final Block b = world.getBlockAt(cx + dx, y, cz + dz);
                    if (b.getType() == Material.WATER || b.getType() == Material.LAVA) {
                        b.setType(Material.AIR, false);
                        dried++;
                    }
                }
            }
        }
        return dried;
    }

    /** MANTENIMIENTO: el ALBANIL del pueblo (si lo hay) repara las casas que se hayan inundado.
     *  Asi, aunque algo se cuele, el pueblo lo arregla solo. Enmarcado como un oficio, no como
     *  "el servidor", para que quede realista. */
    private void repairHouses() {
        int dried = 0;
        for (final Colono c : colonos) {
            dried += deflood(c.x, c.y - 1, c.z, c.floors);
        }
        if (dried > 0) {
            final String quien = tradesman(Villager.Profession.MASON, "El albanil del pueblo");
            Bukkit.getOnlinePlayers().forEach(p -> p.sendMessage(
                    "§7[Pueblo] " + quien + " ha achicado el agua de una casa inundada."));
        }
    }

    /** Nombre de un colono con ese oficio (para atribuirle una tarea), o un generico si no hay. */
    private String tradesman(Villager.Profession prof, String fallback) {
        final String key = profKey(prof);
        for (final Colono c : colonos) {
            if (!c.retired && key.equals(c.profKey)) {
                return c.name + " (" + oficio(prof) + ")";
            }
        }
        return fallback;
    }

    /** Demuele la casa de un colono (al morir o emigrar): la retira y deja un solar de cesped. */
    private void demolish(Colono c) {
        final int fy = c.y - 1;
        for (int dx = -6; dx <= 7; dx++) {    // solo la CASA (los edificios de oficio son permanentes)
            for (int dz = -6; dz <= 6; dz++) {
                for (int y = fy; y <= fy + c.floors * 6 + 8; y++) {
                    if (!world.getBlockAt(c.x + dx, y, c.z + dz).getType().isAir()) {
                        world.getBlockAt(c.x + dx, y, c.z + dz).setType(Material.AIR, false);
                    }
                }
                if (dx >= -6 && dx <= 6) {     // el solar vuelve a ser un claro natural
                    world.getBlockAt(c.x + dx, fy, c.z + dz).setType(Material.GRASS_BLOCK, false);
                    // el terreno se renaturaliza: hierba, alguna flor y algun brote de arbol.
                    final var rng = ThreadLocalRandom.current();
                    final int roll = rng.nextInt(100);
                    if (roll < 35) {
                        world.getBlockAt(c.x + dx, fy + 1, c.z + dz).setType(Material.SHORT_GRASS, false);
                    } else if (roll < 45) {
                        world.getBlockAt(c.x + dx, fy + 1, c.z + dz)
                                .setType(FLOWERS[rng.nextInt(FLOWERS.length)], false);
                    } else if (roll < 48) {
                        world.getBlockAt(c.x + dx, fy + 1, c.z + dz).setType(Material.OAK_SAPLING, false);
                    }
                }
            }
        }
        placed.removeIf(p -> p[0] == c.x && p[1] == c.z);   // libera el hueco
        plugin.buildRegistry().removeAt(c.x, c.z);          // y el solar en el registro compartido
    }

    /** Coloca un bloque SOLO si lo que hay es natural o aire (nunca pisa algo construido). */
    private void put(int x, int y, int z, Material m) {
        final Material cur = world.getBlockAt(x, y, z).getType();
        if (cur.isAir() || natural(cur)) {
            world.getBlockAt(x, y, z).setType(m, false);
        }
    }

    /** Cerca perimetral de radio r con una puerta al oeste (hacia la casa). */
    private void fencePen(int cx, int gy, int cz, int r) {
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                if (Math.abs(dx) != r && Math.abs(dz) != r) {
                    continue;
                }
                put(cx + dx, gy + 1, cz + dz,
                        (dx == -r && dz == 0) ? Material.OAK_FENCE_GATE : Material.OAK_FENCE);
            }
        }
    }

    /** Pequeno toldo: cuatro postes y un techo 3x3 (para puestos tipo mercado/herreria). */
    private void canopy(int cx, int gy, int cz, Material roof) {
        for (int dx = -1; dx <= 1; dx += 2) {
            for (int dz = -1; dz <= 1; dz += 2) {
                put(cx + dx, gy + 1, cz + dz, Material.OAK_FENCE);
                put(cx + dx, gy + 2, cz + dz, Material.OAK_FENCE);
            }
        }
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                put(cx + dx, gy + 3, cz + dz, roof);
            }
        }
    }

    /**
     * PUESTO DE TRABAJO tematico al este de la casa: NO es una casa, es una estructura que
     * PARECE su oficio (huerto, embarcadero, aprisco, taller de cantero, biblioteca, herreria,
     * carniceria, taller de arquero). Nivela un pad 5x5 (respetando lo construido) y construye
     * encima. Devuelve la cota del puesto (para colocar alli al aldeano a trabajar).
     */
    private int buildWorkplace(int hcx, int hcz, Villager.Profession prof) {
        final int wx = hcx + 9;
        final int wz = hcz;
        long sum = 0;
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                sum += groundY(wx + dx, wz + dz);
            }
        }
        final int wy = Math.round(sum / 25f);
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                for (int y = wy + 1; y <= wy + 6; y++) {   // despeja SOLO lo natural
                    final Material mm = world.getBlockAt(wx + dx, y, wz + dz).getType();
                    if (!mm.isAir() && natural(mm)) {
                        world.getBlockAt(wx + dx, y, wz + dz).setType(Material.AIR, false);
                    }
                }
                final int gy = groundY(wx + dx, wz + dz);
                if (gy < wy) {
                    for (int y = gy + 1; y <= wy; y++) {
                        put(wx + dx, y, wz + dz, Material.DIRT);
                    }
                }
                final Material top = world.getBlockAt(wx + dx, wy, wz + dz).getType();
                if (top.isAir() || natural(top)) {
                    world.getBlockAt(wx + dx, wy, wz + dz).setType(Material.GRASS_BLOCK, false);
                }
            }
        }
        workplaceStructure(wx, wy, wz, prof);
        return wy;
    }

    private void workplaceStructure(int wx, int gy, int wz, Villager.Profession prof) {
        if (prof == Villager.Profession.FARMER) {                 // HUERTO
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dz == 0) {
                        put(wx, gy, wz, Material.WATER);
                    } else {
                        put(wx + dx, gy, wz + dz, Material.FARMLAND);
                        put(wx + dx, gy + 1, wz + dz,
                                ((dx + dz) & 1) == 0 ? Material.WHEAT : Material.CARROTS);
                    }
                }
            }
            put(wx + 2, gy + 1, wz, Material.COMPOSTER);
            put(wx - 2, gy + 1, wz + 1, Material.HAY_BLOCK);
            put(wx - 2, gy + 2, wz + 1, Material.CARVED_PUMPKIN);   // espantapajaros
            fencePen(wx, gy, wz, 2);
        } else if (prof == Villager.Profession.FISHERMAN) {       // EMBARCADERO
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    put(wx + dx, gy, wz + dz, Material.WATER);
                }
            }
            put(wx, gy + 1, wz, Material.LILY_PAD);
            for (int dz = -1; dz <= 1; dz++) {
                put(wx + 2, gy + 1, wz + dz, Material.OAK_PLANKS);   // muelle
            }
            put(wx + 2, gy + 2, wz + 1, Material.OAK_FENCE);
            put(wx + 2, gy + 3, wz + 1, Material.LANTERN);
            put(wx + 2, gy + 2, wz - 1, Material.BARREL);
            put(wx - 2, gy + 1, wz, Material.BARREL);
        } else if (prof == Villager.Profession.SHEPHERD) {        // APRISCO
            fencePen(wx, gy, wz, 2);
            put(wx - 1, gy + 1, wz - 1, Material.WHITE_WOOL);       // "ovejas"
            put(wx - 1, gy + 2, wz - 1, Material.WHITE_WOOL);
            put(wx + 1, gy + 1, wz + 1, Material.BLACK_WOOL);
            put(wx + 1, gy + 1, wz - 1, Material.HAY_BLOCK);
            put(wx, gy + 1, wz, Material.SHORT_GRASS);
        } else if (prof == Villager.Profession.MASON) {           // TALLER DE CANTERO
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    put(wx + dx, gy, wz + dz, Material.STONE_BRICKS);
                }
            }
            put(wx, gy + 1, wz, Material.STONECUTTER);
            put(wx + 1, gy + 1, wz, Material.CHISELED_STONE_BRICKS);
            put(wx + 1, gy + 2, wz, Material.STONE_BRICK_WALL);
            put(wx - 1, gy + 1, wz - 1, Material.POLISHED_ANDESITE);
            put(wx - 1, gy + 1, wz + 1, Material.STONE_BRICK_STAIRS);
            put(wx - 1, gy + 2, wz + 1, Material.STONE_BRICKS);      // pilar a medias
        } else if (prof == Villager.Profession.LIBRARIAN) {       // BIBLIOTECA
            canopy(wx, gy, wz, Material.OAK_SLAB);
            put(wx, gy + 1, wz, Material.LECTERN);
            put(wx + 1, gy + 1, wz, Material.BOOKSHELF);
            put(wx - 1, gy + 1, wz, Material.BOOKSHELF);
            put(wx + 1, gy + 2, wz, Material.BOOKSHELF);
            put(wx - 1, gy + 2, wz, Material.BOOKSHELF);
            put(wx, gy + 1, wz - 1, Material.LANTERN);
        } else if (prof == Villager.Profession.TOOLSMITH) {       // HERRERIA
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    put(wx + dx, gy, wz + dz, Material.COBBLESTONE);
                }
            }
            canopy(wx, gy, wz, Material.STONE_BRICK_SLAB);
            put(wx - 1, gy + 1, wz, Material.FURNACE);
            put(wx, gy + 1, wz, Material.BLAST_FURNACE);
            put(wx + 1, gy + 1, wz, Material.FURNACE);
            put(wx + 1, gy + 1, wz - 1, Material.ANVIL);
            put(wx - 1, gy + 1, wz - 1, Material.GRINDSTONE);
            put(wx, gy + 1, wz + 1, Material.CAMPFIRE);             // fragua encendida
        } else if (prof == Villager.Profession.BUTCHER) {         // CARNICERIA (puesto)
            canopy(wx, gy, wz, Material.RED_WOOL);                  // toldo rojo
            put(wx - 1, gy + 1, wz, Material.SMOKER);
            put(wx + 1, gy + 1, wz, Material.BARREL);
            for (int dx = -1; dx <= 1; dx++) {                      // mostrador al frente
                put(wx + dx, gy + 1, wz + 1, Material.OAK_FENCE);
                put(wx + dx, gy + 2, wz + 1, Material.OAK_SLAB);
            }
        } else if (prof == Villager.Profession.FLETCHER) {        // TALLER DE ARQUERO
            put(wx, gy + 1, wz, Material.FLETCHING_TABLE);
            put(wx + 1, gy + 1, wz, Material.HAY_BLOCK);
            for (int dx = -1; dx <= 1; dx++) {                      // diana en un muro al fondo
                put(wx + dx, gy + 1, wz - 2, Material.WHITE_WOOL);
                put(wx + dx, gy + 3, wz - 2, Material.WHITE_WOOL);
            }
            put(wx, gy + 2, wz - 2, Material.RED_WOOL);
            put(wx - 1, gy + 2, wz - 2, Material.WHITE_WOOL);
            put(wx + 1, gy + 2, wz - 2, Material.WHITE_WOOL);
        } else {                                                  // generico
            canopy(wx, gy, wz, Material.OAK_SLAB);
            put(wx, gy + 1, wz, Material.BARREL);
            put(wx, gy + 2, wz, Material.LANTERN);
        }
    }

    private static String oficio(Villager.Profession p) {
        if (p == Villager.Profession.FARMER) return "granjero";
        if (p == Villager.Profession.FISHERMAN) return "pescador";
        if (p == Villager.Profession.SHEPHERD) return "pastor";
        if (p == Villager.Profession.MASON) return "cantero";
        if (p == Villager.Profession.LIBRARIAN) return "bibliotecario";
        if (p == Villager.Profession.TOOLSMITH) return "herrero";
        if (p == Villager.Profession.BUTCHER) return "carnicero";
        if (p == Villager.Profession.FLETCHER) return "arquero";
        return "vecino";
    }
}
