package com.aetheria.plugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.Material;
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
 * Arquitecto guiado: un asistente (por menus clicables) que te ayuda a encargar una casa a
 * medida. Eliges tamano (1-3 plantas), material y mobiliario; calcula el precio; y al
 * confirmar te pide que HAGAS CLIC DERECHO donde quieres la puerta, sobre tu parcela. Solo
 * entonces cobra y construye. Las casas son multiplanta, con terraza, y cada una es unica.
 */
public final class ArchitectModule implements CommandExecutor, Listener {

    private static final String BANCO = "00000000-0000-0000-0000-000000000000";

    /** Encargo en curso de un jugador. */
    private static final class Order {
        int half = 0;            // 2=pequena, 3=mediana, 4=grande (0 = sin elegir)
        String mat;
        boolean furniture;
        boolean furnitureSet;
    }

    private final AetheriaPlugin plugin;
    private final GatewayClient gateway;
    private final ClaimModule claims;
    private final UndoModule undo;
    private final Map<UUID, Order> orders = new ConcurrentHashMap<>();   // configurando
    private final Map<UUID, Order> pending = new ConcurrentHashMap<>();  // confirmado, esperando sitio

    public ArchitectModule(AetheriaPlugin plugin, GatewayClient gateway, ClaimModule claims,
            UndoModule undo) {
        this.plugin = plugin;
        this.gateway = gateway;
        this.claims = claims;
        this.undo = undo;
    }

    // --- Precio y estilo ---  (half = mitad del ancho: 3->7x7, 4->9x9, 5->11x11)
    private static int basePrice(int half) {
        return switch (half) {
            case 3 -> 80;    // pequena: 7x7, 1 planta
            case 4 -> 200;   // mediana: 9x9, 2 plantas
            case 5 -> 400;   // grande: 11x11, 3 plantas
            default -> 0;
        };
    }

    private static int floors(int half) {
        return half - 2;   // 3->1, 4->2, 5->3
    }

    private static double matMult(String mat) {
        return switch (mat) {
            case "piedra" -> 1.3;
            case "ladrillo" -> 1.5;
            case "lujo" -> 2.0;
            default -> 1.0;   // madera
        };
    }

    /** {muro, esquina, tejado/terraza, acento} segun el material elegido. */
    private static Material[] palette(String mat) {
        return switch (mat) {
            case "piedra" -> new Material[] {Material.STONE_BRICKS, Material.COBBLED_DEEPSLATE,
                    Material.STONE_BRICK_SLAB, Material.CHISELED_STONE_BRICKS};
            case "ladrillo" -> new Material[] {Material.BRICKS, Material.DEEPSLATE_BRICKS,
                    Material.DARK_OAK_SLAB, Material.MUD_BRICKS};
            case "lujo" -> new Material[] {Material.QUARTZ_BLOCK, Material.QUARTZ_PILLAR,
                    Material.SMOOTH_QUARTZ_SLAB, Material.GOLD_BLOCK};
            default -> new Material[] {Material.OAK_PLANKS, Material.SPRUCE_LOG,
                    Material.DARK_OAK_SLAB, Material.STRIPPED_SPRUCE_LOG};   // madera
        };
    }

    private static int price(Order o) {
        return (int) Math.round(basePrice(o.half) * matMult(o.mat) + (o.furniture ? 40 : 0));
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
        player.sendMessage("§6[Arquitecto] §fBuenas. Te hago una casa a medida. Primero, "
                + "§e¿de que tamano? §7(1 a 3 plantas)");
        player.sendMessage(Component.text("  ")
                .append(opt("§a[Pequena · 1 planta]", "/arquitecto size small"))
                .append(Component.text("  "))
                .append(opt("§a[Mediana · 2 plantas]", "/arquitecto size medium"))
                .append(Component.text("  "))
                .append(opt("§a[Grande · 3 plantas]", "/arquitecto size large")));
    }

    private void setSize(Player player, String s) {
        final Order o = orders.get(player.getUniqueId());
        if (o == null) { start(player); return; }
        o.half = switch (s.toLowerCase()) {
            case "small", "pequena", "pequeña" -> 3;
            case "medium", "mediana" -> 4;
            case "large", "grande" -> 5;
            default -> 0;
        };
        if (o.half == 0) { start(player); return; }
        player.sendMessage("§6[Arquitecto] §fBien. §e¿De que material?");
        player.sendMessage(Component.text("  ")
                .append(opt("§a[Madera]", "/arquitecto mat madera"))
                .append(Component.text("  "))
                .append(opt("§a[Piedra]", "/arquitecto mat piedra"))
                .append(Component.text("  "))
                .append(opt("§a[Ladrillo]", "/arquitecto mat ladrillo"))
                .append(Component.text("  "))
                .append(opt("§b[Lujo]", "/arquitecto mat lujo")));
    }

    private void setMat(Player player, String m) {
        final Order o = orders.get(player.getUniqueId());
        if (o == null || o.half == 0) { start(player); return; }
        m = m.toLowerCase();
        if (!java.util.Set.of("madera", "piedra", "ladrillo", "lujo").contains(m)) {
            setSize(player, sizeName(o.half));
            return;
        }
        o.mat = m;
        player.sendMessage("§6[Arquitecto] §f¿La quieres §eamueblada§f? (cocina, mesa, cofre, cama)");
        player.sendMessage(Component.text("  ")
                .append(opt("§a[Si, amueblada]", "/arquitecto muebles si"))
                .append(Component.text("  "))
                .append(opt("§7[No, vacia]", "/arquitecto muebles no")));
    }

    private void setFurniture(Player player, String f) {
        final Order o = orders.get(player.getUniqueId());
        if (o == null || o.half == 0 || o.mat == null) { start(player); return; }
        o.furniture = f.toLowerCase().startsWith("s") || f.equalsIgnoreCase("yes");
        o.furnitureSet = true;
        final int p = price(o);
        player.sendMessage(String.format("§6[Arquitecto] §fUna casa §e%s§f (%d plantas) de §e%s§f%s. "
                + "Presupuesto: §a%d AET§f.", sizeLabel(o.half), floors(o.half), o.mat,
                o.furniture ? " amueblada" : " vacia", p));
        player.sendMessage(Component.text("  ")
                .append(opt("§a[Confirmar por " + p + " AET]", "/arquitecto confirmar"))
                .append(Component.text("  "))
                .append(opt("§c[Cancelar]", "/arquitecto cancelar")));
    }

    private void confirm(Player player) {
        final Order o = orders.get(player.getUniqueId());
        if (o == null || o.half == 0 || o.mat == null || !o.furnitureSet) {
            player.sendMessage("§7No tienes un encargo listo. Empieza con §f/arquitecto§7.");
            return;
        }
        orders.remove(player.getUniqueId());
        pending.put(player.getUniqueId(), o);
        player.sendMessage("§6[Arquitecto] §fPerfecto. Ahora §eHAZ CLIC DERECHO en el suelo§f "
                + "donde quieras la §epuerta§f (sobre tu parcela).");
        player.sendMessage("§7La casa se levantara hacia donde mires. O escribe §f/arquitecto aqui§7 "
                + "para ponerla justo delante de ti.");
    }

    /** Clic derecho en el suelo: ahi va la puerta; la casa crece hacia donde mira el jugador. */
    @EventHandler
    public void onPlace(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        final Player player = event.getPlayer();
        final Order o = pending.get(player.getUniqueId());
        if (o == null || event.getClickedBlock() == null) {
            return;
        }
        event.setCancelled(true);
        final BlockFace f = flatFacing(player);
        final var c = event.getClickedBlock();
        final int cx = c.getX() + f.getModX() * o.half;
        final int cz = c.getZ() + f.getModZ() * o.half;
        buildAt(player, o, cx, cz, c.getY(), f.getOppositeFace());
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

    private void buildAt(Player player, Order o, int cx, int cz, int floorY, BlockFace door) {
        if (!claims.ownsChunk(player.getUniqueId(), cx >> 4, cz >> 4)) {
            player.sendMessage("§c[Arquitecto] Ahi no es tu parcela. Reclamala con §f/claim§c o "
                    + "elige un punto sobre tu terreno.");
            return;   // sigue pendiente para reintentar
        }
        final int p = price(o);
        final int floors = floors(o.half);
        final Material[] pal = palette(o.mat);
        gateway.pay(player.getUniqueId().toString(), BANCO, p)
                .whenComplete((json, err) -> Bukkit.getScheduler().runTask(plugin, () -> {
                    if (err != null || !json.get("ok").getAsBoolean()) {
                        final String why = (err == null && json.has("error"))
                                ? json.get("error").getAsString() : "no se pudo cobrar";
                        player.sendMessage("§c[Arquitecto] " + why + ". No he construido nada.");
                        return;
                    }
                    undo.snapshot(player, Blueprint.houseRegion(cx, cz, floorY, o.half, floors), p, "tu casa");
                    final int blocks = Blueprint.buildHouse(player.getWorld(), cx, cz, floorY, door,
                            o.half, floors, pal[0], pal[1], pal[2], pal[3], o.furniture);
                    player.sendMessage(String.format("§a[Arquitecto] ¡Hecho! Casa %s de %d plantas (%d "
                            + "bloques). Se cobraron §e%d AET§a. Si no te gusta, §f/deshacer§a.",
                            sizeLabel(o.half), floors, blocks, p));
                    pending.remove(player.getUniqueId());
                }));
    }

    private void showServices(Player player) {
        player.sendMessage("§6=== Servicios de Aetheria ===");
        player.sendMessage("§eArquitecto §7(/arquitecto): casa a medida guiada, multiplanta y con terraza.");
        player.sendMessage("§7  Tamano: pequena 80 (1) · mediana 200 (2) · grande 400 (3 plantas)");
        player.sendMessage("§7  Material x: madera 1.0 · piedra 1.3 · ladrillo 1.5 · lujo 2.0 · muebles +40");
        player.sendMessage("§eDecorador §7(/decorador): jardin, farola, estatua o fuente (15-60 AET).");
        player.sendMessage("§eViaje §7(/warps): plaza, mercado, taberna, spawn.");
        player.sendMessage("§eParcelas §7(/claim): §fcomprar§7 (50, para siempre) o §falquilar§7 "
                + "(10 + renta; si no pagas, se libera).");
        player.sendMessage("§eGanar §7: trabaja (mina/tala/cosecha/caza) o vende con /sell. §fDeshacer§7: /deshacer.");
    }

    private Component opt(String label, String cmd) {
        return Component.text(label).clickEvent(ClickEvent.runCommand(cmd));
    }

    /** Direccion cardinal (N/S/E/O) hacia donde mira el jugador. */
    private static BlockFace flatFacing(Player player) {
        final float yaw = player.getLocation().getYaw();
        final BlockFace f = player.getFacing();
        if (f == BlockFace.UP || f == BlockFace.DOWN) {
            final int i = Math.round(yaw / 90f) & 3;
            return switch (i) {
                case 1 -> BlockFace.WEST;
                case 2 -> BlockFace.NORTH;
                case 3 -> BlockFace.EAST;
                default -> BlockFace.SOUTH;
            };
        }
        return f;
    }

    private static String sizeName(int half) {
        return half == 3 ? "small" : half == 4 ? "medium" : "large";
    }

    private static String sizeLabel(int half) {
        return half == 3 ? "pequena" : half == 4 ? "mediana" : "grande";
    }
}
