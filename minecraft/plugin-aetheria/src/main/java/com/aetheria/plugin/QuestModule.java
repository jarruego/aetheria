package com.aetheria.plugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import net.kyori.adventure.text.Component;

/**
 * MISIONES del pueblo y PRESTIGIO del jugador.
 *
 * <p>Un <b>pregonero</b> en la plaza de cada aldea (clic derecho = menu de inventario, igual que
 * el mercader) reparte los encargos que el pueblo necesita <i>de verdad</i>: lo que falta en su
 * granero, lo que le falta a la hucha para el proximo vecino, hablar con los vecinos, llevar un
 * paquete a la aldea de al lado... Todos los objetivos y todas las recompensas salen de
 * <b>codigo</b> a partir del estado real de la aldea; la IA, como mucho, podria redactar el sabor
 * (ver {@link #flavor}), nunca elegir que se pide ni cuanto se paga. Y quien da una mision por
 * cumplida es el BACKEND, comprobando el progreso persistido.
 *
 * <p>Cumplir misiones da <b>prestigio</b> en esa aldea, que compite en el mismo ranking que el
 * peculio de los aldeanos ({@link SettlementModule}): el primero de ese ranking es el alcalde.
 */
public final class QuestModule implements Listener, CommandExecutor {

    static final String CRIER_TAG = "aetheria_crier";
    /** Tantas misiones a la vez por aldea (el backend aplica el mismo tope). */
    private static final int MAX_ACTIVE = 3;

    private final AetheriaPlugin plugin;
    private final GatewayClient gateway;
    private final SettlementModule settlement;

    /** Misiones activas por jugador (se refrescan al entrar en una aldea y al abrir el tablon). */
    private final Map<UUID, List<Quest>> cache = new HashMap<>();

    public QuestModule(AetheriaPlugin plugin, GatewayClient gateway, SettlementModule settlement) {
        this.plugin = plugin;
        this.gateway = gateway;
        this.settlement = settlement;
    }

    /** Una mision viva del jugador (espejo en memoria de la fila de `quests`). */
    private static final class Quest {
        String id;
        String town;        // aldea que hace el encargo
        String kind;        // economica | social | construccion | mantenimiento | exploracion
        String type;        // granero | arca | mercado | charla | parcela | paquete
        Material good;      // genero pedido (si el encargo va de genero)
        String destTown;    // aldea de destino (encargos de tipo paquete)
        int progress;
        int target;
        int aet;
        int prestige;
    }

    /**
     * Vecinos con los que ya has hablado, por mision. Vive FUERA del objeto Quest a proposito:
     * la cache de misiones se rehace cada vez que entras en la aldea, y si el conjunto colgara
     * de ella, hablar tres veces con el mismo vecino contaria como tres.
     */
    private final Map<String, Set<String>> talked = new HashMap<>();

    // ------------------------------------------------------------------
    // El pregonero
    // ------------------------------------------------------------------

    /** Se asegura de que hay UN pregonero en la plaza de esa aldea (no se duplica al reiniciar). */
    public void ensureCrier(Location loc, String town, int vid) {
        final String tag = CRIER_TAG + "_" + vid;
        for (final org.bukkit.entity.Entity e : loc.getWorld().getNearbyEntities(loc, 4, 4, 4)) {
            if (e.getScoreboardTags().contains(tag)) {
                return;
            }
        }
        final Villager v = (Villager) loc.getWorld().spawnEntity(loc, EntityType.VILLAGER);
        v.customName(Component.text("§ePregonero de " + town));
        v.setCustomNameVisible(true);
        v.setProfession(Villager.Profession.LIBRARIAN);
        v.setVillagerType(Villager.Type.PLAINS);
        v.setVillagerLevel(5);
        v.setInvulnerable(true);
        v.setPersistent(true);
        v.setRemoveWhenFarAway(false);
        v.setAI(false);   // quieto junto al tablon
        v.addScoreboardTag(CRIER_TAG);
        v.addScoreboardTag(tag);
        DisguiseModule.humanize(v, "m", "Pregonero", "librarian");
    }

    @EventHandler
    public void onClickCrier(PlayerInteractEntityEvent e) {
        if (e.getHand() != EquipmentSlot.HAND
                || !e.getRightClicked().getScoreboardTags().contains(CRIER_TAG)) {
            return;
        }
        e.setCancelled(true);
        int vid = -1;
        for (final String t : e.getRightClicked().getScoreboardTags()) {
            if (t.startsWith(CRIER_TAG + "_")) {
                try {
                    vid = Integer.parseInt(t.substring(CRIER_TAG.length() + 1));
                } catch (NumberFormatException ignored) {
                    vid = -1;
                }
            }
        }
        if (vid < 0 || vid >= settlement.townCount()) {
            vid = settlement.townAt(e.getPlayer());
        }
        if (vid < 0) {
            return;
        }
        openBoard(e.getPlayer(), vid);
    }

    /**
     * Hablar con un VECINO cuenta para los encargos sociales. Se escucha en MONITOR (solo mira,
     * no toca el evento): el clic sigue haciendo lo de siempre, abrir conversacion con el NPC.
     */
    @EventHandler(priority = org.bukkit.event.EventPriority.MONITOR)
    public void onTalkVillager(PlayerInteractEntityEvent e) {
        if (e.getHand() != EquipmentSlot.HAND || e.getPlayer().isSneaking()) {
            return;   // agachado es el gesto de donar al alcalde, no de charlar
        }
        final org.bukkit.entity.Entity ent = e.getRightClicked();
        if (!(ent instanceof Villager) || ent.customName() == null
                || ent.getScoreboardTags().contains(CRIER_TAG)) {
            return;
        }
        final int vid = settlement.townAt(e.getPlayer());
        if (vid < 0) {
            return;
        }
        final String name = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
                .plainText().serialize(ent.customName());
        if (settlement.villagerNames(vid).contains(name)) {
            onTalked(e.getPlayer(), name);
        }
    }

    /** Marca la ventana del pregonero (y de que aldea es) para reconocer los clics. */
    private static final class BoardHolder implements InventoryHolder {
        final int vid;

        BoardHolder(int vid) {
            this.vid = vid;
        }

        @Override
        public Inventory getInventory() {
            return null;
        }
    }

    /** Pide al backend las misiones vivas, completa el cupo con encargos nuevos y abre el menu. */
    private void openBoard(Player p, int vid) {
        p.sendMessage("§7El pregonero repasa sus notas...");
        refresh(p, vid, () -> {
            final List<Quest> mine = questsFor(p, vid);
            final int missing = MAX_ACTIVE - (int) mine.stream()
                    .filter(q -> q.town.equals(settlement.townName(vid))).count();
            if (missing <= 0) {
                show(p, vid);
                return;
            }
            offerNew(p, vid, missing, () -> show(p, vid));
        });
    }

    /** Crea hasta {@code n} encargos nuevos a partir del estado REAL de la aldea. */
    private void offerNew(Player p, int vid, int n, Runnable then) {
        final List<Draft> drafts = draft(p, vid, n);
        if (drafts.isEmpty()) {
            then.run();
            return;
        }
        final int[] left = {drafts.size()};
        for (final Draft d : drafts) {
            gateway.createQuest(p.getUniqueId().toString(), p.getName(), settlement.townName(vid),
                            d.kind, d.objective, d.target, d.aet, d.prestige)
                    .whenComplete((json, err) -> Bukkit.getScheduler().runTask(plugin, () -> {
                        if (--left[0] > 0) {
                            return;
                        }
                        refresh(p, vid, then);   // relee lo que el backend acepto (y como lo taso)
                    }));
        }
    }

    /** Relee del backend las misiones del jugador en esa aldea y refresca la cache. */
    private void refresh(Player p, int vid, Runnable then) {
        final String town = settlement.townName(vid);
        gateway.getQuests(p.getUniqueId().toString(), town)
                .whenComplete((arr, err) -> Bukkit.getScheduler().runTask(plugin, () -> {
                    if (err == null && arr != null) {
                        final List<Quest> list = cache.computeIfAbsent(p.getUniqueId(),
                                k -> new ArrayList<>());
                        list.removeIf(q -> q.town.equals(town));
                        for (final JsonElement el : arr) {
                            list.add(parse(el.getAsJsonObject()));
                        }
                    } else if (err != null) {
                        p.sendMessage("§7(el pregonero no encuentra sus notas ahora mismo)");
                    }
                    // Autocobra las que YA llegaron al objetivo pero quedaron sin cobrar (p.ej. un
                    // fallo de red en su momento): asi una mision cumplida no se queda pegada a tope
                    // en el tablon. El backend vuelve a validar; si no procede, no paga.
                    for (final Quest q : new ArrayList<>(cache.getOrDefault(p.getUniqueId(),
                            List.of()))) {
                        if ((q.town.equals(town) || town.equals(q.destTown))
                                && q.progress >= q.target) {
                            claim(p, q);
                        }
                    }
                    if (then != null) {
                        then.run();
                    }
                }));
    }

    private static Quest parse(JsonObject o) {
        final Quest q = new Quest();
        q.id = o.get("id").getAsString();
        q.town = o.get("village_name").getAsString();
        q.kind = o.get("kind").getAsString();
        q.progress = o.get("progress").getAsInt();
        q.target = o.get("target").getAsInt();
        q.aet = o.has("reward_aet") ? o.get("reward_aet").getAsInt() : 0;
        q.prestige = o.has("reward_prestige") ? o.get("reward_prestige").getAsInt() : 0;
        final JsonObject obj = o.has("objective") && o.get("objective").isJsonObject()
                ? o.getAsJsonObject("objective") : new JsonObject();
        q.type = obj.has("type") ? obj.get("type").getAsString() : "";
        q.destTown = obj.has("town") ? obj.get("town").getAsString() : null;
        if (obj.has("good")) {
            q.good = Material.matchMaterial(obj.get("good").getAsString());
        }
        return q;
    }

    /** Misiones que se pueden ver/entregar en esta aldea: las suyas y los paquetes destinados a ella. */
    private List<Quest> questsFor(Player p, int vid) {
        final String town = settlement.townName(vid);
        final List<Quest> out = new ArrayList<>();
        for (final Quest q : cache.getOrDefault(p.getUniqueId(), List.of())) {
            if (q.town.equals(town) || town.equals(q.destTown)) {
                out.add(q);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------
    // El menu
    // ------------------------------------------------------------------

    private void show(Player p, int vid) {
        final List<Quest> mine = questsFor(p, vid);
        final Inventory inv = Bukkit.createInventory(new BoardHolder(vid), 27,
                Component.text("§eEncargos de " + settlement.townName(vid)));

        final ItemStack head = new ItemStack(Material.PAPER);
        final ItemMeta hm = head.getItemMeta();
        hm.displayName(Component.text("§6Tablon de " + settlement.townName(vid)));
        final List<Component> lore = new ArrayList<>();
        lore.add(Component.text("§7Cumple encargos del pueblo: ganas AET y §ePRESTIGIO§7."));
        lore.add(Component.text("§7El prestigio te sube en el tablon de la plaza;"));
        lore.add(Component.text("§7el primero del ranking es el §ealcalde§7."));
        lore.add(Component.text("§8Escribe /prestigio para ver tu puesto."));
        hm.lore(lore);
        head.setItemMeta(hm);
        inv.setItem(4, head);

        int slot = 10;
        for (final Quest q : mine) {
            if (slot > 16) {
                break;
            }
            inv.setItem(slot++, icon(q));
        }
        if (mine.isEmpty()) {
            final ItemStack none = new ItemStack(Material.BARRIER);
            final ItemMeta nm = none.getItemMeta();
            nm.displayName(Component.text("§7Hoy no hay encargos"));
            nm.lore(List.of(Component.text("§8Vuelve mas tarde: el pueblo siempre necesita algo.")));
            none.setItemMeta(nm);
            inv.setItem(13, none);
        }
        p.openInventory(inv);
    }

    private ItemStack icon(Quest q) {
        final ItemStack it = new ItemStack(iconFor(q));
        final ItemMeta m = it.getItemMeta();
        m.displayName(Component.text("§f" + title(q)));
        final List<Component> lore = new ArrayList<>();
        lore.add(Component.text("§7" + flavor(q)));
        lore.add(Component.text("§8· · ·"));
        lore.add(Component.text("§7Progreso: §f" + q.progress + "§7/§f" + q.target
                + "  " + bar(q.progress, q.target)));
        lore.add(Component.text("§7Paga: §e" + q.aet + " AET§7 y §b" + q.prestige + " de prestigio"));
        if (deliverable(q)) {
            lore.add(Component.text("§aClic: entregar lo que llevas encima"));
        } else {
            lore.add(Component.text("§8Se cumple ahi fuera; el pregonero se entera solo."));
        }
        m.lore(lore);
        it.setItemMeta(m);
        return it;
    }

    private static Material iconFor(Quest q) {
        if (q.good != null) {
            return q.good;
        }
        return switch (q.type) {
            case "arca" -> Material.GOLD_INGOT;
            case "mercado" -> Material.EMERALD;
            case "charla" -> Material.WRITABLE_BOOK;
            case "parcela" -> Material.OAK_DOOR;
            case "paquete" -> Material.MAP;
            default -> Material.PAPER;
        };
    }

    private static boolean deliverable(Quest q) {
        return q.good != null && ("granero".equals(q.type) || "paquete".equals(q.type)
                || "reparto".equals(q.type));
    }

    private String title(Quest q) {
        return switch (q.type) {
            case "granero" -> "Abastecer el granero: " + q.target + " de " + nice(q.good);
            case "reparto" -> "Socorro al pueblo: " + q.target + " de " + nice(q.good);
            case "arca" -> "Aportar " + q.target + " AET al arca";
            case "mercado" -> "Vender " + q.target + " generos en el mercado";
            case "charla" -> "Hablar con " + q.target + " vecinos";
            case "parcela" -> "Reclamar una parcela en el pueblo";
            case "paquete" -> "Llevar " + q.target + " de " + nice(q.good) + " a " + q.destTown;
            default -> "Encargo del pueblo";
        };
    }

    /**
     * SABOR de la mision. Hoy sale de plantillas deterministas: funciona siempre, tambien con
     * {@code LLM_PROVIDER=stub} (coste 0). Este es el unico punto por donde entraria la IA algun
     * dia — y solo para REDACTAR: el objetivo, el genero, la cantidad y la paga ya vienen dados.
     */
    private String flavor(Quest q) {
        return switch (q.type) {
            case "granero" -> "En " + q.town + " escasea " + nice(q.good) + ".";
            case "reparto" -> q.town + " lo esta pasando mal; hace falta de todo.";
            case "arca" -> "Al fondo de " + q.town + " le falta poco para el proximo vecino.";
            case "mercado" -> "El mercader quiere ver movimiento en el mercado.";
            case "charla" -> "Conoce a la gente de " + q.town + ": preguntales por su vida.";
            case "parcela" -> "El pueblo quiere echar raices: hazte con un solar.";
            case "paquete" -> "Un envio de " + q.town + " para " + q.destTown + ".";
            default -> "El pueblo necesita una mano.";
        };
    }

    private static String bar(int progress, int target) {
        final int full = target <= 0 ? 10 : Math.max(0, Math.min(10, progress * 10 / target));
        return "§a" + "|".repeat(full) + "§8" + "|".repeat(10 - full);
    }

    @EventHandler
    public void onBoardClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof BoardHolder holder)) {
            return;
        }
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)
                || e.getRawSlot() < 0 || e.getRawSlot() >= e.getInventory().getSize()) {
            return;
        }
        final int index = e.getRawSlot() - 10;
        final List<Quest> mine = questsFor(p, holder.vid);
        if (index < 0 || index >= mine.size()) {
            return;
        }
        final Quest q = mine.get(index);
        if (!deliverable(q)) {
            p.sendMessage("§7[Pregonero] Eso se cumple ahi fuera. Yo me entero solo, tranquilo.");
            return;
        }
        deliver(p, q, holder.vid);   // el tablon se queda abierto y se repinta al avanzar/cumplir
    }

    /** Si el jugador tiene abierto un tablon de encargos, lo repinta desde la cache: asi una mision
     *  que avanza o se cumple se actualiza (o desaparece) al momento, sin tener que reabrirlo. */
    private void reshowOpenBoard(Player p) {
        if (p.getOpenInventory().getTopInventory().getHolder() instanceof BoardHolder bh) {
            show(p, bh.vid);
        }
    }

    /** Entrega al granero lo que el jugador lleve encima de ese genero (lo que falte, no mas). */
    private void deliver(Player p, Quest q, int vid) {
        final int need = q.target - q.progress;
        int have = 0;
        for (final ItemStack it : p.getInventory().getStorageContents()) {
            if (it != null && it.getType() == q.good) {
                have += it.getAmount();
            }
        }
        if (have <= 0) {
            p.sendMessage("§7[Pregonero] No llevas " + nice(q.good) + " encima.");
            return;
        }
        final int given = Math.min(have, need);
        p.getInventory().removeItem(new ItemStack(q.good, given));
        settlement.depositInGranary(vid, q.good, given);   // entra en el granero de esta aldea
        p.sendMessage("§a[Pregonero] Entregas §f" + given + " " + nice(q.good) + "§a. Gracias.");
        p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_YES, 1f, 1.1f);
        bump(p, q, given);
    }

    // ------------------------------------------------------------------
    // Avance de las misiones (enganches desde el resto del plugin)
    // ------------------------------------------------------------------

    /** Al ENTRAR en una aldea se traen sus encargos, para que cuenten aunque no abras el tablon. */
    public void onEnterTown(Player p, int vid) {
        refresh(p, vid, null);
    }

    /** Vendiste en el mercado (unidades). */
    public void onSold(Player p, int units) {
        forEach(p, "mercado", q -> bump(p, q, units));
    }

    /** Aportaste al arca de una aldea. */
    public void onDonated(Player p, String town, double amount) {
        forEach(p, "arca", q -> {
            if (q.town.equals(town)) {
                bump(p, q, (int) Math.round(amount));
            }
        });
    }

    /** Hablaste con un vecino (solo cuenta una vez por vecino y mision). */
    public void onTalked(Player p, String villager) {
        forEach(p, "charla", q -> {
            if (talked.computeIfAbsent(q.id, k -> new HashSet<>()).add(villager)) {
                bump(p, q, 1);
            }
        });
    }

    /** Reclamaste una parcela. */
    public void onClaimed(Player p, String town) {
        forEach(p, "parcela", q -> {
            if (q.town.equals(town)) {
                bump(p, q, 1);
            }
        });
    }

    private void forEach(Player p, String type, java.util.function.Consumer<Quest> action) {
        for (final Quest q : new ArrayList<>(cache.getOrDefault(p.getUniqueId(), List.of()))) {
            if (q.type.equals(type) && q.progress < q.target) {
                action.accept(q);
            }
        }
    }

    /** Apunta avance en el backend y, si ya esta, pide cobrarla (el backend decide si de verdad). */
    private void bump(Player p, Quest q, int delta) {
        if (delta <= 0) {
            return;
        }
        gateway.questProgress(p.getUniqueId().toString(), q.id, delta)
                .whenComplete((json, err) -> Bukkit.getScheduler().runTask(plugin, () -> {
                    if (err != null || json == null || !json.get("ok").getAsBoolean()
                            || !json.has("data")) {
                        return;
                    }
                    final JsonObject data = json.getAsJsonObject("data");
                    if (!data.has("progress")) {
                        return;
                    }
                    q.progress = data.get("progress").getAsInt();
                    if (!data.has("ready") || !data.get("ready").getAsBoolean()) {
                        p.sendActionBar(Component.text("§e" + title(q) + " §7(" + q.progress + "/"
                                + q.target + ")"));
                        reshowOpenBoard(p);   // repinta la barra de progreso si el tablon esta abierto
                        return;
                    }
                    claim(p, q);
                }));
    }

    /** Cobra la mision. El pago y el prestigio los decide y los apunta el backend. */
    private void claim(Player p, Quest q) {
        gateway.questComplete(p.getUniqueId().toString(), q.id)
                .whenComplete((json, err) -> Bukkit.getScheduler().runTask(plugin, () -> {
                    if (err != null || json == null || !json.get("ok").getAsBoolean()
                            || !json.has("data")) {
                        return;
                    }
                    final JsonObject data = json.getAsJsonObject("data");
                    if (!"ok".equals(data.has("status") ? data.get("status").getAsString() : "")) {
                        return;   // el backend dice que aun no esta (o ya se cobro): no se paga
                    }
                    cache.getOrDefault(p.getUniqueId(), new ArrayList<>()).remove(q);
                    final int aet = data.get("reward_aet").getAsInt();
                    final int pres = data.get("reward_prestige").getAsInt();
                    p.sendMessage("§6§lEncargo cumplido §r§7— " + title(q));
                    p.sendMessage("§7Cobras §e" + aet + " AET§7 y §b" + pres + " de prestigio §7en "
                            + q.town + ". §8(total: " + data.get("score").getAsInt() + ")");
                    p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.7f, 1.3f);
                    // El tablon de la plaza se repinta YA con el prestigio recien ganado.
                    settlement.refreshRankingNow(settlement.townIdByName(q.town));
                    gateway.postEvent("mision", p.getName() + " cumplio un encargo de " + q.town
                            + ": " + title(q).toLowerCase(java.util.Locale.ROOT) + ".");
                    reshowOpenBoard(p);   // la mision cumplida desaparece del tablon al momento
                }));
    }

    // ------------------------------------------------------------------
    // Generacion de encargos POR CODIGO (nunca el LLM)
    // ------------------------------------------------------------------

    /** Un encargo candidato, ya con su objetivo y su tarifa (el backend la recorta a su baremo). */
    private static final class Draft {
        final String kind;
        final JsonObject objective;
        final int target;
        final int aet;
        final int prestige;

        Draft(String kind, JsonObject objective, int target, int aet, int prestige) {
            this.kind = kind;
            this.objective = objective;
            this.target = target;
            this.aet = aet;
            this.prestige = prestige;
        }
    }

    private static JsonObject obj(String type, Material good, String town) {
        final JsonObject o = new JsonObject();
        o.addProperty("type", type);
        if (good != null) {
            o.addProperty("good", good.name());
        }
        if (town != null) {
            o.addProperty("town", town);
        }
        return o;
    }

    /**
     * Compone encargos a partir de lo que le pasa a la aldea AHORA: que le falta al granero, si la
     * hucha va corta, si esta en apuros, cuantos vecinos hay, si hay otra aldea a la que llevar un
     * paquete. Se descartan los tipos que el jugador ya tiene activos.
     */
    private List<Draft> draft(Player p, int vid, int n) {
        final Set<String> already = new HashSet<>();
        for (final Quest q : questsFor(p, vid)) {
            already.add(q.type);
        }
        final List<Draft> out = new ArrayList<>();
        final var rng = ThreadLocalRandom.current();

        // 1. El pueblo lo esta pasando mal: comida y materiales, con mejor paga.
        if (!already.contains("reparto") && "en apuros".equals(settlement.townLevelOf(vid))) {
            out.add(new Draft("mantenimiento", obj("reparto", Material.BREAD, null), 12, 60, 15));
        }
        // 2. Lo que de verdad escasea en su granero.
        final Material shortage = settlement.granaryShortage(vid);
        if (!already.contains("granero") && shortage != null) {
            final int amount = 12 + rng.nextInt(3) * 4;   // 12, 16 o 20
            out.add(new Draft("economica", obj("granero", shortage, null), amount, 80, 15));
        }
        // 3. Lo que le falta a la hucha para el proximo vecino.
        final int falta = (int) Math.max(0, settlement.townNeed(vid) - settlement.townPool(vid));
        if (!already.contains("arca") && falta >= 25) {
            out.add(new Draft("economica", obj("arca", null, null), Math.min(300, falta), 0, 15));
        }
        // 4. Conocer al vecindario (social: vale tanto prestigio como el dinero).
        final int vecinos = settlement.villagerNames(vid).size();
        if (!already.contains("charla") && vecinos >= 2) {
            out.add(new Draft("social", obj("charla", null, null), Math.min(3, vecinos), 40, 16));
        }
        // 5. Un paquete a la aldea vecina (solo si hay otra).
        final String otra = settlement.otherTownName(vid);
        if (!already.contains("paquete") && otra != null) {
            final Material good = PACKAGE_GOODS[rng.nextInt(PACKAGE_GOODS.length)];
            out.add(new Draft("exploracion", obj("paquete", good, otra), 12, 90, 18));
        }
        // 6. Mover el mercado.
        if (!already.contains("mercado")) {
            out.add(new Draft("economica", obj("mercado", null, null), 24, 40, 10));
        }
        // 7. Echar raices en el pueblo.
        if (!already.contains("parcela")) {
            out.add(new Draft("construccion", obj("parcela", null, null), 1, 60, 16));
        }
        return out.size() > n ? out.subList(0, n) : out;
    }

    /** Generos que tiene sentido mover de una aldea a otra. */
    private static final Material[] PACKAGE_GOODS = {
        Material.BREAD, Material.WHEAT, Material.COAL, Material.IRON_INGOT, Material.OAK_LOG,
        Material.COBBLESTONE, Material.LEATHER, Material.PAPER,
    };

    /** El nombre del genero SIEMPRE en castellano: el pueblo no habla ingles. */
    private static String nice(Material m) {
        return Goods.es(m);
    }

    // ------------------------------------------------------------------
    // /prestigio — donde estoy en el ranking
    // ------------------------------------------------------------------

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
            @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("Solo los jugadores tienen prestigio.");
            return true;
        }
        final int vid = settlement.townAt(p);
        if (vid < 0) {
            showAllVillages(p);
            return true;
        }
        showRanking(p, vid);
        return true;
    }

    /** Fuera de una aldea: en cuales tienes prestigio (y cuanto). */
    private void showAllVillages(Player p) {
        gateway.getPlayerReputation(p.getUniqueId().toString())
                .whenComplete((arr, err) -> Bukkit.getScheduler().runTask(plugin, () -> {
                    if (err != null || arr == null || arr.isEmpty()) {
                        p.sendMessage("§7Aun no tienes prestigio en ninguna aldea. Entra en un "
                                + "pueblo y habla con su §epregonero§7.");
                        return;
                    }
                    p.sendMessage("§6§lTu prestigio en Aetheria");
                    for (final JsonElement el : arr) {
                        final JsonObject o = el.getAsJsonObject();
                        p.sendMessage(String.format("§7· §f%s§7: §b%d §7(misiones %d, donado %.0f AET)",
                                o.get("village_name").getAsString(), o.get("score").getAsInt(),
                                o.get("mission_points").getAsInt(), o.get("donated_total").getAsDouble()));
                    }
                    p.sendMessage("§8Entra en una aldea y escribe /prestigio para ver su ranking.");
                }));
    }

    /** Dentro de una aldea: su ranking (vecinos y jugadores juntos) y tu puesto. */
    private void showRanking(Player p, int vid) {
        final List<SettlementModule.Rank> rank = settlement.ranking(vid);
        p.sendMessage("§6§lPrestigio de " + settlement.townName(vid));
        if (rank.isEmpty()) {
            p.sendMessage("§7Aun no hay nadie en el ranking de esta aldea.");
            return;
        }
        int mine = -1;
        for (int i = 0; i < rank.size(); i++) {
            if (rank.get(i).player() && p.getName().equals(rank.get(i).name())) {
                mine = i;
            }
        }
        for (int i = 0; i < Math.min(8, rank.size()); i++) {
            final SettlementModule.Rank r = rank.get(i);
            final String who = (r.player() ? "§b" : "§f") + r.name() + (r.player() ? " §8(jugador)" : "");
            p.sendMessage(String.format("§7%s%d. %s §7— §e%.0f%s", i == 0 ? "§6" : "§7", i + 1, who,
                    r.score(), i == 0 ? " §6(alcalde)" : ""));
        }
        if (mine < 0) {
            p.sendMessage("§7Tu aun no puntuas aqui: habla con el §epregonero§7 de la plaza o "
                    + "aporta al §earca§7.");
        } else {
            p.sendMessage("§7Tu puesto: §f#" + (mine + 1) + " §7de §f" + rank.size()
                    + "§7 con §b" + (int) rank.get(mine).score() + " §7de prestigio.");
            if (mine < 3) {
                p.sendMessage("§aEstas entre los tres primeros: puedes llevarte el §fexcedente§a "
                        + "del granero del pueblo.");
            }
        }
    }
}
