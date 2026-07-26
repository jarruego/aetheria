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
        int floors = 1;   // plantas de su casa (para saber que region ocupa)

        double age(long now) {
            return initialAge + (now - bornMillis) * YEARS_PER_DAY / DAY_MS;
        }

        String toLine() {
            return name + ";" + profKey + ";" + x + ";" + y + ";" + z + ";" + bornMillis + ";"
                    + initialAge + ";" + deathAge + ";" + (parent == null ? "" : parent) + ";"
                    + retired + ";" + floors;
        }
    }

    private final List<Child> children = new ArrayList<>();
    private final List<int[]> placed = new ArrayList<>();   // (x,z) de las casas ya colocadas
    private final List<Colono> colonos = new ArrayList<>(); // colonos adultos (con edad), persistidos
    private final File dataFile;

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
            if (Math.hypot(x - p[0], z - p[1]) < 13) {
                return true;
            }
        }
        return false;
    }

    /**
     * Busca un sitio LLANO ya existente para una casa: prueba posiciones aleatorias alrededor
     * del pueblo (mas lejos conforme crece) y elige la mas plana sin agua. Devuelve {cx,cz,fy}.
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
            for (int dx = -4; dx <= 4 && !water; dx++) {
                for (int dz = -4; dz <= 4; dz++) {
                    final int gy = groundY(cx + dx, cz + dz);
                    if (world.getBlockAt(cx + dx, gy, cz + dz).isLiquid()) {
                        water = true;
                        break;
                    }
                    min = Math.min(min, gy);
                    max = Math.max(max, gy);
                    sum += gy;
                }
            }
            if (water) {
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
    }

    public void start() {
        world.getEntities().stream()   // limpia bebes huerfanos de sesiones anteriores
                .filter(e -> e.getScoreboardTags().contains(BABY_TAG))
                .forEach(org.bukkit.entity.Entity::remove);
        load();   // reaparecen los colonos ya existentes en sus casas (sin reconstruir)
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
                        new Location(world, c.x + 6 + 0.5, c.y, c.z + 0.5), profFromKey(c.profKey));
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

            final int population = json.get("population").getAsInt();
            final int targetExtra = Math.max(0, population - 3);
            final int adults = colonos.size();
            final int have = adults + children.size();
            if (have < targetExtra) {
                // Deficit grande = recuperar tras reinicio (adultos directos); +1 = nace un nino.
                if (targetExtra - have >= 2) {
                    final var rng = ThreadLocalRandom.current();
                    growAdult(colonos.size(), freshName(rng), 20 + rng.nextInt(40), "");
                } else {
                    bearChild();
                }
            } else if (have > targetExtra) {
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
        final String parentName = parent != null ? parent.name : "";
        children.add(new Child(baby, name, parentName, System.currentTimeMillis() + GROW_MS));
        final String of = parent != null ? ", hijo de " + parent.name : "";
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
        final int floors = 1 + rng.nextInt(2);
        final BlockFace door = towardPlaza(cx, cz);        // la puerta mira al pueblo

        prepareTerrain(cx, cz, fy);                        // tala arboles + nivela SUAVE al suelo real
        Blueprint.buildHouse(world, cx, cz, fy, door, 3, floors,
                pal[0], pal[1], pal[2], pal[3], true, name);
        professionFeature(cx, cz, fy, prof, rng);
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
        c.floors = floors;
        colonos.add(c);
        save();

        // Vive en su casa y TRABAJA en su propio puesto (el rasgo del oficio, al este):
        // asi cada uno esta en un sitio distinto y no se amontonan en la plaza.
        final Location home = new Location(world, cx + 0.5, fy + 1, cz + 0.5);
        final Location workspot = new Location(world, cx + 6 + 0.5, fy + 1, cz + 0.5);
        routines.addColono("colono", name, home, workspot, prof);
        Bukkit.getOnlinePlayers().forEach(p -> p.sendMessage(
                "§a[Pueblo] §f" + name + " §7(" + oficio(prof) + ") se ha instalado en el pueblo."));
        plugin.getLogger().info("[Aetheria] Pueblo vivo: +1 colono (" + name + ", " + prof + ").");
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
                demolish(c);   // su casa se derriba y queda un solar libre
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
        for (int dx = -5; dx <= 5; dx++) {
            for (int dz = -5; dz <= 5; dz++) {
                final int x = cx + dx;
                final int z = cz + dz;
                for (int y = fy + 1; y <= fy + 14; y++) {   // despeja/tala por encima
                    if (!world.getBlockAt(x, y, z).getType().isAir()) {
                        world.getBlockAt(x, y, z).setType(Material.AIR, false);
                    }
                }
                final int gy = groundY(x, z);
                if (gy < fy) {                               // rellena lo que falta (poco)
                    for (int y = gy + 1; y <= fy; y++) {
                        world.getBlockAt(x, y, z).setType(Material.DIRT, false);
                    }
                }
                world.getBlockAt(x, fy, z).setType(Material.GRASS_BLOCK, false);
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
            if (!colonos.isEmpty()) {
                demolish(colonos.remove(colonos.size() - 1));   // al emigrar, su casa se derriba
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
                    && b.getY() >= fy && b.getY() <= fy + c.floors * 4 + 6) {
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
        for (int dx = -6; dx <= 7; dx++) {
            for (int dz = -6; dz <= 6; dz++) {
                for (int y = fy + 1; y <= fy + c.floors * 4 + 7; y++) {
                    if (!world.getBlockAt(c.x + dx, y, c.z + dz).getType().isAir()) {
                        world.getBlockAt(c.x + dx, y, c.z + dz).setType(Material.AIR, false);
                    }
                }
                world.getBlockAt(c.x + dx, fy, c.z + dz).setType(Material.GRASS_BLOCK, false);
            }
        }
        placed.removeIf(p -> p[0] == c.x && p[1] == c.z);   // libera el hueco
    }

    /** Un pequeno rasgo junto a la casa segun el oficio (al este). Profession ya no es enum. */
    private void professionFeature(int cx, int cz, int fy, Villager.Profession prof, java.util.Random rng) {
        final int ex = cx + 6;
        if (prof == Villager.Profession.FARMER) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dz == 0) {
                        world.getBlockAt(ex, fy, cz).setType(Material.WATER, false);
                    } else {
                        world.getBlockAt(ex + dx, fy, cz + dz).setType(Material.FARMLAND, false);
                        world.getBlockAt(ex + dx, fy + 1, cz + dz).setType(Material.WHEAT, false);
                    }
                }
            }
            world.getBlockAt(ex + 2, fy + 1, cz).setType(Material.COMPOSTER, false);
        } else if (prof == Villager.Profession.FISHERMAN) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    world.getBlockAt(ex + dx, fy, cz + dz).setType(Material.WATER, false);
                }
            }
            world.getBlockAt(ex, fy + 1, cz - 2).setType(Material.BARREL, false);
        } else if (prof == Villager.Profession.SHEPHERD) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    final boolean edge = Math.abs(dx) == 1 || Math.abs(dz) == 1;
                    world.getBlockAt(ex + dx, fy + 1, cz + dz)
                            .setType(edge ? Material.OAK_FENCE : Material.WHITE_WOOL, false);
                }
            }
        } else if (prof == Villager.Profession.MASON) {
            world.getBlockAt(ex, fy + 1, cz).setType(Material.STONECUTTER, false);
            world.getBlockAt(ex + 1, fy + 1, cz).setType(Material.STONE, false);
            world.getBlockAt(ex + 1, fy + 2, cz).setType(Material.CHISELED_STONE_BRICKS, false);
        } else if (prof == Villager.Profession.LIBRARIAN) {
            world.getBlockAt(ex, fy + 1, cz).setType(Material.LECTERN, false);
            world.getBlockAt(ex + 1, fy + 1, cz).setType(Material.BOOKSHELF, false);
            world.getBlockAt(ex + 1, fy + 2, cz).setType(Material.BOOKSHELF, false);
        } else if (prof == Villager.Profession.TOOLSMITH) {
            world.getBlockAt(ex, fy + 1, cz).setType(Material.ANVIL, false);
            world.getBlockAt(ex + 1, fy + 1, cz).setType(Material.GRINDSTONE, false);
            world.getBlockAt(ex - 1, fy + 1, cz).setType(Material.FURNACE, false);
        } else if (prof == Villager.Profession.BUTCHER) {
            world.getBlockAt(ex, fy + 1, cz).setType(Material.SMOKER, false);
            world.getBlockAt(ex + 1, fy + 1, cz).setType(Material.BARREL, false);
        } else if (prof == Villager.Profession.FLETCHER) {
            world.getBlockAt(ex, fy + 1, cz).setType(Material.FLETCHING_TABLE, false);
            world.getBlockAt(ex + 1, fy + 1, cz).setType(Material.HAY_BLOCK, false);
        } else {
            world.getBlockAt(ex, fy + 1, cz).setType(Material.OAK_FENCE, false);
            world.getBlockAt(ex, fy + 2, cz).setType(Material.LANTERN, false);
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
