package com.aetheria.plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

/**
 * COMERCIO con los vecinos, en AET y sin esmeraldas.
 *
 * <p>El trueque vanilla (esmeraldas por chatarra, con su ventana de aldeano) no pinta nada en
 * Aetheria: aqui la moneda es el <b>AET</b> y el genero sale del <b>granero</b> del pueblo. Asi
 * que la ventana de comercio de Minecraft <b>no se abre nunca</b> sobre nuestros NPC y en su
 * lugar:
 *
 * <ul>
 *   <li><b>Clic derecho</b> sobre un vecino = hablar con el (como siempre).</li>
 *   <li><b>Agachado + clic derecho</b> = COMERCIAR: se abre su trato, con lo que su oficio
 *       produce (y que de verdad hay en el granero) y lo que el pueblo necesita comprar.</li>
 * </ul>
 *
 * <p>El dinero circula de verdad: lo que le compras sale del granero y su importe va al
 * <b>peculio</b> del vecino y a la <b>hucha</b> de su aldea; lo que le vendes entra al granero y
 * <b>lo paga de su bolsillo</b> (si no le llega, te lo dice). Es el mismo tejido economico que
 * mueve el pueblo, no una tienda aparte.
 *
 * <p>Incluye la <b>BOTICA</b> (edificio civico desde 8 vecinos): su boticario te cura por dinero,
 * cobrando segun los corazones que te falten.
 */
public final class NpcTradeModule implements Listener {

    static final String HEALER_TAG = "aetheria_healer";
    private static final String BANCO = "00000000-0000-0000-0000-000000000000";

    /** Lo que el vecino te VENDE cuesta un poco mas que el precio base (su margen). */
    private static final double MARGEN = 1.25;
    /** Lo que te COMPRA lo paga un poco por debajo del precio base. */
    private static final double REBAJA = 0.9;
    /** AET por cada corazon que hay que curarte en la botica. */
    private static final double PRECIO_CORAZON = 6.0;

    private final AetheriaPlugin plugin;
    private final GatewayClient gateway;
    private final SettlementModule settlement;

    public NpcTradeModule(AetheriaPlugin plugin, GatewayClient gateway, SettlementModule settlement) {
        this.plugin = plugin;
        this.gateway = gateway;
        this.settlement = settlement;
    }

    /** Lo que produce cada oficio (lo que puede venderte, si lo hay en el granero). */
    private static final Map<String, Material[]> VENDE = Map.ofEntries(
        Map.entry("farmer", new Material[] {Material.WHEAT, Material.BREAD, Material.CARROT,
            Material.POTATO}),
        Map.entry("fisherman", new Material[] {Material.COD, Material.SALMON,
            Material.COOKED_COD}),
        Map.entry("shepherd", new Material[] {Material.WHITE_WOOL, Material.LEATHER}),
        Map.entry("mason", new Material[] {Material.COBBLESTONE, Material.STONE,
            Material.STONE_BRICKS}),
        Map.entry("toolsmith", new Material[] {Material.IRON_INGOT, Material.COAL}),
        Map.entry("librarian", new Material[] {Material.BOOK, Material.PAPER}),
        Map.entry("butcher", new Material[] {Material.BEEF, Material.COOKED_BEEF,
            Material.COOKED_PORKCHOP}),
        Map.entry("fletcher", new Material[] {Material.ARROW, Material.STICK}),
        Map.entry("leatherworker", new Material[] {Material.BREAD, Material.LEATHER})
    );

    /** Lo que cada oficio NECESITA y por tanto te compra (su materia prima). */
    private static final Map<String, Material[]> COMPRA = Map.ofEntries(
        Map.entry("farmer", new Material[] {Material.WHEAT_SEEDS, Material.BONE_MEAL}),
        Map.entry("fisherman", new Material[] {Material.STRING}),
        Map.entry("shepherd", new Material[] {Material.WHEAT}),
        Map.entry("mason", new Material[] {Material.COBBLESTONE, Material.SAND}),
        Map.entry("toolsmith", new Material[] {Material.RAW_IRON, Material.COAL,
            Material.COBBLESTONE}),
        Map.entry("librarian", new Material[] {Material.PAPER, Material.LEATHER}),
        Map.entry("butcher", new Material[] {Material.BEEF, Material.PORKCHOP,
            Material.CHICKEN}),
        Map.entry("fletcher", new Material[] {Material.OAK_LOG, Material.STRING,
            Material.FEATHER}),
        Map.entry("leatherworker", new Material[] {Material.WHEAT, Material.LEATHER})
    );

    // ------------------------------------------------------------------
    // El BOTICARIO de la botica (edificio civico a partir de 8 vecinos)
    // ------------------------------------------------------------------

    /** Se asegura de que hay UN boticario en la botica de esa aldea (no se duplica al reiniciar). */
    public void ensureHealer(Location loc, String town, int vid) {
        // Aldea DESCARGADA: no se toca (si no, se spawnearia un boticario de mas al no ver el
        // persistido, y saldrian dos).
        if (!loc.getWorld().isChunkLoaded(loc.getBlockX() >> 4, loc.getBlockZ() >> 4)) {
            return;
        }
        final String tag = HEALER_TAG + "_" + vid;
        org.bukkit.entity.Entity keep = null;
        for (final org.bukkit.entity.Entity e : loc.getWorld().getNearbyEntities(loc, 8, 6, 8)) {
            if (!e.getScoreboardTags().contains(tag)) {
                continue;
            }
            if (keep == null) {
                keep = e;   // el boticario bueno
            } else {
                e.remove();   // duplicado: fuera
            }
        }
        if (keep != null) {
            return;
        }
        final Villager v = (Villager) loc.getWorld().spawnEntity(loc, EntityType.VILLAGER);
        v.customName(Component.text("§dBoticario de " + town));
        v.setCustomNameVisible(true);
        v.setProfession(Villager.Profession.CLERIC);
        v.setVillagerType(Villager.Type.PLAINS);
        v.setVillagerLevel(5);
        v.setInvulnerable(true);
        v.setPersistent(true);
        v.setRemoveWhenFarAway(false);
        v.setAI(false);   // atiende detras de su mostrador
        v.addScoreboardTag(HEALER_TAG);
        v.addScoreboardTag(tag);
        DisguiseModule.humanize(v, "f", "Boticaria", "cleric");
    }

    // ------------------------------------------------------------------
    // Clics: nunca la ventana vanilla
    // ------------------------------------------------------------------

    /**
     * Se atiende en HIGH y se CANCELA siempre sobre nuestros NPC: es lo que impide que salga la
     * ventana de trueque de Minecraft (esmeraldas) que no pinta nada aqui.
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onClick(PlayerInteractEntityEvent e) {
        if (e.getHand() != EquipmentSlot.HAND || !(e.getRightClicked() instanceof Villager v)) {
            return;
        }
        final Player p = e.getPlayer();
        if (v.getScoreboardTags().contains(HEALER_TAG)) {
            e.setCancelled(true);
            openHealer(p, townOfHealer(v));
            return;
        }
        if (v.customName() == null) {
            return;
        }
        final String name = PlainTextComponentSerializer.plainText().serialize(v.customName());
        final String prof = settlement.professionOf(name);
        if (prof == null) {
            return;   // no es un vecino nuestro (el mercader y el alguacil ya se apañan solos)
        }
        e.setCancelled(true);   // ni trueque vanilla ni nada: lo gestionamos nosotros
        if (p.isSneaking()) {
            openTrade(p, name, prof);
        }
        // De pie no se hace nada aqui: la conversacion la abre ConversationManager.
    }

    // ------------------------------------------------------------------
    // El trato con un vecino
    // ------------------------------------------------------------------

    /** Marca la ventana de trato (con quien y de que aldea). */
    private static final class TradeHolder implements InventoryHolder {
        final String name;
        final int vid;

        TradeHolder(String name, int vid) {
            this.name = name;
            this.vid = vid;
        }

        @Override
        public Inventory getInventory() {
            return null;
        }
    }

    private void openTrade(Player p, String name, String prof) {
        final int vid = settlement.townOfColono(name);
        if (vid < 0) {
            return;
        }
        final Inventory inv = Bukkit.createInventory(new TradeHolder(name, vid), 27,
                Component.text("§6Trato con " + name));

        final ItemStack head = new ItemStack(Material.PAPER);
        final ItemMeta hm = head.getItemMeta();
        hm.displayName(Component.text("§e" + name + " §7· " + oficio(prof)));
        hm.lore(List.of(
                Component.text("§7Arriba: lo que TE VENDE (sale de su granero)."),
                Component.text("§7Abajo: lo que TE COMPRA (lo paga de su bolsillo)."),
                Component.text("§8Lleva " + (int) settlement.wealthOf(name) + " AET encima.")));
        head.setItemMeta(hm);
        inv.setItem(4, head);

        int slot = 9;
        for (final Material g : VENDE.getOrDefault(prof, new Material[0])) {
            final Double base = ShopModule.priceOf(g);
            final int stock = settlement.granaryCount(vid, g);
            if (base == null || stock <= 0 || slot > 13) {
                continue;
            }
            final ItemStack it = new ItemStack(g);
            final ItemMeta m = it.getItemMeta();
            m.displayName(Component.text("§aComprar §f" + Goods.es(g)));
            m.lore(List.of(
                    Component.text("§7Clic izq: 1 ud · §e" + precio(base * MARGEN) + " AET"),
                    Component.text("§7Clic der: 16 ud · §e" + precio(base * MARGEN * 16) + " AET"),
                    Component.text("§8Le quedan " + stock + " en el granero.")));
            it.setItemMeta(m);
            inv.setItem(slot++, it);
        }

        slot = 18;
        for (final Material g : COMPRA.getOrDefault(prof, new Material[0])) {
            final Double base = ShopModule.priceOf(g);
            if (base == null || slot > 22) {
                continue;
            }
            final ItemStack it = new ItemStack(g);
            final ItemMeta m = it.getItemMeta();
            m.displayName(Component.text("§eVenderle §f" + Goods.es(g)));
            m.lore(List.of(
                    Component.text("§7Clic: le vendes TODO lo que lleves"),
                    Component.text("§7Te paga §e" + precio(base * REBAJA) + " AET §7por unidad"),
                    Component.text("§8Hasta donde le llegue el bolsillo.")));
            it.setItemMeta(m);
            inv.setItem(slot++, it);
        }
        p.openInventory(inv);
    }

    @EventHandler
    public void onTradeClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof TradeHolder h)) {
            return;
        }
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p) || e.getRawSlot() < 0
                || e.getRawSlot() >= e.getInventory().getSize()) {
            return;
        }
        final ItemStack clicked = e.getCurrentItem();
        if (clicked == null || clicked.getType() == Material.AIR
                || clicked.getType() == Material.PAPER) {
            return;
        }
        final int slot = e.getRawSlot();
        if (slot >= 9 && slot <= 13) {
            buy(p, h, clicked.getType(), e.getClick() == ClickType.RIGHT ? 16 : 1);
        } else if (slot >= 18 && slot <= 22) {
            sell(p, h, clicked.getType());
        }
    }

    /** El jugador COMPRA genero del granero: paga, se lo lleva, y el vecino cobra su parte. */
    private void buy(Player p, TradeHolder h, Material g, int amount) {
        final Double base = ShopModule.priceOf(g);
        if (base == null) {
            return;
        }
        final int stock = settlement.granaryCount(h.vid, g);
        final int units = Math.min(amount, stock);
        if (units <= 0) {
            p.sendMessage("§7[" + h.name + "] Ya no me queda de eso, lo siento.");
            return;
        }
        final double total = precio(base * MARGEN * units);
        p.closeInventory();
        gateway.pay(p.getUniqueId().toString(), BANCO, total)
                .whenComplete((json, err) -> Bukkit.getScheduler().runTask(plugin, () -> {
                    if (err != null || json == null || !json.get("ok").getAsBoolean()) {
                        p.sendMessage("§c[" + h.name + "] No te llega el dinero para eso.");
                        return;
                    }
                    int taken = 0;
                    final Material[] one = {g};
                    for (int i = 0; i < units && settlement.takeFromGranary(h.vid, one) != null; i++) {
                        taken++;
                    }
                    if (taken > 0) {
                        p.getInventory().addItem(new ItemStack(g, taken));
                    }
                    // El trato mueve la economia REAL: su bolsillo y la hucha de su aldea.
                    settlement.addWealth(h.name, total * 0.6);
                    settlement.addTownPool(h.vid, total * 0.4);
                    p.sendMessage(String.format("§a[%s] §fAhi tienes §e%d %s§f. Son §e%.2f AET§f.",
                            h.name, taken, Goods.es(g), total));
                    p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_TRADE, 1f, 1.1f);
                }));
    }

    /** El jugador VENDE al vecino: el genero entra en el granero y le sale del peculio. */
    private void sell(Player p, TradeHolder h, Material g) {
        final Double base = ShopModule.priceOf(g);
        if (base == null) {
            return;
        }
        int have = 0;
        for (final ItemStack it : p.getInventory().getStorageContents()) {
            if (it != null && it.getType() == g) {
                have += it.getAmount();
            }
        }
        if (have <= 0) {
            p.sendMessage("§7[" + h.name + "] No llevas " + Goods.es(g) + " encima.");
            return;
        }
        final double unit = precio(base * REBAJA);
        final double bolsillo = settlement.wealthOf(h.name);
        final int units = (int) Math.min(have, Math.floor(bolsillo / Math.max(0.01, unit)));
        if (units <= 0) {
            p.sendMessage("§7[" + h.name + "] Me gustaria, pero hoy no me llega el bolsillo.");
            return;
        }
        final double total = precio(unit * units);
        p.getInventory().removeItem(new ItemStack(g, units));
        p.closeInventory();
        gateway.reward(p.getUniqueId().toString(), total, "trato con " + h.name)
                .whenComplete((json, err) -> Bukkit.getScheduler().runTask(plugin, () -> {
                    if (err != null || json == null || !json.get("ok").getAsBoolean()) {
                        p.getInventory().addItem(new ItemStack(g, units));   // devuelve el genero
                        p.sendMessage("§cNo se pudo cerrar el trato. Intentalo de nuevo.");
                        return;
                    }
                    settlement.depositInGranary(h.vid, g, units);
                    settlement.spendWealth(h.name, total);
                    p.sendMessage(String.format("§a[%s] §fTrato hecho: §e%d %s §fpor §e%.2f AET§f.",
                            h.name, units, Goods.es(g), total));
                    p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_TRADE, 1f, 0.9f);
                }));
    }

    // ------------------------------------------------------------------
    // La BOTICA: te curan por dinero, segun lo tocado que vengas
    // ------------------------------------------------------------------

    private static final class HealHolder implements InventoryHolder {
        final String town;

        HealHolder(String town) {
            this.town = town;
        }

        @Override
        public Inventory getInventory() {
            return null;
        }
    }

    /** Corazones que le faltan al jugador (medio corazon cuenta como uno para cobrar). */
    private static int heartsMissing(Player p) {
        final double max = p.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH).getValue();
        return (int) Math.ceil((max - p.getHealth()) / 2.0);
    }

    /** Nombre de la aldea a partir del nombre del boticario ("Boticario/a de X" -> "X"). */
    private static String townOfHealer(Villager v) {
        if (v.customName() == null) {
            return "el pueblo";
        }
        return PlainTextComponentSerializer.plainText().serialize(v.customName())
                .replace("Boticario de ", "").replace("Boticaria de ", "");
    }

    /**
     * OBJETO de la botica (caldero o alambique): al hacerle clic derecho tambien abre la cura. Es
     * una via alternativa a clicar sobre la boticaria, mas fiable (interaccion de bloque, sin NPC).
     */
    @EventHandler
    public void onBoticaObject(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getHand() != EquipmentSlot.HAND) {
            return;
        }
        final Block b = e.getClickedBlock();
        if (b == null) {
            return;
        }
        final Material m = b.getType();
        if (m != Material.CAULDRON && m != Material.WATER_CAULDRON && m != Material.BREWING_STAND) {
            return;
        }
        final String town = settlement.boticaTownAt(b);
        if (town == null) {
            return;   // no es un caldero/alambique de una botica
        }
        e.setCancelled(true);
        openHealer(e.getPlayer(), town);
    }

    private void openHealer(Player p, String town) {
        final int hearts = heartsMissing(p);
        final Inventory inv = Bukkit.createInventory(new HealHolder(town), 9,
                Component.text("§dBotica de " + town));

        final ItemStack cura = new ItemStack(hearts > 0 ? Material.GOLDEN_APPLE : Material.APPLE);
        final ItemMeta cm = cura.getItemMeta();
        final List<Component> lore = new ArrayList<>();
        if (hearts <= 0) {
            cm.displayName(Component.text("§7No necesitas cura"));
            lore.add(Component.text("§8Estas entero. Vuelve cuando te hagan sangre."));
        } else {
            cm.displayName(Component.text("§aCurarte §f" + hearts + " corazon"
                    + (hearts == 1 ? "" : "es")));
            lore.add(Component.text("§7Te cobra §e" + precio(hearts * PRECIO_CORAZON) + " AET"));
            lore.add(Component.text("§8(" + (int) PRECIO_CORAZON + " AET por corazon)"));
            lore.add(Component.text("§7Clic para que te atienda."));
        }
        cm.lore(lore);
        cura.setItemMeta(cm);
        inv.setItem(4, cura);
        p.openInventory(inv);
    }

    @EventHandler
    public void onHealClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof HealHolder h)) {
            return;
        }
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p) || e.getRawSlot() != 4) {
            return;
        }
        final int hearts = heartsMissing(p);
        if (hearts <= 0) {
            return;
        }
        final double total = precio(hearts * PRECIO_CORAZON);
        p.closeInventory();
        gateway.pay(p.getUniqueId().toString(), BANCO, total)
                .whenComplete((json, err) -> Bukkit.getScheduler().runTask(plugin, () -> {
                    if (err != null || json == null || !json.get("ok").getAsBoolean()) {
                        p.sendMessage("§c[Botica de " + h.town + "] Sin dinero no hay remedio, "
                                + "lo siento.");
                        return;
                    }
                    final double max = p.getAttribute(
                            org.bukkit.attribute.Attribute.MAX_HEALTH).getValue();
                    p.setHealth(max);
                    p.setFireTicks(0);
                    p.removePotionEffect(org.bukkit.potion.PotionEffectType.POISON);
                    p.removePotionEffect(org.bukkit.potion.PotionEffectType.WITHER);
                    p.addPotionEffect(new org.bukkit.potion.PotionEffect(
                            org.bukkit.potion.PotionEffectType.REGENERATION, 100, 0));
                    p.sendMessage(String.format("§d[Botica de %s] §fComo nuevo. Son §e%.2f AET§f.",
                            h.town, total));
                    p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.6f, 1.6f);
                    p.getWorld().spawnParticle(org.bukkit.Particle.HEART,
                            p.getLocation().add(0, 1.6, 0), 12, 0.4, 0.4, 0.4, 0.02);
                }));
    }

    private static double precio(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private static String oficio(String profKey) {
        return switch (profKey) {
            case "farmer" -> "granjero";
            case "fisherman" -> "pescador";
            case "shepherd" -> "pastor";
            case "mason" -> "cantero";
            case "toolsmith" -> "herrero";
            case "librarian" -> "bibliotecario";
            case "butcher" -> "carnicero";
            case "fletcher" -> "arquero";
            case "leatherworker" -> "tabernero";
            default -> "vecino";
        };
    }
}
