package com.aetheria.plugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;

/**
 * Arquitecto guiado: un asistente conversacional (por menus clicables en el chat) que te
 * ayuda a encargar una casa a medida. Vas eligiendo tamano, material y si la quieres
 * amueblada; el arquitecto CALCULA el precio segun lo que pides y solo construye cuando
 * confirmas y pagas. Para construir necesitas ser dueno de la parcela (una peticion viable).
 *
 * Comandos: /arquitecto (empieza) y sus pasos; /servicios (guia de servicios y precios).
 */
public final class ArchitectModule implements CommandExecutor {

    private static final String BANCO = "00000000-0000-0000-0000-000000000000";

    /** Encargo en curso de un jugador. */
    private static final class Order {
        int half = 0;            // 2=pequena, 3=mediana, 4=grande (0 = sin elegir)
        String mat;              // clave de material
        boolean furniture;
        boolean furnitureSet;
    }

    private final AetheriaPlugin plugin;
    private final GatewayClient gateway;
    private final ClaimModule claims;
    private final Map<UUID, Order> orders = new ConcurrentHashMap<>();

    public ArchitectModule(AetheriaPlugin plugin, GatewayClient gateway, ClaimModule claims) {
        this.plugin = plugin;
        this.gateway = gateway;
        this.claims = claims;
    }

    // --- Tabla de precios ---
    private static int basePrice(int half) {
        return switch (half) {
            case 2 -> 80;
            case 3 -> 160;
            case 4 -> 280;
            default -> 0;
        };
    }

    private static double matMult(String mat) {
        return switch (mat) {
            case "madera" -> 1.0;
            case "piedra" -> 1.3;
            case "ladrillo" -> 1.5;
            case "lujo" -> 2.0;
            default -> 1.0;
        };
    }

    /** {muro, esquina, tejado} segun el material elegido. */
    private static Material[] palette(String mat) {
        return switch (mat) {
            case "piedra" -> new Material[] {Material.STONE_BRICKS, Material.CHISELED_STONE_BRICKS,
                    Material.COBBLESTONE};
            case "ladrillo" -> new Material[] {Material.BRICKS, Material.CHISELED_STONE_BRICKS,
                    Material.DARK_OAK_PLANKS};
            case "lujo" -> new Material[] {Material.QUARTZ_BLOCK, Material.QUARTZ_PILLAR,
                    Material.SMOOTH_QUARTZ};
            default -> new Material[] {Material.OAK_PLANKS, Material.SPRUCE_LOG,
                    Material.DARK_OAK_PLANKS};   // madera
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
            case "cancelar" -> {
                orders.remove(player.getUniqueId());
                player.sendMessage("§7Encargo cancelado.");
            }
            default -> start(player);
        }
        return true;
    }

    private void start(Player player) {
        orders.put(player.getUniqueId(), new Order());
        player.sendMessage("§6[Arquitecto] §fBuenas. Te construyo una casa a medida donde estes,"
                + " pero antes cuentame que quieres. Primero, §e¿de que tamano?");
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
        o.half = switch (s.toLowerCase()) {
            case "small", "pequena", "pequeña" -> 2;
            case "medium", "mediana" -> 3;
            case "large", "grande" -> 4;
            default -> 0;
        };
        if (o.half == 0) { start(player); return; }
        player.sendMessage("§6[Arquitecto] §fBien. §e¿De que material la levanto?");
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
        player.sendMessage("§6[Arquitecto] §f¿La quieres §eamueblada§f? (cama, mesa, cofre, horno)");
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
        player.sendMessage(String.format("§6[Arquitecto] §fUna casa §e%s§f de §e%s§f%s. "
                + "Mi presupuesto: §a%d AET§f.", sizeLabel(o.half), o.mat,
                o.furniture ? " amueblada" : " vacia", p));
        player.sendMessage("§7Ponte donde la quieras (sobre tu parcela) y confirma:");
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
        // Peticion viable: hay que ser dueno de la parcela donde vas a construir.
        final int chunkX = player.getLocation().getBlockX() >> 4;
        final int chunkZ = player.getLocation().getBlockZ() >> 4;
        if (!claims.ownsChunk(player.getUniqueId(), chunkX, chunkZ)) {
            player.sendMessage("§c[Arquitecto] Solo construyo en tu propia parcela. Reclama este "
                    + "sitio con §f/claim§c (o ponte sobre una parcela tuya) y vuelve a confirmar.");
            return;
        }
        final int p = price(o);
        gateway.pay(player.getUniqueId().toString(), BANCO, p)
                .whenComplete((json, err) -> Bukkit.getScheduler().runTask(plugin, () -> {
                    if (err != null || !json.get("ok").getAsBoolean()) {
                        final String why = (err == null && json.has("error"))
                                ? json.get("error").getAsString() : "no se pudo cobrar";
                        player.sendMessage("§c[Arquitecto] " + why + ". No he construido nada.");
                        return;
                    }
                    final Material[] pal = palette(o.mat);
                    final int blocks = Blueprint.buildHouse(player, o.half, pal[0], pal[1], pal[2], o.furniture);
                    player.sendMessage(String.format("§a[Arquitecto] ¡Hecho! Tu casa %s de %s%s, "
                            + "aqui delante (%d bloques). Se cobraron §e%d AET§a.",
                            sizeLabel(o.half), o.mat, o.furniture ? " amueblada" : "", blocks, p));
                    orders.remove(player.getUniqueId());
                }));
    }

    private void showServices(Player player) {
        player.sendMessage("§6=== Servicios de Aetheria ===");
        player.sendMessage("§eArquitecto §7(/arquitecto): casa a medida, guiada.");
        player.sendMessage("§7  Tamano: pequena 80 · mediana 160 · grande 280 (base)");
        player.sendMessage("§7  Material x: madera 1.0 · piedra 1.3 · ladrillo 1.5 · lujo 2.0");
        player.sendMessage("§7  Amueblada: +40. (Ej.: mediana de piedra amueblada = 248 AET)");
        player.sendMessage("§eDecorador §7(/decorador): jardin, farola, estatua o fuente en tu "
                + "parcela (15-60 AET).");
        player.sendMessage("§eViaje §7(/warps): plaza, mercado, taberna, spawn.");
        player.sendMessage("§eParcelas §7(/claim): §fcomprar§7 (50, para siempre) o §falquilar§7 "
                + "(10 + renta cada periodo; si no pagas, se libera). Protege tu terreno.");
        player.sendMessage("§eGanar §7: trabaja (mina/tala/cosecha/caza) o vende con /sell.");
    }

    private Component opt(String label, String cmd) {
        return Component.text(label).clickEvent(ClickEvent.runCommand(cmd));
    }

    private static String sizeName(int half) {
        return half == 2 ? "small" : half == 3 ? "medium" : "large";
    }

    private static String sizeLabel(int half) {
        return half == 2 ? "pequena" : half == 3 ? "mediana" : "grande";
    }
}
