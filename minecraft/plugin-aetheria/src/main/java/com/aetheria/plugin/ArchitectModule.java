package com.aetheria.plugin;

import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.jetbrains.annotations.NotNull;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;

/**
 * Arquitecto guiado: eliges gama de materiales, tamano y mobiliario; el arquitecto tira los
 * DADOS (materiales, ancho, altura, tejado, ventanas...) para que cada casa sea distinta, te
 * da el precio, y al confirmar te pide HACER CLIC DERECHO donde quieres la puerta.
 */
public final class ArchitectModule implements CommandExecutor, Listener {

    private static final String BANCO = "00000000-0000-0000-0000-000000000000";

    /** Gama de materiales: pools de muro/esquina/tejado/acento (se elige al azar) + coste. */
    private record Tier(Material[] walls, Material[] corners, Material[] roofs, Material[] accents,
            int factor, String label) {}

    // NB: se evitan bloques revendibles (oro/diamante/esmeralda) para no romper la economia.
    // El "dorado" del lujo es gilded_blackstone (no se funde a lingotes).
    private static final Map<String, Tier> TIERS = Map.of(
        "madera", new Tier(
            new Material[] {Material.OAK_PLANKS, Material.SPRUCE_PLANKS, Material.BIRCH_PLANKS,
                Material.DARK_OAK_PLANKS, Material.JUNGLE_PLANKS},
            new Material[] {Material.OAK_LOG, Material.SPRUCE_LOG, Material.DARK_OAK_LOG,
                Material.STRIPPED_OAK_LOG},
            new Material[] {Material.DARK_OAK_PLANKS, Material.SPRUCE_PLANKS, Material.COBBLESTONE},
            new Material[] {Material.STRIPPED_SPRUCE_LOG, Material.COBBLESTONE, Material.BRICKS,
                Material.MOSSY_COBBLESTONE},
            6, "rustica"),
        "piedra", new Tier(
            new Material[] {Material.STONE_BRICKS, Material.COBBLESTONE, Material.ANDESITE,
                Material.STONE, Material.DEEPSLATE_BRICKS},
            new Material[] {Material.CHISELED_STONE_BRICKS, Material.POLISHED_ANDESITE,
                Material.DEEPSLATE_TILES, Material.COBBLESTONE},
            new Material[] {Material.COBBLESTONE, Material.STONE_BRICKS, Material.DEEPSLATE_TILES,
                Material.DARK_OAK_PLANKS},
            new Material[] {Material.CHISELED_STONE_BRICKS, Material.MOSSY_STONE_BRICKS,
                Material.POLISHED_DIORITE, Material.POLISHED_GRANITE},
            8, "de piedra"),
        "ladrillo", new Tier(
            new Material[] {Material.BRICKS, Material.DEEPSLATE_BRICKS, Material.MUD_BRICKS,
                Material.POLISHED_BLACKSTONE_BRICKS, Material.RED_SANDSTONE},
            new Material[] {Material.POLISHED_BLACKSTONE, Material.DEEPSLATE_TILES,
                Material.CHISELED_STONE_BRICKS, Material.DARK_OAK_LOG},
            new Material[] {Material.DEEPSLATE_TILES, Material.NETHER_BRICKS,
                Material.DARK_OAK_PLANKS, Material.BRICKS},
            new Material[] {Material.CHISELED_POLISHED_BLACKSTONE, Material.CUT_RED_SANDSTONE,
                Material.POLISHED_BLACKSTONE, Material.TERRACOTTA},
            10, "noble"),
        "lujo", new Tier(
            new Material[] {Material.QUARTZ_BLOCK, Material.SMOOTH_QUARTZ, Material.CALCITE,
                Material.DIORITE, Material.WHITE_TERRACOTTA, Material.POLISHED_DIORITE},
            new Material[] {Material.QUARTZ_PILLAR, Material.CHISELED_QUARTZ_BLOCK,
                Material.SMOOTH_QUARTZ, Material.POLISHED_DIORITE},
            new Material[] {Material.SMOOTH_QUARTZ, Material.PRISMARINE, Material.DARK_PRISMARINE,
                Material.PRISMARINE_BRICKS},
            new Material[] {Material.CHISELED_QUARTZ_BLOCK, Material.GILDED_BLACKSTONE,
                Material.PRISMARINE_BRICKS, Material.LIGHT_BLUE_TERRACOTTA},
            14, "de lujo"));

    /** Encargo en curso. size 1/2/3; half y floors se tiran al presupuestar. */
    private static final class Order {
        int size;
        int half;
        int floors;
        String mat;
        String style = "casona";   // casona (equilibrada) | aldeana (estilo pueblo) | torre (alta)
        Material[] palette;
        boolean furniture;
        boolean furnitureSet;
    }

    private final AetheriaPlugin plugin;
    private final GatewayClient gateway;
    private final ClaimModule claims;
    private final UndoModule undo;
    private final Map<UUID, Order> orders = new ConcurrentHashMap<>();
    private final Map<UUID, Order> pending = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastClick = new ConcurrentHashMap<>();

    public ArchitectModule(AetheriaPlugin plugin, GatewayClient gateway, ClaimModule claims,
            UndoModule undo) {
        this.plugin = plugin;
        this.gateway = gateway;
        this.claims = claims;
        this.undo = undo;
    }

    private static int price(Order o) {
        final int unit = o.half * o.half * o.floors;
        final int factor = TIERS.getOrDefault(o.mat, TIERS.get("madera")).factor();
        return unit * factor + (o.furniture ? 30 * o.floors : 0);
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
            @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Solo los jugadores pueden usar el arquitecto.");
            return true;
        }
        if (command.getName().equalsIgnoreCase("servicios")) {
            showServices(player);
            return true;
        }
        if (args.length == 0) {
            start(player);
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "size" -> setSize(player, args.length > 1 ? args[1] : "");
            case "mat" -> setMat(player, args.length > 1 ? args[1] : "");
            case "estilo" -> setStyle(player, args.length > 1 ? args[1] : "");
            case "muebles" -> setFurniture(player, args.length > 1 ? args[1] : "");
            case "confirmar" -> confirm(player);
            case "aqui" -> buildInFront(player);
            case "cancelar" -> {
                orders.remove(player.getUniqueId());
                pending.remove(player.getUniqueId());
                player.sendMessage("§7Encargo cancelado.");
            }
            default -> start(player);
        }
        return true;
    }

    private void start(Player player) {
        orders.put(player.getUniqueId(), new Order());
        player.sendMessage("§6[Arquitecto] §fBuenas. Te hago una casa unica. Primero, "
                + "§e¿de que tamano?");
        player.sendMessage(Component.text("  ")
                .append(opt("§a[Pequena]", "/arquitecto size small"))
                .append(Component.text("  "))
                .append(opt("§a[Mediana]", "/arquitecto size medium"))
                .append(Component.text("  "))
                .append(opt("§a[Grande]", "/arquitecto size large")));
    }

    private void setSize(Player player, String s) {
        final Order o = orders.get(player.getUniqueId());
        if (o == null) { start(player); return; }
        o.size = switch (s.toLowerCase()) {
            case "small", "pequena", "pequeña" -> 1;
            case "medium", "mediana" -> 2;
            case "large", "grande" -> 3;
            default -> 0;
        };
        if (o.size == 0) { start(player); return; }
        player.sendMessage("§6[Arquitecto] §fBien. §e¿Que gama de materiales?");
        player.sendMessage(Component.text("  ")
                .append(opt("§a[Rustica]", "/arquitecto mat madera"))
                .append(Component.text("  "))
                .append(opt("§a[Piedra]", "/arquitecto mat piedra"))
                .append(Component.text("  "))
                .append(opt("§a[Noble]", "/arquitecto mat ladrillo"))
                .append(Component.text("  "))
                .append(opt("§b[Lujo]", "/arquitecto mat lujo")));
    }

    private void setMat(Player player, String m) {
        final Order o = orders.get(player.getUniqueId());
        if (o == null || o.size == 0) { start(player); return; }
        m = m.toLowerCase();
        if (!TIERS.containsKey(m)) {
            setSize(player, sizeName(o.size));
            return;
        }
        o.mat = m;
        player.sendMessage("§6[Arquitecto] §f¿En que §eestilo§f?");
        player.sendMessage(Component.text("  ")
                .append(opt("§a[Casona]", "/arquitecto estilo casona"))
                .append(Component.text("  "))
                .append(opt("§a[Aldeana]", "/arquitecto estilo aldeana"))
                .append(Component.text("  "))
                .append(opt("§a[Torre]", "/arquitecto estilo torre"))
                .append(Component.text("  "))
                .append(opt("§b[Vanilla]", "/arquitecto estilo vanilla")));
        player.sendMessage("§7Casona: equilibrada. Aldeana: compacta estilo pueblo. Torre: alta. "
                + "Vanilla: una casa de aldea REAL de Minecraft.");
    }

    private void setStyle(Player player, String s) {
        final Order o = orders.get(player.getUniqueId());
        if (o == null || o.size == 0 || o.mat == null) { start(player); return; }
        o.style = switch (s.toLowerCase()) {
            case "aldeana", "aldea", "pueblo" -> "aldeana";
            case "torre", "tower" -> "torre";
            case "vanilla", "real" -> "vanilla";
            default -> "casona";
        };
        player.sendMessage("§6[Arquitecto] §f¿La quieres §eamueblada§f? (habitaciones con muebles)");
        player.sendMessage(Component.text("  ")
                .append(opt("§a[Si, amueblada]", "/arquitecto muebles si"))
                .append(Component.text("  "))
                .append(opt("§7[No, vacia]", "/arquitecto muebles no")));
    }

    private void setFurniture(Player player, String f) {
        final Order o = orders.get(player.getUniqueId());
        if (o == null || o.size == 0 || o.mat == null) { start(player); return; }
        o.furniture = f.toLowerCase().startsWith("s") || f.equalsIgnoreCase("yes");
        o.furnitureSet = true;
        // Se tiran los dados del ancho, la altura y los materiales concretos.
        final Random rng = new Random();
        o.half = 2 + o.size + rng.nextInt(2);     // pequena 3-4, mediana 4-5, grande 5-6
        o.floors = o.size + rng.nextInt(2);       // pequena 1-2, mediana 2-3, grande 3-4
        // El ESTILO cambia la silueta (y la paleta en "aldeana", para el look de aldea vanilla).
        switch (o.style) {
            case "torre" -> { o.floors += 2; o.half = Math.max(2, o.half - 1); }   // alta y estrecha
            case "aldeana" -> { o.floors = Math.min(o.floors, 2); o.half = 2 + o.size; }  // compacta
            default -> { }   // casona: equilibrada, como sale del dado
        }
        o.palette = "aldeana".equals(o.style) ? villagePalette(rng) : palette(o.mat, rng);
        final int p = price(o);
        player.sendMessage(String.format("§6[Arquitecto] §fUna casa §e%s§f estilo §e%s§f, %dx%d y %d "
                + "plantas%s. Presupuesto: §a%d AET§f.", TIERS.get(o.mat).label(), o.style,
                o.half * 2 + 1, o.half * 2 + 1, o.floors, o.furniture ? ", amueblada" : "", p));
        player.sendMessage(Component.text("  ")
                .append(opt("§a[Confirmar por " + p + " AET]", "/arquitecto confirmar"))
                .append(Component.text("  "))
                .append(opt("§c[Cancelar]", "/arquitecto cancelar")));
    }

    private void confirm(Player player) {
        final Order o = orders.get(player.getUniqueId());
        if (o == null || !o.furnitureSet) {
            player.sendMessage("§7No tienes un encargo listo. Empieza con §f/arquitecto§7.");
            return;
        }
        orders.remove(player.getUniqueId());
        pending.put(player.getUniqueId(), o);
        player.sendMessage("§6[Arquitecto] §fPerfecto. Ahora §eHAZ CLIC DERECHO en el suelo§f "
                + "donde quieras la §epuerta§f (sobre tu parcela).");
        player.sendMessage("§7La casa crece hacia donde mires. O escribe §f/arquitecto aqui§7 "
                + "para ponerla delante de ti.");
    }

    /** Clic derecho en el suelo: ahi va la puerta; la casa crece hacia donde mira el jugador. */
    @EventHandler
    public void onPlace(PlayerInteractEvent event) {
        final Action a = event.getAction();
        if (a != Action.RIGHT_CLICK_BLOCK && a != Action.RIGHT_CLICK_AIR) {
            return;
        }
        if (event.getHand() == EquipmentSlot.OFF_HAND) {
            return;   // evita el doble disparo (mano + secundaria)
        }
        final Player player = event.getPlayer();
        final Order o = pending.get(player.getUniqueId());
        if (o == null) {
            return;
        }
        final long now = System.currentTimeMillis();
        final Long last = lastClick.get(player.getUniqueId());
        if (last != null && now - last < 400) {
            return;   // antirebote
        }
        lastClick.put(player.getUniqueId(), now);

        Block target = event.getClickedBlock();
        if (target == null) {
            target = player.getTargetBlockExact(6);   // clic al aire: mira que apunta
        }
        if (target == null) {
            player.sendMessage("§7[Arquitecto] Apunta al suelo (a menos de 6 bloques) y clic derecho.");
            return;
        }
        event.setCancelled(true);
        final BlockFace f = flatFacing(player);
        final int cx = target.getX() + f.getModX() * o.half;
        final int cz = target.getZ() + f.getModZ() * o.half;
        buildAt(player, o, cx, cz, target.getY(), f.getOppositeFace());
    }

    private void buildInFront(Player player) {
        final Order o = pending.get(player.getUniqueId());
        if (o == null) {
            player.sendMessage("§7Primero confirma un encargo con §f/arquitecto§7.");
            return;
        }
        final BlockFace f = flatFacing(player);
        final int cx = player.getLocation().getBlockX() + f.getModX() * (o.half + 2);
        final int cz = player.getLocation().getBlockZ() + f.getModZ() * (o.half + 2);
        buildAt(player, o, cx, cz, player.getWorld().getHighestBlockYAt(cx, cz), f.getOppositeFace());
    }

    private void buildAt(Player player, Order o, int cx0, int cz0, int hintY, BlockFace door) {
        if (!claims.ownsChunk(player.getUniqueId(), cx0 >> 4, cz0 >> 4)) {
            player.sendMessage("§c[Arquitecto] Ahi no es tu parcela. Reclamala con §f/claim§c o "
                    + "elige un punto sobre tu terreno.");
            return;
        }
        // Cota del suelo = MEDIA del suelo firme de la huella (equilibrada en cuestas), no un
        // punto suelto. buildHouse ya nivela columna a columna y clava pilotes sobre agua/hielo.
        int cx = cx0;
        int cz = cz0;
        int floorY = TerrainPlanner.meanFirmY(player.getWorld(),
                cx - o.half, cz - o.half, cx + o.half, cz + o.half);
        int[] region = orderRegion(o, cx, cz, floorY);
        // Anti-solape: si ahi ya hay algo construido, prueba 2-3 huecos al lado antes de rechazar.
        if (plugin.buildRegistry().overlaps(region)) {
            final int d = o.half * 2 + 3;
            final int[][] offs = {{d, 0}, {-d, 0}, {0, d}, {0, -d}};
            boolean moved = false;
            for (final int[] off : offs) {
                final int nx = cx0 + off[0];
                final int nz = cz0 + off[1];
                if (!claims.ownsChunk(player.getUniqueId(), nx >> 4, nz >> 4)) {
                    continue;
                }
                final int nfy = TerrainPlanner.meanFirmY(player.getWorld(),
                        nx - o.half, nz - o.half, nx + o.half, nz + o.half);
                final int[] nr = orderRegion(o, nx, nz, nfy);
                if (!plugin.buildRegistry().overlaps(nr)) {
                    cx = nx; cz = nz; floorY = nfy; region = nr; moved = true;
                    break;
                }
            }
            if (!moved) {
                player.sendMessage("§c[Arquitecto] Ahi ya hay algo construido y no vi hueco al lado. "
                        + "Elige otro sitio. No he cobrado nada.");
                return;
            }
            player.sendMessage("§7[Arquitecto] No cabia justo ahi; la pongo unos bloques al lado.");
        }
        final int fCx = cx;
        final int fCz = cz;
        final int fFloorY = floorY;
        final int[] fRegion = region;
        final int p = price(o);
        final Material[] pal = o.palette;
        gateway.pay(player.getUniqueId().toString(), BANCO, p)
                .whenComplete((json, err) -> Bukkit.getScheduler().runTask(plugin, () -> {
                    if (err != null || !json.get("ok").getAsBoolean()) {
                        final String why = (err == null && json.has("error"))
                                ? json.get("error").getAsString() : "no se pudo cobrar";
                        player.sendMessage("§c[Arquitecto] " + why + ". No he construido nada.");
                        return;
                    }
                    undo.snapshot(player, fRegion, p, "tu casa");
                    if ("vanilla".equals(o.style)) {
                        // Casa de aldea REAL de Minecraft (plantilla del propio servidor).
                        final String key = VanillaStructures.placeRandomHouse(
                                player.getWorld(), fCx, fCz, fFloorY);
                        if (key == null) {
                            gateway.pay(BANCO, player.getUniqueId().toString(), p);   // reembolso
                            player.sendMessage("§c[Arquitecto] No pude colocar la casa vanilla; te he "
                                    + "devuelto el dinero. Prueba en otro sitio mas despejado.");
                            return;
                        }
                        plugin.buildRegistry().add(fRegion);
                        player.sendMessage(String.format("§a[Arquitecto] ¡Hecho! Una casa de aldea "
                                + "REAL de Minecraft. Se cobraron §e%d AET§a. Si no te gusta, "
                                + "§f/deshacer§a.", p));
                        pending.remove(player.getUniqueId());
                        return;
                    }
                    final int blocks = Blueprint.buildHouse(player.getWorld(), fCx, fCz, fFloorY, door,
                            o.half, o.floors, pal[0], pal[1], pal[2], pal[3], o.furniture, player.getName());
                    plugin.buildRegistry().add(fRegion);   // registra para que nadie lo pise despues
                    player.sendMessage(String.format("§a[Arquitecto] ¡Hecho! Casa %s de %d plantas (%d "
                            + "bloques). Se cobraron §e%d AET§a. Si no te gusta, §f/deshacer§a.",
                            TIERS.get(o.mat).label(), o.floors, blocks, p));
                    pending.remove(player.getUniqueId());
                }));
    }

    /** Caja 3D del encargo (para anti-solape y deshacer), segun el estilo. */
    private int[] orderRegion(Order o, int cx, int cz, int floorY) {
        return "vanilla".equals(o.style)
                ? VanillaStructures.region(cx, cz, floorY)
                : Blueprint.houseRegion(cx, cz, floorY, o.half, o.floors);
    }

    /** Gamas de material disponibles (las usa tambien el catalogo del creativo). */
    static final String[] TIER_KEYS = {"madera", "piedra", "ladrillo", "lujo"};
    /** Estilos de silueta disponibles (el catalogo los muestra todos). */
    static final String[] STYLE_KEYS = {"casona", "aldeana", "torre"};

    /** Etiqueta legible de una gama ("rustica", "de piedra"...). */
    static String tierLabel(String tier) {
        return TIERS.getOrDefault(tier, TIERS.get("madera")).label();
    }

    /**
     * Medidas de una casa segun tamano (1-3) y estilo, con los mismos dados que usa el
     * arquitecto. Devuelve {half, floors}. Lo comparte el catalogo del creativo para que la
     * galeria muestre EXACTAMENTE lo que el arquitecto construye.
     */
    static int[] dims(int size, String style, Random rng) {
        int half = 2 + size + rng.nextInt(2);
        int floors = size + rng.nextInt(2);
        switch (style) {
            case "torre" -> { floors += 2; half = Math.max(2, half - 1); }
            case "aldeana" -> { floors = Math.min(floors, 2); half = 2 + size; }
            default -> { }
        }
        return new int[] {half, floors};
    }

    static Material[] paletteFor(String tier, String style, Random rng) {
        return "aldeana".equals(style) ? villagePalette(rng) : palette(tier, rng);
    }

    private static Material[] palette(String tier, Random rng) {
        final Tier t = TIERS.getOrDefault(tier, TIERS.get("madera"));
        return new Material[] {
            t.walls()[rng.nextInt(t.walls().length)],
            t.corners()[rng.nextInt(t.corners().length)],
            t.roofs()[rng.nextInt(t.roofs().length)],
            t.accents()[rng.nextInt(t.accents().length)],
        };
    }

    /** Paleta del estilo ALDEANA: recrea el look de las casas de aldea vanilla (entramado de
     *  madera sobre base de piedra), independientemente de la gama elegida. */
    private static Material[] villagePalette(Random rng) {
        final Material[] walls = {Material.OAK_PLANKS, Material.SPRUCE_PLANKS, Material.BIRCH_PLANKS};
        final Material[] corners = {Material.OAK_LOG, Material.SPRUCE_LOG, Material.STRIPPED_OAK_LOG};
        final Material[] roofs = {Material.COBBLESTONE, Material.SPRUCE_PLANKS, Material.DARK_OAK_PLANKS};
        final Material[] accents = {Material.COBBLESTONE, Material.MOSSY_COBBLESTONE,
                Material.STRIPPED_SPRUCE_LOG};
        return new Material[] {
            walls[rng.nextInt(walls.length)], corners[rng.nextInt(corners.length)],
            roofs[rng.nextInt(roofs.length)], accents[rng.nextInt(accents.length)],
        };
    }

    private void showServices(Player player) {
        player.sendMessage("§6=== Servicios de Aetheria ===");
        player.sendMessage("§eArquitecto §7(/arquitecto): casa unica, guiada. Gamas rustica/piedra/"
                + "noble/lujo; cada casa varia en materiales, ancho, altura, tejado y ventanas.");
        player.sendMessage("§eDecorador §7(/decorador): jardin, farola, estatua o fuente (15-60 AET).");
        player.sendMessage("§eViaje §7(/warps): plaza, mercado, taberna, spawn.");
        player.sendMessage("§eParcelas §7(/claim): pequena/mediana/grande (1x1..3x3), comprar o "
                + "alquilar. Deshacer construccion: §f/deshacer§7.");
        player.sendMessage("§eGanar §7: trabaja (mina/tala/cosecha/caza) o vende con /sell.");
    }

    private Component opt(String label, String cmd) {
        return Component.text(label).clickEvent(ClickEvent.runCommand(cmd));
    }

    /** Direccion cardinal (N/S/E/O) hacia donde mira el jugador. */
    private static BlockFace flatFacing(Player player) {
        final BlockFace f = player.getFacing();
        if (f != BlockFace.UP && f != BlockFace.DOWN) {
            return f;
        }
        final int i = Math.round(player.getLocation().getYaw() / 90f) & 3;
        return switch (i) {
            case 1 -> BlockFace.WEST;
            case 2 -> BlockFace.NORTH;
            case 3 -> BlockFace.EAST;
            default -> BlockFace.SOUTH;
        };
    }

    private static String sizeName(int size) {
        return size == 1 ? "small" : size == 2 ? "medium" : "large";
    }
}
