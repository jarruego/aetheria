package com.aetheria.plugin;

import java.util.LinkedHashMap;
import java.util.Map;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.jetbrains.annotations.NotNull;

import net.kyori.adventure.text.Component;

/**
 * Decorador: servicio guiado para embellecer TU parcela con pequenas estructuras
 * (jardin, farola, estatua, gran fuente). Eliges en un menu, se cobra segun la pieza y solo
 * se construye si eres dueno del terreno. Complementa al arquitecto (que hace casas).
 */
public final class DecoratorModule implements CommandExecutor, org.bukkit.event.Listener {

    private static final String BANCO = "00000000-0000-0000-0000-000000000000";

    private record Deco(String blueprint, int price, String label) {}

    // clave -> (blueprint, precio, etiqueta)
    private static final Map<String, Deco> CATALOG = new LinkedHashMap<>();

    static {
        CATALOG.put("jardin", new Deco("garden", 40, "Jardin con flores"));
        CATALOG.put("farola", new Deco("lamppost", 15, "Farola"));
        CATALOG.put("estatua", new Deco("statue", 50, "Estatua"));
        CATALOG.put("fuente", new Deco("bigfountain", 60, "Gran fuente"));
    }

    private final AetheriaPlugin plugin;
    private final GatewayClient gateway;
    private final ClaimModule claims;
    private final UndoModule undo;

    public DecoratorModule(AetheriaPlugin plugin, GatewayClient gateway, ClaimModule claims,
            UndoModule undo) {
        this.plugin = plugin;
        this.gateway = gateway;
        this.claims = claims;
        this.undo = undo;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
            @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Solo los jugadores pueden usar el decorador.");
            return true;
        }
        if (args.length == 0) {
            menu(player);
            return true;
        }
        final Deco deco = CATALOG.get(args[0].toLowerCase());
        if (deco == null) {
            menu(player);
            return true;
        }
        build(player, deco);
        return true;
    }

    /** Iconos de cada decoracion en la ventana. */
    private static final java.util.Map<String, Material> ICONS = java.util.Map.of(
        "jardin", Material.POPPY, "farola", Material.LANTERN,
        "estatua", Material.STONE_BRICK_WALL, "fuente", Material.WATER_BUCKET);

    /** Ventana con marca (para reconocer los clics del decorador). */
    private static final class DecoHolder implements org.bukkit.inventory.InventoryHolder {
        final String[] keys = new String[9];

        @Override
        public org.bukkit.inventory.Inventory getInventory() {
            return null;
        }
    }

    /** El decorador tambien se maneja con una CAJA DE INVENTARIO, no clicando texto en el chat. */
    private void menu(Player player) {
        final DecoHolder holder = new DecoHolder();
        final org.bukkit.inventory.Inventory inv = Bukkit.createInventory(holder, 9,
                Component.text("§6Decorador de Aetheria"));
        int slot = 1;
        for (final var e : CATALOG.entrySet()) {
            final Material icon = ICONS.getOrDefault(e.getKey(), Material.PAPER);
            final org.bukkit.inventory.ItemStack it = new org.bukkit.inventory.ItemStack(icon);
            final org.bukkit.inventory.meta.ItemMeta m = it.getItemMeta();
            m.displayName(Component.text("§a" + e.getValue().label()));
            m.lore(java.util.List.of(
                    Component.text("§7Precio: §e" + e.getValue().price() + " AET"),
                    Component.text("§7Se construye frente a ti,"),
                    Component.text("§7sobre tu parcela (§f/claim§7).")));
            it.setItemMeta(m);
            inv.setItem(slot, it);
            holder.keys[slot] = e.getKey();
            slot += 2;
        }
        player.openInventory(inv);
    }

    @EventHandler
    public void onMenuClick(org.bukkit.event.inventory.InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof DecoHolder holder)) {
            return;
        }
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player player)) {
            return;
        }
        final int slot = e.getRawSlot();
        if (slot < 0 || slot >= holder.keys.length || holder.keys[slot] == null) {
            return;
        }
        final Deco deco = CATALOG.get(holder.keys[slot]);
        player.closeInventory();
        if (deco != null) {
            Bukkit.getScheduler().runTask(plugin, () -> build(player, deco));
        }
    }

    private void build(Player player, Deco deco) {
        final int chunkX = player.getLocation().getBlockX() >> 4;
        final int chunkZ = player.getLocation().getBlockZ() >> 4;
        if (!claims.ownsChunk(player.getUniqueId(), chunkX, chunkZ)) {
            player.sendMessage("§c[Decorador] Solo decoro en tu parcela. Reclama este sitio con "
                    + "§f/claim§c y vuelve a intentarlo.");
            return;
        }
        // Anti-solape: no decoro encima de algo ya construido.
        final int[] region = Blueprint.buildRegion(player, deco.blueprint(), 0);
        if (plugin.buildRegistry().overlaps(region)) {
            player.sendMessage("§c[Decorador] Ahi ya hay algo. Elige otro hueco. No he cobrado nada.");
            return;
        }
        gateway.pay(player.getUniqueId().toString(), BANCO, deco.price())
                .whenComplete((json, err) -> Bukkit.getScheduler().runTask(plugin, () -> {
                    if (err != null || !json.get("ok").getAsBoolean()) {
                        final String why = (err == null && json.has("error"))
                                ? json.get("error").getAsString() : "no se pudo cobrar";
                        player.sendMessage("§c[Decorador] " + why + ". No he construido nada.");
                        return;
                    }
                    undo.snapshot(player, region, deco.price(), deco.label().toLowerCase());
                    final int blocks = Blueprint.place(player, deco.blueprint());
                    plugin.buildRegistry().add(region);
                    player.sendMessage(String.format("§a[Decorador] Listo: §f%s§a, frente a ti (%d "
                            + "bloques). Se cobraron §e%d AET§a. (Puedes §f/deshacer§a.)",
                            deco.label(), blocks, deco.price()));
                }));
    }
}
