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
import org.bukkit.block.BlockFace;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Villager;

/**
 * Pueblo VIVO: reconcilia el mundo fisico con la poblacion objetivo de la simulacion (crece
 * cuando prospera, mengua cuando decae). Al crecer NIVELA el terreno, construye una casa
 * (con el nombre del colono en el cartel y un rasgo segun su oficio), la conecta con un
 * camino al pueblo, y llega un colono con su rutina. Al decaer, un colono emigra.
 */
public final class SettlementModule {

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

    /** Un nino del pueblo creciendo: su entidad bebe, su nombre y cuando se hara adulto. */
    private static final class Child {
        final Villager baby;
        final String name;
        final long matureAt;

        Child(Villager baby, String name, long matureAt) {
            this.baby = baby;
            this.name = name;
            this.matureAt = matureAt;
        }
    }

    private final List<Child> children = new ArrayList<>();
    private final List<int[]> placed = new ArrayList<>();   // (x,z) de las casas ya colocadas
    private final List<String> records = new ArrayList<>(); // colonos persistidos (nombre;oficio;x;y;z)
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
                + records.size() + " colonos cargados).");
    }

    /** Reaparece a los colonos guardados en sus casas (los bloques ya persisten en el mundo). */
    private void load() {
        if (!dataFile.exists()) {
            return;
        }
        try (BufferedReader r = new BufferedReader(new FileReader(dataFile))) {
            String line;
            while ((line = r.readLine()) != null) {
                final String[] f = line.split(";");
                if (f.length < 5) {
                    continue;
                }
                final String name = f[0];
                final Villager.Profession prof = profFromKey(f[1]);
                final int x = Integer.parseInt(f[2]);
                final int y = Integer.parseInt(f[3]);
                final int z = Integer.parseInt(f[4]);
                routines.addColono("colono", name, new Location(world, x + 0.5, y, z + 0.5),
                        village.plaza(), prof);
                placed.add(new int[] {x, z});
                records.add(line);
            }
        } catch (Exception e) {   // nunca hacemos caer el plugin por esto
            plugin.getLogger().warning("[Aetheria] no pude cargar colonos: " + e.getMessage());
        }
    }

    private void save() {
        try (FileWriter w = new FileWriter(dataFile, false)) {
            for (final String rec : records) {
                w.write(rec + "\n");
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

    private void reconcile() {
        gateway.getVillage().whenComplete((json, err) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (err != null || json == null) {
                return;
            }
            matureChildren();   // los ninos que ya han crecido se mudan a su casa

            final int population = json.get("population").getAsInt();
            final int targetExtra = Math.max(0, population - 3);
            final int adults = routines.colonoCount();
            final int have = adults + children.size();
            if (have < targetExtra) {
                // Deficit grande = recuperar tras reinicio (adultos directos); +1 = nace un nino.
                if (targetExtra - have >= 2) {
                    growAdult(adults, NAMES[ThreadLocalRandom.current().nextInt(NAMES.length)]);
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

    /** Nace un nino: un bebe aldeano cerca de la plaza que crecera con el tiempo. */
    private void bearChild() {
        final var rng = ThreadLocalRandom.current();
        final String name = NAMES[rng.nextInt(NAMES.length)];
        final Location plaza = village.plaza();
        final Location at = plaza.clone().add(rng.nextInt(5) - 2, 0, rng.nextInt(5) - 2);
        final Villager baby = (Villager) world.spawnEntity(at, EntityType.VILLAGER);
        baby.setBaby();
        baby.customName(net.kyori.adventure.text.Component.text("§b" + name + " §7(nino)"));
        baby.setCustomNameVisible(true);
        baby.setPersistent(true);
        baby.setRemoveWhenFarAway(false);
        baby.setInvulnerable(true);
        baby.addScoreboardTag(BABY_TAG);
        convo.registerConversable(baby, "nino", name);   // se puede hablar con los ninos
        children.add(new Child(baby, name, System.currentTimeMillis() + GROW_MS));
        Bukkit.getOnlinePlayers().forEach(p -> p.sendMessage(
                "§d[Pueblo] §fHa nacido §b" + name + "§f en el pueblo."));
        plugin.getLogger().info("[Aetheria] Pueblo vivo: nace un nino (" + name + ").");
    }

    /** Los ninos que han crecido se convierten en adultos con casa y oficio propios. */
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
            growAdult(routines.colonoCount(), c.name);
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

    private void growAdult(int index, String name) {
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
        records.add(name + ";" + profKey(prof) + ";" + cx + ";" + (fy + 1) + ";" + cz);
        save();

        final Location home = new Location(world, cx + 0.5, fy + 1, cz + 0.5);
        routines.addColono("colono", name, home, village.plaza(), prof);
        Bukkit.getOnlinePlayers().forEach(p -> p.sendMessage(
                "§a[Pueblo] §f" + name + " §7(" + oficio(prof) + ") se ha instalado en el pueblo."));
        plugin.getLogger().info("[Aetheria] Pueblo vivo: +1 colono (" + name + ", " + prof + ").");
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
            if (!records.isEmpty()) {
                records.remove(records.size() - 1);
            }
            if (!placed.isEmpty()) {
                placed.remove(placed.size() - 1);
            }
            save();
            Bukkit.getOnlinePlayers().forEach(p -> p.sendMessage(
                    "§7[Pueblo] " + name + " ha hecho las maletas y ha emigrado a otra tierra."));
            plugin.getLogger().info("[Aetheria] Pueblo vivo: -1 colono (" + name + " emigra).");
        }
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
