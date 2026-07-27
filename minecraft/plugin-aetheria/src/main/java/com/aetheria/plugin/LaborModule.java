package com.aetheria.plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.Ageable;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Sheep;

/**
 * #11 - Los aldeanos TRABAJAN DE VERDAD, y de ese trabajo vive el pueblo.
 *
 * <p>Hasta ahora la economia era un numero: el backend sorteaba unos ingresos por tick y los
 * aldeanos solo caminaban de casa al puesto. Aqui el trabajo pasa a ser <b>fisico</b>: el
 * granjero siega una espiga madura y la <b>replanta</b>, el leñador tala un tronco y deja un
 * <b>brote</b>, el cantero pica piedra en su cantera, el herrero <b>funde</b> lo que el cantero
 * saco, el pastor esquila una oveja de verdad... Cada faena deposita su genero en el
 * <b>granero</b> de la aldea, engorda el <b>peculio del propio colono</b> y se manda por lotes
 * al backend ({@code /v1/production}), que la convierte en ingresos del sector. Si el pueblo
 * deja de trabajar, la economia decae sola.
 *
 * <p><b>Coste acotado a proposito.</b> No se simula a veinte aldeanos bloque a bloque: cada
 * ciclo (3 s) se atiende a unos pocos por turno rotatorio (uno por cada seis vecinos), solo de
 * dia, y cada faena mira un entorno pequeño alrededor del aldeano. Son acciones VISIBLES de vez
 * en cuando, no una simulacion completa. Si el aldeano esta en un trozo de mundo descargado
 * (nadie cerca), no se toca ningun bloque: solo cuenta su jornada a media produccion.
 */
public final class LaborModule {

    private static final long PERIOD = 60L;          // una tanda de faenas cada 3 s
    private static final long REPORT_PERIOD = 1200L; // se reporta la produccion al backend cada 60 s
    private static final int SCAN_R = 6;             // radio de busqueda de la faena (barato)
    private static final int SCAN_Y = 3;

    /** Datos minimos de un colono en activo para trabajar (instantanea del SettlementModule). */
    public static final class Laborer {
        final String name;
        final String profKey;
        final int vid;

        public Laborer(String name, String profKey, int vid) {
            this.name = name;
            this.profKey = profKey;
            this.vid = vid;
        }
    }

    /** Resultado de una faena: que se ha producido y cuanto vale. */
    private static final class Yield {
        final Material good;
        final double value;
        final String verb;

        Yield(Material good, double value, String verb) {
            this.good = good;
            this.value = value;
            this.verb = verb;
        }
    }

    private final AetheriaPlugin plugin;
    private final World world;
    private final GatewayClient gateway;
    private final NpcRoutineModule routines;
    private SettlementModule settlement;

    private int cursor = 0;
    // Produccion acumulada por sector desde el ultimo reporte (AET y descripcion legible).
    private final java.util.Map<String, double[]> pending = new java.util.HashMap<>();
    private final java.util.Map<String, java.util.Map<Material, Integer>> pendingGoods =
            new java.util.HashMap<>();

    public LaborModule(AetheriaPlugin plugin, World world, GatewayClient gateway,
            NpcRoutineModule routines) {
        this.plugin = plugin;
        this.world = world;
        this.gateway = gateway;
        this.routines = routines;
    }

    public void start(SettlementModule settlement) {
        this.settlement = settlement;
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, PERIOD, PERIOD);
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::report, REPORT_PERIOD,
                REPORT_PERIOD);
        plugin.getLogger().info("[Aetheria] #11: los aldeanos trabajan fisicamente (produccion real).");
    }

    /** Una tanda de faenas: solo de dia y solo unos pocos vecinos por turno (coste acotado). */
    private void tick() {
        if (settlement == null || world.getTime() >= 12000L) {
            return;   // de noche no se trabaja (coincide con la rutina: taberna y cama)
        }
        final List<Laborer> all = settlement.activeLaborers();
        if (all.isEmpty()) {
            return;
        }
        final int batch = Math.min(8, all.size() / 6 + 1);   // ~1 faena por vecino cada 15-24 s
        for (int i = 0; i < batch; i++) {
            cursor = (cursor + 1) % all.size();
            workOne(all.get(cursor));
        }
    }

    /** La jornada de UN colono: intenta la faena de su oficio donde este ahora mismo. */
    private void workOne(Laborer lab) {
        final Mob npc = routines.entityOf(lab.name);
        if (npc == null || npc.isDead() || !npc.isValid()) {
            // Sin nadie cerca (trozo de mundo descargado): no se tocan bloques, pero su jornada
            // cuenta a media produccion. La economia no se para porque no haya publico.
            credit(lab, baseValue(lab.profKey) * 0.5, null, 0);
            return;
        }
        final Location at = npc.getLocation();
        final Yield y = doWork(lab, at);
        if (y == null) {
            credit(lab, baseValue(lab.profKey) * 0.5, null, 0);   // hoy no encontro faena
            return;
        }
        npc.swingMainHand();
        final int left = settlement.depositInGranary(lab.vid, y.good, 1);
        // Lo que no cabe en el granero se vende fuera: excedente al sector comercio.
        credit(lab, y.value, y.good, left > 0 ? 1 : 0);
    }

    /** Ejecuta la faena propia del oficio. Devuelve lo producido, o null si no habia donde. */
    private Yield doWork(Laborer lab, Location at) {
        return switch (lab.profKey) {
            case "farmer" -> harvest(at);
            case "fletcher" -> chopTree(at);
            case "mason" -> quarry(at);
            case "toolsmith" -> smelt(lab, at);
            case "shepherd" -> shear(at);
            case "fisherman" -> fish(at);
            case "butcher" -> smoke(lab, at);
            case "librarian" -> copyBook(at);
            case "leatherworker" -> serve(lab, at);
            default -> null;
        };
    }

    // --- Faenas ---

    /** GRANJERO: siega una espiga MADURA y la vuelve a plantar (age 0). Si no hay nada maduro,
     *  siembra en la tierra de labor que este vacia. */
    private Yield harvest(Location at) {
        final Block crop = find(at, b -> b.getBlockData() instanceof Ageable a
                && a.getAge() == a.getMaximumAge() && isCrop(b.getType()));
        if (crop != null) {
            final Material type = crop.getType();
            final Ageable data = (Ageable) crop.getBlockData();
            data.setAge(0);
            crop.setBlockData(data, false);          // cosechado y REPLANTADO en el acto
            effect(crop.getLocation(), Particle.HAPPY_VILLAGER, Sound.BLOCK_CROP_BREAK);
            return new Yield(cropGood(type), 1.3, "siega");
        }
        final Block empty = find(at, b -> b.getType() == Material.FARMLAND
                && b.getRelative(0, 1, 0).getType().isAir());
        if (empty != null) {
            empty.getRelative(0, 1, 0).setType(Material.WHEAT, false);
            effect(empty.getLocation(), Particle.HAPPY_VILLAGER, Sound.ITEM_CROP_PLANT);
            return new Yield(Material.WHEAT_SEEDS, 0.8, "siembra");
        }
        return null;
    }

    /** ARQUERO/leñador: tala un tronco (nunca de una casa) y deja un BROTE para que vuelva a
     *  crecer el arbol. Tala de abajo arriba, hasta 3 bloques: no arrasa el bosque. */
    private Yield chopTree(Location at) {
        final Block log = find(at, b -> Tag.LOGS.isTagged(b.getType())
                && !settlement.isVillageBuilt(b));
        if (log == null) {
            return null;
        }
        Block top = log;
        int cut = 0;
        while (cut < 3 && Tag.LOGS.isTagged(top.getType())) {
            final Block next = top.getRelative(0, 1, 0);
            top.setType(Material.AIR, false);
            cut++;
            top = next;
        }
        effect(log.getLocation(), Particle.CRIT, Sound.BLOCK_WOOD_BREAK);
        // Repone: si el suelo quedo libre, planta un brote donde estaba el tronco.
        final Block soil = log.getRelative(0, -1, 0);
        if (Tag.DIRT.isTagged(soil.getType()) && log.getType().isAir()) {
            log.setType(saplingFor(soil), false);
        }
        return new Yield(Material.OAK_LOG, 1.1, "tala");
    }

    /** CANTERO: pica piedra en la cantera junto a su taller. Solo terreno natural y como mucho
     *  4 bloques por debajo del suelo: queda un hoyo de cantera, no un socavon sin fin. */
    private Yield quarry(Location at) {
        final int floor = at.getBlockY();
        final Block stone = find(at, b -> b.getY() < floor && b.getY() >= floor - 4
                && isRock(b.getType()) && !settlement.isVillageBuilt(b)
                && b.getRelative(0, 1, 0).getType().isAir());
        if (stone == null) {
            return null;
        }
        final Material rock = stone.getType();
        stone.setType(Material.AIR, false);
        effect(stone.getLocation(), Particle.CLOUD, Sound.BLOCK_STONE_BREAK);
        return new Yield(rock.name().endsWith("_ORE") ? Material.RAW_IRON : Material.COBBLESTONE,
                rock.name().endsWith("_ORE") ? 2.2 : 1.0, "cantera");
    }

    /** HERRERO: funde en su fragua lo que el cantero saco. CONSUME material del granero: sin
     *  piedra ni mineral no hay lingote (la cadena de oficios se nota). */
    private Yield smelt(Laborer lab, Location at) {
        final Block furnace = find(at, b -> b.getType() == Material.FURNACE
                || b.getType() == Material.BLAST_FURNACE);
        if (furnace == null) {
            return null;
        }
        final Material raw = settlement.takeFromGranary(lab.vid,
                new Material[] {Material.RAW_IRON, Material.COBBLESTONE, Material.STONE});
        if (raw == null) {
            return null;   // sin materia prima, el herrero espera al cantero
        }
        effect(furnace.getLocation().add(0.5, 1, 0.5), Particle.FLAME, Sound.BLOCK_ANVIL_USE);
        return raw == Material.RAW_IRON
                ? new Yield(Material.IRON_INGOT, 2.4, "fundicion")
                : new Yield(Material.STONE_BRICKS, 1.4, "labra");
    }

    /** PASTOR: esquila una oveja DE VERDAD (si la hay cerca); si no, no hay lana. */
    private Yield shear(Location at) {
        for (final org.bukkit.entity.Entity e : world.getNearbyEntities(at, 8, 4, 8)) {
            if (e instanceof Sheep s && !s.isSheared() && !s.isDead()) {
                s.setSheared(true);
                effect(s.getLocation(), Particle.CLOUD, Sound.ENTITY_SHEEP_SHEAR);
                return new Yield(woolOf(s), 1.5, "esquileo");
            }
        }
        return null;
    }

    /** PESCADOR: echa el sedal al agua que tenga a mano. */
    private Yield fish(Location at) {
        final Block water = find(at, b -> b.getType() == Material.WATER);
        if (water == null) {
            return null;
        }
        effect(water.getLocation().add(0.5, 1, 0.5), Particle.SPLASH,
                Sound.ENTITY_FISHING_BOBBER_SPLASH);
        return ThreadLocalRandom.current().nextInt(100) < 25
                ? new Yield(Material.SALMON, 1.6, "pesca")
                : new Yield(Material.COD, 1.2, "pesca");
    }

    /** CARNICERO: ahuma en su horno lo que haya en el granero (carne cruda). */
    private Yield smoke(Laborer lab, Location at) {
        final Block smoker = find(at, b -> b.getType() == Material.SMOKER
                || b.getType() == Material.CAMPFIRE);
        if (smoker == null) {
            return null;
        }
        final Material raw = settlement.takeFromGranary(lab.vid,
                new Material[] {Material.BEEF, Material.PORKCHOP, Material.CHICKEN});
        effect(smoker.getLocation().add(0.5, 1, 0.5), Particle.SMOKE, Sound.BLOCK_FURNACE_FIRE_CRACKLE);
        if (raw == null) {
            return new Yield(Material.BEEF, 1.0, "matanza");   // sale a por genero
        }
        return new Yield(cooked(raw), 1.8, "ahumado");
    }

    /** TABERNERO: sirve en la barra. Convierte el genero del granero (trigo, carne, pescado) en
     *  comida y bebida para el pueblo; es puro COMERCIO, el oficio que mueve el dinero. */
    private Yield serve(Laborer lab, Location at) {
        final Block bar = find(at, b -> b.getType() == Material.BARREL
                || b.getType() == Material.BREWING_STAND);
        if (bar == null) {
            return null;   // fuera de la taberna no sirve a nadie
        }
        effect(bar.getLocation().add(0.5, 1, 0.5), Particle.HAPPY_VILLAGER, Sound.ENTITY_VILLAGER_YES);
        final Material raw = settlement.takeFromGranary(lab.vid,
                new Material[] {Material.WHEAT, Material.COD, Material.COOKED_BEEF, Material.CARROT});
        if (raw == null) {
            return new Yield(Material.POTION, 1.0, "ronda");   // solo bebida: la despensa esta seca
        }
        return raw == Material.WHEAT
                ? new Yield(Material.BREAD, 2.0, "cocina")
                : new Yield(Material.COOKED_COD, 1.8, "cocina");
    }

    /** BIBLIOTECARIO: copia un libro en su atril. */
    private Yield copyBook(Location at) {
        final Block lectern = find(at, b -> b.getType() == Material.LECTERN
                || b.getType() == Material.BOOKSHELF);
        if (lectern == null) {
            return null;
        }
        effect(lectern.getLocation().add(0.5, 1, 0.5), Particle.ENCHANT, Sound.ITEM_BOOK_PAGE_TURN);
        return new Yield(Material.BOOK, 1.5, "copia");
    }

    // --- Utilidades ---

    /** Busca el bloque MAS CERCANO al aldeano que cumpla la condicion, en un entorno pequeño
     *  (radio 6 y +-3 en altura): unos cientos de bloques, no miles. */
    private Block find(Location at, java.util.function.Predicate<Block> ok) {
        final int cx = at.getBlockX();
        final int cy = at.getBlockY();
        final int cz = at.getBlockZ();
        Block best = null;
        int bestD = Integer.MAX_VALUE;
        for (int dx = -SCAN_R; dx <= SCAN_R; dx++) {
            for (int dz = -SCAN_R; dz <= SCAN_R; dz++) {
                for (int dy = -SCAN_Y; dy <= SCAN_Y; dy++) {
                    final int d = dx * dx + dz * dz + dy * dy;
                    if (d >= bestD) {
                        continue;
                    }
                    final Block b = world.getBlockAt(cx + dx, cy + dy, cz + dz);
                    if (ok.test(b)) {
                        best = b;
                        bestD = d;
                    }
                }
            }
        }
        return best;
    }

    private void effect(Location loc, Particle particle, Sound sound) {
        world.spawnParticle(particle, loc.clone().add(0.5, 0.6, 0.5), 8, 0.3, 0.3, 0.3, 0.01);
        world.playSound(loc, sound, 0.7f, 1.0f);
    }

    /** Apunta el valor producido: parte al PECULIO del colono y el resto al sector economico. */
    private void credit(Laborer lab, double value, Material good, int surplus) {
        if (value <= 0) {
            return;
        }
        settlement.addWealth(lab.name, value * 0.45);   // lo que se queda el aldeano
        final String sector = surplus > 0 ? "comercio" : sectorOf(lab.profKey);
        pending.computeIfAbsent(sector, k -> new double[1])[0] += value;
        if (good != null) {
            pendingGoods.computeIfAbsent(sector, k -> new java.util.HashMap<>())
                    .merge(good, 1, Integer::sum);
        }
    }

    /** Manda al backend la produccion acumulada (un lote por minuto, no faena a faena). */
    private void report() {
        if (pending.isEmpty()) {
            return;
        }
        final com.google.gson.JsonArray entries = new com.google.gson.JsonArray();
        for (final var e : pending.entrySet()) {
            final com.google.gson.JsonObject o = new com.google.gson.JsonObject();
            o.addProperty("sector", e.getKey());
            o.addProperty("value", Math.round(e.getValue()[0] * 100.0) / 100.0);
            o.addProperty("goods", describe(pendingGoods.get(e.getKey())));
            entries.add(o);
        }
        pending.clear();
        pendingGoods.clear();
        gateway.production(entries);
    }

    private static String describe(java.util.Map<Material, Integer> goods) {
        if (goods == null || goods.isEmpty()) {
            return "";
        }
        final List<String> parts = new ArrayList<>();
        for (final var e : goods.entrySet()) {
            parts.add(e.getKey().name().toLowerCase(java.util.Locale.ROOT) + " x" + e.getValue());
        }
        return String.join(", ", parts.subList(0, Math.min(4, parts.size())));
    }

    /** Sector economico al que pertenece cada oficio (los tres negocios del pueblo). */
    private static String sectorOf(String profKey) {
        return switch (profKey) {
            case "farmer", "fisherman", "shepherd", "butcher" -> "agricultura";
            case "mason", "toolsmith", "fletcher" -> "artesania";
            default -> "comercio";
        };
    }

    /** Valor de referencia de una jornada de ese oficio (AET). */
    private static double baseValue(String profKey) {
        return switch (profKey) {
            case "toolsmith" -> 2.0;
            case "shepherd", "librarian", "butcher", "leatherworker" -> 1.5;
            default -> 1.2;
        };
    }

    private static boolean isCrop(Material m) {
        return m == Material.WHEAT || m == Material.CARROTS || m == Material.POTATOES
                || m == Material.BEETROOTS;
    }

    private static Material cropGood(Material crop) {
        return switch (crop) {
            case CARROTS -> Material.CARROT;
            case POTATOES -> Material.POTATO;
            case BEETROOTS -> Material.BEETROOT;
            default -> Material.WHEAT;
        };
    }

    private static boolean isRock(Material m) {
        if (m.name().endsWith("_ORE")) {
            return true;
        }
        return switch (m) {
            case STONE, GRANITE, DIORITE, ANDESITE, DEEPSLATE, TUFF, COBBLESTONE, CALCITE -> true;
            default -> false;
        };
    }

    private static Material saplingFor(Block soil) {
        return soil.getType() == Material.PODZOL ? Material.SPRUCE_SAPLING : Material.OAK_SAPLING;
    }

    private static Material woolOf(Sheep s) {
        final org.bukkit.DyeColor c = s.getColor();
        if (c == null) {
            return Material.WHITE_WOOL;
        }
        final Material m = Material.matchMaterial(c.name() + "_WOOL");
        return m == null ? Material.WHITE_WOOL : m;
    }

    private static Material cooked(Material raw) {
        return switch (raw) {
            case PORKCHOP -> Material.COOKED_PORKCHOP;
            case CHICKEN -> Material.COOKED_CHICKEN;
            default -> Material.COOKED_BEEF;
        };
    }
}
