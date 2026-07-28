package com.aetheria.plugin;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;

import net.kyori.adventure.text.Component;

/**
 * DONACIONES a una aldea: el jugador puede <b>acelerar el crecimiento del pueblo que quiera</b>
 * metiendo dinero en su hucha (la misma que llenan los aldeanos trabajando). Tres vias, para que
 * cada uno use la que prefiera:
 *
 * <ol>
 *   <li><b>El ARCA de la plaza</b> (lo mas visible): un cofre rotulado en cada aldea; al hacerle
 *       clic se abre una ventana con los importes y se ve cuanto falta para el proximo vecino.</li>
 *   <li><b>El ALCALDE</b>: agachado + clic derecho sobre el (lo gestiona SettlementModule); el
 *       propio alcalde se acerca de vez en cuando a explicarlo.</li>
 *   <li><b>/donar [cantidad]</b>, para quien prefiera teclear.</li>
 * </ol>
 *
 * <p>No compra ventajas: el dinero va al comun del pueblo, que crece antes y devuelve el favor
 * en forma de excedente en el granero.
 */
public final class DonationModule implements Listener, CommandExecutor {

    /** Importes de la ventana del arca, con el icono con el que se muestran. */
    private static final int[] AMOUNTS = {25, 100, 500};
    private static final Material[] ICONS = {Material.GOLD_NUGGET, Material.GOLD_INGOT,
        Material.GOLD_BLOCK};

    private final AetheriaPlugin plugin;
    private final GatewayClient gateway;
    private final SettlementModule settlement;
    private QuestModule quests;   // opcional: hay encargos de "aporta X al arca"

    public DonationModule(AetheriaPlugin plugin, GatewayClient gateway, SettlementModule settlement) {
        this.plugin = plugin;
        this.gateway = gateway;
        this.settlement = settlement;
    }

    /** Engancha las misiones (se inyecta despues, como en SettlementModule). */
    public void setQuests(QuestModule quests) {
        this.quests = quests;
    }

    /** Marca nuestra ventana (y de que aldea es) para reconocerla al hacer clic. */
    private static final class ArcaHolder implements InventoryHolder {
        final int vid;

        ArcaHolder(int vid) {
            this.vid = vid;
        }

        @Override
        public Inventory getInventory() {
            return null;
        }
    }

    /** Clic derecho sobre el ARCA de la plaza: abre la ventana de aportaciones. */
    @EventHandler(ignoreCancelled = true)
    public void onClickChest(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getHand() != EquipmentSlot.HAND) {
            return;
        }
        final Block b = e.getClickedBlock();
        if (b == null || b.getType() != Material.CHEST) {
            return;
        }
        final int vid = townOfChest(b);
        if (vid < 0) {
            return;   // es un cofre cualquiera, no el arca del pueblo
        }
        e.setCancelled(true);   // no se abre el cofre: se abre la ventana de donaciones
        open(e.getPlayer(), vid);
    }

    /** ¿Es este bloque el arca de alguna aldea? Devuelve su aldea, o -1. */
    private int townOfChest(Block b) {
        for (int vid = 0; vid < settlement.townCount(); vid++) {
            final int[] c = settlement.donationChest(vid);
            if (c != null && b.getX() == c[0] && b.getY() == c[1] && b.getZ() == c[2]) {
                return vid;
            }
        }
        return -1;
    }

    private void open(Player p, int vid) {
        final Inventory inv = Bukkit.createInventory(new ArcaHolder(vid), 9,
                Component.text("§6Arca de " + settlement.townName(vid)));
        final double pool = settlement.townPool(vid);
        final double need = settlement.townNeed(vid);
        final ItemStack info = new ItemStack(Material.PAPER);
        final ItemMeta im = info.getItemMeta();
        im.displayName(Component.text("§eFondo de " + settlement.townName(vid)));
        final List<Component> lore = new ArrayList<>();
        lore.add(Component.text(String.format("§7Ahorrado: §f%.0f §7de §f%.0f AET", pool, need)));
        lore.add(Component.text(String.format("§7Faltan §f%.0f AET §7para el proximo vecino",
                Math.max(0, need - pool))));
        lore.add(Component.text("§8Lo que aportes vuelve al pueblo:"));
        lore.add(Component.text("§8mas vecinos trabajando = mas genero en el granero."));
        im.lore(lore);
        info.setItemMeta(im);
        inv.setItem(0, info);

        for (int i = 0; i < AMOUNTS.length; i++) {
            final ItemStack it = new ItemStack(ICONS[i]);
            final ItemMeta m = it.getItemMeta();
            m.displayName(Component.text("§aAportar §f" + AMOUNTS[i] + " AET"));
            m.lore(List.of(Component.text("§7Clic para aportarlo al fondo del pueblo")));
            it.setItemMeta(m);
            inv.setItem(3 + i, it);
        }
        p.openInventory(inv);
    }

    @EventHandler
    public void onArcaClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof ArcaHolder holder)) {
            return;
        }
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) {
            return;
        }
        final int slot = e.getRawSlot() - 3;
        if (slot < 0 || slot >= AMOUNTS.length) {
            return;
        }
        p.closeInventory();
        donate(p, holder.vid, AMOUNTS[slot]);
    }

    /** /donar [cantidad] — aporta a la aldea en la que estas (25 AET por defecto). */
    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
            @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("Solo los jugadores pueden donar.");
            return true;
        }
        final int vid = settlement.townAt(p);
        if (vid < 0) {
            p.sendMessage("§7No estas en ninguna aldea. Acercate al pueblo al que quieras ayudar.");
            return true;
        }
        double amount = 25;
        if (args.length >= 1) {
            try {
                amount = Double.parseDouble(args[0].replace(',', '.'));
            } catch (NumberFormatException ex) {
                p.sendMessage("§7Uso: §f/donar [cantidad]§7 (por defecto, 25 AET).");
                return true;
            }
        }
        if (amount < 1 || amount > 100000) {
            p.sendMessage("§7La aportacion debe estar entre 1 y 100000 AET.");
            return true;
        }
        donate(p, vid, amount);
        return true;
    }

    /**
     * Cobra al jugador y mete lo aportado en la hucha de esa aldea. Va por
     * <b>/v1/donation</b> (no por /v1/pay): el cobro y el <b>prestigio</b> que gana en esa aldea
     * son la misma transaccion, en un solo viaje de red. El prestigio por donar crece por raiz
     * cuadrada, asi que ayudar mucho suma... pero la alcaldia no se compra.
     */
    void donate(Player p, int vid, double amount) {
        final String town = settlement.townName(vid);
        gateway.donateToVillage(p.getUniqueId().toString(), p.getName(), town, amount)
                .whenComplete((json, err) -> Bukkit.getScheduler().runTask(plugin, () -> {
                    if (err != null || json == null || !json.get("ok").getAsBoolean()) {
                        final String why = (err == null && json != null && json.has("error"))
                                ? json.get("error").getAsString() : "no tienes tanto AET";
                        p.sendMessage("§c[Arca de " + town + "] " + why + ".");
                        return;
                    }
                    settlement.donate(vid, amount);
                    if (quests != null) {
                        quests.onDonated(p, town, amount);   // hay encargos de aportar al arca
                    }
                    final double falta = Math.max(0,
                            settlement.townNeed(vid) - settlement.townPool(vid));
                    p.sendMessage(String.format("§a[%s] §fGracias por tus §e%.0f AET§f. "
                            + "§7Faltan %.0f AET para el proximo vecino.", town, amount, falta));
                    p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_YES, 1f, 1.2f);
                    p.getWorld().spawnParticle(Particle.HAPPY_VILLAGER,
                            p.getLocation().add(0, 1, 0), 12, 0.4, 0.5, 0.4, 0.02);
                    gateway.postEvent("donacion", String.format("%s ha aportado %.0f AET a %s.",
                            p.getName(), amount, town));
                }));
    }
}
