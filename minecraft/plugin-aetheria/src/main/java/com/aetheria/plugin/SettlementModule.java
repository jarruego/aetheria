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
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;

/**
 * Pueblo VIVO: reconcilia el mundo fisico con la poblacion objetivo de la simulacion (crece
 * cuando prospera, mengua cuando decae). Al crecer NIVELA el terreno, construye una casa
 * (con el nombre del colono en el cartel y un rasgo segun su oficio), la conecta con un
 * camino al pueblo, y llega un colono con su rutina. Al decaer, un colono emigra.
 */
public final class SettlementModule implements Listener {

    private static final long PERIOD = 1200L;   // reconcilia cada 60 s (una casa por vez)
    private static final String[] NAMES = {"Bruno", "Lena", "Tobias", "Mila", "Ada", "Iker",
        "Noa", "Gala", "Hugo", "Vera", "Leo", "Sol", "Dario", "Enara", "Cloe", "Nil"};
    private static final Villager.Profession[] PROFS = {Villager.Profession.FARMER,
        Villager.Profession.FISHERMAN, Villager.Profession.SHEPHERD, Villager.Profession.MASON,
        Villager.Profession.LIBRARIAN, Villager.Profession.TOOLSMITH, Villager.Profession.BUTCHER,
        Villager.Profession.FLETCHER};
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
        final long matureAt;

        Child(Villager baby, String name, String parent, long matureAt) {
            this.baby = baby;
            this.name = name;
            this.parent = parent;
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

        double age(long now) {
            return initialAge + (now - bornMillis) * YEARS_PER_DAY / DAY_MS;
        }

        String toLine() {
            return name + ";" + profKey + ";" + x + ";" + y + ";" + z + ";" + bornMillis + ";"
                    + initialAge + ";" + deathAge + ";" + (parent == null ? "" : parent) + ";"
                    + retired + ";" + floors + ";" + (spouse == null ? "" : spouse);
        }
    }

    private final List<Child> children = new ArrayList<>();
    private final List<int[]> placed = new ArrayList<>();   // (x,z) de las casas ya colocadas
    private final List<Colono> colonos = new ArrayList<>(); // colonos adultos (con edad), persistidos
    private final File dataFile;
    private final File civicFile;

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

    /** True si (x,z) esta demasiado cerca de otra casa o del centro del pueblo. */
    private boolean tooClose(int x, int z) {
        final Location plaza = village.plaza();
        if (Math.hypot(x - plaza.getX(), z - plaza.getZ()) < 16) {
            return true;
        }
        for (final int[] p : placed) {
            if (Math.hypot(x - p[0], z - p[1]) < 18) {   // deja sitio a casa + puesto de trabajo
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
     * Busca un sitio LLANO ya existente para una casa: prueba posiciones aleatorias alrededor
     * del pueblo (mas lejos conforme crece), plano, SIN AGUA y SIN construcciones (no se pisa
     * lo que haya edificado un jugador). Devuelve {cx,cz,fy} o null.
     */
    private int[] findBuildSpot(int index) {
        final var rng = ThreadLocalRandom.current();
        final Location plaza = village.plaza();
        final int px = plaza.getBlockX();
        final int pz = plaza.getBlockZ();
        int[] best = null;
        int bestFlat = Integer.MAX_VALUE;
        for (int t = 0; t < 24; t++) {
            final double ang = rng.nextDouble() * Math.PI * 2;
            final int dist = 20 + index * 2 + rng.nextInt(34);
            final int cx = px + (int) Math.round(Math.cos(ang) * dist);
            final int cz = pz + (int) Math.round(Math.sin(ang) * dist);
            if (tooClose(cx, cz)) {
                continue;
            }
            int min = Integer.MAX_VALUE;
            int max = Integer.MIN_VALUE;
            long sum = 0;
            boolean water = false;
            boolean built = false;   // hay bloques construidos por alguien: no se pisa
            for (int dx = -5; dx <= 5 && !water && !built; dx++) {
                for (int dz = -5; dz <= 5; dz++) {
                    final int gy = groundY(cx + dx, cz + dz);
                    final Block g = world.getBlockAt(cx + dx, gy, cz + dz);
                    if (g.isLiquid()) {
                        water = true;
                        break;
                    }
                    if (!natural(g.getType())) {
                        built = true;   // el propio suelo es algo puesto por alguien
                        break;
                    }
                    for (int y = gy + 1; y <= gy + 7; y++) {   // ¿construccion sobre el suelo?
                        final Material above = world.getBlockAt(cx + dx, y, cz + dz).getType();
                        if (!above.isAir() && !natural(above)) {
                            built = true;
                            break;
                        }
                    }
                    if (built) {
                        break;
                    }
                    if (dx >= -4 && dx <= 4 && dz >= -4 && dz <= 4) {
                        min = Math.min(min, gy);
                        max = Math.max(max, gy);
                        sum += gy;
                    }
                }
            }
            if (water || built) {
                continue;
            }
            final int flat = max - min;
            final int fy = Math.round(sum / 81f);
            if (flat <= 2) {
                return new int[] {cx, cz, fy};   // sitio ya casi llano: perfecto
            }
            if (flat < bestFlat) {
                bestFlat = flat;
                best = new int[] {cx, cz, fy};
            }
        }
        return best;   // el mas llano encontrado (o null si todo estaba pegado)
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
    }

    public void start() {
        world.getEntities().stream()   // limpia bebes huerfanos de sesiones anteriores
                .filter(e -> e.getScoreboardTags().contains(BABY_TAG))
                .forEach(org.bukkit.entity.Entity::remove);
        final boolean fresh = !dataFile.exists();
        load();   // reaparecen los colonos ya existentes en sus casas (sin reconstruir)
        loadCivic();
        if (fresh && colonos.isEmpty()) {
            // Mundo NUEVO: dos aldeanos fundadores, cada uno con su casa pequena y su puesto.
            final var rng = ThreadLocalRandom.current();
            growAdult(0, freshName(rng), 22 + rng.nextInt(30), "");
            growAdult(1, freshName(rng), 22 + rng.nextInt(30), "");
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
                } else {   // formato antiguo: se le asigna una edad plausible
                    c.bornMillis = System.currentTimeMillis();
                    c.initialAge = 20 + rng.nextInt(40);
                    c.deathAge = randomDeathAge(rng);
                    c.parent = "";
                }
                // Corrige nombres duplicados heredados de versiones antiguas (p.ej. tres "Tobias").
                for (final Colono other : colonos) {
                    if (other.name.equals(c.name)) {
                        c.name = freshName(rng);
                        renamed = true;
                        break;
                    }
                }
                routines.addColono("colono", c.name, new Location(world, c.x + 0.5, c.y, c.z + 0.5),
                        new Location(world, c.x + 9 + 0.5, c.y, c.z + 0.5), profFromKey(c.profKey));
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

    private static Villager.Profession profFromKey(String key) {
        for (final Villager.Profession p : PROFS) {
            if (p.getKey().getKey().equals(key)) {
                return p;
            }
        }
        return Villager.Profession.FARMER;
    }

    /** Un nombre que NO este ya en uso por otro colono, nino o vecino del nucleo. */
    private String freshName(java.util.Random rng) {
        final java.util.Set<String> used = new java.util.HashSet<>();
        for (final Colono c : colonos) {
            used.add(c.name);
        }
        for (final Child ch : children) {
            used.add(ch.name);
        }
        used.add("Nara");
        used.add("Pol");
        used.add("Sella");
        final List<String> free = new ArrayList<>();
        for (final String n : NAMES) {
            if (!used.contains(n)) {
                free.add(n);
            }
        }
        if (!free.isEmpty()) {
            return free.get(rng.nextInt(free.size()));
        }
        // Todos los nombres en uso: genera una variante unica ("Tobias II", "Tobias III"...).
        final String base = NAMES[rng.nextInt(NAMES.length)];
        for (int i = 2; ; i++) {
            final String cand = base + " " + roman(i);
            if (!used.contains(cand)) {
                return cand;
            }
        }
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
            updateBios();       // refresca su ficha (edad/oficio/familia) para que hablen de si

            // Todos los aldeanos son colonos (no hay vecinos "base"): el objetivo es la poblacion.
            final int target = Math.max(2, json.get("population").getAsInt());
            final int adults = colonos.size();
            final int have = adults + children.size();
            if (have < target) {
                final var rng = ThreadLocalRandom.current();
                // Hasta tener 2 adultos fundadores, llegan adultos directos; luego, nacen ninos.
                if (adults < 2 || target - have >= 2) {
                    growAdult(colonos.size(), freshName(rng), 20 + rng.nextInt(40), "");
                } else {
                    bearChild();
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
        }));
    }

    /** Nace un nino de un padre/madre del pueblo; jugara cerca de su casa y crecera con el tiempo. */
    private void bearChild() {
        final var rng = ThreadLocalRandom.current();
        final String name = freshName(rng);
        // Padre/madre: un colono adulto no jubilado, si lo hay.
        final List<Colono> adults = new ArrayList<>();
        for (final Colono c : colonos) {
            if (!c.retired) {
                adults.add(c);
            }
        }
        final Colono parent = adults.isEmpty() ? null : adults.get(rng.nextInt(adults.size()));
        // Aparece junto a la casa de su familia (o en la plaza si no hay familia).
        final Location base = parent != null
                ? new Location(world, parent.x + 0.5, parent.y, parent.z + 2.5)
                : village.plaza();
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
        String childOf = "";
        if (parent != null) {
            childOf = ", hijo de " + parent.name;
            if (parent.spouse != null && !parent.spouse.isEmpty()) {
                childOf += " y " + parent.spouse;
            }
        }
        convo.setBio(name, "Eres " + name + ", un nino pequeno del pueblo de Aetheria" + childOf
                + ". Todavia no trabajas; hablas con la inocencia de un nino.");
        final String parentName = parent != null ? parent.name : "";
        children.add(new Child(baby, name, parentName, System.currentTimeMillis() + GROW_MS));
        final String of = childOf;
        Bukkit.getOnlinePlayers().forEach(p -> p.sendMessage(
                "§d[Pueblo] §fHa nacido §b" + name + "§f" + of + " en el pueblo."));
        gateway.postEvent("nacimiento", "Ha nacido " + name + of + " en el pueblo.");
        plugin.getLogger().info("[Aetheria] Pueblo vivo: nace un nino (" + name + ").");
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
            growAdult(colonos.size(), c.name, WORK_AGE, c.parent);
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
        Bukkit.getOnlinePlayers().forEach(p -> p.sendMessage(
                "§7[Pueblo] La prosperidad se nota: el pueblo ha mejorado la plaza."));
        gateway.postEvent("mejora", "El pueblo, prospero, ha embellecido su plaza.");
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

    private void growAdult(int index, String name, double initialAge, String parent) {
        final int[] spot = findBuildSpot(index);
        if (spot == null) {
            return;   // no encontro sitio libre; lo reintenta el proximo ciclo
        }
        final int cx = spot[0];
        final int cz = spot[1];
        final int fy = spot[2];
        final var rng = ThreadLocalRandom.current();
        final Villager.Profession prof = PROFS[rng.nextInt(PROFS.length)];
        final Material[] pal = COMBOS[rng.nextInt(COMBOS.length)];
        // Un aldeano SOLTERO vive en una casa PEQUENA (una sola cama). Nada de mansiones para
        // uno solo: al casarse ya se le construye una mediana (ver maybeMarry).
        final int halfX = 3;
        final int halfZ = rng.nextInt(100) < 30 ? 4 : 3;   // a veces algo mas larga, sin agigantar
        final BlockFace door = towardPlaza(cx, cz);        // la puerta mira al pueblo

        prepareTerrain(cx, cz, fy);                        // tala arboles + nivela SUAVE al suelo real
        Blueprint.buildHouse(world, cx, cz, fy, door, halfX, halfZ, 1, false,
                pal[0], pal[1], pal[2], pal[3], true, 1, name);   // 1 cama (soltero)
        final int wy = buildWorkplace(cx, cz, prof);       // puesto de trabajo tematico, al este
        pathTo(cx, cz, village.plaza());                   // sendero que sigue el relieve al pueblo
        placed.add(new int[] {cx, cz});

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
        colonos.add(c);
        save();

        // Vive en su casa y TRABAJA en su propio puesto (el rasgo del oficio, al este):
        // asi cada uno esta en un sitio distinto y no se amontonan en la plaza.
        final Location home = new Location(world, cx + 0.5, fy + 1, cz + 0.5);
        final Location workspot = new Location(world, cx + 9 + 0.5, wy + 1, cz + 0.5);
        routines.addColono("colono", name, home, workspot, prof);
        Bukkit.getOnlinePlayers().forEach(p -> p.sendMessage(
                "§a[Pueblo] §f" + name + " §7(" + oficio(prof) + ") se ha instalado en el pueblo."));
        plugin.getLogger().info("[Aetheria] Pueblo vivo: +1 colono (" + name + ", " + prof + ").");
    }

    private boolean compatible(Colono a, Colono b) {
        if (a == b) {
            return false;
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
        for (int i = 0; i < singles.size() && a == null; i++) {
            for (int j = i + 1; j < singles.size(); j++) {
                if (compatible(singles.get(i), singles.get(j))) {
                    a = singles.get(i);
                    b = singles.get(j);
                    break;
                }
            }
        }
        if (a == null) {
            return;
        }
        final int[] spot = findBuildSpot(colonos.size() + 2);
        if (spot == null) {
            return;   // sin sitio libre ahora; se reintenta el proximo ciclo
        }
        final int cx = spot[0];
        final int cz = spot[1];
        final int fy = spot[2];
        final Material[] pal = COMBOS[rng.nextInt(COMBOS.length)];
        final int halfX = 4;
        final int halfZ = rng.nextInt(100) < 50 ? 4 : 5;   // MEDIANA (mayor que la de soltero)
        final BlockFace door = towardPlaza(cx, cz);
        prepareTerrain(cx, cz, fy);
        Blueprint.buildHouse(world, cx, cz, fy, door, halfX, halfZ, 1, false,
                pal[0], pal[1], pal[2], pal[3], true, 3, a.name + " y " + b.name);   // 3 camas
        final int wy = buildWorkplace(cx, cz, profFromKey(a.profKey));   // taller familiar
        pathTo(cx, cz, village.plaza());

        demolish(a);   // sus dos casas pequenas (y puestos) se derriban y liberan solar
        demolish(b);

        a.x = cx;  a.y = fy + 1;  a.z = cz;  a.floors = 1;  a.spouse = b.name;
        b.x = cx;  b.y = fy + 1;  b.z = cz;  b.floors = 1;  b.spouse = a.name;
        placed.add(new int[] {cx, cz});
        save();

        final Location home = new Location(world, cx + 0.5, fy + 1, cz + 0.5);
        final Location workspot = new Location(world, cx + 9 + 0.5, wy + 1, cz + 0.5);
        routines.setHomeWork(a.name, home, workspot);
        routines.setHomeWork(b.name, home, workspot);

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
                final Colono heir = pickSuccessor(c);
                final String successor;
                if (heir != null) {
                    heir.profKey = c.profKey;   // hereda el puesto del fallecido
                    routines.setProfession(heir.name, profFromKey(c.profKey));
                    successor = heir.name;
                } else {
                    successor = "nadie de momento";
                }
                final String family = livingChildren(c);
                final String msg = String.format(
                        "Ha fallecido %s, %s, a los %d anos. %sLe releva %s en su oficio.",
                        c.name, oficio(profFromKey(c.profKey)), (int) age,
                        family.isEmpty() ? "" : "Le sobreviven " + family + ". ", successor);
                gateway.postEvent("obituario", msg);
                Bukkit.getOnlinePlayers().forEach(p -> p.sendMessage("§8[Pueblo] §7" + msg));
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

    /** Un colono (preferiblemente joven y no jubilado) que hereda el oficio del fallecido. */
    private Colono pickSuccessor(Colono dead) {
        Colono best = null;
        for (final Colono c : colonos) {
            if (c == dead || c.retired) {
                continue;
            }
            if (best == null || c.initialAge < best.initialAge) {
                best = c;
            }
        }
        return best;
    }

    /** Refresca la ficha (edad, oficio, familia) de cada colono para que hable de si mismo. */
    private void updateBios() {
        final long now = System.currentTimeMillis();
        for (final Colono c : colonos) {
            final int age = (int) c.age(now);
            final String job = c.retired
                    ? "jubilado (antes fue " + oficio(profFromKey(c.profKey)) + ")"
                    : oficio(profFromKey(c.profKey));
            final StringBuilder fam = new StringBuilder();
            if (c.spouse != null && !c.spouse.isEmpty()) {
                fam.append(" Estas casado con ").append(c.spouse).append(".");
            }
            if (c.parent != null && !c.parent.isEmpty()) {
                fam.append(" Tu padre o madre es ").append(c.parent).append(".");
            }
            final String kids = livingChildren(c);   // "sus hijos X, Y" o ""
            if (!kids.isEmpty()) {
                fam.append(" Tus hijos son ").append(kids.replace("sus hijos ", "")).append(".");
            }
            final String bio = "Eres " + c.name + ", vecino del pueblo de Aetheria. Tienes " + age
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

    private BlockFace towardPlaza(int cx, int cz) {
        final Location plaza = village.plaza();
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
            if (b.getX() >= c.x - 6 && b.getX() <= c.x + 12 && b.getZ() >= c.z - 6 && b.getZ() <= c.z + 6
                    && b.getY() >= fy && b.getY() <= fy + c.floors * 6 + 8) {
                return c;
            }
        }
        return null;
    }

    /** True si el bloque esta en el nucleo del pueblo (casas base, mercado, taberna, plaza). */
    private boolean inVillageCore(Block b) {
        final int sx = village.spawnX();
        final int sz = village.spawnZ();
        final int by = village.baseY();
        return b.getX() >= sx - 22 && b.getX() <= sx + 22 && b.getZ() >= sz + 8 && b.getZ() <= sz + 34
                && b.getY() >= by - 1 && b.getY() <= by + 12;
    }

    private boolean protect(Player player, Block b) {
        final Colono c = ownerAt(b);
        if (c != null) {
            player.sendMessage("§cEsta es la casa de §f" + c.name + "§c. Todavia vive en la aldea: "
                    + "no puedes destruir ni coger nada suyo.");
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

    /** Demuele la casa de un colono (al morir o emigrar): la retira y deja un solar de cesped. */
    private void demolish(Colono c) {
        final int fy = c.y - 1;
        for (int dx = -6; dx <= 12; dx++) {   // cubre la casa (±6) y el puesto de trabajo (al este)
            for (int dz = -6; dz <= 6; dz++) {
                for (int y = fy; y <= fy + c.floors * 6 + 8; y++) {
                    if (!world.getBlockAt(c.x + dx, y, c.z + dz).getType().isAir()) {
                        world.getBlockAt(c.x + dx, y, c.z + dz).setType(Material.AIR, false);
                    }
                }
                if (dx >= -6 && dx <= 6) {     // solo el solar de la casa queda con cesped
                    world.getBlockAt(c.x + dx, fy, c.z + dz).setType(Material.GRASS_BLOCK, false);
                }
            }
        }
        placed.removeIf(p -> p[0] == c.x && p[1] == c.z);   // libera el hueco
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
