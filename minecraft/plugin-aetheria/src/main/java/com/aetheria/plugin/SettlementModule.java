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
import org.bukkit.event.player.PlayerInteractEvent;
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
        Villager.Profession.FLETCHER, Villager.Profession.LEATHERWORKER};
    /** El TABERNERO es un oficio especial: su puesto de trabajo ES la taberna (se queda dentro
     *  sirviendo), asi que no se le construye un edificio propio y solo existe si hay taberna. */
    private static final Villager.Profession TAVERN_KEEPER = Villager.Profession.LEATHERWORKER;
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
    private final MarketModule market;
    private int farmRadius = 2;   // los cultivos del pueblo se amplian con el tiempo
    private int civic = 0;        // mejoras civicas de la plaza ya construidas (persistido)

    private static final String BABY_TAG = "aetheria_baby";
    private static final long GROW_MS = 6 * 60 * 1000L;   // un bebe tarda ~6 min en hacerse adulto
    private static final double YEARS_PER_DAY = 2.0;       // envejecen 2 anos por dia real
    private static final long DAY_MS = 86_400_000L;
    private static final int WORK_AGE = 16;
    private static final int RETIRE_AGE = 65;
    // CORTEJO: un colono no se casa nada mas aparecer; primero tiene que "conocerse" (llevar un
    // rato como adulto en la aldea). Evita que los fundadores se casen en el primer ciclo.
    private static final long COURTSHIP_MS = 4 * 60 * 1000L;

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
        double wealth;       // PECULIO propio: lo que ha ahorrado con su trabajo (se hereda)
        int halfX = 2;       // media huella de su casa (para que el albanil la reconstruya igual)
        int halfZ = 2;
        int pal;             // paleta de materiales de su casa (indice en COMBOS)
        boolean dimsKnown;   // ¿sabemos como es su casa? (si no, el albanil NO la reconstruye)
        String origin;       // si es FUNDADOR: nombre de la aldea de la que vino (null si no lo es)

        double age(long now) {
            return initialAge + (now - bornMillis) * YEARS_PER_DAY / DAY_MS;
        }

        String toLine() {
            return name + ";" + profKey + ";" + x + ";" + y + ";" + z + ";" + bornMillis + ";"
                    + initialAge + ";" + deathAge + ";" + (parent == null ? "" : parent) + ";"
                    + retired + ";" + floors + ";" + (spouse == null ? "" : spouse) + ";" + gender
                    + ";" + vid + ";" + surname + ";" + wealth + ";" + halfX + ";" + halfZ + ";" + pal
                    + ";" + (origin == null ? "" : origin);
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
    private final File civicFile2;    // civic-buildings.txt: "vid:clave" edificios civicos ya construidos
    private final File childFile;     // ninos.txt: los ninos del pueblo (aun no son adultos)
    // Edificios civicos ya levantados por aldea (granero, taberna, mercado). Clave "vid:tipo".
    private final java.util.Set<String> civicBuilt = new java.util.HashSet<>();
    // Solares RESERVADOS para edificios civicos aun no construidos (taberna/mercado): las casas
    // los evitan desde el principio, para no tener que pisarlos luego. En memoria (se recalcula).
    private final List<int[]> civicReserved = new ArrayList<>();
    // Casas EN VENTA (sin propietario): al casarse, las dos casitas de los novios no se demuelen,
    // quedan vacantes y se reasignan a los proximos colonos (asi no aparecen/desaparecen por magia).
    private final List<int[]> vacants = new ArrayList<>();   // {x, y, z, floors}
    private final java.util.Map<java.util.UUID, Integer> inTown = new java.util.HashMap<>();

    private static final double TOWN_RADIUS = 48;   // desde donde se considera que estas "en" la aldea
    // Las aldeas crecen SIN TOPE (hasta el infinito). En vez de un cap por aldea, cuando una llega a
    // cierto tamano una PAREJA se escinde y funda otra aldea (ver splitThreshold/trySplit). El techo
    // de aldeas es solo una salvaguarda para que el mundo no se llene de pueblos sin freno.
    private static final int MAX_TOWNS = 24;
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
        /** HUCHA de la aldea: lo que lleva ahorrado para traer al siguiente vecino. Sube con el
         *  trabajo de sus habitantes y baja con lo que cuesta mantenerlos. */
        double pool;
        /** Ciclos seguidos con la hucha en rojo (una mala racha, no una mala noche). */
        int hungry;
        /** Veces que quiso crecer y no habia solar libre (para avisarlo por el log). */
        int noRoom;
        /** Cuantas veces ESTA aldea ya se ha escindido (fundo una colonia). Sube su umbral de
         *  escision: cada linaje coloniza segun su propia madurez. */
        int splits;
        Town(String name, int cx, int cz, int baseY) {
            this.name = name;
            this.cx = cx;
            this.cz = cz;
            this.baseY = baseY;
        }
    }

    /**
     * Lo que le cuesta a una aldea de {@code n} vecinos traer al siguiente: casa, enseres y
     * comida hasta que produzca. <b>Cada vecino cuesta mas que el anterior</b>, pero a la vez
     * hay mas gente produciendo: el pueblo arranca despacio y luego coge ritmo, sin dispararse.
     */
    private static double growthCost(int n) {
        return TownMath.growthCost(n);   // la cuenta vive en TownMath (y esta cubierta por tests)
    }

    /** Poblacion a la que una aldea se ESCINDE (una pareja parte a fundar otra). Empieza en 6 y
     *  sube 2 por cada vez que ESA aldea ya se ha escindido (contador PROPIO): cada linaje coloniza
     *  segun su propia madurez, asi una aldea joven vuelve a partir de 6 (colonizacion en cascada) y
     *  una que ya pario varias colonias se calma. */
    private int splitThreshold(Town t) {
        return TownMath.splitThreshold(t.splits);
    }

    /**
     * Tamano a efectos de COSTE: solo los ADULTOS de la aldea.
     *
     * <p>Los ninos cuentan como vecinos en todo lo demas (marcador, panel de la plaza, edificios
     * civicos) y comen su parte del coste de vida cada ciclo, pero <b>no encarecen la llegada del
     * siguiente vecino</b>: no producen. Como cada vecino cuesta el doble que el anterior, contar
     * dos criticos cuadruplicaba el precio (480 AET en vez de 120) y dejaba a la aldea atascada
     * pagando como si fuera el doble de grande de lo que trabaja.
     *
     * <p>Tampoco se cobra por los que se marcharon a fundar otra aldea: se probo y dejaba a la
     * madre demasiado tocada. El freno a colonizar sin parar lo pone el UMBRAL de escision
     * ({@link #splitThreshold}, +2 por cada aldea fundada).
     */
    private int chargedSize(int vid) {
        return countInTown(vid);
    }

    /** Desgracias que sirven de excusa para que unos vecinos se marchen a fundar una aldea nueva. */
    private static final String[] SPLIT_REASONS = {
        "una mala cosecha", "una plaga en los cultivos", "un incendio que arraso un par de casas",
        "una disputa vecinal", "la escasez de tierras de labor", "un pozo que se seco",
        "un invierno muy duro",
    };

    private final List<Town> towns = new ArrayList<>();
    private final java.util.Map<Integer, String> alcaldes = new java.util.HashMap<>();  // vid -> alcalde

    // --- PRESTIGIO: un solo ranking por aldea donde compiten vecinos y jugadores (0009) ---

    /** Una linea del ranking de una aldea. Puede ser un vecino o un jugador: el primero manda. */
    public record Rank(String name, double score, boolean player, java.util.UUID uuid) { }

    /**
     * Prestigio de un ALDEANO = lo que ha ahorrado trabajando + un pellizco por veterania. Se
     * reutiliza el peculio que ya existia (sube con su trabajo, baja con {@code spendUpkeep}):
     * ni stat nuevo ni decadencia aparte.
     *
     * <p>Pero el peculio NO entra en bruto, y esto es la clave del equilibrio: es un acumulado
     * de por vida que crece <b>mientras el servidor este encendido</b> (~60 AET/hora en el
     * vecino mas trabajador), mientras que el prestigio del jugador solo sube cuando juega y a
     * ritmo acotado (3 encargos a la vez). En bruto, cualquier aldeano viejo se volvia
     * inalcanzable con solo dejar el mundo corriendo. Por eso el dinero del aldeano se comprime
     * por <b>raiz cuadrada y con techo</b>, igual que ya se comprimian las donaciones del
     * jugador: un vecino rico y veterano es un rival serio (tope 190 = ~13 encargos) pero
     * <b>alcanzable</b>, hoy y dentro de seis meses.
     */
    private static final double RIQUEZA_FACTOR = 6.0;    // prestigio = 6 * raiz(peculio)
    private static final double RIQUEZA_TOPE = 150.0;    // techo de lo que aporta el peculio
    private static final double BONUS_VETERANIA = 4.0;   // por dia real vivido en el pueblo
    private static final double VETERANIA_TOPE = 40.0;   // lo que como mucho aporta la veterania

    /** Ranking vigente por aldea (vecinos + jugadores), recalculado en cada ciclo de townLife(). */
    private final java.util.Map<Integer, List<Rank>> ranking = new java.util.HashMap<>();
    /** Ultimo prestigio conocido de los JUGADORES por aldea (se refresca en 2o plano). */
    private final java.util.Map<Integer, List<Rank>> playerRep = new java.util.HashMap<>();
    /** Misiones (opcional): el alguacil de cada aldea y los avances del jugador. */
    private QuestModule quests;
    /** Comercio con vecinos y BOTICA (opcional): pone al boticario cuando se construye. */
    private NpcTradeModule trade;
    /** Quien traza carreteras y senderos (ver RoadBuilder). */
    private final RoadBuilder roads;
    /**
     * Parcelas de jugador (opcional). El pueblo las consulta ANTES de plantar cualquier cosa:
     * casas, edificios civicos, puestos de oficio y carreteras. Lo que es de un jugador no se
     * toca — un colono llego a levantar su casa encima de la de alguien.
     */
    private ClaimModule claims;
    /** Margen alrededor del solar que tampoco puede pisar una parcela (el terreno se allana). */
    private static final int CLAIM_MARGIN = 10;

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
    /** Version compartida con {@link RoadBuilder}: los caminos allanan lo mismo que el pueblo. */
    static boolean isNatural(Material m) {
        return natural(m);
    }

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
     *  cueva, cuesta). Escanea ±6: como la huella de una casa es ±3, deja al menos 3 bloques de
     *  HUECO respecto a cualquier otra construccion (nada de casas pegadas). */
    private int[] evaluateSpot(int cx, int cz) {
        // PARCELA DE JUGADOR: intocable. Se mira con MARGEN (el solar se allana mas alla de la
        // huella de la casa), porque una casa del pueblo llego a comerse parte de la de un
        // jugador. Ante la duda, el pueblo construye en otro sitio.
        if (claims != null && claims.anyClaimIn(cx - CLAIM_MARGIN, cz - CLAIM_MARGIN,
                cx + CLAIM_MARGIN, cz + CLAIM_MARGIN)) {
            return null;
        }
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        for (int dx = -6; dx <= 6; dx++) {
            for (int dz = -6; dz <= 6; dz++) {
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
        // Respeta el REGISTRO anti-solape: nada de casas encima (ni pegadas) de un edificio ya
        // reservado/construido — incluidos los edificios civicos permanentes (granero/taberna/
        // mercado), que se reservan al fundar el pueblo. Esto evito que una casa pisara el granero.
        final int[] here = {cx - 5, min - 2, cz - 5, cx + 5, min + 14, cz + 5};
        if (plugin.buildRegistry().overlaps(here) || overlapsReserved(here)) {
            return null;
        }
        // El suelo se pone en la cota MAS BAJA de la huella: asi se TALLA el poco terreno que
        // sobresale (casa encajada en el relieve) en vez de RELLENAR con tierra por debajo (que
        // dejaba las casas elevadas sobre un "pegote"). Sin agua/hielo en la huella (ya rechazado).
        return new int[] {min, max - min};
    }

    public SettlementModule(AetheriaPlugin plugin, GatewayClient gateway, VillageModule village,
            NpcRoutineModule routines, ConversationManager convo, World world, MarketModule market) {
        this.plugin = plugin;
        this.gateway = gateway;
        this.village = village;
        this.routines = routines;
        this.convo = convo;
        this.world = world;
        this.market = market;
        plugin.getDataFolder().mkdirs();
        this.roads = new RoadBuilder(plugin, world);
        this.dataFile = new File(plugin.getDataFolder(), "colonos.txt");
        this.civicFile = new File(plugin.getDataFolder(), "civic.txt");
        this.nameFile = new File(plugin.getDataFolder(), "village.txt");
        this.buildingsFile = new File(plugin.getDataFolder(), "buildings.txt");
        this.vacantsFile = new File(plugin.getDataFolder(), "vacants.txt");
        this.civicFile2 = new File(plugin.getDataFolder(), "civic-buildings.txt");
        this.childFile = new File(plugin.getDataFolder(), "ninos.txt");
    }

    public void start() {
        world.getEntities().stream()   // limpia bebes huerfanos y paneles viejos de sesiones anteriores
                .filter(e -> e.getScoreboardTags().contains(BABY_TAG)
                        || e.getScoreboardTags().contains(PANEL_TAG))
                .forEach(org.bukkit.entity.Entity::remove);
        // FUENTE DE VERDAD: la base de datos (0010). Si responde y hay pueblo guardado, se usa
        // eso; si no (gateway caido, primer arranque), se tira de los .txt locales, que se siguen
        // escribiendo como copia de seguridad.
        final boolean fromDb = loadFromDb();
        final boolean fresh = !fromDb && !dataFile.exists();
        if (!fromDb) {
            loadTowns();       // las aldeas (nombre + centro) ANTES que los colonos y edificios
            loadBuildings();   // los edificios de oficio (permanentes)
            loadCivicBuildings();   // que edificios civicos (granero/taberna/mercado) ya existen
        }
        loadVacants();     // casas en venta que quedaron de bodas anteriores
        for (int vid = 0; vid < towns.size(); vid++) {   // aldeas que YA tienen taberna
            if (civicBuilt.contains(vid + ":taberna")) {
                routines.setTavern(townCenter(vid));
            }
        }
        load();            // reaparecen los colonos ya existentes en sus casas (sin reconstruir)
        loadChildren();    // y los ninos, que tambien son vecinos (antes se perdian al reiniciar)
        loadCivic();
        reserveCivicSpots();   // reserva/levanta los solares civicos ANTES de fundar casas (anti-choque)
        refreshTradeSigns();   // corrige el rotulo/orientacion de los carteles de oficio ya puestos
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

    /**
     * Reaparece a los colonos guardados en sus casas (los bloques ya persisten en el mundo).
     *
     * <p>Si el pueblo se ha cargado de la BASE DE DATOS, los colonos ya estan en memoria y aqui
     * solo hay que darles cuerpo: no se relee el .txt (si no, saldrian por duplicado).
     */
    private void load() {
        if (!colonos.isEmpty()) {
            for (final Colono c : colonos) {
                routines.addColono("colono", c.name, new Location(world, c.x + 0.5, c.y, c.z + 0.5),
                        ensureBuilding(c.vid, profFromKey(c.profKey)), profFromKey(c.profKey),
                        townCenter(c.vid), c.gender);
                routines.setStayAtWork(c.name, isKeeper(c.profKey));
                if (c.retired) {
                    routines.retire(c.name);
                }
                placed.add(new int[] {c.x, c.z});
            }
            return;
        }
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
                    c.wealth = f.length >= 16 && !f[15].isEmpty() ? Double.parseDouble(f[15]) : 0;
                    if (f.length >= 19) {   // huella y paleta de su casa (para reconstruirla igual)
                        c.halfX = Integer.parseInt(f[16]);
                        c.halfZ = Integer.parseInt(f[17]);
                        c.pal = Integer.parseInt(f[18]);
                        c.dimsKnown = true;
                    }
                    if (f.length >= 20 && !f[19].isEmpty()) {   // fundador venido de otra aldea
                        c.origin = f[19];
                    }
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
                        townCenter(c.vid), c.gender);
                routines.setStayAtWork(c.name, isKeeper(c.profKey));
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

    /** Crea el bebe de un nino del pueblo (entidad, nombre sobre la cabeza y conversable). */
    private Villager spawnBaby(String name, Location at) {
        // Reutiliza el bebe YA persistido con ese nombre (evita CLONES al reiniciar) y quita los
        // duplicados cargados. Los bebes de aldeas descargadas los reconcilia onEntitiesLoad.
        final String label = name + " (nino)";
        Villager baby = null;
        for (final Villager v : world.getEntitiesByClass(Villager.class)) {
            if (!v.getScoreboardTags().contains(BABY_TAG) || v.customName() == null) {
                continue;
            }
            final String pn = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
                    .plainText().serialize(v.customName());
            if (label.equals(pn)) {
                if (baby == null) {
                    baby = v;
                } else {
                    v.remove();   // duplicado del mismo nino: fuera
                }
            }
        }
        if (baby == null) {
            baby = (Villager) world.spawnEntity(at, EntityType.VILLAGER);
        }
        baby.setBaby();
        baby.setAgeLock(true);   // NO crece solo (vanilla): sigue nino hasta que madura por codigo
        baby.customName(Component.text("§b" + name + " §7(nino)"));
        baby.setCustomNameVisible(true);
        baby.setPersistent(true);
        baby.setRemoveWhenFarAway(false);
        baby.setInvulnerable(true);
        baby.addScoreboardTag(BABY_TAG);
        convo.registerConversable(baby, "nino", name);   // se puede hablar con los ninos
        return baby;
    }

    /**
     * Los NINOS tambien se persisten. Antes solo vivian en memoria: al reiniciar el servidor
     * desaparecian y la aldea perdia habitantes de golpe (con la poblacion cuentan igual que un
     * adulto para el marcador, el coste del siguiente vecino y los edificios civicos).
     */
    private void saveChildren() {
        try (FileWriter w = new FileWriter(childFile, false)) {
            for (final Child c : children) {
                w.write(c.name + ";" + (c.parent == null ? "" : c.parent) + ";" + c.gender + ";"
                        + c.vid + ";" + c.surname + ";" + c.matureAt + "\n");
            }
        } catch (Exception e) {
            plugin.getLogger().warning("[Aetheria] no pude guardar ninos: " + e.getMessage());
        }
    }

    private void loadChildren() {
        children.clear();
        if (!childFile.exists()) {
            return;
        }
        try (BufferedReader r = new BufferedReader(new FileReader(childFile))) {
            String line;
            while ((line = r.readLine()) != null) {
                final String[] f = line.split(";", -1);
                if (f.length < 6) {
                    continue;
                }
                final String parent = f[1];
                final int vid = Integer.parseInt(f[3]);
                // Reaparece junto a la casa de su familia (o en la plaza si ya no queda nadie).
                final Colono fam = findColono(parent);
                final Location at = fam != null
                        ? new Location(world, fam.x + 0.5, fam.y, fam.z + 2.5)
                        : townCenter(vid);
                children.add(new Child(spawnBaby(f[0], at), f[0], parent, f[2], vid, f[4],
                        Long.parseLong(f[5])));
            }
        } catch (Exception e) {
            plugin.getLogger().warning("[Aetheria] no pude cargar ninos: " + e.getMessage());
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
        pushState();   // y a la base de datos, que es la fuente de verdad (0010)
    }

    // ------------------------------------------------------------------
    // EL PUEBLO EN LA BASE DE DATOS (migracion 0010, Fase 1 del plan de mejoras)
    //
    // Hasta ahora el estado del pueblo vivia en ficheros de texto de la carpeta del plugin, lo
    // que contradecia la regla de oro #4 y dejaba al backend sin saber que existian las aldeas.
    // Ahora la fuente de verdad es Postgres; los .txt se siguen escribiendo como copia local de
    // seguridad, por si el gateway no responde justo al arrancar (sin ellos, el plugin creeria
    // que el mundo esta vacio y reconstruiria el pueblo encima del que ya hay).
    //
    // El plugin sigue MANDANDO: el decide quien nace y donde se construye. Esto es solo donde lo
    // apunta. Se manda la instantanea entera (como se reescribia el fichero entero), con un
    // pequeno freno para no inundar el gateway cuando pasan muchas cosas seguidas.
    // ------------------------------------------------------------------

    private long lastPush;
    private boolean pushPending;
    private static final long PUSH_COOLDOWN_MS = 8000L;

    /** Manda a la base de datos el estado del pueblo (con freno: como mucho cada 8 s). */
    private void pushState() {
        final long now = System.currentTimeMillis();
        if (now - lastPush < PUSH_COOLDOWN_MS) {
            if (!pushPending) {   // se agenda una unica escritura de recogida
                pushPending = true;
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    pushPending = false;
                    lastPush = System.currentTimeMillis();
                    gateway.saveVillageState(stateJson());
                }, 20L * 9);
            }
            return;
        }
        lastPush = now;
        gateway.saveVillageState(stateJson());
    }

    /** La instantanea del pueblo, tal cual la guarda el backend. */
    private com.google.gson.JsonObject stateJson() {
        final com.google.gson.JsonObject root = new com.google.gson.JsonObject();
        root.addProperty("world", world.getName());

        final com.google.gson.JsonArray vs = new com.google.gson.JsonArray();
        for (int vid = 0; vid < towns.size(); vid++) {
            final Town t = towns.get(vid);
            final com.google.gson.JsonObject o = new com.google.gson.JsonObject();
            o.addProperty("vid", vid);
            o.addProperty("name", t.name);
            o.addProperty("cx", t.cx);
            o.addProperty("cz", t.cz);
            o.addProperty("base_y", t.baseY);
            o.addProperty("pool", t.pool);
            o.addProperty("splits", t.splits);
            vs.add(o);
        }
        root.add("villages", vs);

        final com.google.gson.JsonArray cs = new com.google.gson.JsonArray();
        for (final Colono c : colonos) {
            final com.google.gson.JsonObject o = new com.google.gson.JsonObject();
            o.addProperty("name", c.name);
            o.addProperty("vid", c.vid);
            o.addProperty("prof_key", c.profKey);
            o.addProperty("surname", c.surname);
            o.addProperty("gender", c.gender);
            o.addProperty("x", c.x);
            o.addProperty("y", c.y);
            o.addProperty("z", c.z);
            o.addProperty("born_millis", c.bornMillis);
            o.addProperty("initial_age", c.initialAge);
            o.addProperty("death_age", c.deathAge);
            o.addProperty("parent", c.parent);
            o.addProperty("spouse", c.spouse);
            o.addProperty("origin", c.origin);
            o.addProperty("retired", c.retired);
            o.addProperty("floors", c.floors);
            o.addProperty("half_x", c.halfX);
            o.addProperty("half_z", c.halfZ);
            o.addProperty("pal", c.pal);
            o.addProperty("dims_known", c.dimsKnown);
            o.addProperty("wealth", c.wealth);
            cs.add(o);
        }
        root.add("colonos", cs);

        final com.google.gson.JsonArray bs = new com.google.gson.JsonArray();
        for (final Building b : buildings) {
            final com.google.gson.JsonObject o = new com.google.gson.JsonObject();
            o.addProperty("vid", b.vid);
            o.addProperty("prof_key", b.profKey);
            o.addProperty("cx", b.cx);
            o.addProperty("cz", b.cz);
            o.addProperty("base_y", b.baseY);
            bs.add(o);
        }
        root.add("buildings", bs);

        final com.google.gson.JsonArray cv = new com.google.gson.JsonArray();
        for (final String key : civicBuilt) {
            final int sep = key.indexOf(':');
            if (sep <= 0) {
                continue;
            }
            final com.google.gson.JsonObject o = new com.google.gson.JsonObject();
            try {
                o.addProperty("vid", Integer.parseInt(key.substring(0, sep)));
            } catch (NumberFormatException ex) {
                continue;
            }
            o.addProperty("kind", key.substring(sep + 1));
            cv.add(o);
        }
        root.add("civics", cv);

        final com.google.gson.JsonArray rs = new com.google.gson.JsonArray();
        for (final int[] box : plugin.buildRegistry().all()) {
            final com.google.gson.JsonArray a = new com.google.gson.JsonArray();
            for (final int v : box) {
                a.add(v);
            }
            rs.add(a);
        }
        root.add("regions", rs);
        return root;
    }

    /**
     * Carga el pueblo desde la base de datos al arrancar. Devuelve true si habia algo guardado
     * (entonces los .txt no se leen). Se espera un momento a proposito: el mundo no puede
     * empezar a construirse sin saber que hay ya, y sin esto el plugin plantaria una aldea nueva
     * encima de la que existe.
     */
    private boolean loadFromDb() {
        try {
            final com.google.gson.JsonObject st = gateway.loadVillageState(world.getName())
                    .get(10, java.util.concurrent.TimeUnit.SECONDS);
            if (st == null || !st.has("villages") || st.getAsJsonArray("villages").isEmpty()) {
                return false;
            }
            towns.clear();
            for (final com.google.gson.JsonElement el : st.getAsJsonArray("villages")) {
                final com.google.gson.JsonObject o = el.getAsJsonObject();
                final Town t = new Town(o.get("name").getAsString(), o.get("cx").getAsInt(),
                        o.get("cz").getAsInt(), o.get("base_y").getAsInt());
                t.pool = o.get("pool").getAsDouble();
                t.splits = o.get("splits").getAsInt();
                towns.add(t);
            }
            colonos.clear();
            for (final com.google.gson.JsonElement el : st.getAsJsonArray("colonos")) {
                final com.google.gson.JsonObject o = el.getAsJsonObject();
                final Colono c = new Colono();
                c.name = o.get("name").getAsString();
                c.vid = o.get("vid").getAsInt();
                c.profKey = o.get("prof_key").getAsString();
                c.surname = str(o, "surname");
                c.gender = o.has("gender") && !o.get("gender").isJsonNull()
                        ? o.get("gender").getAsString() : "m";
                c.x = o.get("x").getAsInt();
                c.y = o.get("y").getAsInt();
                c.z = o.get("z").getAsInt();
                c.bornMillis = o.get("born_millis").getAsLong();
                c.initialAge = o.get("initial_age").getAsDouble();
                c.deathAge = o.get("death_age").getAsInt();
                c.parent = nullable(o, "parent");
                c.spouse = nullable(o, "spouse");
                c.origin = str(o, "origin");
                c.retired = o.get("retired").getAsBoolean();
                c.floors = o.get("floors").getAsInt();
                c.halfX = o.get("half_x").getAsInt();
                c.halfZ = o.get("half_z").getAsInt();
                c.pal = o.get("pal").getAsInt();
                c.dimsKnown = o.get("dims_known").getAsBoolean();
                c.wealth = o.get("wealth").getAsDouble();
                colonos.add(c);
            }
            buildings.clear();
            for (final com.google.gson.JsonElement el : st.getAsJsonArray("buildings")) {
                final com.google.gson.JsonObject o = el.getAsJsonObject();
                buildings.add(new Building(o.get("vid").getAsInt(), o.get("prof_key").getAsString(),
                        o.get("cx").getAsInt(), o.get("cz").getAsInt(), o.get("base_y").getAsInt()));
            }
            civicBuilt.clear();
            for (final com.google.gson.JsonElement el : st.getAsJsonArray("civics")) {
                final com.google.gson.JsonObject o = el.getAsJsonObject();
                civicBuilt.add(o.get("vid").getAsInt() + ":" + o.get("kind").getAsString());
            }
            final List<int[]> boxes = new ArrayList<>();
            for (final com.google.gson.JsonElement el : st.getAsJsonArray("regions")) {
                final com.google.gson.JsonArray a = el.getAsJsonArray();
                final int[] box = new int[6];
                for (int i = 0; i < 6 && i < a.size(); i++) {
                    box[i] = a.get(i).getAsInt();
                }
                boxes.add(box);
            }
            plugin.buildRegistry().replaceAll(boxes);
            plugin.getLogger().info("[Aetheria] Pueblo cargado de la BASE DE DATOS: "
                    + towns.size() + " aldeas, " + colonos.size() + " colonos, "
                    + buildings.size() + " edificios.");
            return true;
        } catch (Exception e) {
            plugin.getLogger().warning("[Aetheria] no pude leer el pueblo de la base de datos ("
                    + e.getMessage() + "); tiro de la copia local en disco.");
            return false;
        }
    }

    private static String str(com.google.gson.JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : "";
    }

    private static String nullable(com.google.gson.JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : null;
    }

    /** Deja constancia en la historia del pueblo de un vecino que ya no esta. */
    private void recordGone(Colono c, String cause) {
        final com.google.gson.JsonObject o = new com.google.gson.JsonObject();
        o.addProperty("world", world.getName());
        o.addProperty("name", c.name);
        o.addProperty("vid", c.vid);
        o.addProperty("prof_key", c.profKey);
        o.addProperty("surname", c.surname);
        o.addProperty("gender", c.gender);
        o.addProperty("born_millis", c.bornMillis);
        o.addProperty("age_at_death", c.age(System.currentTimeMillis()));
        o.addProperty("wealth", c.wealth);
        o.addProperty("cause", cause);
        gateway.recordDeath(o);
    }

    private static String profKey(Villager.Profession p) {
        return p.getKey().getKey();
    }

    /** True si ese oficio es el de TABERNERO (se queda en la barra, no pasea). */
    private static boolean isKeeper(String key) {
        return profKey(TAVERN_KEEPER).equals(key);
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
        final boolean hayTaberna = civicBuilt.contains(vid + ":taberna");
        final List<Villager.Profession> cand = new ArrayList<>();
        for (int i = 0; i < PROFS.length; i++) {
            if (counts[i] == min && (hayTaberna || PROFS[i] != TAVERN_KEEPER)) {
                cand.add(PROFS[i]);   // sin taberna no hay tabernero que valga
            }
        }
        if (cand.isEmpty()) {
            return Villager.Profession.FARMER;
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
            sweepBabies();      // y bebes-clon / ninos que crecieron solos (zona del spawn, que no
                                // dispara EntitiesLoadEvent tras arrancar)
            ageAndDeath();      // envejecen; a los 65 se jubilan; de muy mayores mueren (lento)
            matureChildren();   // los ninos que ya han crecido se mudan a su casa
            maybeMarry();       // dos solteros pueden casarse y mudarse a una casa mediana nueva
            repairHouses();     // mantenimiento: seca las casas que se hayan inundado
            spendUpkeep();      // cada vecino gasta lo suyo en vivir (su peculio no solo sube)
            updateBios();       // refresca su ficha (edad/oficio/familia) para que hablen de si

            growTowns();        // cada aldea ahorra: al llenar la hucha, llega/nace un vecino
            final String level = json.has("level") ? json.get("level").getAsString() : "estable";
            worldWork(level);   // los NPC mejoran el mundo (amplian cultivos) con el tiempo
            townLife();         // alcalde de cada aldea + granero donde los oficios producen
            mayorPitch();       // el alcalde invita a los jugadores a colaborar con su aldea
        }));
    }

    /**
     * CRECIMIENTO POR ALDEA. Cada aldea tiene una <b>hucha</b> que llena con el trabajo de sus
     * vecinos y vacia con lo que cuesta mantenerlos. Cuando la hucha cubre el <b>coste del
     * siguiente vecino</b> (que sube con la poblacion), llega uno: nace de una pareja fertil de
     * esa aldea o, si no hay ninguna, se instala un forastero; y la hucha vuelve a empezar.
     *
     * <p>Al reves tambien: si la hucha se queda <b>en numeros rojos</b>, la aldea no da de comer
     * a todos y pierde a un vecino (se marcha, o simplemente desaparece un dia). Es lo que hace
     * que un pueblo que deja de trabajar mengue de verdad.
     */
    private void growTowns() {
        final var rng = ThreadLocalRandom.current();
        for (int vid = 0; vid < towns.size(); vid++) {
            final Town t = towns.get(vid);
            final int n = townPopulation(vid);   // los ninos tambien comen (y cuentan en el HUD)
            t.pool -= LIVING_COST * n;   // lo que cuesta dar de comer y alojar a los que ya estan
            final double need = growthCost(chargedSize(vid));
            if (t.pool >= need * TownMath.NEIGHBOUR_MARGIN) {
                // Se trae un vecino nuevo SOLO si queda reserva despues de pagarlo. Antes bastaba
                // con cubrir el coste justo: la hucha quedaba en CERO y la primera noche (de noche
                // no se produce, pero se sigue comiendo) la metia en numeros rojos, asi que la
                // aldea perdia al vecino que acababa de ganar. Ese era el sube y baja que dejaba
                // un pueblo grande en tres habitantes.
                // Se cobra SOLO si el vecino se ha instalado de verdad. Antes se descontaba
                // antes de intentarlo: si no habia solar libre (terreno, parcela de un jugador,
                // choque con lo ya construido), la aldea pagaba 480 AET y no llegaba nadie. Asi
                // se quedaba clavada en 4-6 habitantes por mucho que produjera.
                if (newNeighbour(vid, rng)) {
                    t.pool -= need;
                    t.hungry = 0;
                } else if (++t.noRoom % 10 == 1) {
                    plugin.getLogger().warning("[Aetheria] " + t.name + " tiene fondos para otro "
                            + "vecino pero NO ENCUENTRA SOLAR libre (reintentando).");
                }
            } else if (t.pool < 0) {
                // HAMBRE: un solo ciclo en rojo no echa a nadie (una noche cualquiera lo provoca).
                // Hace falta una mala racha SOSTENIDA, y al que se va se le liquida lo suyo dejando
                // la hucha a cero, sin el reembolso de antes (que disparaba una recompra inmediata
                // y de ahi el yo-yo de poblacion).
                t.hungry++;
                if (t.hungry >= TownMath.HUNGRY_CYCLES) {
                    t.pool = 0;
                    t.hungry = 0;
                    loseNeighbour(vid, rng);
                }
            } else {
                t.hungry = 0;   // vuelve a haber fondo: se olvida la mala racha
            }
            trySplit(vid, rng);   // si la aldea es ya grande, una pareja parte a fundar otra
        }
        saveTowns();   // la hucha se persiste: el progreso no se pierde al reiniciar
        final int pop = totalPopulation();
        if (pop != lastReportedPop) {   // el backend necesita el numero real para su economia
            lastReportedPop = pop;
            gateway.setPopulation(pop);
        }
    }

    private int lastReportedPop = -1;

    /** Coste de vida por vecino y ciclo (60 s), que sale de la hucha de su aldea. */
    private static final double LIVING_COST = 0.8;

    /** Llega un vecino nuevo a ESA aldea (ya no hay tope por aldea: crece sin limite; cuando se
     *  hace grande, una pareja se escinde y funda otra, ver {@link #trySplit}). Nace de una pareja
     *  fertil de la aldea o, si no hay, se instala un forastero. */
    private boolean newNeighbour(int vid, java.util.Random rng) {
        final int enAldea = countInTown(vid);
        if (enAldea < 2) {
            // Los dos primeros de una aldea son de distinto sexo (para que pueda haber familia).
            final String g = enAldea == 1 ? oppositeOfSole(vid) : randGender(rng);
            return growAdult(vid, colonos.size(), freshName(g, rng), randomSurname(rng), g,
                    20 + rng.nextInt(40), "");
        }
        if (bearChild(vid)) {
            return true;
        }
        final String g = randGender(rng);   // sin pareja fertil en la aldea, llega un forastero
        return growAdult(vid, colonos.size(), freshName(g, rng), randomSurname(rng), g,
                20 + rng.nextInt(40), "");
    }

    /** La aldea no da para tantos: se pierde un vecino (primero los ninos, luego alguien al azar). */
    private void loseNeighbour(int vid, java.util.Random rng) {
        for (int i = children.size() - 1; i >= 0; i--) {
            if (children.get(i).vid == vid) {
                final Child c = children.remove(i);
                if (c.baby != null) {
                    c.baby.remove();
                }
                saveChildren();
                Bukkit.getOnlinePlayers().forEach(p -> p.sendMessage(
                        "§7[Pueblo] La familia de §b" + c.name + " §7se marcha buscando mejor suerte."));
                return;
            }
        }
        final List<Colono> vecinos = new ArrayList<>();
        for (final Colono c : colonos) {
            if (c.vid == vid) {
                vecinos.add(c);
            }
        }
        if (vecinos.size() <= 2) {
            return;   // una aldea nunca se queda por debajo de la pareja fundadora
        }
        final Colono gone = vecinos.get(rng.nextInt(vecinos.size()));
        colonos.remove(gone);
        routines.removeColono(gone.name);
        convo.clearBio(gone.name);
        inherit(gone);   // lo que ahorro se queda en la familia
        final Colono widow = findColono(gone.spouse);
        if (widow != null) {
            widow.spouse = null;
        } else {
            demolish(gone);
        }
        save();
        final String msg = gone.name + " se marcha de " + t(vid) + ": el pueblo no da para todos.";
        gateway.postEvent("emigracion", msg);
        routines.pushGossip(msg);
        Bukkit.getOnlinePlayers().forEach(p -> p.sendMessage("§7[Pueblo] " + msg));
    }

    // --- DONACIONES: el jugador puede acelerar el crecimiento de UNA aldea concreta ---

    /** Lo que aporta el jugador cada vez que dona a una aldea. */
    private static final double DONATION = 25;

    // NOTA: aqui vivia la DONACION AL ALCALDE (agachado + clic derecho sobre el). Se ha quitado:
    // para aportar al pueblo ya esta el ARCA de la plaza (visible, con su ventana de importes) y
    // /donar. Sobre el alcalde, el clic derecho no hace nada especial: es un vecino mas con el que
    // se habla. Y el gesto de agacharse queda libre para COMERCIAR con los vecinos (NpcTradeModule).


    /** El ALCALDE se acerca de vez en cuando a un jugador cercano y le explica que puede
     *  colaborar con el crecimiento de su aldea. Con cooldown, y solo si hay alguien cerca. */
    private void mayorPitch() {
        final long now = System.currentTimeMillis();
        if (now - lastPitch < 240_000L) {
            return;   // como mucho, una invitacion cada 4 minutos
        }
        for (final var entry : alcaldes.entrySet()) {
            final int vid = entry.getKey();
            if (vid >= towns.size()) {
                continue;
            }
            final org.bukkit.entity.Mob alc = routines.entityOf(entry.getValue());
            if (alc == null || alc.isDead()) {
                continue;
            }
            for (final Player p : world.getPlayers()) {
                if (p.getLocation().distanceSquared(alc.getLocation()) > 400) {
                    continue;   // a 20 bloques
                }
                lastPitch = now;
                alc.getPathfinder().moveTo(p.getLocation(), 1.0);   // se acerca a saludar
                final int falta = (int) Math.max(0,
                        growthCost(chargedSize(vid)) - towns.get(vid).pool);
                p.sendMessage("§6[" + entry.getValue() + "] §f" + p.getName() + ", soy el alcalde de "
                        + towns.get(vid).name + ". Nos faltan §e" + falta + " AET§f para que se "
                        + "instale otro vecino.");
                p.sendMessage("§7Si quieres echar una mano, usa el §fARCA de la plaza§7 (o /donar). "
                        + "Lo que adelantes vuelve al granero del pueblo.");
                return;
            }
        }
    }

    private long lastPitch;

    // --- API para el modulo de DONACIONES (arca de la plaza y /donar) ---

    /** La aldea en cuyo radio esta el jugador, o -1 si esta a campo abierto. */
    public int townAt(Player p) {
        if (!p.getWorld().equals(world)) {
            return -1;
        }
        int near = -1;
        double best = TOWN_RADIUS;
        for (int i = 0; i < towns.size(); i++) {
            final Town t = towns.get(i);
            final double d = Math.hypot(p.getX() - (t.cx + 0.5), p.getZ() - (t.cz + 0.5));
            if (d <= TOWN_RADIUS && d < best) {
                best = d;
                near = i;
            }
        }
        return near;
    }

    public String townName(int vid) {
        return vid >= 0 && vid < towns.size() ? towns.get(vid).name : "";
    }

    /** Si el bloque esta dentro de una BOTICA ya construida, devuelve el nombre de su aldea (para
     *  que el caldero/alambique de la botica abra la cura); si no, null. La botica se levanta en
     *  (t.cx+3, t.cz-13) con medio lado 4 (ver ensureCivics). */
    public String boticaTownAt(Block b) {
        for (int vid = 0; vid < towns.size(); vid++) {
            if (!civicBuilt.contains(vid + ":botica")) {
                continue;
            }
            final Town t = towns.get(vid);
            if (Math.abs(b.getX() - (t.cx + 3)) <= 5 && Math.abs(b.getZ() - (t.cz + boticaDz(vid))) <= 5
                    && Math.abs(b.getY() - t.baseY) <= 8) {
                return t.name;
            }
        }
        return null;
    }

    /** Lo que la aldea lleva ahorrado para su proximo vecino. */
    public double townPool(int vid) {
        return vid >= 0 && vid < towns.size() ? Math.max(0, towns.get(vid).pool) : 0;
    }

    /** Lo que cuesta el proximo vecino de esa aldea. */
    public double townNeed(int vid) {
        return vid >= 0 && vid < towns.size() ? growthCost(chargedSize(vid)) : 0;
    }

    /** El alcalde en ejercicio de esa aldea (cadena vacia si no hay). */
    public String mayorOf(int vid) {
        return alcaldes.getOrDefault(vid, "");
    }

    /** DONACION del jugador: entra directa en la hucha de la aldea y se persiste. */
    public void donate(int vid, double amount) {
        if (vid >= 0 && vid < towns.size() && amount > 0) {
            towns.get(vid).pool += amount;
            saveTowns();
        }
    }

    /** #11 - Suma a la hucha de una aldea lo que sus vecinos han producido con su trabajo. */
    public void addTownPool(int vid, double amount) {
        if (vid >= 0 && vid < towns.size() && amount > 0) {
            towns.get(vid).pool += amount;
        }
    }

    /** Nace un nino de una PAREJA fertil (un hombre y una mujer, casados) DE ESA ALDEA. Dos
     *  personas del mismo sexo no tienen hijos biologicos. Devuelve true si hubo nacimiento. */
    /** Cuantos hijos tiene ya esa madre (bebes + hijos ya adultos), por su nombre. */
    private int childCount(String motherName) {
        int n = 0;
        for (final Child c : children) {
            if (motherName.equals(c.parent)) {
                n++;
            }
        }
        for (final Colono c : colonos) {
            if (motherName.equals(c.parent)) {
                n++;
            }
        }
        return n;
    }

    private boolean bearChild(int vid) {
        final var rng = ThreadLocalRandom.current();
        // Madres posibles: mujer no jubilada, casada con un hombre no jubilado.
        final List<Colono> mothers = new ArrayList<>();
        for (final Colono c : colonos) {
            if (c.vid != vid || c.retired || !"f".equals(c.gender) || c.spouse == null) {
                continue;
            }
            final Colono sp = findColono(c.spouse);
            if (sp != null && "m".equals(sp.gender) && !sp.retired) {
                mothers.add(c);
            }
        }
        mothers.removeIf(m -> childCount(m.name) >= 2);   // cada pareja: MAXIMO 2 hijos
        if (mothers.isEmpty()) {
            return false;   // sin pareja fertil (o ya con 2 hijos): que venga un inmigrante
        }
        final Colono mother = mothers.get(rng.nextInt(mothers.size()));
        final Colono father = findColono(mother.spouse);
        final String gender = randGender(rng);
        final String name = freshName(gender, rng);
        // Aparece junto a la casa de su familia.
        final Location base = new Location(world, mother.x + 0.5, mother.y, mother.z + 2.5);
        final Location at = base.clone().add(rng.nextInt(3) - 1, 0, rng.nextInt(3) - 1);
        final Villager baby = spawnBaby(name, at);
        final String hijo = "f".equals(gender) ? "hija" : "hijo";
        final String of = ", " + hijo + " de " + father.name + " y " + mother.name;
        convo.setBio(name, "Eres " + name + ", un nino pequeno del pueblo de Aetheria" + of
                + ". Todavia no trabajas; hablas con la inocencia de un nino.");
        final String famSurname = (father != null && !father.surname.isEmpty())
                ? father.surname : mother.surname;   // hereda el apellido de la familia
        children.add(new Child(baby, name, mother.name, gender, mother.vid, famSurname,
                System.currentTimeMillis() + GROW_MS));
        saveChildren();
        Bukkit.getOnlinePlayers().forEach(p -> p.sendMessage(
                "§d[Pueblo] §fHa nacido §b" + name + "§f" + of + "."));
        gateway.postEvent("nacimiento", "Nace " + name + of + ".");
        routines.pushGossip("ha nacido " + name + of + ".");
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
            saveChildren();
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
        final int fx = village.spawnX() + 14;
        final int fz = village.spawnZ() + 22;
        if (!world.isChunkLoaded(fx >> 4, fz >> 4)) {
            return;   // zona de cultivos descargada: no forzar la carga del chunk
        }
        farmRadius++;
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

    private boolean growAdult(int vid, int index, String given, String surname, String gender,
            double initialAge, String parent) {
        final Location center = townCenter(vid);
        final String name = surname.isEmpty() ? given : given + " " + surname;   // "Nombre Apellido"
        final var rng = ThreadLocalRandom.current();
        final Villager.Profession prof = neededProfession(vid, rng);   // el oficio que falta en la aldea
        final int cx;
        final int cz;
        final int fy;
        int hx = 2;        // huella y paleta de su casa: se guardan para poder RECONSTRUIRLA igual
        int hz = 2;
        int palIdx = 0;
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
                return false;   // sin sitio libre ni casa en venta: NO se cobra (se reintenta luego)
            }
            cx = spot[0];
            cz = spot[1];
            fy = spot[2];
            palIdx = rng.nextInt(COMBOS.length);
            final Material[] pal = COMBOS[palIdx];
            // Un aldeano SOLTERO vive en una casa MUY PEQUENA (una sola cama). Al casarse se le
            // construye una mediana (ver maybeMarry).
            final int halfX = 2;
            final int halfZ = rng.nextInt(100) < 35 ? 3 : 2;   // 5x5 o 5x7, modesta
            hx = halfX;
            hz = halfZ;
            final BlockFace door = towardPlaza(center, cx, cz); // la puerta mira a la plaza
            prepareTerrain(cx, cz, fy);                        // tala arboles + nivela al suelo real
            Blueprint.buildHouse(world, cx, cz, fy, door, halfX, halfZ, 1, false,
                    pal[0], pal[1], pal[2], pal[3], true, 1, name);   // 1 cama (soltero)
            deflood(cx, fy, cz, 1);                            // por si algo de agua se colo
            pathTo(cx, cz, fy, Math.max(halfX, halfZ), center);   // sendero de la puerta a la plaza
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
        c.halfX = hx;
        c.halfZ = hz;
        c.pal = palIdx;
        c.dimsKnown = vac == null;   // si se mudo a una casa en venta, no sabemos como es por dentro
        colonos.add(c);
        save();

        final Location home = new Location(world, cx + 0.5, fy + 1, cz + 0.5);
        routines.addColono("colono", name, home, workspot, prof, center, gender);
        routines.setStayAtWork(name, prof == TAVERN_KEEPER);
        final String pueblo = towns.get(Math.max(0, Math.min(vid, towns.size() - 1))).name;
        Bukkit.getOnlinePlayers().forEach(p -> p.sendMessage(
                "§a[Pueblo] §f" + name + " §7(" + oficio(prof) + ") se ha instalado en §f" + pueblo + "§7."));
        plugin.getLogger().info("[Aetheria] Pueblo vivo: +1 colono (" + name + ", " + prof
                + ") en aldea " + vid + ".");
        return true;   // vecino instalado de verdad
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
        final long now = System.currentTimeMillis();
        final List<Colono> singles = new ArrayList<>();
        for (final Colono c : colonos) {
            // Solteros que YA se han "conocido" un rato (cortejo): no se casan recien llegados.
            if (c.spouse == null && !c.retired && now - c.bornMillis >= COURTSHIP_MS) {
                singles.add(c);
            }
        }
        if (singles.size() < 2 || rng.nextInt(100) >= 40) {
            return;   // no siempre hay solteros elegibles, ni siempre se casan (amor a fuego lento)
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
        // La MEDIANA se construye SOBRE el solar de A (se demuele su casita y se levanta la nueva
        // encima); la casita de B queda EN VENTA. Asi la pareja pasa de dos casitas a UNA mediana +
        // una en venta (2 casas para 2 personas), sin dejar dos casas vacias por magia.
        final int cx = a.x;
        final int cz = a.z;
        final int fy = a.y - 1;
        final int palIdx = rng.nextInt(COMBOS.length);
        final Material[] pal = COMBOS[palIdx];
        final int halfX = 3;
        final int halfZ = rng.nextInt(100) < 40 ? 4 : 3;   // MEDIANA (algo mayor que la de soltero)
        final BlockFace door = towardPlaza(center, cx, cz);
        demolish(a);                        // libera y limpia el solar de A para la casa mayor
        prepareTerrain(cx, cz, fy);
        Blueprint.buildHouse(world, cx, cz, fy, door, halfX, halfZ, 1, false,
                pal[0], pal[1], pal[2], pal[3], true, 4, a.name + " y " + b.name);   // 2 hab x 2 camas
        deflood(cx, fy, cz, 1);                                          // por si se colo agua
        pathTo(cx, cz, fy, Math.max(halfX, halfZ), center);
        final Location workA = ensureBuilding(a.vid, profFromKey(a.profKey));
        final Location workB = ensureBuilding(b.vid, profFromKey(b.profKey));

        vacate(b);   // la casita de B queda EN VENTA para el proximo colono

        a.x = cx;  a.y = fy + 1;  a.z = cz;  a.floors = 1;  a.spouse = b.name;
        b.x = cx;  b.y = fy + 1;  b.z = cz;  b.floors = 1;  b.spouse = a.name;
        a.halfX = halfX;  a.halfZ = halfZ;  a.pal = palIdx;   // la casa comun, para reconstruirla
        b.halfX = halfX;  b.halfZ = halfZ;  b.pal = palIdx;
        a.dimsKnown = true;  b.dimsKnown = true;
        placed.add(new int[] {cx, cz});
        plugin.buildRegistry().add(new int[] {cx - halfX - 1, fy - 2, cz - halfZ - 1,
                cx + halfX + 1, fy + 14, cz + halfZ + 1});
        save();

        // Casa compartida (con destinos ligeramente distintos para no apilarse), pero cada uno
        // trabaja en el edificio de SU oficio.
        routines.setHomeWork(a.name, new Location(world, cx + 1.0, fy + 1, cz + 0.5), workA);
        routines.setHomeWork(b.name, new Location(world, cx, fy + 1, cz + 1.5), workB);

        final String msg = a.name + " y " + b.name
                + " se han casado y se han mudado juntos a una casa nueva.";
        gateway.postEvent("boda", msg);
        routines.pushGossip(a.name + " y " + b.name + " se han casado.");
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
                    routines.setStayAtWork(heir.name, isKeeper(c.profKey));
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
                recordGone(c, "vejez");   // el pueblo guarda memoria de los suyos (0010)
                routines.pushGossip("ha muerto " + c.name + ", el " + oficioDelDifunto + ".");
                Bukkit.getOnlinePlayers().forEach(p -> p.sendMessage("§8[Pueblo] §7" + msg));
                inherit(c);   // su peculio pasa a la viuda/viudo y a sus hijos
                if (relevo != null) {
                    gateway.postEvent("relevo", relevo);   // cambio de oficio para cubrir la baja
                    routines.pushGossip(relevo);
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
                routines.pushGossip(msg);
                Bukkit.getOnlinePlayers().forEach(p -> p.sendMessage("§7[Pueblo] " + msg));
            }
        }
        if (changed) {
            save();
        }
    }

    /**
     * #11 - HERENCIA. Lo que un colono ahorro trabajando no se evapora al morir: la mitad va a
     * su viuda/viudo y el resto se reparte entre sus hijos vivos. Si no deja a nadie, su
     * patrimonio pasa al comun del pueblo (el evento "obituario" ya lo abona en la tesoreria).
     * Queda escrito en la cronica, que para eso es una cronica.
     */
    private void inherit(Colono dead) {
        if (dead.wealth < 0.01) {
            return;
        }
        final double total = dead.wealth;
        dead.wealth = 0;
        final Colono widow = findColono(dead.spouse);
        final List<Colono> kids = new ArrayList<>();
        for (final Colono c : colonos) {
            if (dead.name.equals(c.parent)) {
                kids.add(c);
            }
        }
        if (widow == null && kids.isEmpty()) {
            gateway.postEvent("herencia", String.format(
                    "%s muere sin herederos: sus %.0f AET pasan al comun del pueblo.",
                    dead.name, total));
            return;
        }
        final double forWidow = widow != null ? (kids.isEmpty() ? total : total / 2) : 0;
        final double forKids = total - forWidow;
        if (widow != null) {
            widow.wealth += forWidow;
        }
        for (final Colono k : kids) {
            k.wealth += forKids / kids.size();
        }
        final StringBuilder quien = new StringBuilder();
        if (widow != null) {
            quien.append(widow.name);
        }
        for (final Colono k : kids) {
            quien.append(quien.length() > 0 ? ", " : "").append(k.name);
        }
        final String msg = String.format("La herencia de %s (%.0f AET) pasa a %s.",
                dead.name, total, quien);
        gateway.postEvent("herencia", msg);
        routines.pushGossip(msg);
        Bukkit.getOnlinePlayers().forEach(p -> p.sendMessage("§7[Pueblo] " + msg));
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
        if (prof == TAVERN_KEEPER) {
            return tavernBar(vid);   // su puesto es la taberna: detras de la barra, sirviendo
        }
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
        pathTo(cx, cz, fy, 3, townCenter(vid));   // el puesto de oficio ocupa ~3
        placed.add(new int[] {cx, cz});
        plugin.buildRegistry().add(new int[] {cx - 5, fy - 2, cz - 5, cx + 5, fy + 14, cz + 5});
        buildings.add(new Building(vid, key, cx, cz, fy));
        saveBuildings();
        gateway.postEvent("edificio", "El pueblo levanta " + buildingName(prof) + " en "
                + towns.get(Math.max(0, Math.min(vid, towns.size() - 1))).name + ".");
        routines.pushGossip("el pueblo ha levantado " + buildingName(prof) + ".");
        return new Location(world, cx + 0.5, fy + 1, cz + 0.5);
    }

    /** Vuelve a poner el cartel de cada edificio de oficio ya existente (arregla orientacion/texto
     *  sin tener que reconstruir ni reiniciar el mundo). */
    private void refreshTradeSigns() {
        for (final Building b : buildings) {
            final BlockFace door = towardPlaza(townCenter(b.vid), b.cx, b.cz);
            tradeSign(b.cx, b.baseY, b.cz, profFromKey(b.profKey), door);
        }
    }

    /** Cartel del oficio delante del puesto de trabajo (se pone una sola vez, no trepa). */
    private void tradeSign(int cx, int fy, int cz, Villager.Profession prof, BlockFace door) {
        final int sx = cx + door.getModX() * 3;
        final int sz = cz + door.getModZ() * 3;
        // Cota FIJA del edificio (no groundY: si no, la valla del cartel se ve como "suelo" y al
        // refrescar se pone OTRO cartel encima -> carteles superpuestos). Limpia antes la columna.
        for (int y = fy + 1; y <= fy + 6; y++) {
            final Material m = world.getBlockAt(sx, y, sz).getType();
            if (m == Material.OAK_SIGN || m == Material.OAK_WALL_SIGN || m == Material.OAK_FENCE) {
                world.getBlockAt(sx, y, sz).setType(Material.AIR, false);
            }
        }
        world.getBlockAt(sx, fy + 1, sz).setType(Material.OAK_FENCE, false);
        final Block b = world.getBlockAt(sx, fy + 2, sz);
        b.setType(Material.OAK_SIGN, false);
        if (b.getBlockData() instanceof org.bukkit.block.data.Rotatable rot) {
            rot.setRotation(door);   // mira HACIA LA PLAZA (de donde viene la gente), no al edificio
            b.setBlockData(rot, false);
        }
        if (b.getState() instanceof org.bukkit.block.Sign s) {
            // Reparte el rotulo en dos lineas si es largo ("Taller de arquero" no cabe en una y
            // salia recortado a "Taller de"). Un solo termino va centrado en la linea 2.
            final String label = tradeLabel(prof);
            final int sp = label.length() > 12 ? label.lastIndexOf(' ') : -1;
            final var front = s.getSide(org.bukkit.block.sign.Side.FRONT);
            if (sp > 0) {
                front.line(1, Component.text("§6" + label.substring(0, sp)));
                front.line(2, Component.text("§6" + label.substring(sp + 1)));
            } else {
                front.line(1, Component.text("§6" + label));
            }
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
        if (p == TAVERN_KEEPER) return "una Taberna";
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
     * Vida civica de cada aldea: el ALCALDE (ya no el mas veterano, sino <b>el primero del
     * ranking de prestigio</b>, sea vecino o jugador) con su panel en la plaza, el tablon de
     * prestigio, y un GRANERO donde cada oficio deposita algo de su produccion (la economia se
     * vuelve tangible: al abrir el barril ves trigo, lana, hierro... segun quien trabaje aqui).
     */
    private void townLife() {
        for (int vid = 0; vid < towns.size(); vid++) {
            final int v = vid;   // final para la lambda del refresco del ranking
            final Town t = towns.get(vid);
            if (!plazaLoaded(t)) {
                // Aldea DESCARGADA (nadie cerca): NO se toca. Antes se refrescaban cada 60 s los
                // civicos, el granero, el arca, el ranking y hasta se pedia el prestigio al backend,
                // forzando la carga de su chunk una y otra vez: era la causa principal del lag. Su
                // vida civica se repinta sola al entrar un jugador (refreshTownVisuals).
                continue;
            }
            ensureCivics(vid, t);   // granero (siempre), taberna (>=4 hab), mercado (>=6 hab)
            ensureDonationChest(vid, t);   // arca del pueblo: donde el jugador puede aportar
            applyRanking(vid);             // ranking, panel, tablon y alcalde (con lo ya sabido)
            // Trae el prestigio de los jugadores y REPINTA el ranking en cuanto llega (antes se
            // guardaba "para el proximo ciclo", asi que un nombre tardaba hasta 2 ciclos en salir).
            refreshPlayerRep(v, () -> applyRanking(v));
            // El granero ya NO se llena solo: lo llena el TRABAJO FISICO de los aldeanos
            // (LaborModule deposita cada cosecha, tala, lingote... segun se producen).
        }
    }

    /** Recalcula el ranking de una aldea y repinta lo que depende de el (panel, tablon, alcalde). */
    private void applyRanking(int vid) {
        if (vid < 0 || vid >= towns.size()) {
            return;
        }
        final Town t = towns.get(vid);
        // UN SOLO RANKING: los vecinos por su peculio, los jugadores por su prestigio.
        final List<Rank> rk = computeRanking(vid);
        ranking.put(vid, rk);
        final String alcalde = rk.isEmpty() ? "" : rk.get(0).name();
        // El cartel de info (nombre/alcalde/habitantes) se quito: ya sale en el marcador lateral.
        // Se limpia el que hubiera quedado flotando de antes.
        removeInfoPanel(t);
        prestigeBoard(vid, t, rk);
        final String prev = alcaldes.get(vid);
        if (!alcalde.isEmpty() && !alcalde.equals(prev)) {
            if (prev != null) {
                // Mismo camino de siempre: cambiar de alcalde queda en la cronica, tanto si el
                // relevo lo gana un vecino como si lo gana un jugador con prestigio.
                final boolean isPlayer = rk.get(0).player();
                gateway.postEvent("gobierno", alcalde + (isPlayer ? " (forastero)" : "")
                        + " toma el cargo de alcalde de " + t.name + ".");
            }
            alcaldes.put(vid, alcalde);
        }
    }

    /**
     * Repinta el tablon de esa aldea AHORA con el prestigio recien ganado, sin esperar al ciclo
     * de 60 s. Lo llaman el alguacil (al cobrar un encargo) y el arca (al aportar): si no, el
     * jugador cumplia una mision y no se veia reflejado hasta un par de minutos despues.
     */
    public void refreshRankingNow(int vid) {
        refreshPlayerRep(vid, () -> applyRanking(vid));
    }

    /**
     * El ranking de una aldea, de mas a menos prestigio. Mezcla en la MISMA tabla:
     * <ul>
     *   <li><b>vecinos</b>: su peculio (lo que han ahorrado trabajando) + veterania acotada;</li>
     *   <li><b>jugadores</b>: su prestigio en esa aldea, que ya calcula el backend
     *       ({@code misiones + raiz(donado)}), asi que la formula vive en un solo sitio.</li>
     * </ul>
     * Un aldeano rico y veterano puede gobernar por delante de cualquier jugador; y un jugador
     * que se vuelca con el pueblo puede desbancarlo.
     */
    private List<Rank> computeRanking(int vid) {
        final long now = System.currentTimeMillis();
        final List<Rank> out = new ArrayList<>();
        for (final Colono c : colonos) {
            if (c.vid != vid || c.retired) {
                continue;
            }
            final double dias = Math.max(0, (now - c.bornMillis) / (double) DAY_MS);
            out.add(new Rank(c.name, TownMath.villagerScore(c.wealth, dias), false, null));
        }
        out.addAll(playerRep.getOrDefault(vid, List.of()));
        out.sort((a, b) -> Double.compare(b.score(), a.score()));
        return out;
    }

    /** Trae del backend el prestigio de los jugadores de esa aldea; {@code then} corre despues. */
    private void refreshPlayerRep(int vid, Runnable then) {
        final String town = townName(vid);
        gateway.getVillageReputation(town).whenComplete((arr, err) -> {
            if (err != null || arr == null) {
                return;   // se conserva el ultimo conocido: un fallo de red no destituye a nadie
            }
            final List<Rank> list = new ArrayList<>();
            for (final com.google.gson.JsonElement el : arr) {
                final com.google.gson.JsonObject o = el.getAsJsonObject();
                java.util.UUID id = null;
                try {
                    id = java.util.UUID.fromString(o.get("player_uuid").getAsString());
                } catch (Exception ignored) {
                    continue;
                }
                list.add(new Rank(o.get("username").getAsString(), o.get("score").getAsDouble(),
                        true, id));
            }
            Bukkit.getScheduler().runTask(plugin, () -> {
                playerRep.put(vid, list);
                if (then != null) {
                    then.run();
                }
            });
        });
    }

    /** Repinta YA lo visual de una aldea (arca, panel, tablon, ranking) y pide el prestigio de sus
     *  jugadores para que el nombre aparezca sin esperar al ciclo de 60 s. Se llama al entrar en la
     *  aldea y al conectar. */
    private void refreshTownVisuals(int vid) {
        if (vid < 0 || vid >= towns.size()) {
            return;
        }
        ensureDonationChest(vid, towns.get(vid));
        applyRanking(vid);                                // inmediato, con lo ya sabido
        refreshPlayerRep(vid, () -> applyRanking(vid));   // y re-pinta al llegar el prestigio real
    }

    /** Al conectar dentro de una aldea, repinta su cartel/arca/ranking en un par de segundos (a que
     *  el mundo este cargado), para no esperar al ciclo de 60 s. */
    @EventHandler
    public void onJoin(org.bukkit.event.player.PlayerJoinEvent e) {
        final Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (p.isOnline() && p.getWorld().equals(world)) {
                final int vid = townAt(p);
                if (vid >= 0) {
                    refreshTownVisuals(vid);
                }
            }
        }, 40L);
    }

    /** Al CARGAR las entidades de un trozo de mundo, reconcilia los BEBES: quita los huerfanos
     *  (clones o ninos que crecieron y ya no estan en la lista), deja UNO por nombre, y a los que
     *  siguen siendo ninos les vuelve a fijar el aspecto de bebe (por si un baby vanilla habia
     *  crecido solo). Asi no quedan "ninos sueltos" ni con aspecto adulto al acercarse a la aldea. */
    @EventHandler
    public void onBabiesLoad(org.bukkit.event.world.EntitiesLoadEvent e) {
        final java.util.Set<String> seen = new java.util.HashSet<>();
        for (final org.bukkit.entity.Entity ent : e.getEntities()) {
            if (!(ent instanceof Villager v) || !v.getScoreboardTags().contains(BABY_TAG)
                    || v.customName() == null) {
                continue;
            }
            final String pn = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
                    .plainText().serialize(v.customName());
            final String childName = pn.endsWith(" (nino)") ? pn.substring(0, pn.length() - 7) : pn;
            boolean isChild = false;
            for (final Child c : children) {
                if (c.name.equals(childName)) {
                    isChild = true;
                    break;
                }
            }
            if (!isChild || !seen.add(childName)) {
                v.remove();   // no es un nino actual (clon/huerfano que crecio) o es un duplicado
                continue;
            }
            v.setBaby();          // por si el baby vanilla habia crecido solo (aspecto adulto)
            v.setAgeLock(true);   // y que no vuelva a crecer
        }
    }

    /** Barre TODOS los bebes cargados (no solo los recien cargados): misma limpieza que
     *  {@link #onBabiesLoad}. Se llama cada ciclo para cubrir la zona del spawn, que no dispara
     *  EntitiesLoadEvent despues de arrancar. */
    private void sweepBabies() {
        final java.util.Set<String> seen = new java.util.HashSet<>();
        for (final Villager v : world.getEntitiesByClass(Villager.class)) {
            if (!v.getScoreboardTags().contains(BABY_TAG) || v.customName() == null) {
                continue;
            }
            final String pn = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
                    .plainText().serialize(v.customName());
            final String childName = pn.endsWith(" (nino)") ? pn.substring(0, pn.length() - 7) : pn;
            boolean isChild = false;
            for (final Child c : children) {
                if (c.name.equals(childName)) {
                    isChild = true;
                    break;
                }
            }
            if (!isChild || !seen.add(childName)) {
                v.remove();
                continue;
            }
            v.setBaby();
            v.setAgeLock(true);
        }
    }

    /** La aldea que se llama asi, o -1. */
    public int townIdByName(String name) {
        for (int i = 0; i < towns.size(); i++) {
            if (towns.get(i).name.equals(name)) {
                return i;
            }
        }
        return -1;
    }

    /** El ranking vigente de una aldea (vacio si aun no se ha calculado). */
    public List<Rank> ranking(int vid) {
        return ranking.getOrDefault(vid, List.of());
    }

    /**
     * ¿Tiene este jugador llave del granero? Solo los TRES PRIMEROS del ranking de la aldea, y
     * aun asi solo para el excedente (ver {@link #onGranaryBarrel}).
     */
    public boolean granaryAccess(int vid, java.util.UUID player) {
        final List<Rank> rk = ranking(vid);
        for (int i = 0; i < Math.min(3, rk.size()); i++) {
            if (rk.get(i).player() && player.equals(rk.get(i).uuid())) {
                return true;
            }
        }
        return false;
    }

    private static final String RANK_TAG = "aetheria_rank";

    /**
     * TABLON DE PRESTIGIO de la plaza: un panel grande, encima del de informacion, con los ocho
     * primeros del ranking. Es la forma de que el jugador vea de un vistazo por donde va la
     * carrera por la alcaldia sin escribir ningun comando.
     */
    /** ¿Esta cargado el trozo de mundo de la plaza? Si NO, no se tocan sus paneles flotantes:
     *  buscar entidades daria vacio y se spawnearia un duplicado cada ciclo (era lo que llenaba la
     *  plaza de texto superpuesto y ralentizaba la aldea). */
    private boolean plazaLoaded(Town t) {
        return world.isChunkLoaded(t.cx >> 4, t.cz >> 4);
    }

    /** El TextDisplay con ese tag cerca de {@code loc}, quedandose con UNO y ELIMINANDO los
     *  duplicados apilados. null si no hay ninguno. */
    private TextDisplay singlePanel(Location loc, String tag, double r) {
        TextDisplay keep = null;
        for (final org.bukkit.entity.Entity e : world.getNearbyEntities(loc, r, r, r)) {
            if (e instanceof TextDisplay td && e.getScoreboardTags().contains(tag)) {
                if (keep == null) {
                    keep = td;
                } else {
                    td.remove();   // duplicado apilado: fuera
                }
            }
        }
        return keep;
    }

    private void prestigeBoard(int vid, Town t, List<Rank> rk) {
        if (!plazaLoaded(t)) {
            return;
        }
        // TITULO en un display APARTE (un TextDisplay no permite tamanos por linea): el nombre de la
        // aldea, MAS GRANDE y CENTRADO, encima de la lista.
        final Location titleLoc = new Location(world, t.cx + 0.5, t.baseY + 8.6, t.cz + 0.5);
        final String titleTag = RANK_TAG + "_title_" + vid;
        TextDisplay title = singlePanel(titleLoc, titleTag, 10);
        if (title == null) {
            title = (TextDisplay) world.spawnEntity(titleLoc, EntityType.TEXT_DISPLAY);
            title.addScoreboardTag(PANEL_TAG);
            title.addScoreboardTag(titleTag);
            title.setBillboard(Display.Billboard.CENTER);
            title.setSeeThrough(false);
            title.setPersistent(true);
            title.setAlignment(TextDisplay.TextAlignment.CENTER);
            title.setViewRange(1.4f);
            title.setTransformation(new org.bukkit.util.Transformation(
                    new org.joml.Vector3f(), new org.joml.Quaternionf(),
                    new org.joml.Vector3f(2.6f, 2.6f, 2.6f), new org.joml.Quaternionf()));
        }
        title.text(Component.text("§6§l" + t.name));
        title.teleport(titleLoc);

        final Location loc = new Location(world, t.cx + 0.5, t.baseY + 6.0, t.cz + 0.5);
        final String tag = RANK_TAG + "_" + vid;
        TextDisplay board = singlePanel(loc, tag, 10);
        if (board == null) {
            board = (TextDisplay) world.spawnEntity(loc, EntityType.TEXT_DISPLAY);
            board.addScoreboardTag(PANEL_TAG);
            board.addScoreboardTag(tag);
            board.setBillboard(Display.Billboard.CENTER);
            board.setSeeThrough(false);
            board.setPersistent(true);
            board.setBackgroundColor(Color.fromARGB(200, 25, 18, 8));
            board.setAlignment(TextDisplay.TextAlignment.LEFT);
            board.setViewRange(1.4f);   // se lee desde lejos: es el cartel gordo de la plaza
            // Mas GRANDE que el panel de informacion (es el tablon principal de la plaza).
            board.setTransformation(new org.bukkit.util.Transformation(
                    new org.joml.Vector3f(), new org.joml.Quaternionf(),
                    new org.joml.Vector3f(1.5f, 1.5f, 1.5f), new org.joml.Quaternionf()));
        }
        final StringBuilder sb = new StringBuilder();   // el titulo va en su propio display, encima
        if (rk.isEmpty()) {
            sb.append("§7(aun no hay nadie en el tablon)");
        }
        final int top = Math.min(8, rk.size());
        for (int i = 0; i < top; i++) {
            final Rank r = rk.get(i);
            sb.append(i == 0 ? "§6" : "§7").append(i + 1).append(". ")
              .append(r.player() ? "§b" : "§f").append(r.name())
              .append(" §8").append((int) Math.round(r.score()))
              .append(i == 0 ? " §6(alcalde)" : "")
              .append('\n');
        }
        // Los JUGADORES salen siempre, aunque aun no entren en los ocho primeros: es su carrera
        // la que cuenta el tablon, y quedarse fuera del corte sin ver tu puesto desanima.
        final StringBuilder rezagados = new StringBuilder();
        for (int i = top; i < rk.size(); i++) {
            final Rank r = rk.get(i);
            if (r.player()) {
                rezagados.append("§7").append(i + 1).append(". §b").append(r.name())
                         .append(" §8").append((int) Math.round(r.score())).append('\n');
            }
        }
        if (rezagados.length() > 0) {
            sb.append(" \n§8· viajeros mas abajo ·\n").append(rezagados);
        }
        sb.append(" \n§8/prestigio para ver tu puesto");
        board.text(Component.text(sb.toString()));
        board.teleport(loc);
    }

    /** Posicion del ARCA del pueblo (fondo comun) en la plaza de una aldea. */
    public int[] donationChest(int vid) {
        if (vid < 0 || vid >= towns.size()) {
            return null;
        }
        final Town t = towns.get(vid);
        // EN LA PLAZA, sobre su eje central: el centro exacto lo ocupa el pozo y el borde norte
        // la campana, asi que el arca va dos bloques al sur del pozo, de cara a quien entra.
        return new int[] {t.cx, t.baseY + 1, t.cz + 2};
    }

    /**
     * ARCA DEL PUEBLO: un cofre en la plaza con su rotulo flotante. Haciendole clic el jugador
     * puede APORTAR dinero a la hucha de esa aldea (sin comandos ni gestos raros). Es la via
     * visible; tambien se puede donar al alcalde o con /donar.
     */
    private void ensureDonationChest(int vid, Town t) {
        if (!plazaLoaded(t)) {
            return;   // aldea descargada: no se toca (evita duplicar el arca y su cartel)
        }
        final int[] c = donationChest(vid);
        final Block b = world.getBlockAt(c[0], c[1], c[2]);
        if (b.getType() != Material.CHEST) {
            world.getBlockAt(c[0], c[1] - 1, c[2]).setType(Material.STONE_BRICKS, false);
            b.setType(Material.CHEST, false);
        }
        final String tag = "aetheria_arca_" + vid;
        final Location loc = new Location(world, c[0] + 0.5, c[1] + 1.4, c[2] + 0.5);
        final TextDisplay existing = singlePanel(loc, tag, 3);
        if (existing != null) {
            existing.text(Component.text("§6Arca de " + t.name + "\n§7clic para aportar"));
            return;
        }
        final TextDisplay td = (TextDisplay) world.spawnEntity(loc, EntityType.TEXT_DISPLAY);
        td.addScoreboardTag(PANEL_TAG);
        td.addScoreboardTag(tag);
        td.setBillboard(Display.Billboard.CENTER);
        td.setPersistent(true);
        td.setAlignment(TextDisplay.TextAlignment.CENTER);
        td.setBackgroundColor(Color.fromARGB(170, 20, 15, 5));
        td.text(Component.text("§6Arca de " + t.name + "\n§7clic para aportar"));
    }

    private static final String PANEL_TAG = "aetheria_panel";

    /** El cartel de info de la plaza (nombre/alcalde/habitantes) se RETIRO: esos datos ya salen en
     *  el marcador lateral, y un TextDisplay menos aligera la plaza. Esto borra el que hubiera
     *  quedado flotando de versiones anteriores. */
    private void removeInfoPanel(Town t) {
        if (!plazaLoaded(t)) {
            return;
        }
        // El cartel de info llevaba el tag por-aldea "aetheria_panel_<vid>" (el tablon de prestigio
        // lleva "aetheria_rank_<vid>" y el arca "aetheria_arca_<vid>", asi que no se tocan).
        final Location loc = new Location(world, t.cx + 0.5, t.baseY + 3.4, t.cz + 0.5);
        for (final org.bukkit.entity.Entity e : world.getNearbyEntities(loc, 10, 6, 10)) {
            if (e instanceof TextDisplay && e.getScoreboardTags().stream()
                    .anyMatch(s -> s.startsWith(PANEL_TAG + "_"))) {
                e.remove();
            }
        }
    }

    /** Reserva los solares de los edificios civicos (taberna/mercado) de cada aldea y construye ya
     *  el granero. Se llama ANTES de fundar casas para que ninguna caiga sobre ellos. */
    private void reserveCivicSpots() {
        civicReserved.clear();
        for (int vid = 0; vid < towns.size(); vid++) {
            final Town t = towns.get(vid);
            civicReserved.add(civicRegion(t.cx + 9, t.cz, t.baseY, 4));        // taberna (futura)
            civicReserved.add(civicRegion(t.cx - 3, t.cz + 12, t.baseY, 3));   // mercado (futuro)
            civicReserved.add(civicRegion(t.cx + 3, t.cz + boticaDz(vid), t.baseY, 4));   // botica (futura)
            ensureCivics(vid, t);   // el granero se levanta ya (permanente); taberna/mercado si toca
        }
    }

    private static int[] civicRegion(int cx, int cz, int baseY, int half) {
        return new int[] {cx - half - 1, baseY - 2, cz - half - 1, cx + half + 1, baseY + 16, cz + half + 1};
    }

    private boolean overlapsReserved(int[] region) {
        for (final int[] r : civicReserved) {
            if (region[0] <= r[3] && region[3] >= r[0] && region[1] <= r[4] && region[4] >= r[1]
                    && region[2] <= r[5] && region[5] >= r[2]) {
                return true;
            }
        }
        return false;
    }

    /** Construye los edificios civicos que la POBLACION de la aldea justifica (una vez cada uno):
     *  el GRANERO desde el principio, la TABERNA con 4 habitantes y el MERCADO con 6. Posiciones
     *  fijas alrededor de la plaza; se registran para que las casas no los pisen. */
    private void ensureCivics(int vid, Town t) {
        // La poblacion decide cuando se CONSTRUYE cada edificio, pero el mantenimiento (rotulo,
        // ajustes) se hace SIEMPRE que ya exista: si no, un pueblo que mengua se quedaba sin
        // refrescar su taberna. Se cuentan los mismos vecinos que muestra el marcador (ninos
        // incluidos); antes esto miraba solo a los adultos y el mercado no aparecia con 6.
        ensureCivic(vid, "granero", t.cx - 12, t.cz, t.baseY, 4, 0,
                "El pueblo construye un granero en " + t.name + ".");
        if (civicBuilt.contains(vid + ":granero")) {
            reclaimStrayGranaryBarrels(vid);   // recoge barriles sueltos de versiones anteriores
            refreshGranaryLabels(vid);         // etiqueta cada barril con su genero y cantidad
        }
        ensureCivic(vid, "taberna", t.cx + 9, t.cz, t.baseY, 5, 4,
                "El pueblo abre una taberna en " + t.name + ".");
        ensureCivic(vid, "mercado", t.cx - 3, t.cz + 12, t.baseY, 5, 6,
                "El pueblo levanta un mercado en " + t.name + ".");
        // BOTICA: cuando el pueblo llega a 8 vecinos ya da para tener quien cure.
        final int bdz = boticaDz(vid);
        final boolean hadBotica = civicBuilt.contains(vid + ":botica");
        ensureCivic(vid, "botica", t.cx + 3, t.cz + bdz, t.baseY, 5, 8,
                "El pueblo abre una botica en " + t.name + ": ya hay quien cure a los heridos.");
        if (!hadBotica && civicBuilt.contains(vid + ":botica") && bdz == -18) {
            civicBuilt.add(vid + ":botica-new");   // se construyo en el sitio nuevo (separada)
            saveCivicBuildings();
        }
        if (civicBuilt.contains(vid + ":botica") && trade != null) {
            trade.ensureHealer(new Location(world, t.cx + 3 + 0.5, t.baseY + 1, t.cz + bdz - 1 + 0.5),
                    t.name, vid);
        }
        // El mercader (entidad) puede desaparecer entre reinicios: se re-asegura si hay mercado.
        if (civicBuilt.contains(vid + ":mercado")) {
            market.ensureTrader(new Location(world, t.cx - 3 + 0.5, t.baseY + 1, t.cz + 12 + 0.5),
                    t.name);
        }
        // El PREGONERO reparte los encargos del pueblo desde el primer dia (junto al arca).
        if (quests != null) {
            quests.ensureCrier(new Location(world, t.cx + 3.5, t.baseY + 1, t.cz + 2.5), t.name, vid);
        }
    }

    /** Engancha las misiones (dependencia opcional; se inyecta despues para evitar el ciclo). */
    public void setQuests(QuestModule quests) {
        this.quests = quests;
    }

    /** Engancha el comercio/botica (igual: se inyecta despues, es opcional). */
    public void setTrade(NpcTradeModule trade) {
        this.trade = trade;
    }

    /** Engancha las parcelas de jugador, para no construir NUNCA sobre lo que es de alguien. */
    public void setClaims(ClaimModule claims) {
        this.claims = claims;
        roads.setClaims(claims);
    }

    /** Desplazamiento en Z de la BOTICA respecto al centro de la aldea. Las aldeas NUEVAS
     *  (escindidas, sin portal al norte) la separan de la plaza (-18, su borde sur queda fuera del
     *  nucleo). Las que YA tenian botica (sin el centinela) y el pueblo del spawn (vid 0, con portal
     *  al norte) la dejan donde estaba (-13): no se mueve ni se duplica nada existente. */
    private int boticaDz(int vid) {
        if (civicBuilt.contains(vid + ":botica") && !civicBuilt.contains(vid + ":botica-new")) {
            return -13;
        }
        return vid == 0 ? -13 : -18;
    }

    private void ensureCivic(int vid, String type, int cx, int cz, int baseY, int half, int minPop,
            String msg) {
        final String key = vid + ":" + type;
        if (civicBuilt.contains(key)) {
            village.civicSign(type, cx, cz, baseY, t(vid));   // mantenimiento: rotulo y ajustes
            return;
        }
        if (townPopulation(vid) < minPop) {
            return;   // el pueblo aun no da para este edificio
        }
        // Los civicos van en un punto FIJO junto a la plaza, asi que no pasan por evaluateSpot:
        // aqui se comprueba a mano que ese solar no sea de un jugador. Si lo es, el pueblo se
        // queda sin ese edificio antes que invadir una parcela.
        if (claims != null && claims.anyClaimIn(cx - half - 2, cz - half - 2,
                cx + half + 2, cz + half + 2)) {
            return;
        }
        // Los carteles llevan el nombre de LA ALDEA (Aetheria es el mundo, no el pueblo).
        final String town = t(vid);
        switch (type) {
            case "granero" -> village.buildGranary(cx, cz, baseY, town);
            case "taberna" -> village.buildTavern(cx, cz, baseY, town);
            case "mercado" -> village.buildMarket(cx, cz, baseY, town);
            case "botica" -> village.buildApothecary(cx, cz, baseY, town);
            default -> { return; }
        }
        plugin.buildRegistry().add(new int[] {cx - half - 1, baseY - 2, cz - half - 1,
                cx + half + 1, baseY + 16, cz + half + 1});
        placed.add(new int[] {cx, cz});
        civicBuilt.add(key);
        saveCivicBuildings();
        if ("taberna".equals(type)) {
            routines.setTavern(townCenter(vid));   // desde ya, la vida social se muda a la taberna
        }
        gateway.postEvent("edificio", msg);
        routines.pushGossip(msg);
        // Hito del mundo: que se levante un granero/taberna/mercado se anuncia a TODOS (con el
        // nombre del pueblo), no solo a quien pasa cerca. Son sucesos poco frecuentes.
        Bukkit.getOnlinePlayers().forEach(p -> p.sendMessage("§3[Aetheria] §7" + msg));
    }

    private String t(int vid) {
        return towns.get(Math.max(0, Math.min(vid, towns.size() - 1))).name;
    }

    private void loadCivicBuildings() {
        civicBuilt.clear();
        if (!civicFile2.exists()) {
            return;
        }
        try (BufferedReader r = new BufferedReader(new FileReader(civicFile2))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (!line.isBlank()) {
                    civicBuilt.add(line.trim());
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("[Aetheria] no pude cargar edificios civicos: " + e.getMessage());
        }
    }

    private void saveCivicBuildings() {
        try (FileWriter w = new FileWriter(civicFile2, false)) {
            for (final String k : civicBuilt) {
                w.write(k + "\n");
            }
        } catch (Exception e) {
            plugin.getLogger().warning("[Aetheria] no pude guardar edificios civicos: " + e.getMessage());
        }
    }

    /** Clave PDC (invisible) con la que se marca cada barril del granero segun el genero que guarda,
     *  para reencontrar el suyo tras un reinicio. */
    private static final String GRANARY_KEY = "granary_good";

    /** Posiciones de los barriles que el EDIFICIO del granero ya tiene, relativas a su origen
     *  (t.cx-12, t.baseY, t.cz). Deben cuadrar con {@link VillageModule#buildGranary}: fila baja y
     *  fila alta del fondo (norte, dz=-2) mas el barril central (0,1,0). Cada genero reutiliza uno
     *  de estos; NO se plantan barriles nuevos. */
    private static final int[][] GRANARY_BARRELS = {
        {-2, 1, -2}, {-1, 1, -2}, {0, 1, -2}, {1, 1, -2}, {2, 1, -2},
        {-2, 2, -2}, {-1, 2, -2}, {0, 2, -2}, {1, 2, -2}, {2, 2, -2},
        {0, 1, 0},
    };

    /**
     * Barril DEDICADO a un genero DENTRO del granero, reutilizando los barriles que el edificio ya
     * tiene en la pared del fondo (no se apilan barriles nuevos). Cada tipo de recurso ocupa uno,
     * asi las pociones u otros objetos NO apilables ya no atascan el granero entero. El barril se
     * marca con PDC ({@link #GRANARY_KEY}) y se ROTULA con el nombre del genero (se ve al abrirlo).
     * {@code createIfMissing=false} devuelve null si ese genero aun no tiene barril (para
     * {@code takeFromGranary}, que no debe asignar ninguno).
     */
    private org.bukkit.inventory.Inventory granaryBarrel(int vid, Material good,
            boolean createIfMissing) {
        if (vid < 0 || vid >= towns.size()) {
            return null;
        }
        final Town t = towns.get(vid);
        final int gx = t.cx - 12;
        final int gz = t.cz;
        final org.bukkit.NamespacedKey key = new org.bukkit.NamespacedKey(plugin, GRANARY_KEY);
        final String tag = good.name();
        int firstFree = -1;
        for (int i = 0; i < GRANARY_BARRELS.length; i++) {
            final int[] o = GRANARY_BARRELS[i];
            final org.bukkit.block.Block b = world.getBlockAt(gx + o[0], t.baseY + o[1], gz + o[2]);
            if (b.getType() != Material.BARREL) {
                continue;   // hueco de la pared sin barril (griefeado): no plantamos fuera de sitio
            }
            if (b.getState() instanceof org.bukkit.block.Container c) {
                final String owner = c.getPersistentDataContainer()
                        .get(key, org.bukkit.persistence.PersistentDataType.STRING);
                if (tag.equals(owner)) {
                    return c.getInventory();               // el barril de este genero
                }
                if (owner == null && firstFree < 0 && c.getInventory().isEmpty()) {
                    firstFree = i;                          // barril de la pared libre, aun sin dueno
                }
            }
        }
        if (!createIfMissing || firstFree < 0) {
            return null;   // todos los barriles del granero ya tienen dueno: el resto se vende fuera
        }
        final int[] o = GRANARY_BARRELS[firstFree];
        final org.bukkit.block.Block b = world.getBlockAt(gx + o[0], t.baseY + o[1], gz + o[2]);
        if (b.getState() instanceof org.bukkit.block.Container c) {
            c.getPersistentDataContainer()
                    .set(key, org.bukkit.persistence.PersistentDataType.STRING, tag);
            c.customName(net.kyori.adventure.text.Component.text("§6" + granaryLabel(good)));
            c.update();
        }
        return world.getBlockAt(gx + o[0], t.baseY + o[1], gz + o[2])
                .getState() instanceof org.bukkit.block.Container c2 ? c2.getInventory() : null;
    }

    /** Nombre del genero para rotular su barril, en castellano ("IRON_INGOT" -> "Hierro"). */
    private static String granaryLabel(Material m) {
        return Goods.esCap(m);
    }

    /** Recoge los barriles SUELTOS que una version anterior dejo apilados hacia el sur del granero
     *  (fuera de la pared): vuelca su contenido en el barril de pared que le toca a cada genero y
     *  retira el barril sobrante. Se llama en el mantenimiento del granero; una vez limpio, no hace
     *  nada. */
    private void reclaimStrayGranaryBarrels(int vid) {
        if (vid < 0 || vid >= towns.size()) {
            return;
        }
        final Town t = towns.get(vid);
        final int gx = t.cx - 12;
        final org.bukkit.NamespacedKey key = new org.bukkit.NamespacedKey(plugin, GRANARY_KEY);
        for (int dz = 1; dz <= 24; dz++) {   // la fila vieja crecia hacia +Z desde el central
            final org.bukkit.block.Block b = world.getBlockAt(gx, t.baseY + 1, t.cz + dz);
            if (b.getType() != Material.BARREL
                    || !(b.getState() instanceof org.bukkit.block.Container c)) {
                continue;
            }
            final String owner = c.getPersistentDataContainer()
                    .get(key, org.bukkit.persistence.PersistentDataType.STRING);
            if (owner == null) {
                continue;   // no es un barril nuestro: no se toca
            }
            for (final org.bukkit.inventory.ItemStack st : c.getInventory().getContents()) {
                if (st == null) {
                    continue;
                }
                final org.bukkit.inventory.Inventory dest = granaryBarrel(vid, st.getType(), true);
                if (dest != null) {
                    dest.addItem(st);   // lo que no quepa se pierde (era excedente ya de por si)
                }
            }
            c.getInventory().clear();
            b.setType(Material.AIR, false);   // fuera el barril suelto
        }
    }

    /**
     * #11 - Deposita en el granero de la aldea lo que un colono acaba de producir DE VERDAD
     * (una espiga segada, un tronco talado, un lingote fundido...). Cada genero cae en SU barril;
     * lo que no cupo en ese barril (o si no queda hueco para uno nuevo) se devuelve como excedente,
     * que se vende fuera y va al sector comercio.
     */
    public int depositInGranary(int vid, Material good, int amount) {
        final org.bukkit.inventory.Inventory inv = granaryBarrel(vid, good, true);
        if (inv == null) {
            return amount;
        }
        final var left = inv.addItem(new org.bukkit.inventory.ItemStack(good, amount));
        int rest = 0;
        for (final org.bukkit.inventory.ItemStack s : left.values()) {
            rest += s.getAmount();
        }
        return rest;
    }

    /**
     * #11 - Saca del granero UNA unidad del primer material de la lista que haya (la cadena de
     * oficios: el herrero funde lo que el cantero pico, el carnicero ahuma lo que hay). Devuelve
     * el material consumido, o null si el granero no tenia nada de eso. Cada genero vive en su
     * propio barril, asi que se consulta el barril de cada material.
     */
    public Material takeFromGranary(int vid, Material[] wanted) {
        for (final Material m : wanted) {
            final org.bukkit.inventory.Inventory inv = granaryBarrel(vid, m, false);
            if (inv != null && inv.contains(m)) {
                inv.removeItem(new org.bukkit.inventory.ItemStack(m, 1));
                return m;
            }
        }
        return null;
    }

    /**
     * EXCEDENTE: lo que pasa de dos pilas de un genero. Por debajo de eso, lo del barril es la
     * despensa de trabajo de los oficios (el herrero necesita la piedra del cantero, el carnicero
     * la carne...) y no se toca. Por encima, es lo que hoy el pueblo vendria vendiendo fuera con
     * prima: eso si se lo puede llevar quien se ha ganado la confianza del pueblo.
     */
    private static final int SURPLUS_THRESHOLD = TownMath.SURPLUS_THRESHOLD;

    /**
     * GRANERO CERRADO. Hasta ahora cualquiera podia abrir los barriles del granero y vaciar la
     * despensa del pueblo. Ahora:
     * <ul>
     *   <li>si no estas entre los <b>tres primeros</b> del ranking de esa aldea, ni lo abres;</li>
     *   <li>si lo estas, te llevas <b>solo el excedente</b> (lo que pasa de dos pilas): la
     *       reserva con la que trabajan los oficios se queda donde esta.</li>
     * </ul>
     * Por eso el barril no se abre "a lo normal": se entrega el excedente en mano. Asi no hay
     * forma de vaciar la despensa aunque tengas llave.
     */
    @EventHandler(ignoreCancelled = true)
    public void onGranaryBarrel(PlayerInteractEvent e) {
        if (e.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK
                || e.getHand() != org.bukkit.inventory.EquipmentSlot.HAND) {
            return;
        }
        final Block b = e.getClickedBlock();
        if (b == null || b.getType() != Material.BARREL || !b.getWorld().equals(world)) {
            return;
        }
        final int vid = granaryTownOf(b);
        if (vid < 0) {
            return;   // un barril cualquiera del mundo: no es asunto nuestro
        }
        e.setCancelled(true);
        final Player p = e.getPlayer();
        final String town = townName(vid);
        if (!granaryAccess(vid, p.getUniqueId())) {
            p.sendMessage("§c[Granero de " + town + "] §7Esto es la despensa del pueblo. Solo los "
                    + "tres primeros del tablon de prestigio tienen llave.");
            p.sendMessage("§8Cumple encargos del alguacil o aporta al arca: /prestigio");
            return;
        }
        if (!(b.getState() instanceof org.bukkit.block.Container c)) {
            return;
        }
        final Material good = granaryGoodOf(c);
        final int total = good == null ? 0 : count(c.getInventory(), good);
        final int surplus = total - SURPLUS_THRESHOLD;
        if (good == null || surplus <= 0) {
            p.sendMessage("§e[Granero de " + town + "] §7Esto lo necesita el pueblo: vuelve cuando "
                    + "haya excedente. §8(" + total + "/" + SURPLUS_THRESHOLD + " de reserva)");
            return;
        }
        final int space = freeSpaceFor(p, good);
        final int take = Math.min(surplus, space);
        if (take <= 0) {
            p.sendMessage("§7No te cabe mas " + Goods.es(good) + " encima.");
            return;
        }
        c.getInventory().removeItem(new org.bukkit.inventory.ItemStack(good, take));
        p.getInventory().addItem(new org.bukkit.inventory.ItemStack(good, take));
        p.sendMessage("§a[Granero de " + town + "] §fTe llevas §e" + take + " " + Goods.es(good)
                + "§f del excedente. §7(la reserva del pueblo se queda intacta)");
        p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_BARREL_OPEN, 0.8f, 1.1f);
    }

    /** ¿Es este barril uno de los del granero de alguna aldea? Devuelve su aldea, o -1. */
    private int granaryTownOf(Block b) {
        for (int vid = 0; vid < towns.size(); vid++) {
            final Town t = towns.get(vid);
            for (final int[] o : GRANARY_BARRELS) {
                if (b.getX() == t.cx - 12 + o[0] && b.getY() == t.baseY + o[1]
                        && b.getZ() == t.cz + o[2]) {
                    return vid;
                }
            }
        }
        return -1;
    }

    /** El genero al que esta dedicado ese barril del granero (marca PDC), o null. */
    private Material granaryGoodOf(org.bukkit.block.Container c) {
        final String tag = c.getPersistentDataContainer().get(
                new org.bukkit.NamespacedKey(plugin, GRANARY_KEY),
                org.bukkit.persistence.PersistentDataType.STRING);
        return tag == null ? null : Material.matchMaterial(tag);
    }

    private static int count(org.bukkit.inventory.Inventory inv, Material good) {
        int n = 0;
        for (final org.bukkit.inventory.ItemStack s : inv.getContents()) {
            if (s != null && s.getType() == good) {
                n += s.getAmount();
            }
        }
        return n;
    }

    // --- ETIQUETAS de los barriles del granero (para VER que hay dentro sin abrirlos) ---
    private static final String GRANARY_LABEL_TAG = "aetheria_granary_label";
    private final java.util.Map<String, org.bukkit.entity.TextDisplay> granaryLabels =
            new java.util.HashMap<>();
    private boolean granaryLabelsPurged = false;

    /** Pone (o actualiza) sobre cada barril del granero una etiqueta flotante "Genero xN" con lo
     *  que guarda, para que el jugador vea el contenido y la cantidad sin tener que abrirlo (el
     *  barril no se abre: es la despensa del pueblo). Se refresca cada ciclo de vida de la aldea. */
    private void refreshGranaryLabels(int vid) {
        if (!granaryLabelsPurged) {   // limpia etiquetas huerfanas de un arranque anterior
            for (final org.bukkit.entity.TextDisplay td
                    : world.getEntitiesByClass(org.bukkit.entity.TextDisplay.class)) {
                if (td.getScoreboardTags().contains(GRANARY_LABEL_TAG)) {
                    td.remove();
                }
            }
            granaryLabelsPurged = true;
        }
        if (vid < 0 || vid >= towns.size()) {
            return;
        }
        final Town t = towns.get(vid);
        if (!plazaLoaded(t)) {
            return;   // aldea descargada: no se fuerza la carga del chunk ni se tocan etiquetas
        }
        for (final int[] o : GRANARY_BARRELS) {
            final int bx = t.cx - 12 + o[0];
            final int by = t.baseY + o[1];
            final int bz = t.cz + o[2];
            final String key = bx + "," + by + "," + bz;
            final Block b = world.getBlockAt(bx, by, bz);
            String text = null;
            if (b.getType() == Material.BARREL && b.getState() instanceof org.bukkit.block.Container c) {
                // Orienta el FRENTE del barril (la tapa con anillas) hacia el interior (SUR), donde
                // esta el jugador: asi no se ve el "culo". La etiqueta es una entidad APARTE (no va
                // vinculada al barril), asi que girar el barril no la afecta.
                if (b.getBlockData() instanceof org.bukkit.block.data.Directional dir
                        && dir.getFacing() != BlockFace.SOUTH) {
                    dir.setFacing(BlockFace.SOUTH);
                    b.setBlockData(dir, false);
                }
                final Material good = granaryGoodOf(c);
                final int amount = good == null ? 0 : count(c.getInventory(), good);
                if (good != null && amount > 0) {
                    // Se marca el EXCEDENTE: es justo lo que los tres primeros del tablon de
                    // prestigio pueden llevarse (lo que pasa de la reserva del pueblo).
                    text = "§e" + granaryLabel(good) + " §7x" + amount
                            + (amount > SURPLUS_THRESHOLD ? "\n§a excedente disponible" : "");
                }
            }
            final org.bukkit.entity.TextDisplay existing = granaryLabels.get(key);
            if (text == null) {   // barril vacio o sin genero: fuera su etiqueta
                if (existing != null) {
                    if (existing.isValid()) {
                        existing.remove();
                    }
                    granaryLabels.remove(key);
                }
                continue;
            }
            if (existing != null && existing.isValid()) {
                existing.text(net.kyori.adventure.text.Component.text(text));
                continue;
            }
            final String txt = text;
            // DELANTE de la cara sur del barril (hacia el interior del granero), a media altura.
            // Ojo con el 1.08: el bloque del barril ocupa de bz a bz+1, asi que cualquier valor
            // por debajo de bz+1 deja la etiqueta DENTRO del barril y el modelo la tapa (era el
            // motivo de que "no se vieran"). Y a escala 0.4 para que quepa una por barril: a
            // tamano natural, "Ladrillo de piedra x128" invade a los barriles vecinos.
            final Location loc = new Location(world, bx + 0.5, by + 0.45, bz + 1.08);
            final org.bukkit.entity.TextDisplay td = world.spawn(loc,
                    org.bukkit.entity.TextDisplay.class, d -> {
                        d.text(net.kyori.adventure.text.Component.text(txt));
                        d.setBillboard(org.bukkit.entity.Display.Billboard.CENTER);
                        d.setSeeThrough(false);
                        d.setBackgroundColor(Color.fromARGB(190, 20, 15, 5));
                        d.setViewRange(0.35f);   // se leen dentro del granero, no desde el pueblo
                        d.setTransformation(new org.bukkit.util.Transformation(
                                new org.joml.Vector3f(), new org.joml.Quaternionf(),
                                new org.joml.Vector3f(0.4f, 0.4f, 0.4f), new org.joml.Quaternionf()));
                        d.addScoreboardTag(GRANARY_LABEL_TAG);
                    });
            granaryLabels.put(key, td);
        }
    }

    /** Cuantas unidades de ese genero le caben todavia al jugador encima. */
    private static int freeSpaceFor(Player p, Material good) {
        int space = 0;
        for (final org.bukkit.inventory.ItemStack s : p.getInventory().getStorageContents()) {
            if (s == null || s.getType().isAir()) {
                space += good.getMaxStackSize();
            } else if (s.getType() == good) {
                space += Math.max(0, good.getMaxStackSize() - s.getAmount());
            }
        }
        return space;
    }

    // --- Datos que necesitan las MISIONES (el alguacil pide lo que de verdad hace falta) ---

    /** Cuanto hay de ese genero en el granero de la aldea. */
    public int granaryCount(int vid, Material good) {
        final org.bukkit.inventory.Inventory inv = granaryBarrel(vid, good, false);
        return inv == null ? 0 : count(inv, good);
    }

    /** Generos basicos que un pueblo deberia tener siempre a mano. */
    private static final Material[] STAPLES = {
        Material.BREAD, Material.WHEAT, Material.COBBLESTONE, Material.OAK_LOG,
        Material.IRON_INGOT, Material.COAL, Material.WHITE_WOOL, Material.LEATHER,
        Material.PAPER, Material.COOKED_COD,
    };

    /** El genero que MAS falta en el granero de esa aldea (o null si va sobrado de todo). */
    public Material granaryShortage(int vid) {
        Material worst = null;
        int least = Integer.MAX_VALUE;
        for (final Material m : STAPLES) {
            final int n = granaryCount(vid, m);
            if (n < 32 && n < least) {
                least = n;
                worst = m;
            }
        }
        return worst;
    }

    /**
     * Los vecinos con los que se puede hablar en una aldea (para los encargos sociales). Se
     * dejan fuera los JUBILADOS a proposito: su NPC lleva el "(jubilado)" pegado al nombre, no
     * casaria con el encargo y el jugador se quedaria con una mision imposible de cerrar.
     */
    public java.util.List<String> villagerNames(int vid) {
        final java.util.List<String> out = new ArrayList<>();
        for (final Colono c : colonos) {
            if (c.vid == vid && !c.retired) {
                out.add(c.name);
            }
        }
        return out;
    }

    /** Como le va a esa aldea ("en apuros", "estable", "prospera", "floreciente"). */
    public String townLevelOf(int vid) {
        return vid < 0 || vid >= towns.size() ? "estable"
                : townLevel(townWealth(vid), townPopulation(vid));
    }

    /** El nombre de OTRA aldea (la mas cercana), o null si esta es la unica del mundo. */
    public String otherTownName(int vid) {
        if (vid < 0 || vid >= towns.size()) {
            return null;
        }
        final Town from = towns.get(vid);
        String best = null;
        double bestD = Double.MAX_VALUE;
        for (int i = 0; i < towns.size(); i++) {
            if (i == vid) {
                continue;
            }
            final double d = Math.hypot(towns.get(i).cx - from.cx, towns.get(i).cz - from.cz);
            if (d < bestD) {
                bestD = d;
                best = towns.get(i).name;
            }
        }
        return best;
    }

    /** True si el bloque es parte de lo CONSTRUIDO del pueblo (casa, edificio o nucleo de plaza):
     *  el trabajo fisico de los aldeanos nunca pica ni tala nada de esto. */
    public boolean isVillageBuilt(Block b) {
        return ownerAt(b) != null || buildingAt(b) || inVillageCore(b);
    }

    /** #11 - Suma al PECULIO de un colono lo que ha ganado con su trabajo (se hereda al morir). */
    public void addWealth(String name, double amount) {
        final Colono c = findColono(name);
        if (c != null && amount > 0) {
            c.wealth += amount;
        }
    }

    // --- API para el COMERCIO con vecinos (NpcTradeModule): quien es cada uno y que tiene ---

    /** Oficio (clave inglesa) de un vecino, o null si ese nombre no es de un colono. */
    public String professionOf(String name) {
        final Colono c = findColono(name);
        return c == null ? null : c.profKey;
    }

    /** Aldea a la que pertenece un vecino, o -1. */
    public int townOfColono(String name) {
        final Colono c = findColono(name);
        return c == null ? -1 : c.vid;
    }

    /** Peculio de un vecino (lo que puede pagarte de su bolsillo). */
    public double wealthOf(String name) {
        final Colono c = findColono(name);
        return c == null ? 0 : c.wealth;
    }

    /** Le saca de su peculio lo que acaba de gastar (comprandote genero). No baja de cero. */
    public void spendWealth(String name, double amount) {
        final Colono c = findColono(name);
        if (c != null && amount > 0) {
            c.wealth = Math.max(0, c.wealth - amount);
        }
    }

    // --- #15: datos por ALDEA y de toda la COMARCA (para el marcador) ---

    /** Ficha de la aldea en cuyo radio esta el jugador: nombre, vecinos, riqueza, prosperidad y
     *  alcalde. Devuelve null si esta a campo abierto (fuera de toda aldea). */
    public String[] townInfo(Player p) {
        if (!p.getWorld().equals(world)) {
            return null;
        }
        int near = -1;
        double bestD = TOWN_RADIUS;
        for (int i = 0; i < towns.size(); i++) {
            final Town t = towns.get(i);
            final double d = Math.hypot(p.getX() - (t.cx + 0.5), p.getZ() - (t.cz + 0.5));
            if (d <= TOWN_RADIUS && d < bestD) {
                bestD = d;
                near = i;
            }
        }
        if (near < 0) {
            return null;
        }
        final int hab = townPopulation(near);   // los ninos tambien son vecinos del pueblo
        final double wealth = townWealth(near);
        // El marcador tiene que enseñar el MISMO coste que se cobra de verdad (si no, la barra de
        // "proximo vecino" mentiria en las aldeas que ya han colonizado).
        final double need = growthCost(chargedSize(near));
        final double pool = towns.get(near).pool;
        return new String[] {
            towns.get(near).name,
            String.valueOf(hab),
            String.format("%.0f", wealth),
            townLevel(wealth, hab),
            alcaldes.getOrDefault(near, ""),
            // Progreso de la HUCHA hacia el proximo vecino: al 100% llega uno y vuelve a 0.
            String.format("%.0f", Math.max(0, Math.min(100, pool * 100 / need))),
            String.format("%.0f/%.0f", Math.max(0, pool), need),
        };
    }

    // Escalones de prosperidad de UNA aldea, en AET ahorrados POR VECINO.
    private static final double[] TOWN_STEPS = {8, 30, 80};

    /** Riqueza de una aldea = lo que han ahorrado SUS vecinos con su trabajo (no un numero
     *  global): las aldeas que producen mas son de verdad mas ricas que las demas. */
    private double townWealth(int vid) {
        double sum = 0;
        for (final Colono c : colonos) {
            if (c.vid == vid) {
                sum += c.wealth;
            }
        }
        return sum;
    }

    /** Prosperidad de UNA aldea, por riqueza POR VECINO (una aldea pequena y rica prospera). */
    private static String townLevel(double wealth, int hab) {
        final double per = hab <= 0 ? 0 : wealth / hab;
        if (per < TOWN_STEPS[0]) {
            return "en apuros";
        }
        if (per < TOWN_STEPS[1]) {
            return "estable";
        }
        return per < TOWN_STEPS[2] ? "prospera" : "floreciente";
    }

    /** Centro de la plaza de una aldea (lo usa el alguacil para no salirse de su ronda). */
    public Location plazaCenter(int vid) {
        return vid < 0 || vid >= towns.size() ? null : townCenter(vid);
    }

    /** Numero de aldeas del mundo. */
    public int townCount() {
        return towns.size();
    }

    /** Poblacion total del mundo (adultos + ninos). */
    public int totalPopulation() {
        return colonos.size() + children.size();
    }

    /** Cada vecino GASTA lo suyo en vivir (comida, lena, ropa). Sin esto el peculio solo subiria
     *  y toda aldea acabaria "floreciente": asi la riqueza de una aldea refleja si trabaja. */
    private void spendUpkeep() {
        for (final Colono c : colonos) {
            c.wealth = Math.max(0, c.wealth - 0.6);
        }
    }

    /** Donde tiene su PUESTO de trabajo un colono (el edificio de su oficio, ya construido), o
     *  null si su oficio aun no tiene edificio en su aldea. NO lo construye: solo lo consulta. */
    private Location workplaceOf(Colono c) {
        if (isKeeper(c.profKey)) {
            return tavernBar(c.vid);
        }
        for (final Building b : buildings) {
            if (b.vid == c.vid && b.profKey.equals(c.profKey)) {
                return new Location(world, b.cx + 0.5, b.baseY + 1, b.cz + 0.5);
            }
        }
        return null;
    }

    /** Instantanea de los colonos EN ACTIVO (ni jubilados ni muertos) para el trabajo fisico. */
    public List<LaborModule.Laborer> activeLaborers() {
        final List<LaborModule.Laborer> out = new ArrayList<>();
        for (final Colono c : colonos) {
            if (!c.retired) {
                out.add(new LaborModule.Laborer(c.name, c.profKey, c.vid, workplaceOf(c)));
            }
        }
        return out;
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
            if (c.origin != null && !c.origin.isEmpty() && c.vid < towns.size()) {
                fam.append(fem ? " Eres FUNDADORA de " : " Eres FUNDADOR de ")
                        .append(towns.get(c.vid).name).append(", viniste desde ").append(c.origin)
                        .append("; todos en la aldea saben que la fundaste tu y lo cuentas con orgullo.");
            }
            // VECINOS REALES de su aldea (hasta 5): asi habla de gente que existe DE VERDAD, no de
            // personajes fantasma de mundos antiguos (Nara/Pol ya no existen).
            final StringBuilder veci = new StringBuilder();
            int shown = 0;
            for (final Colono o : colonos) {
                if (o.vid != c.vid || o.name.equals(c.name) || o.name.equals(c.spouse) || shown >= 5) {
                    continue;
                }
                veci.append(shown == 0 ? " Otros vecinos de tu aldea: " : ", ").append(o.name);
                shown++;
            }
            if (shown > 0) {
                veci.append(". No te inventes vecinos ni nombres: si no lo tienes aqui, no existe.");
            }
            // Su PECULIO (lo que ha ahorrado trabajando): que hable de si le va bien o mal.
            final String bolsa = c.wealth < 10 ? " Apenas tienes ahorros; vives al dia."
                    : c.wealth < 60 ? String.format(" Tienes unos %.0f AET ahorrados de tu trabajo.",
                            c.wealth)
                    : String.format(" Has ahorrado %.0f AET: te va bien y se te nota.", c.wealth);
            final String bio = "Eres " + c.name + ", " + (fem ? "vecina" : "vecino")
                    + " del pueblo de Aetheria. Tienes " + age
                    + " anos y tu oficio es " + job + "." + fam + bolsa + veci
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

    /**
     * Sendero desde la casa hasta la plaza que SE PUEDE SUBIR ANDANDO.
     *
     * <p>El camino ya no se limita a calcar el relieve (si el terreno daba un salto de un
     * bloque, el sendero quedaba cortado: habia que saltar). Ahora se regula la RASANTE:
     * la cota del camino sube o baja como mucho <b>un bloque cada dos casillas</b>, tallando
     * lo que sobresale y rellenando lo que falta, y en la casilla intermedia se pone una
     * <b>losa</b>. Asi cada paso es de medio bloque: se sube y se baja caminando, sin saltar,
     * en los dos sentidos (una escalera de losas, no un escalon).
     *
     * <p>Nunca pisa lo construido: talla/rellena solo terreno natural.
     */
    // Los CAMINOS (carreteras entre aldeas y senderos de puerta a plaza) viven ahora en
    // RoadBuilder: trazar un camino no tiene nada que ver con casar a dos vecinos.

    private void pathTo(int cx, int cz, int fy, int half, Location plaza) {
        roads.pathTo(cx, cz, fy, half, plaza);
    }

    private void buildRoad(int x0, int z0, int x1, int z1) {
        roads.buildRoad(x0, z0, x1, z1);
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
        final Material m = b.getType();
        // ARBOLES: hojas, brotes y matojos son NATURALEZA, no casa ni mobiliario -> siempre se
        // pueden romper, aunque el arbol crezca dentro del pueblo. (No aplica a troncos aun: esos
        // pueden ser esquina de una casa; se comprueba mas abajo tras descartar casas/edificios.)
        if (Tag.LEAVES.isTagged(m) || Tag.SAPLINGS.isTagged(m) || m == Material.VINE
                || m == Material.SHORT_GRASS || m == Material.TALL_GRASS || m == Material.FERN
                || m == Material.LARGE_FERN || Tag.FLOWERS.isTagged(m)) {
            return false;
        }
        if (terrain(m)) {
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
        // TRONCOS que NO son de una casa ni de un edificio son un ARBOL -> recolectables (aunque
        // esten en el radio del pueblo). Asi se puede talar sin el mensaje de "pertenece al pueblo".
        if (Tag.LOGS.isTagged(m)) {
            return false;
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
        double bestD = TOWN_RADIUS + 1;
        for (int i = 0; i < towns.size(); i++) {
            final Town t = towns.get(i);
            final double d = Math.hypot(p.getX() - (t.cx + 0.5), p.getZ() - (t.cz + 0.5));
            if (d <= TOWN_RADIUS && d < bestD) {
                bestD = d;
                near = i;
            }
        }
        final Integer was = inTown.get(p.getUniqueId());
        if (near >= 0 && (was == null || was != near)) {
            inTown.put(p.getUniqueId(), near);
            refreshTownVisuals(near);   // arca/panel/tablon/ranking AL MOMENTO, sin esperar 60 s
            if (quests != null) {
                quests.onEnterTown(p, near);   // trae sus encargos de esta aldea (cuentan ya)
            }
            p.showTitle(Title.title(
                    Component.text("§6" + towns.get(near).name),
                    Component.text("§7Un pueblo de Aetheria"),
                    Title.Times.times(java.time.Duration.ofMillis(400),
                            java.time.Duration.ofSeconds(3), java.time.Duration.ofMillis(900))));
        } else if (near < 0 && was != null) {
            inTown.remove(p.getUniqueId());
        }
    }

    /** Detras de la BARRA de la taberna de esa aldea (el puesto del tabernero). La taberna la
     *  construye ensureCivics en townCenter + 9 al este; el pasillo de servicio queda a +2 de su
     *  centro. Si aun no hay taberna, se queda en la plaza. */
    private Location tavernBar(int vid) {
        if (vid < 0 || vid >= towns.size() || !civicBuilt.contains(vid + ":taberna")) {
            return townCenter(vid);
        }
        final Town t = towns.get(vid);
        return new Location(world, t.cx + 9 + 2 + 0.5, t.baseY + 1, t.cz + 0.5);
    }

    private Location townCenter(int vid) {
        final Town t = towns.get(Math.max(0, Math.min(vid, towns.size() - 1)));
        return new Location(world, t.cx + 0.5, t.baseY + 1, t.cz + 0.5);
    }

    /** Vecinos de una aldea CONTANDO A LOS NINOS: es lo que ve el jugador (si acaba de nacer
     *  uno, el marcador y el panel de la plaza lo reflejan al momento, no cuando crece). */
    private int townPopulation(int vid) {
        int n = countInTown(vid);
        for (final Child c : children) {
            if (c.vid == vid) {
                n++;
            }
        }
        return n;
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

    /**
     * ESCISION (nuevo modo de fundar aldeas). Cuando una aldea alcanza el {@link #splitThreshold()},
     * una PAREJA SIN HIJOS (o, en su defecto, dos solteros) se marcha empujada por una desgracia y
     * funda una aldea nueva lejos. Los que parten <b>pierden todo su peculio</b> (su prestigio de
     * aldeano: empiezan de cero) y en la aldea nueva constan como <b>FUNDADORES venidos de la aldea
     * X</b>. La aldea de origen sigue creciendo sin tope; el umbral de la proxima escision sube.
     */
    /** Parte del fondo comun que se llevan los fundadores a la aldea nueva (dote de fundacion). */
    private static final double SPLIT_DOWRY = TownMath.SPLIT_DOWRY;

    private void trySplit(int vid, java.util.Random rng) {
        if (towns.size() >= MAX_TOWNS) {
            return;   // salvaguarda: el mundo no funda aldeas sin freno
        }
        if (townPopulation(vid) < splitThreshold(towns.get(vid))) {
            return;
        }
        // El pueblo ha alcanzado su tamano de escision: PRIMERO levanta lo que su poblacion ya
        // justifica (taberna >=4, mercado >=6...) y solo DESPUES se van los fundadores. Si no, la
        // escision (que corre antes que townLife) bajaba la poblacion y el mercado no se construia
        // nunca al llegar justo a 6.
        ensureCivics(vid, towns.get(vid));
        final Colono[] pair = findFounders(vid);
        if (pair == null) {
            return;   // no hay una pareja sin hijos ni dos solteros: la escision espera
        }
        final String originName = t(vid);
        final int newVid = createTown();
        if (newVid < 0) {
            return;   // no encontro sitio para la aldea nueva: se reintenta el proximo ciclo
        }
        final boolean couple = pair[0].spouse != null && pair[0].spouse.equals(pair[1].name);
        relocateFounders(newVid, pair[0], pair[1], couple, originName, rng);
        // DOTE DE FUNDACION: los que parten se llevan la mitad del fondo comun de su aldea. Antes
        // la hucha de la madre se quedaba intacta y la hija nacia sin un AET: como al perder dos
        // vecinos el coste del siguiente cae en picado, la madre los reponia al instante y la
        // escision no se notaba. Ahora la madre se queda a medias (le cuesta reponerse) y la hija
        // arranca con algo con lo que crecer.
        final double dote = Math.max(0, towns.get(vid).pool) * SPLIT_DOWRY;
        towns.get(vid).pool -= dote;
        towns.get(newVid).pool += dote;
        towns.get(vid).splits++;   // esta aldea ha colonizado una vez mas: su umbral sube (+2)
        save();
        saveTowns();
        final String reason = SPLIT_REASONS[rng.nextInt(SPLIT_REASONS.length)];
        final String newName = towns.get(newVid).name;
        final String who = pair[0].name + " y " + pair[1].name;
        final String msg = "Tras " + reason + " en " + originName + ", " + who + " parten y fundan "
                + newName + (dote >= 1 ? " con " + (int) dote + " AET del fondo comun." : ".");
        gateway.postEvent("fundacion", msg);
        routines.pushGossip(msg);
        Bukkit.getOnlinePlayers().forEach(pl -> pl.sendMessage("§d[Mundo] §f" + msg));
        plugin.getLogger().info("[Aetheria] Escision: " + who + " fundan " + newName + " (desde "
                + originName + ").");
    }

    /** Elige quien se escinde: una PAREJA CASADA SIN HIJOS si la hay; si no, dos SOLTEROS de la
     *  aldea (a ser posible de distinto sexo, para que la aldea nueva pueda crecer). null si no hay
     *  candidatos validos. */
    private Colono[] findFounders(int vid) {
        for (final Colono a : colonos) {   // 1) pareja casada sin descendencia
            if (a.vid != vid || a.spouse == null || a.spouse.isEmpty()) {
                continue;
            }
            final Colono b = findColono(a.spouse);
            if (b != null && b.vid == vid && childCount(a.name) == 0 && childCount(b.name) == 0) {
                return new Colono[] {a, b};
            }
        }
        Colono first = null;
        Colono opposite = null;
        Colono same = null;
        for (final Colono c : colonos) {   // 2) dos solteros (mejor de distinto sexo)
            if (c.vid != vid || (c.spouse != null && !c.spouse.isEmpty())) {
                continue;
            }
            if (first == null) {
                first = c;
            } else if (!c.gender.equals(first.gender) && opposite == null) {
                opposite = c;
            } else if (same == null) {
                same = c;
            }
        }
        final Colono second = opposite != null ? opposite : same;
        return first != null && second != null ? new Colono[] {first, second} : null;
    }

    /** Muda a los dos fundadores a la aldea nueva: derriba sus casas viejas, les construye vivienda
     *  junto a la plaza nueva, les pone el peculio a CERO (pierden su prestigio) y les marca el
     *  origen. Una pareja comparte casa (2 camas); dos solteros van a casitas separadas. */
    private void relocateFounders(int newVid, Colono a, Colono b, boolean couple, String origin,
            java.util.Random rng) {
        demolish(a);
        if (!couple || a.x != b.x || a.z != b.z) {
            demolish(b);
        }
        if (couple) {
            final int[] h = buildStarterHouse(newVid, colonos.size(), a.name.split(" ")[0], 2, rng);
            placeFounder(a, newVid, origin, h);
            placeFounder(b, newVid, origin, h);
        } else {
            placeFounder(a, newVid, origin,
                    buildStarterHouse(newVid, colonos.size(), a.name.split(" ")[0], 1, rng));
            placeFounder(b, newVid, origin,
                    buildStarterHouse(newVid, colonos.size() + 1, b.name.split(" ")[0], 1, rng));
        }
    }

    /** Asienta a un fundador en la aldea nueva (su casa ya construida, o el centro si no hubo sitio):
     *  actualiza aldea, casa, peculio a 0 y origen, y lo re-registra en la rutina. */
    private void placeFounder(Colono c, int newVid, String origin, int[] h) {
        final Villager.Profession prof = profFromKey(c.profKey);
        c.vid = newVid;
        c.wealth = 0;          // pierde su prestigio de aldeano: empieza de cero en la aldea nueva
        c.origin = origin;
        if (h != null) {
            c.x = h[0];
            c.z = h[1];
            c.y = h[2] + 1;
            c.halfX = h[3];
            c.halfZ = h[4];
            c.pal = h[5];
            c.floors = 1;
            c.dimsKnown = true;
        }
        final Location center = townCenter(newVid);
        final Location home = new Location(world, c.x + 0.5, c.y, c.z + 0.5);
        final Location work = ensureBuilding(newVid, prof);
        routines.removeColono(c.name);
        routines.addColono("colono", c.name, home, work, prof, center, c.gender);
        routines.setStayAtWork(c.name, isKeeper(c.profKey));
        if (c.retired) {
            routines.retire(c.name);
        }
    }

    /** Construye una casa modesta junto a la plaza de una aldea y devuelve {cx,cz,fy,halfX,halfZ,pal}
     *  (o null si no hubo hueco). {@code beds}=2 para la pareja fundadora, 1 para un soltero. */
    private int[] buildStarterHouse(int vid, int index, String sign, int beds, java.util.Random rng) {
        final Location center = townCenter(vid);
        final int[] spot = findBuildSpot(center, index);
        if (spot == null) {
            return null;
        }
        final int cx = spot[0];
        final int cz = spot[1];
        final int fy = spot[2];
        final int palIdx = rng.nextInt(COMBOS.length);
        final Material[] pal = COMBOS[palIdx];
        final int halfX = 2;
        final int halfZ = beds >= 2 ? 3 : (rng.nextInt(100) < 35 ? 3 : 2);
        final BlockFace door = towardPlaza(center, cx, cz);
        prepareTerrain(cx, cz, fy);
        Blueprint.buildHouse(world, cx, cz, fy, door, halfX, halfZ, 1, false,
                pal[0], pal[1], pal[2], pal[3], true, beds, sign);
        deflood(cx, fy, cz, 1);
        pathTo(cx, cz, fy, Math.max(halfX, halfZ), center);
        placed.add(new int[] {cx, cz});
        plugin.buildRegistry().add(new int[] {cx - halfX - 1, fy - 2, cz - halfZ - 1,
                cx + halfX + 1, fy + 14, cz + halfZ + 1});
        return new int[] {cx, cz, fy, halfX, halfZ, palIdx};
    }

    /** Funda una aldea NUEVA vacia lejos de todas, sobre tierra firme, con su plaza, nombre y una
     *  carretera hasta la mas cercana. Devuelve su id, o -1 si no encontro sitio. No mete gente:
     *  de eso se encarga quien la funda ({@link #trySplit}). */
    private int createTown() {
        final var rng = ThreadLocalRandom.current();
        final int[] site = findTownSite(rng);
        if (site == null) {
            return -1;
        }
        final Location plaza = village.buildPlazaAt(site[0], site[1]);
        towns.add(new Town(pickTownName(), plaza.getBlockX(), plaza.getBlockZ(),
                plaza.getBlockY() - 1));
        final int newVid = towns.size() - 1;
        saveTowns();
        final Town from = nearestTown(plaza.getBlockX(), plaza.getBlockZ(), newVid);
        if (from != null) {   // CARRETERA desde la aldea mas cercana: las aldeas quedan conectadas
            buildRoad(from.cx, from.cz, plaza.getBlockX(), plaza.getBlockZ());
        }
        return newVid;
    }

    /** Busca un emplazamiento para una aldea nueva a 200-360 bloques de una existente, sobre tierra
     *  firme y lejos de las demas. Devuelve {cx,cz} o null. */
    private int[] findTownSite(java.util.Random rng) {
        if (towns.isEmpty()) {
            return null;
        }
        final Town origin = towns.get(rng.nextInt(towns.size()));   // en racimo, no en estrella
        for (int t = 0; t < 80; t++) {
            final double ang = rng.nextDouble() * Math.PI * 2;
            final int dist = 200 + rng.nextInt(160);
            final int cx = origin.cx + (int) Math.round(Math.cos(ang) * dist);
            final int cz = origin.cz + (int) Math.round(Math.sin(ang) * dist);
            boolean far = true;
            for (final Town tw : towns) {
                if (Math.hypot(cx - tw.cx, cz - tw.cz) < 150) {
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
            if (!coreIsDry(cx, cz) || contiguousLand(cx, cz) < SITE_MIN_LAND) {
                continue;   // un islote no da para un pueblo: hace falta tierra de verdad
            }
            return new int[] {cx, cz};
        }
        return null;
    }

    // --- ¿Da este sitio para un PUEBLO? (no fundar en un islote de cuatro bloques) ---

    private static final int SITE_RADIUS = 20;      // cuanto se mira alrededor del futuro centro
    private static final int SITE_MIN_LAND = 450;   // columnas de tierra SEGUIDA que hacen falta
    private static final int SITE_CORE = 5;         // el solar de la plaza, seco entero

    /** True si la columna es tierra (su bloque mas alto no es agua ni hielo). Una lectura por
     *  columna: no baja escaneando como {@link #groundY}. */
    private boolean dryColumn(int x, int z) {
        final int y = world.getHighestBlockYAt(x, z);
        final Block b = world.getBlockAt(x, y, z);
        final Material m = b.getType();
        return !b.isLiquid() && m != Material.ICE && m != Material.PACKED_ICE
                && m != Material.BLUE_ICE && m != Material.FROSTED_ICE;
    }

    /** El solar de la plaza (11x11) tiene que estar seco entero: ahi va el pozo, el arca y el panel. */
    private boolean coreIsDry(int cx, int cz) {
        for (int dx = -SITE_CORE; dx <= SITE_CORE; dx++) {
            for (int dz = -SITE_CORE; dz <= SITE_CORE; dz++) {
                if (!dryColumn(cx + dx, cz + dz)) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Cuantas columnas de TIERRA SEGUIDA hay alrededor del punto, con el agua cortando de verdad
     * (relleno por inundacion en 4 direcciones dentro de {@link #SITE_RADIUS}). Se corta al llegar
     * al minimo: si el sitio es un islote, se queda corto en cuatro pasos y no cuesta nada.
     *
     * <p>Antes solo se miraba <b>una columna</b> y por eso una aldea acabo fundada sobre un islote
     * diminuto rodeado de mar.
     */
    private int contiguousLand(int cx, int cz) {
        final java.util.Set<Long> seen = new java.util.HashSet<>();
        final java.util.ArrayDeque<int[]> pend = new java.util.ArrayDeque<>();
        pend.add(new int[] {cx, cz});
        seen.add((((long) cx) << 32) ^ (cz & 0xffffffffL));
        int land = 0;
        while (!pend.isEmpty() && land < SITE_MIN_LAND) {
            final int[] p = pend.poll();
            if (!dryColumn(p[0], p[1])) {
                continue;   // aqui corta el agua: por este lado no sigue la tierra
            }
            land++;
            for (final int[] d : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                final int nx = p[0] + d[0];
                final int nz = p[1] + d[1];
                if (Math.abs(nx - cx) > SITE_RADIUS || Math.abs(nz - cz) > SITE_RADIUS) {
                    continue;
                }
                final long key = (((long) nx) << 32) ^ (nz & 0xffffffffL);
                if (seen.add(key)) {
                    pend.add(new int[] {nx, nz});
                }
            }
        }
        return land;
    }

    /** El primer nombre de aldea libre de la lista curada; si se agotan, "Aldea N". */
    private String pickTownName() {
        final java.util.Set<String> used = new java.util.HashSet<>();
        for (final Town tw : towns) {
            used.add(tw.name);
        }
        for (final String n : TOWN_NAMES) {
            if (!used.contains(n)) {
                return n;
            }
        }
        return "Aldea " + (towns.size() + 1);
    }

    /** La aldea ya existente mas cercana a un punto (ignorando la de indice `skip`). */
    private Town nearestTown(int x, int z, int skip) {
        Town best = null;
        double bestD = Double.MAX_VALUE;
        for (int i = 0; i < towns.size(); i++) {
            if (i == skip) {
                continue;
            }
            final double d = Math.hypot(towns.get(i).cx - x, towns.get(i).cz - z);
            if (d < bestD) {
                bestD = d;
                best = towns.get(i);
            }
        }
        return best;
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
                            final Town t = new Town(f[0], Integer.parseInt(f[1]),
                                    Integer.parseInt(f[2]), Integer.parseInt(f[3]));
                            if (f.length >= 5 && !f[4].isEmpty()) {
                                t.pool = Double.parseDouble(f[4]);   // hucha de crecimiento
                            }
                            if (f.length >= 6 && !f[5].isEmpty()) {
                                t.splits = Integer.parseInt(f[5]);   // veces que ya se escindio
                            }
                            towns.add(t);
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
                w.write(t.name + ";" + t.cx + ";" + t.cz + ";" + t.baseY + ";" + t.pool + ";"
                        + t.splits + "\n");
            }
        } catch (Exception ex) {
            plugin.getLogger().warning("[Aetheria] no pude guardar aldeas: " + ex.getMessage());
        }
        pushState();
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

    /**
     * MANTENIMIENTO a cargo del ALBANIL (#11). Cada ciclo:
     * <ol>
     *   <li>Achica el agua que se haya colado en cualquier casa.</li>
     *   <li>Revisa UNA casa por turno (rotatorio, barato) y, si esta <b>dañada</b> (boquetes en
     *       el muro: creeper, incendio, un jugador cavando al lado), la <b>reconstruye</b> igual
     *       que estaba (misma huella y paleta) y le <b>renivela</b> el solar.</li>
     * </ol>
     * Solo se hace si la aldea TIENE un albanil vivo y en activo: si no hay cantero, las casas
     * se quedan rotas hasta que llegue uno. El pueblo se arregla con sus oficios, no por magia.
     */
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
        if (colonos.isEmpty()) {
            return;
        }
        repairCursor = (repairCursor + 1) % colonos.size();
        final Colono c = colonos.get(repairCursor);
        if (!c.dimsKnown || !hasMason(c.vid) || damage(c) < 3) {
            return;   // sin albanil, sin saber como era la casa, o esta entera: nada que hacer
        }
        final int fy = c.y - 1;
        final Material[] pal = COMBOS[Math.max(0, Math.min(c.pal, COMBOS.length - 1))];
        final BlockFace door = towardPlaza(townCenter(c.vid), c.x, c.z);
        prepareTerrain(c.x, c.z, fy);   // renivela el solar antes de levantarla otra vez
        Blueprint.buildHouse(world, c.x, c.z, fy, door, c.halfX, c.halfZ, 1, false,
                pal[0], pal[1], pal[2], pal[3], true, c.spouse != null ? 3 : 1,
                c.spouse != null ? c.name + " y " + c.spouse : c.name);
        deflood(c.x, fy, c.z, c.floors);
        final String quien = tradesman(Villager.Profession.MASON, "El albanil del pueblo");
        final String msg = quien + " ha reparado la casa de " + c.name + ".";
        gateway.postEvent("reparacion", msg);
        routines.pushGossip(msg);
        Bukkit.getOnlinePlayers().forEach(p -> p.sendMessage("§7[Pueblo] " + msg));
    }

    private int repairCursor = -1;

    /** True si esa aldea tiene un cantero/albanil vivo y no jubilado. */
    private boolean hasMason(int vid) {
        final String key = profKey(Villager.Profession.MASON);
        for (final Colono c : colonos) {
            if (c.vid == vid && !c.retired && key.equals(c.profKey)) {
                return true;
            }
        }
        return false;
    }

    /** Cuenta los BOQUETES del muro de una casa: huecos de aire (o agua) en el anillo perimetral
     *  a media altura, donde deberia haber pared, ventana o puerta. Barato: ~40 bloques. */
    private int damage(Colono c) {
        final int fy = c.y - 1;
        int holes = 0;
        for (int dx = -c.halfX; dx <= c.halfX; dx++) {
            for (int dz = -c.halfZ; dz <= c.halfZ; dz++) {
                if (Math.abs(dx) != c.halfX && Math.abs(dz) != c.halfZ) {
                    continue;   // solo el anillo del muro
                }
                for (int y = fy + 1; y <= fy + 2; y++) {
                    final Block b = world.getBlockAt(c.x + dx, y, c.z + dz);
                    if (b.getType().isAir() || b.isLiquid()) {
                        holes++;
                    }
                }
            }
        }
        return holes;
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
        if (p == TAVERN_KEEPER) return "tabernero";
        return "vecino";
    }
}
