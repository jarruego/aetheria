package com.aetheria.plugin;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import net.kyori.adventure.text.Component;

/**
 * MERCADO fisico con menu de INVENTARIO. Cada aldea (con 6+ habitantes) tiene un mercader; al
 * hacerle clic derecho se abre una ventana con los generos:
 *   · CLIC IZQUIERDO  = comprar 1 (paga; cuesta un poco mas que el precio de venta = margen).
 *   · CLIC DERECHO    = vender TODO lo que lleves de ese tipo (cobra).
 * Mucho mas usable que por comandos. La economia va por el gateway (pay/reward), como siempre.
 */
public final class MarketModule implements Listener {

    private static final String TRADER_TAG = "aetheria_market";
    private static final String BANCO = "00000000-0000-0000-0000-000000000000";
    private static final double BUY_MARKUP = 1.6;   // comprar cuesta 1.6x el precio de venta

    // Generos ofertados en la ventana (los mas habituales de comerciar).
    private static final Material[] GOODS = {
        Material.WHEAT, Material.BREAD, Material.CARROT, Material.POTATO, Material.BEETROOT,
        Material.PUMPKIN, Material.MELON_SLICE, Material.COAL, Material.COPPER_INGOT,
        Material.IRON_INGOT, Material.GOLD_INGOT, Material.DIAMOND, Material.EMERALD,
        Material.OAK_LOG, Material.SPRUCE_LOG, Material.COBBLESTONE, Material.STONE,
        Material.STRING, Material.BONE, Material.GUNPOWDER,
    };

    private final AetheriaPlugin plugin;
    private final GatewayClient gateway;
    private QuestModule quests;   // opcional: hay encargos de "vende N generos en el mercado"

    public MarketModule(AetheriaPlugin plugin, GatewayClient gateway) {
        this.plugin = plugin;
        this.gateway = gateway;
    }

    /** Engancha las misiones (se inyecta despues, para evitar el ciclo de construccion). */
    public void setQuests(QuestModule quests) {
        this.quests = quests;
    }

    /** Marca nuestra ventana para reconocerla en los clics. */
    private static final class ShopHolder implements InventoryHolder {
        @Override
        public Inventory getInventory() {
            return null;
        }
    }

    /** Se asegura de que hay UN mercader en el punto (spawnea solo si falta; asi sobrevive a
     *  reinicios sin duplicarse). */
    public void ensureTrader(Location loc, String town) {
        for (final org.bukkit.entity.Entity e : loc.getWorld().getNearbyEntities(loc, 3, 4, 3)) {
            if (e.getScoreboardTags().contains(TRADER_TAG)) {
                return;   // ya hay mercader
            }
        }
        final Villager v = (Villager) loc.getWorld().spawnEntity(loc, EntityType.VILLAGER);
        v.customName(Component.text("§6Mercader de " + town));
        v.setCustomNameVisible(true);
        v.setProfession(Villager.Profession.CARTOGRAPHER);
        v.setVillagerType(Villager.Type.PLAINS);
        v.setVillagerLevel(5);
        v.setInvulnerable(true);
        v.setPersistent(true);
        v.setRemoveWhenFarAway(false);
        v.setAI(false);   // quieto tras el mostrador
        v.addScoreboardTag(TRADER_TAG);
        DisguiseModule.humanize(v, "m", "Mercader", "trader");   // aspecto humano (si hay plugin)
    }

    @EventHandler
    public void onClickTrader(PlayerInteractEntityEvent e) {
        if (!e.getRightClicked().getScoreboardTags().contains(TRADER_TAG)) {
            return;
        }
        e.setCancelled(true);
        openShop(e.getPlayer());
    }

    private void openShop(Player player) {
        final int size = Math.min(54, (((GOODS.length + 8) / 9) + 1) * 9);
        final Inventory inv = Bukkit.createInventory(new ShopHolder(), size,
                Component.text("§6Mercado del pueblo"));
        for (final Material g : GOODS) {
            final Double sell = ShopModule.priceOf(g);
            if (sell == null) {
                continue;
            }
            final double buy = Math.round(sell * BUY_MARKUP * 100.0) / 100.0;
            final ItemStack icon = new ItemStack(g);
            final ItemMeta meta = icon.getItemMeta();
            final List<Component> lore = new ArrayList<>();
            lore.add(Component.text("§aClic izq: COMPRAR 1  §7(" + buy + " AET)"));
            lore.add(Component.text("§eClic der: VENDER todo  §7(" + sell + " AET/ud)"));
            meta.lore(lore);
            icon.setItemMeta(meta);
            inv.addItem(icon);
        }
        player.openInventory(inv);
    }

    @EventHandler
    public void onShopClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof ShopHolder)) {
            return;
        }
        e.setCancelled(true);   // nada se coge a mano de la ventana
        if (!(e.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (e.getRawSlot() < 0 || e.getRawSlot() >= e.getInventory().getSize()) {
            return;   // clic en el inventario propio del jugador: se ignora
        }
        final ItemStack clicked = e.getCurrentItem();
        if (clicked == null || clicked.getType() == Material.AIR) {
            return;
        }
        final Material g = clicked.getType();
        if (e.getClick() == ClickType.RIGHT) {
            sellAll(player, g);
        } else if (e.getClick() == ClickType.LEFT) {
            buyOne(player, g);
        }
    }

    private void sellAll(Player player, Material g) {
        final Double sell = ShopModule.priceOf(g);
        if (sell == null) {
            return;
        }
        int count = 0;
        for (final ItemStack it : player.getInventory().getStorageContents()) {
            if (it != null && it.getType() == g) {
                count += it.getAmount();
            }
        }
        if (count <= 0) {
            player.sendMessage("§7No llevas " + nice(g) + " que vender.");
            return;
        }
        player.getInventory().remove(g);
        final double total = Math.round(sell * count * 100.0) / 100.0;
        final int sold = count;
        gateway.reward(player.getUniqueId().toString(), total, "mercado:" + g.name().toLowerCase())
                .whenComplete((json, err) -> Bukkit.getScheduler().runTask(plugin, () -> {
                    if (err != null || !json.get("ok").getAsBoolean()) {
                        player.getInventory().addItem(new ItemStack(g, sold));   // devuelve
                        player.sendMessage("§cNo se pudo vender. Intentalo de nuevo.");
                        return;
                    }
                    player.sendMessage(String.format("§aVendidos §f%d %s §apor §e%.2f AET§a.",
                            sold, nice(g), total));
                    if (quests != null) {
                        quests.onSold(player, sold);   // cuenta para los encargos del mercado
                    }
                }));
    }

    private void buyOne(Player player, Material g) {
        final Double sell = ShopModule.priceOf(g);
        if (sell == null) {
            return;
        }
        final double cost = Math.round(sell * BUY_MARKUP * 100.0) / 100.0;
        gateway.pay(player.getUniqueId().toString(), BANCO, cost)
                .whenComplete((json, err) -> Bukkit.getScheduler().runTask(plugin, () -> {
                    if (err != null || !json.get("ok").getAsBoolean()) {
                        final String why = (err == null && json.has("error"))
                                ? json.get("error").getAsString() : "no tienes suficiente AET";
                        player.sendMessage("§c" + why + ".");
                        return;
                    }
                    player.getInventory().addItem(new ItemStack(g, 1));
                    player.sendMessage(String.format("§aComprado §f1 %s §apor §e%.2f AET§a.",
                            nice(g), cost));
                }));
    }

    private static String nice(Material m) {
        return m.name().toLowerCase().replace('_', ' ');
    }
}
