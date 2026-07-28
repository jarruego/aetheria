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
 * <p>Un <b>alguacil</b> en la plaza de cada aldea (clic derecho = menu de inventario, igual que
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

    /** NIVEL de cada tipo de encargo por jugador y aldea = cuantas veces ya lo ha cumplido (clave
     *  "uuid;aldea;tipo" -> nº). Persistido en quest-tiers.txt. Sube el objetivo de la SIGUIENTE
     *  mision de ese tipo (mas pan, un vecino mas, mas donacion, top mas alto...), asi una mision
     *  NUNCA se repite igual; cuando un tipo llega a su techo, deja de ofrecerse. */
    private final Map<String, Integer> tiers = new HashMap<>();
    private final java.io.File tierFile;

    public QuestModule(AetheriaPlugin plugin, GatewayClient gateway, SettlementModule settlement) {
        this.plugin = plugin;
        this.gateway = gateway;
        this.settlement = settlement;
        this.tierFile = new java.io.File(plugin.getDataFolder(), "quest-tiers.txt");
        loadTiers();
    }

    /** Una mision viva del jugador (espejo en memoria de la fila de `quests`). */
    private static final class Quest {
        String id;
        String town;        // aldea que hace el encargo
        String kind;        // economica | social | construccion | mantenimiento | exploracion
        String type;        // granero | arca | mercado | charla | parcela | paquete | alcaldia
        Material good;      // genero pedido (si el encargo va de genero)
        String destTown;    // aldea de destino (encargos de tipo paquete)
        int rankTarget;     // puesto a alcanzar (encargos de tipo alcaldia: top 5,4,3,2,1)
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
    // El alguacil
    // ------------------------------------------------------------------

    /** Como se llama en cada aldea: "Alguacil de Villalce". */
    private static String crierName(String town) {
        return "§eAlguacil de " + town;
    }

    /** Se asegura de que hay UN alguacil en la plaza de esa aldea (no se duplica). */
    public void ensureCrier(Location loc, String town, int vid) {
        // Si la aldea esta DESCARGADA no se toca: buscar entidades daria vacio y spawnearia un
        // alguacil de mas (y al cargarse el chunk apareceria tambien el persistido) -> duplicado.
        if (!loc.getWorld().isChunkLoaded(loc.getBlockX() >> 4, loc.getBlockZ() >> 4)) {
            return;
        }
        final String tag = CRIER_TAG + "_" + vid;
        // Busca TODOS los alguaciles de esta aldea en un radio amplio (por si se alejo en su ronda),
        // se queda con UNO y ELIMINA los duplicados que hayan podido aparecer.
        org.bukkit.entity.Entity keep = null;
        for (final org.bukkit.entity.Entity e : loc.getWorld().getNearbyEntities(loc, 40, 12, 40)) {
            if (!e.getScoreboardTags().contains(tag)) {
                continue;
            }
            if (keep == null) {
                keep = e;
            } else {
                e.remove();   // duplicado: fuera
            }
        }
        if (keep != null) {
            keep.customName(Component.text(crierName(town)));
            if (keep instanceof Villager ex) {
                ex.setAI(true);
            }
            criers.put(vid, keep.getUniqueId());
            return;
        }
        final Villager v = (Villager) loc.getWorld().spawnEntity(loc, EntityType.VILLAGER);
        v.customName(Component.text(crierName(town)));
        v.setCustomNameVisible(true);
        v.setProfession(Villager.Profession.LIBRARIAN);
        v.setVillagerType(Villager.Type.PLAINS);
        v.setVillagerLevel(5);
        v.setInvulnerable(true);
        v.setPersistent(true);
        v.setRemoveWhenFarAway(false);
        v.addScoreboardTag(CRIER_TAG);
        v.addScoreboardTag(tag);
        criers.put(vid, v.getUniqueId());
        DisguiseModule.humanize(v, "m", "Alguacil", "librarian");
    }

    // ------------------------------------------------------------------
    // La RONDA del alguacil: no se queda clavado, pero tampoco se va del pueblo
    // ------------------------------------------------------------------

    /** Hasta donde se aleja del centro de la plaza en su ronda. */
    private static final double RONDA = 5.0;
    /** A que distancia repara en un jugador que pasa. */
    private static final double SALUDO = 7.0;
    /** Cada cuanto puede volver a ofrecerle faena al MISMO jugador. */
    private static final long SALUDO_MS = 120_000L;

    private final Map<Integer, UUID> criers = new HashMap<>();
    private final Map<UUID, Long> greeted = new HashMap<>();

    /** Arranca la ronda de los alguaciles (una pasada cada 2 s, barata: solo aldeas cargadas). */
    public void start() {
        Bukkit.getScheduler().runTaskTimer(plugin, this::patrol, 100L, 40L);
    }

    private void patrol() {
        for (int vid = 0; vid < settlement.townCount(); vid++) {
            final org.bukkit.entity.Mob crier = crierOf(vid);
            if (crier == null) {
                continue;   // aldea sin cargar (o sin alguacil todavia)
            }
            final Location plaza = settlement.plazaCenter(vid);
            if (plaza == null) {
                continue;
            }
            final Player near = nearestPlayer(crier);
            if (near != null) {
                // SE PARA a hablar con quien pasa: deja la ronda, se gira y le ofrece faena.
                crier.getPathfinder().stopPathfinding();
                faceToward(crier, near);
                offerWork(near, crier, vid);
                continue;
            }
            final double away = crier.getLocation().distance(plaza);
            if (away > RONDA + 1.5) {
                crier.getPathfinder().moveTo(plaza, 0.9);   // se ha ido lejos: vuelve a la plaza
            } else if (ThreadLocalRandom.current().nextInt(100) < 30) {
                // Paseo corto por la plaza, siempre dentro del radio de la ronda.
                final double ang = ThreadLocalRandom.current().nextDouble() * Math.PI * 2;
                final double r = 1.5 + ThreadLocalRandom.current().nextDouble() * (RONDA - 1.5);
                crier.getPathfinder().moveTo(plaza.clone().add(Math.cos(ang) * r, 0,
                        Math.sin(ang) * r), 0.7);
            }
        }
    }

    /** El alguacil de esa aldea, si esta cargado (se recuerda por UUID; si no, se busca por tag). */
    private org.bukkit.entity.Mob crierOf(int vid) {
        final UUID id = criers.get(vid);
        if (id != null && Bukkit.getEntity(id) instanceof org.bukkit.entity.Mob m && m.isValid()) {
            return m;
        }
        final Location plaza = settlement.plazaCenter(vid);
        if (plaza == null) {
            return null;
        }
        final String tag = CRIER_TAG + "_" + vid;
        for (final org.bukkit.entity.Entity e : plaza.getWorld().getNearbyEntities(plaza, 16, 8, 16)) {
            if (e instanceof org.bukkit.entity.Mob m && e.getScoreboardTags().contains(tag)) {
                criers.put(vid, e.getUniqueId());
                return m;
            }
        }
        return null;
    }

    private Player nearestPlayer(org.bukkit.entity.Mob crier) {
        Player best = null;
        double bestD = SALUDO * SALUDO;
        for (final Player p : crier.getWorld().getPlayers()) {
            final double d = p.getLocation().distanceSquared(crier.getLocation());
            if (d < bestD) {
                bestD = d;
                best = p;
            }
        }
        return best;
    }

    /** Le ofrece faena de palabra al que pasa (con enfriamiento, para no cansar). */
    private void offerWork(Player p, org.bukkit.entity.Mob crier, int vid) {
        final long now = System.currentTimeMillis();
        final Long last = greeted.get(p.getUniqueId());
        if (last != null && now - last < SALUDO_MS) {
            return;
        }
        greeted.put(p.getUniqueId(), now);
        final String who = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
                .plainText().serialize(crier.customName() == null
                        ? Component.text("Alguacil") : crier.customName());
        p.sendMessage("§6[" + who + "] §fHay faena en " + settlement.townName(vid)
                + ", si te interesa haz clic y te cuento.");
        p.playSound(crier.getLocation(), Sound.ENTITY_VILLAGER_AMBIENT, 0.7f, 1.1f);
    }

    /** Gira al alguacil hacia el jugador (funciona aunque este parado). */
    private static void faceToward(org.bukkit.entity.Entity npc, Player p) {
        final Location l = npc.getLocation();
        final double dx = p.getLocation().getX() - l.getX();
        final double dz = p.getLocation().getZ() - l.getZ();
        l.setYaw((float) Math.toDegrees(Math.atan2(-dx, dz)));
        l.setPitch(0f);
        npc.teleport(l);
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

    /** Marca la ventana del alguacil (y de que aldea es) para reconocer los clics. */
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
        p.sendMessage("§7El alguacil repasa sus notas...");
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
                        p.sendMessage("§7(el alguacil no encuentra sus notas ahora mismo)");
                    }
                    checkLadder(p, vid);   // escalera de prestigio: ¿ya has llegado al puesto pedido?
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
        q.rankTarget = obj.has("rank") ? obj.get("rank").getAsInt() : 0;
        if (obj.has("good")) {
            q.good = Material.matchMaterial(obj.get("good").getAsString());
        }
        return q;
    }

    private static String tierKey(java.util.UUID id, String town, String type) {
        return id + ";" + town + ";" + type;
    }

    /** Cuantas veces ha cumplido ya este jugador un tipo de encargo en esta aldea (su NIVEL). */
    private int tier(Player p, String town, String type) {
        return tiers.getOrDefault(tierKey(p.getUniqueId(), town, type), 0);
    }

    /** Sube el nivel de ese tipo al cumplirlo (el proximo encargo de ese tipo sera mas dificil). */
    private void bumpTier(Player p, Quest q) {
        final String key = tierKey(p.getUniqueId(), q.town, q.type);
        tiers.merge(key, 1, Integer::sum);
        saveTiers();
    }

    /** Puesto (1 = alcalde) del jugador en el ranking de la aldea, o MAX_VALUE si aun no figura. */
    private int playerRank(Player p, int vid) {
        final List<SettlementModule.Rank> rk = settlement.ranking(vid);
        for (int i = 0; i < rk.size(); i++) {
            if (rk.get(i).player() && p.getUniqueId().equals(rk.get(i).uuid())) {
                return i + 1;
            }
        }
        return Integer.MAX_VALUE;
    }

    /** Comprueba las misiones de ESCALERA (alcaldia): si el jugador ya ha alcanzado el puesto
     *  pedido, la da por cumplida. Se llama al refrescar (abrir tablon / entrar en la aldea). */
    private void checkLadder(Player p, int vid) {
        for (final Quest q : new ArrayList<>(questsFor(p, vid))) {
            if ("alcaldia".equals(q.type) && q.progress < q.target && q.rankTarget >= 1
                    && playerRank(p, vid) <= q.rankTarget) {
                bump(p, q, q.target - q.progress);
            }
        }
    }

    private void loadTiers() {
        if (!tierFile.exists()) {
            return;
        }
        try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(tierFile))) {
            String line;
            while ((line = r.readLine()) != null) {
                final String[] f = line.split(";", -1);
                if (f.length >= 4) {
                    tiers.put(f[0] + ";" + f[1] + ";" + f[2], Integer.parseInt(f[3]));
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("[Aetheria] no pude cargar niveles de misiones: " + e.getMessage());
        }
    }

    private void saveTiers() {
        try (java.io.FileWriter w = new java.io.FileWriter(tierFile, false)) {
            for (final Map.Entry<String, Integer> e : tiers.entrySet()) {
                w.write(e.getKey() + ";" + e.getValue() + "\n");
            }
        } catch (Exception e) {
            plugin.getLogger().warning("[Aetheria] no pude guardar niveles de misiones: " + e.getMessage());
        }
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
            lore.add(Component.text("§8Se cumple ahi fuera; el alguacil se entera solo."));
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
            case "alcaldia" -> Material.GOLDEN_HELMET;
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
            case "alcaldia" -> q.rankTarget <= 1 ? "Ser el ALCALDE de " + q.town
                    : "Llegar al top " + q.rankTarget + " de " + q.town;
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
            case "alcaldia" -> q.rankTarget <= 1 ? "Encabeza el tablon de prestigio de " + q.town + "."
                    : "Gánate un puesto entre los notables de " + q.town + ".";
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
            p.sendMessage("§7[Alguacil] Eso se cumple ahi fuera. Yo me entero solo, tranquilo.");
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
            p.sendMessage("§7[Alguacil] No llevas " + nice(q.good) + " encima.");
            return;
        }
        final int given = Math.min(have, need);
        p.getInventory().removeItem(new ItemStack(q.good, given));
        settlement.depositInGranary(vid, q.good, given);   // entra en el granero de esta aldea
        p.sendMessage("§a[Alguacil] Entregas §f" + given + " " + nice(q.good) + "§a. Gracias.");
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
                    bumpTier(p, q);   // sube el nivel: el proximo de ese tipo sera mas dificil
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
        final String town = settlement.townName(vid);
        final Set<String> already = new HashSet<>();
        for (final Quest q : questsFor(p, vid)) {
            already.add(q.type);
        }
        final List<Draft> out = new ArrayList<>();
        final var rng = ThreadLocalRandom.current();
        // Cada tipo ESCALA con el nivel (cuantas veces ya lo cumpliste): asi una mision NUNCA se
        // repite igual. Cuando un tipo llega a su techo, deja de ofrecerse (la aldea puede quedarse
        // sin misiones de ese tipo, a proposito).

        // 1. Reparto cuando el pueblo esta en apuros: cada vez, mas pan.
        if (!already.contains("reparto") && "en apuros".equals(settlement.townLevelOf(vid))) {
            out.add(new Draft("mantenimiento", obj("reparto", Material.BREAD, null),
                    12 + 6 * tier(p, town, "reparto"), 60, 15));
        }
        // 2. Lo que de verdad escasea en su granero (el material cambia con la necesidad); mas cada vez.
        final Material shortage = settlement.granaryShortage(vid);
        if (!already.contains("granero") && shortage != null) {
            out.add(new Draft("economica", obj("granero", shortage, null),
                    12 + 4 * tier(p, town, "granero"), 80, 15));
        }
        // 3. Aportar a la hucha: cada vez, mas; se agota al pasar de 500.
        final int falta = (int) Math.max(0, settlement.townNeed(vid) - settlement.townPool(vid));
        if (!already.contains("arca") && falta >= 25) {
            final int target = 25 + 50 * tier(p, town, "arca");
            if (target <= 500) {
                out.add(new Draft("economica", obj("arca", null, null), target, 0, 15));
            }
        }
        // 4. Hablar con vecinos: cada vez con UNO mas; se agota cuando habria que hablar con todos.
        final int vecinos = settlement.villagerNames(vid).size();
        if (!already.contains("charla") && vecinos >= 2) {
            final int count = 3 + tier(p, town, "charla");
            if (count <= vecinos) {
                out.add(new Draft("social", obj("charla", null, null), count, 40, 16));
            }
        }
        // 5. Escalera de PRESTIGIO: llegar a top 5, luego 4, 3, 2, 1; se agota tras la alcaldia.
        if (!already.contains("alcaldia") && vecinos >= 3) {
            final int rankGoal = 5 - tier(p, town, "alcaldia");
            if (rankGoal >= 1 && playerRank(p, vid) > rankGoal) {   // solo si aun no lo has alcanzado
                final JsonObject o = obj("alcaldia", null, null);
                o.addProperty("rank", rankGoal);
                out.add(new Draft("social", o, 1, 40, 16));
            }
        }
        // 6. Un paquete a la aldea vecina: cada vez, mas; se agota al 6o.
        final String otra = settlement.otherTownName(vid);
        if (!already.contains("paquete") && otra != null && tier(p, town, "paquete") < 6) {
            final Material good = PACKAGE_GOODS[rng.nextInt(PACKAGE_GOODS.length)];
            out.add(new Draft("exploracion", obj("paquete", good, otra),
                    12 + 4 * tier(p, town, "paquete"), 90, 18));
        }
        // 7. Mover el mercado: cada vez, mas unidades; se agota al 6o.
        if (!already.contains("mercado") && tier(p, town, "mercado") < 6) {
            out.add(new Draft("economica", obj("mercado", null, null),
                    24 + 12 * tier(p, town, "mercado"), 40, 10));
        }
        // 8. Echar raices: una sola vez.
        if (!already.contains("parcela") && tier(p, town, "parcela") == 0) {
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
                                + "pueblo y habla con su §ealguacil§7.");
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
            p.sendMessage("§7Tu aun no puntuas aqui: habla con el §ealguacil§7 de la plaza o "
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
