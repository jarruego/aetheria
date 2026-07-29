package com.aetheria.plugin;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;

/**
 * LOS CAMINOS de Aetheria: las carreteras que unen aldeas y los senderos que van de la puerta de
 * cada casa a la plaza.
 *
 * <p>Estaba todo dentro de {@code SettlementModule} junto con nacimientos, bodas, oficios y
 * gobierno. Se saco aqui (Fase 1 del plan de mejoras) porque trazar un camino no tiene nada que
 * ver con casar a dos vecinos, y porque asi tocar las carreteras no puede romper el pueblo.
 *
 * <p>La cuenta de la rasante —la parte que se equivocaba y dejaba escalones imposibles— vive
 * aparte todavia, en {@link RoadProfile}, que no toca Bukkit y esta cubierta por tests.
 *
 * <p>Como se construye un camino, en dos fases y por lotes (unas pocas casillas por tick, que son
 * cientos de bloques y muchos en trozos de mundo sin generar):
 * <ol>
 *   <li><b>Se mide</b> el terreno de punta a punta, y el agua si la hay.</li>
 *   <li><b>Se proyecta</b> la rasante entre la cota de una plaza y la de la otra y se pavimenta:
 *       tablero de puente sobre el agua, terraplen sobre un barranco, tunel si el monte queda por
 *       encima. Nada de eso necesita codigo especial: sale de donde cae la rasante.</li>
 * </ol>
 */
public final class RoadBuilder {

    private final AetheriaPlugin plugin;
    private final World world;
    private ClaimModule claims;   // parcelas de jugador: un camino jamas las atraviesa

    public RoadBuilder(AetheriaPlugin plugin, World world) {
        this.plugin = plugin;
        this.world = world;
    }

    /** Engancha las parcelas (se inyecta despues: ClaimModule se crea mas tarde). */
    public void setClaims(ClaimModule claims) {
        this.claims = claims;
    }

    /**
     * SENDERO de una construccion a la plaza. Va <b>de puerta a puerta</b>: arranca en el umbral,
     * a ras del suelo de la casa, y muere en el borde de la plaza, tambien a ras.
     *
     * <p>Se planifica igual que las carreteras: primero se mide el terreno de la casa a la plaza,
     * luego se proyecta la rasante <b>atada a las dos alturas</b> (el suelo de la casa y el de la
     * plaza) con medio bloque cada dos casillas, y solo despues se pica y se rellena. Antes se
     * decidia casilla a casilla y, al toparse con un edificio, se saltaba la casilla sin tocarla:
     * quedaban esos escalones raros pegados a las paredes y senderos que morian en el aire.
     *
     * @param fy   cota del SUELO de la construccion (el umbral queda a fy+1)
     * @param half media huella de la construccion: por ahi esta la puerta
     */
    public void pathTo(int cx, int cz, int fy, int half, Location plaza) {
        final int tx = plaza.getBlockX();
        final int tz = plaza.getBlockZ();
        // 1) Punto de partida: la primera casilla FUERA de la casa en direccion a la plaza (el
        //    umbral de la puerta, que mira siempre a la plaza).
        int x = cx;
        int z = cz;
        for (int i = 0; i <= half; i++) {
            if (Math.abs(tx - x) >= Math.abs(tz - z)) {
                x += Integer.signum(tx - x);
            } else {
                z += Integer.signum(tz - z);
            }
        }
        // 2) Casillas hasta el borde de la plaza (la plaza ya esta pavimentada: no se repisa).
        final List<int[]> tiles = new ArrayList<>();
        for (int guard = 0; guard < 220; guard++) {
            if (Math.abs(tx - x) <= 4 && Math.abs(tz - z) <= 4) {
                break;
            }
            if (Math.abs(tx - x) >= Math.abs(tz - z)) {
                x += Integer.signum(tx - x);
            } else {
                z += Integer.signum(tz - z);
            }
            tiles.add(new int[] {x, z});
        }
        final int n = tiles.size();
        if (n == 0) {
            return;
        }
        // 3) Perfil atado a las DOS alturas: el umbral de la casa y el suelo de la plaza.
        final int[] terr = new int[n];
        final boolean[] wet = new boolean[n];
        final int[] prof = new int[n];
        for (int i = 0; i < n; i++) {
            terr[i] = 2 * (groundY(tiles.get(i)[0], tiles.get(i)[1]) + 1);
        }
        planProfile(terr, wet, prof, 2 * (fy + 1), 2 * (plaza.getBlockY()));
        // 4) A picar y rellenar.
        for (int i = 0; i < n; i++) {
            final int px = tiles.get(i)[0];
            final int pz = tiles.get(i)[1];
            if (claims != null && claims.isClaimed(px, pz)) {
                continue;   // parcela de un jugador: el sendero no entra
            }
            final int ny = Math.floorDiv(prof[i], 2) - 1;
            if (plugin.buildRegistry().containsColumn(px, pz) || isBuilt(px, pz, ny)) {
                // LO CONSTRUIDO NO SE TOCA, ni lo nuestro ni lo que genera Minecraft. Un sendero
                // llego a pavimentar el interior de una casa (dejando dentro al jugador) y el
                // desbroce se comio las esquinas de la taberna: son TRONCOS, y un tronco cuenta
                // como "natural" para poder talar arboles... salvo cuando es una pared.
                continue;
            }
            final boolean slab = Math.floorMod(prof[i], 2) == 1;
            final Material at = world.getBlockAt(px, ny, pz).getType();
            if (!at.isAir() && !natural(at)) {
                continue;   // hay algo construido justo ahi (una pared): no se toca
            }
            for (int y = ny + 1; y <= ny + 3; y++) {   // despeja lo que sobresale
                final Material m = world.getBlockAt(px, y, pz).getType();
                if (!m.isAir() && natural(m)) {
                    world.getBlockAt(px, y, pz).setType(Material.AIR, false);
                }
            }
            stabilizeCeiling(px, pz, ny + 4);
            for (int y = ny; y >= ny - 6; y--) {       // y no deja el sendero en el aire
                final Block b = world.getBlockAt(px, y, pz);
                if (b.getType().isAir() || b.isLiquid()) {
                    b.setType(Material.DIRT, false);
                } else {
                    break;
                }
            }
            // Nada de GRAVA en el firme: es un bloque que CAE, y en cuanto algo le quita el apoyo
            // (una excavacion al lado, un pilon retirado) se desmorona sobre el camino.
            world.getBlockAt(px, ny, pz).setType(slab ? Material.COBBLESTONE : Material.DIRT_PATH,
                    false);
            if (slab) {
                world.getBlockAt(px, ny + 1, pz).setType(Material.COBBLESTONE_SLAB, false);
            }
        }
    }

    /**
     * #14 - CARRETERA entre dos aldeas. Es un camino de verdad: 3 casillas de ancho, con la
     * misma <b>rasante regulada</b> que los senderos (medio bloque por casilla, con losas de
     * escalon, para poder recorrerla andando), <b>puentes de madera</b> donde cruza agua y
     * faroles cada tramo.
     *
     * <p>Se construye <b>por lotes</b> (unas pocas casillas por tick): son cientos de bloques y
     * cargar de golpe 300 casillas —muchas en trozos de mundo aun sin generar— congelaria el
     * servidor. Asi la carretera "se va abriendo" sin que se note.
     */
    public void buildRoad(int x0, int z0, int x1, int z1, String nameStart, String nameEnd) {
        // TRAZADO CON ESQUIVE. Se avanza hacia la otra aldea, pero si la siguiente casilla cae
        // sobre OBRA (una casa nuestra, un edificio civico o una aldea que genero Minecraft), el
        // camino se desplaza de lado y la rodea. Si no consigue rodearla en unas cuantas casillas,
        // se abandona el trazado: mejor quedarse sin carretera que abrirla a traves de una casa.
        final List<int[]> tiles = new ArrayList<>();
        int x = x0;
        int z = z0;
        int lateral = 0;         // casillas seguidas esquivando
        int ladoEsquive = 0;     // por que lado se rodea (se mantiene mientras dure el rodeo)
        for (int guard = 0; (x != x1 || z != z1) && guard < 900; guard++) {
            final int dx = x1 - x;
            final int dz = z1 - z;
            final boolean porX = Math.abs(dx) >= Math.abs(dz);
            int nx = x + (porX ? Integer.signum(dx) : 0);
            int nz = z + (porX ? 0 : Integer.signum(dz));
            if (blocked(nx, nz)) {
                if (ladoEsquive == 0) {   // empieza el rodeo: se elige el lado que esta libre
                    final int a = porX ? z + 1 : x + 1;
                    ladoEsquive = blocked(porX ? x : a, porX ? a : z) ? -1 : 1;
                }
                final int sx = x + (porX ? 0 : ladoEsquive);
                final int sz = z + (porX ? ladoEsquive : 0);
                if (lateral >= 16 || blocked(sx, sz)) {
                    plugin.getLogger().warning("[Aetheria] Carretera cancelada: no hay por donde "
                            + "pasar sin meterse en una construccion (" + x + "," + z + ").");
                    return;   // antes que atravesar una casa, no hay carretera
                }
                nx = sx;
                nz = sz;
                lateral++;
            } else {
                lateral = 0;
                ladoEsquive = 0;
            }
            // El ancho va perpendicular a como se ha avanzado en ESTA casilla.
            tiles.add(new int[] {nx, nz, nx != x ? 0 : 1, nx != x ? 1 : 0});
            x = nx;
            z = nz;
        }

        // DOS FASES, las dos por lotes para no congelar el servidor:
        //   1) SE MIDE el terreno de punta a punta (y el agua, si la hay).
        //   2) SE PROYECTA la rasante entre la cota de UNA aldea y la de la OTRA, con pendiente
        //      acotada, y solo entonces se pavimenta.
        // Antes se decidia la altura casilla a casilla mirando solo el suelo de debajo: la
        // carretera no sabia a que altura estaba el pueblo de destino y aparecian saltos.
        final int n = tiles.size();
        final int[] terr = new int[n];        // cota "deseable" de cada casilla (medios bloques)
        final boolean[] wet = new boolean[n]; // ¿esa casilla es agua? (ahi va tablero de puente)
        final int[] prof = new int[n];        // la rasante ya proyectada
        final int startH = 2 * (groundY(x0, z0) + 1);
        final int endH = 2 * (groundY(x1, z1) + 1);
        final int[] state = {0, 0};   // {fase, indice}
        final java.util.Set<Long> painted = new java.util.HashSet<>();   // columnas ya pavimentadas
        final int[] task = new int[1];
        task[0] = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (state[0] == 0) {   // --- fase 1: medir ---
                for (int k = 0; k < 12 && state[1] < n; k++, state[1]++) {
                    final int[] t = tiles.get(state[1]);
                    final int gy = groundY(t[0], t[1]);
                    final int wt = waterSurfaceY(t[0], t[1], gy);
                    wet[state[1]] = wt >= 0;
                    // Sobre agua, la cota deseable es el tablero (un bloque de aire sobre la
                    // lamina); en tierra, el propio suelo.
                    terr[state[1]] = wt >= 0 ? 2 * (wt + 2) : 2 * (gy + 1);
                }
                if (state[1] >= n) {
                    planProfile(terr, wet, prof, startH, endH);
                    state[0] = 1;
                    state[1] = 0;
                }
                return;
            }
            for (int k = 0; k < 6 && state[1] < n; k++, state[1]++) {   // --- fase 2: pavimentar ---
                final int[] t = tiles.get(state[1]);
                // La carretera acaba a 15 bloques del CENTRO de cada aldea: asi no se mete entre los
                // edificios ni los pisa; los senderos internos del pueblo la enlazan.
                if (Math.hypot(t[0] - x0, t[1] - z0) < 15 || Math.hypot(t[0] - x1, t[1] - z1) < 15) {
                    continue;
                }
                paveTile(tiles, state[1], prof[state[1]], wet[state[1]], painted);
            }
            if (state[1] >= n) {
                Bukkit.getScheduler().cancelTask(task[0]);
                // Un cartel en CADA extremo del camino (donde empieza el pavimento, a 15 bloques de
                // cada aldea), apuntando a la aldea de destino: "Camino a <aldea>".
                placeRoadSign(tiles, prof, x0, z0, nameEnd);    // saliendo del origen -> a la nueva
                placeRoadSign(tiles, prof, x1, z1, nameStart);  // saliendo de la nueva -> al origen
            }
        }, 1L, 1L).getTaskId();
    }

    /** Cartel "Camino a &lt;dest&gt;" en el extremo del camino mas cercano a (tx,tz): sobre un poste en
     *  el arcen, a la altura de la rasante y mirando hacia la aldea. */
    private void placeRoadSign(List<int[]> tiles, int[] prof, int tx, int tz, String dest) {
        if (dest == null || dest.isBlank()) {
            return;
        }
        int best = -1;
        double bestD = Double.MAX_VALUE;
        for (int i = 0; i < tiles.size(); i++) {
            final int[] t = tiles.get(i);
            final double d = Math.hypot(t[0] - tx, t[1] - tz);
            if (d >= 15 && d < bestD) {   // el borde pavimentado (la carretera para a 15 del centro)
                bestD = d;
                best = i;
            }
        }
        if (best < 0) {
            return;
        }
        final int[] t = tiles.get(best);
        final int ny = Math.floorDiv(prof[best], 2) - 1;
        final int sx = t[0] + t[2] * 2;   // arcen (carril exterior), nunca el centro del camino
        final int sz = t[1] + t[3] * 2;
        world.getBlockAt(sx, ny + 1, sz).setType(Material.OAK_FENCE, false);
        final Block sb = world.getBlockAt(sx, ny + 2, sz);
        sb.setType(Material.OAK_SIGN, false);
        if (sb.getBlockData() instanceof org.bukkit.block.data.type.Sign sd) {
            sd.setRotation(cardinalToward(sx, sz, tx, tz));
            sb.setBlockData(sd, false);
        }
        if (sb.getState() instanceof org.bukkit.block.Sign sign) {
            final org.bukkit.block.sign.SignSide front =
                    sign.getSide(org.bukkit.block.sign.Side.FRONT);
            front.line(1, net.kyori.adventure.text.Component.text("Camino a"));
            front.line(2, net.kyori.adventure.text.Component.text("§6" + dest));
            sign.update();
        }
    }

    private static org.bukkit.block.BlockFace cardinalToward(int fx, int fz, int tx, int tz) {
        if (Math.abs(tx - fx) >= Math.abs(tz - fz)) {
            return tx >= fx ? org.bukkit.block.BlockFace.EAST : org.bukkit.block.BlockFace.WEST;
        }
        return tz >= fz ? org.bukkit.block.BlockFace.SOUTH : org.bukkit.block.BlockFace.NORTH;
    }

    /**
     * Proyecta la RASANTE de la carretera entre las dos aldeas: sale de la cota de una y llega a
     * la de la otra <b>sin un solo salto</b>, con medio bloque de desnivel por casilla como
     * maximo. Lo que no cabe en esa pendiente lo resuelve el terreno:
     *
     * <ul>
     *   <li>si la rasante queda <b>por debajo</b> del monte, la carretera lo atraviesa: <b>tunel</b>;</li>
     *   <li>si queda <b>por encima</b> del suelo (barranco, vaguada), se levanta <b>terraplen</b>
     *       o <b>puente</b> segun haya agua debajo.</li>
     * </ul>
     *
     * <p>Se hace con dos pasadas (ida y vuelta) sobre la cota deseable: la de ida limita cuanto
     * puede SUBIR y la de vuelta cuanto puede BAJAR, asi que el resultado cumple la pendiente en
     * los dos sentidos. Los cruces de agua se allanan antes, para que un islote no hunda el puente.
     */
    private void planProfile(int[] terr, boolean[] wet, int[] prof, int startH, int endH) {
        // La cuenta vive en RoadProfile (matematica pura, con tests): aqui solo se copia.
        System.arraycopy(RoadProfile.plan(terr, wet, startH, endH), 0, prof, 0, prof.length);
    }

    /** Y del bloque de AGUA mas alto que cubre la columna (x,z) por encima del suelo solido, o -1
     *  si no esta cubierta de agua. Sirve para trazar el puente POR ENCIMA del agua en vez de
     *  hundir la carretera hasta el lecho. */
    private int waterSurfaceY(int x, int z, int groundY) {
        int top = -1;
        for (int y = groundY + 1; y <= groundY + 24; y++) {
            final Block b = world.getBlockAt(x, y, z);
            final Material m = b.getType();
            if (b.isLiquid() || m == Material.ICE || m == Material.PACKED_ICE
                    || m == Material.BLUE_ICE || m == Material.FROSTED_ICE) {
                top = y;   // agua (o su superficie helada): por aqui pasa el tablero
            } else if (m == Material.KELP || m == Material.KELP_PLANT || m == Material.SEAGRASS
                    || m == Material.TALL_SEAGRASS || m == Material.SEA_PICKLE
                    || m == Material.BUBBLE_COLUMN || Tag.CORALS.isTagged(m)
                    || Tag.CORAL_PLANTS.isTagged(m)) {
                continue;   // VEGETACION del fondo: no tapa la columna, el agua sigue por encima
                // (esto cortaba los puentes: un alga bastaba para que la casilla no fuera "agua"
                //  y la carretera se hundia hasta el lecho con su escalera de piedra)
            } else if (!m.isAir()) {
                break;   // algo solido de verdad tapa la columna: no es agua abierta
            }
        }
        return top;
    }

    /** Hasta cuantas casillas de bajio/islote se puentean de largo sin cortar el tablero. */
    private static final int BRIDGE_GAP = RoadProfile.BRIDGE_GAP;

    /** Cuanto puede rellenar la calzada hacia abajo para cruzar un barranco (terraplen/viaducto). */
    private static final int ROAD_FILL = 40;
    /** Cada cuantas casillas puede la rasante ganar o perder medio bloque (cuanto mas alto, mas suave). */
    private static final int STEP_EVERY = RoadProfile.STEP_EVERY;

    /**
     * Pavimenta UNA casilla de carretera (con sus dos arcenes) a la cota que le toca segun la
     * RASANTE ya proyectada entre las dos aldeas ({@link #planProfile}). Aqui ya no se decide
     * altura: solo se ejecuta. Segun donde caiga la rasante respecto al terreno sale una cosa u
     * otra sin codigo especial — tablero de puente sobre el agua, terraplen sobre un barranco,
     * o tunel si el monte queda por encima.
     */
    private void paveTile(List<int[]> tiles, int index, int h, boolean water,
            java.util.Set<Long> painted) {
        final int[] tile = tiles.get(index);
        final int x = tile[0];
        final int z = tile[1];
        final int perpX = tile[2];
        final int perpZ = tile[3];
        final int gy = groundY(x, z);   // fondo solido (bajo el agua, si la hay)
        if (!water && !natural(world.getBlockAt(x, gy, z).getType())) {
            return;   // plaza o edificio: la carretera pasa de largo sin tocar nada
        }
        if (claims != null && claims.isClaimed(x, z)) {
            return;   // parcela de un jugador: la carretera no la atraviesa a la brava
        }
        final int ny = Math.floorDiv(h, 2) - 1;
        final boolean slab = Math.floorMod(h, 2) == 1;
        // ANCHO 3 DE VERDAD. Antes se pavimentaba solo la franja perpendicular al avance y, en los
        // tramos en diagonal (donde el avance alterna entre X y Z), las franjas no se solapaban:
        // la calzada salia a trozos de uno o dos bloques. Ahora se pavimenta el CUADRO 3x3 de cada
        // casilla, asi que la carretera mide tres bloques de ancho vaya en la direccion que vaya.
        for (int ox = -1; ox <= 2; ox++) {
            for (int oz = -1; oz <= 2; oz++) {
            final int px = x + ox;
            final int pz = z + oz;
            // CUATRO bloques de ancho: dos carriles de calzada (0 y 1) y sus dos arcenes (-1 y 2).
            final int lane = (perpX != 0 ? ox : oz);
            final boolean edge = lane == -1 || lane == 2;
            if (claims != null && claims.isClaimed(px, pz)) {
                continue;   // ni un bloque dentro de la parcela de alguien
            }
            if (plugin.buildRegistry().containsColumn(px, pz) || isBuilt(px, pz, ny)) {
                continue;   // obra de alguien (nuestra o de Minecraft): no se toca
            }
            // MANDA QUIEN LLEGO PRIMERO. Cada casilla pinta un cuadro de 4x4 y los cuadros se
            // solapan; si una columna se repintaba desde una casilla posterior (a otra cota),
            // aparecian escalones absurdos justo donde la calzada gira. Pintada una vez, se
            // respeta.
            if (!painted.add((((long) px) << 32) ^ (pz & 0xffffffffL))) {
                continue;
            }
            final Material at = world.getBlockAt(px, ny, pz).getType();
            if (!at.isAir() && !at.name().contains("WATER") && !natural(at)) {
                continue;   // algo construido (una plaza, una casa): la carretera no lo pisa
            }
            for (int y = ny + 1; y <= ny + 4; y++) {
                final Block b = world.getBlockAt(px, y, pz);
                if (!b.getType().isAir() && (natural(b.getType()) || b.isLiquid())) {
                    b.setType(Material.AIR, false);
                }
            }
            stabilizeCeiling(px, pz, ny + 5);   // que el techo no se derrumbe sobre la calzada
            if (water) {   // PUENTE: tablero de madera, barandilla en los arcenes y pilones de piedra
                world.getBlockAt(px, ny, pz).setType(Material.OAK_PLANKS, false);
                if (edge) {
                    world.getBlockAt(px, ny + 1, pz).setType(Material.OAK_FENCE, false);
                }
                if (index % 5 == 0 && edge) {   // pilon cada pocos tramos: del tablero al lecho
                    for (int y = ny - 1; y > gy; y--) {
                        world.getBlockAt(px, y, pz).setType(Material.STONE_BRICKS, false);
                    }
                }
                continue;
            }
            // VIADUCTO: la calzada nunca se queda colgando sobre el vacio. Se rellena hacia abajo
            // hasta encontrar suelo firme, hasta MUY hondo: antes solo bajaba 5 bloques y al cruzar
            // un barranco el camino "seguia al fondo del precipicio", con un salto mortal en medio.
            // Los tres primeros bloques son tierra (el camino) y por debajo, piedra: se ve como un
            // terraplen/viaducto, no como una columna de barro.
            int filled = 0;
            for (int y = ny; y >= ny - ROAD_FILL; y--) {
                final Block b = world.getBlockAt(px, y, pz);
                if (b.getType().isAir() || b.isLiquid()) {
                    b.setType(ny - y < 3 ? Material.DIRT : Material.STONE_BRICKS, false);
                    filled++;
                } else {
                    break;
                }
            }
            // Calzada de UN SOLO material a lo ancho (nada de carril de tierra + arcenes de grava,
            // que parecia dos carriles): un unico camino ancho y despejado.
            world.getBlockAt(px, ny, pz).setType(Material.DIRT_PATH, false);
            if (slab) {
                world.getBlockAt(px, ny + 1, pz).setType(Material.COBBLESTONE_SLAB, false);
            }
            // Si el terraplen es alto, el arcen lleva BARANDILLA: se cruza el barranco sin caerse.
            if (filled >= 3 && edge) {
                world.getBlockAt(px, ny + 1, pz).setType(Material.OAK_FENCE, false);
            }
            }
        }
        // TUNEL: si sobre la calzada sigue habiendo montaña, es que la carretera la atraviesa (la
        // rasante nunca trepa mas de medio bloque por casilla, asi que ante un monte se mete por
        // dentro). Se ilumina cada pocos pasos para que no sea una boca de lobo.
        final boolean tunnel = !water
                && !world.getBlockAt(x, ny + 5, z).getType().isAir()
                && world.getBlockAt(x, ny + 5, z).getType().isSolid();
        if (tunnel && index % 6 == 0) {
            // TUNEL: el farol va COLGADO DEL TECHO y pegado a la pared lateral, alternando lado.
            // Antes se ponia suelto a media altura: sin nada encima ni debajo, se caia al primer
            // toque; y a la altura de la cabeza, estorbaba el paso.
            final int s = (index / 6) % 2 == 0 ? 2 : -1;
            final int lx = x + perpX * s;
            final int lz = z + perpZ * s;
            if (world.getBlockAt(lx, ny + 5, lz).getType().isSolid()) {
                final Block b = world.getBlockAt(lx, ny + 4, lz);
                b.setType(Material.LANTERN, false);
                if (b.getBlockData() instanceof org.bukkit.block.data.type.Lantern lan) {
                    lan.setHanging(true);   // colgado de la roca, no apoyado en el aire
                    b.setBlockData(lan, false);
                }
            }
        }
        if (index % 24 == 12 && !tunnel) {
            // Farola FUERA de la calzada, alternando lado y lado: nunca en el firme por el que se
            // anda (ni en el centro ni en el arcen), para no estrechar el paso. Si justo ahi no hay
            // suelo donde apoyarla (terraplen, cuneta), se arrima al arcen antes que quedar flotando.
            final int fuera = (index / 24) % 2 == 0 ? 3 : -2;
            final int arcen = (index / 24) % 2 == 0 ? 2 : -1;
            int s = fuera;
            if (!world.getBlockAt(x + perpX * fuera, ny, z + perpZ * fuera).getType().isSolid()) {
                s = arcen;
            }
            final int lx = x + perpX * s;
            final int lz = z + perpZ * s;
            world.getBlockAt(lx, ny + 1, lz).setType(Material.OAK_FENCE, false);
            world.getBlockAt(lx, ny + 2, lz).setType(Material.LANTERN, false);
        }
    }



    /**
     * ASEGURA EL TECHO sobre la calzada recien abierta.
     *
     * <p>Al abrir una trinchera o un tunel, la grava y la arena que quedan justo encima <b>se
     * caen</b> —son bloques con gravedad— y acaban tapando el camino que se acaba de trazar. Aqui
     * se convierten en su equivalente solido (grava a piedra, arena a arenisca), asi que el techo
     * aguanta y el camino sigue despejado. Solo se tocan los bloques que de verdad caerian: el
     * resto del terreno se queda como estaba.
     */
    private void stabilizeCeiling(int x, int z, int fromY) {
        for (int y = fromY; y < fromY + 6; y++) {
            final Block b = world.getBlockAt(x, y, z);
            final Material m = b.getType();
            if (!falls(m)) {
                return;   // el primer bloque que se sostiene solo ya sujeta lo de arriba
            }
            b.setType(stable(m), false);
        }
    }

    /** ¿Es un bloque que se desploma al quedarse sin apoyo? */
    private static boolean falls(Material m) {
        return m == Material.GRAVEL || m == Material.SUSPICIOUS_GRAVEL
                || m == Material.SUSPICIOUS_SAND || Tag.SAND.isTagged(m)
                || m.name().endsWith("_CONCRETE_POWDER");
    }

    /** Su equivalente que NO cae, del mismo aire (grava a piedra, arena a arenisca). */
    private static Material stable(Material m) {
        final String n = m.name();
        if (n.contains("RED_SAND")) {
            return Material.RED_SANDSTONE;
        }
        if (Tag.SAND.isTagged(m) || m == Material.SUSPICIOUS_SAND) {
            return Material.SANDSTONE;
        }
        return Material.STONE;
    }

    /** ¿Esta casilla es intransitable para un camino (obra de alguien o parcela de un jugador)? */
    private boolean blocked(int x, int z) {
        if (plugin.buildRegistry().containsColumn(x, z)) {
            return true;
        }
        if (claims != null && claims.isClaimed(x, z)) {
            return true;
        }
        return isBuilt(x, z, groundY(x, z));
    }

    /**
     * ¿Hay OBRA en esta columna? Vale para lo nuestro y para lo que genera Minecraft (una aldea
     * vanilla, un puesto de saqueadores, un templo...).
     *
     * <p>Hace falta mirar la columna entera y no solo el bloque de la calzada porque el desbroce
     * quita lo "natural", y un TRONCO cuenta como natural para poder talar arboles: junto a unos
     * tablones, ese tronco es la esquina de una casa. Regla: si en la columna hay algo que solo
     * puede haber puesto alguien (tablones, puerta, cristal, escalera, cama, farol, mesa...), esa
     * columna NO SE TOCA, ni para pavimentar ni para desbrozar.
     */
    private boolean isBuilt(int x, int z, int aroundY) {
        for (int y = aroundY - 1; y <= aroundY + 6; y++) {
            if (manMade(world.getBlockAt(x, y, z).getType())) {
                return true;
            }
        }
        return false;
    }

    /** Materiales que NO salen de la generacion natural del terreno: los ha puesto alguien. */
    private static boolean manMade(Material m) {
        if (Tag.PLANKS.isTagged(m) || Tag.DOORS.isTagged(m) || Tag.BEDS.isTagged(m)
                || Tag.WOOL.isTagged(m) || Tag.WOODEN_STAIRS.isTagged(m)
                || Tag.WOODEN_SLABS.isTagged(m) || Tag.WOODEN_TRAPDOORS.isTagged(m)
                || Tag.FENCE_GATES.isTagged(m) || Tag.WOODEN_FENCES.isTagged(m)
                || Tag.ALL_SIGNS.isTagged(m)) {
            return true;
        }
        final String n = m.name();
        return n.contains("GLASS") || n.endsWith("_BRICKS") || n.contains("BOOKSHELF")
                || n.contains("CRAFTING_TABLE") || n.contains("FURNACE") || n.contains("SMOKER")
                || n.contains("BARREL") || n.contains("CHEST") || n.contains("LOOM")
                || n.contains("STONECUTTER") || n.contains("COMPOSTER") || n.contains("CAULDRON")
                || n.contains("BELL") || n.contains("CAMPFIRE") || n.contains("ANVIL")
                || n.contains("LECTERN") || n.contains("GRINDSTONE") || n.contains("SCAFFOLDING")
                || n.contains("BREWING_STAND") || n.contains("HAY_BLOCK") || n.contains("CARPET")
                || n.contains("TERRACOTTA") && n.contains("GLAZED");
    }

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

    /** True si el bloque es NATURAL (se puede allanar sin cargarse nada construido). */
    private static boolean natural(Material m) {
        return SettlementModule.isNatural(m);
    }
}
