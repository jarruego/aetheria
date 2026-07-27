package com.aetheria.plugin;

import java.util.EnumMap;
import java.util.Map;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

/**
 * Mercado del pueblo: el jugador VENDE recursos por AET al mercado del sistema.
 *   /sell [all]  -> vende el objeto de la mano (o todos los de ese tipo)
 *   /worth       -> muestra cuanto vale el objeto de la mano
 *   /shop        -> lista precios de referencia
 *
 * <p>Comprar/vender genera un flujo economico real (el dinero sale del sistema, como en un
 * mercado); junto con los Trabajos, da formas claras de ganarse la vida.
 */
public final class ShopModule implements CommandExecutor {

    // Precio de venta por unidad (AET).
    private static final Map<Material, Double> PRICE = new EnumMap<>(Material.class);

    static {
        put(0.4, Material.WHEAT); put(0.3, Material.CARROT, Material.POTATO, Material.BEETROOT);
        put(0.6, Material.BREAD); put(0.5, Material.PUMPKIN, Material.MELON_SLICE);
        put(0.5, Material.COAL, Material.CHARCOAL); put(0.3, Material.REDSTONE);
        put(0.4, Material.LAPIS_LAZULI); put(0.6, Material.QUARTZ);
        put(0.8, Material.COPPER_INGOT); put(3.0, Material.IRON_INGOT); put(5.0, Material.GOLD_INGOT);
        put(12.0, Material.DIAMOND); put(10.0, Material.EMERALD); put(15.0, Material.NETHERITE_SCRAP);
        put(0.3, Material.OAK_LOG, Material.SPRUCE_LOG, Material.BIRCH_LOG, Material.JUNGLE_LOG,
                Material.ACACIA_LOG, Material.DARK_OAK_LOG);
        put(0.1, Material.ROTTEN_FLESH); put(0.5, Material.BONE, Material.STRING, Material.SPIDER_EYE);
        put(1.0, Material.GUNPOWDER); put(3.0, Material.ENDER_PEARL); put(4.0, Material.BLAZE_ROD);
        put(0.2, Material.COBBLESTONE); put(0.3, Material.STONE, Material.DIRT);
    }

    private static void put(double v, Material... mats) {
        for (final Material m : mats) {
            PRICE.put(m, v);
        }
    }

    /** Precio de venta por unidad (AET) de un material, o null si no se compra. Lo usa el mercado. */
    public static Double priceOf(Material m) {
        return PRICE.get(m);
    }

    private final AetheriaPlugin plugin;
    private final GatewayClient gateway;

    public ShopModule(AetheriaPlugin plugin, GatewayClient gateway) {
        this.plugin = plugin;
        this.gateway = gateway;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
            @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Solo los jugadores pueden comerciar.");
            return true;
        }
        switch (command.getName().toLowerCase()) {
            case "shop" -> showPrices(player);
            case "worth" -> worth(player);
            case "sell" -> sell(player, args.length >= 1 && args[0].equalsIgnoreCase("all"));
            default -> { }
        }
        return true;
    }

    private void worth(Player player) {
        final ItemStack hand = player.getInventory().getItemInMainHand();
        final Double price = PRICE.get(hand.getType());
        if (hand.getType() == Material.AIR || price == null) {
            player.sendMessage("§7Eso no se compra en el mercado. Usa §f/shop§7 para ver precios.");
            return;
        }
        player.sendMessage(String.format("§e%s§7 vale §a%.2f AET§7/ud (x%d = §a%.2f AET§7).",
                nice(hand.getType()), price, hand.getAmount(), price * hand.getAmount()));
    }

    private void sell(Player player, boolean all) {
        final ItemStack hand = player.getInventory().getItemInMainHand();
        final Material type = hand.getType();
        final Double price = PRICE.get(type);
        if (type == Material.AIR || price == null) {
            player.sendMessage("§7Eso no se compra en el mercado. Usa §f/shop§7 para ver precios.");
            return;
        }
        int count = 0;
        if (all) {
            for (final ItemStack it : player.getInventory().getStorageContents()) {
                if (it != null && it.getType() == type) {
                    count += it.getAmount();
                }
            }
            player.getInventory().remove(type);
        } else {
            count = hand.getAmount();
            player.getInventory().setItemInMainHand(null);
        }
        if (count <= 0) {
            player.sendMessage("§7No tienes nada que vender.");
            return;
        }
        final double total = Math.round(price * count * 100.0) / 100.0;
        final int sold = count;
        gateway.reward(player.getUniqueId().toString(), total, "venta:" + type.name().toLowerCase())
                .whenComplete((json, err) -> Bukkit.getScheduler().runTask(plugin, () -> {
                    if (err != null || !json.get("ok").getAsBoolean()) {
                        player.sendMessage("§cNo se pudo completar la venta. Intentalo de nuevo.");
                        // devuelve los items para no perderlos
                        player.getInventory().addItem(new ItemStack(type, sold));
                        return;
                    }
                    player.sendMessage(String.format("§aVendidos §f%d %s §apor §e%.2f AET§a.",
                            sold, nice(type), total));
                }));
    }

    private void showPrices(Player player) {
        player.sendMessage("§6=== Mercado de Aetheria (venta) ===");
        player.sendMessage("§7Trigo §a0.4  §7Hierro §a3  §7Oro §a5  §7Diamante §a12  §7Esmeralda §a10");
        player.sendMessage("§7Carbon §a0.5  §7Perla ender §a3  §7Vara de blaze §a4  §7Polvora §a1");
        player.sendMessage("§7Vende lo que lleves en la mano con §f/sell §7(o §f/sell all§7).");
        player.sendMessage("§7Mira el valor exacto de un objeto con §f/worth§7.");
    }

    private static String nice(Material m) {
        return m.name().toLowerCase().replace('_', ' ');
    }
}
