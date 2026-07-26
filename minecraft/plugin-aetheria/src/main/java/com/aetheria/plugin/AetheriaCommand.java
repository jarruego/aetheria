package com.aetheria.plugin;

import java.util.Arrays;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * Comando /aetheria: puente entre el jugador y el backend.
 *   /aetheria ask  &lt;mensaje&gt;      -> conversacion con un NPC (3 niveles en el backend)
 *   /aetheria plan &lt;objetivo&gt;     -> pide un plan; si se aprueba, se ejecuta (lista blanca)
 *   /aetheria npc  spawn|remove [k] -> gestiona el NPC (entidad real) con esa clave
 */
public final class AetheriaCommand implements CommandExecutor {

    private final AetheriaPlugin plugin;
    private final GatewayClient gateway;
    private final NpcManager npcs;
    private final PlanExecutor executor;
    private final String defaultNpc;
    private SchematicModule schematics;   // null si FAWE/WorldEdit no esta instalado

    public AetheriaCommand(AetheriaPlugin plugin, GatewayClient gateway, NpcManager npcs,
            String defaultNpc) {
        this.plugin = plugin;
        this.gateway = gateway;
        this.npcs = npcs;
        this.defaultNpc = defaultNpc;
        this.executor = new PlanExecutor(plugin, npcs);
    }

    /** Activa las ordenes de esquematicos (solo si FAWE esta presente). */
    public void setSchematics(SchematicModule schematics) {
        this.schematics = schematics;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
            @NotNull String label, @NotNull String[] args) {
        // Guardar un cubo del mundo como .schem SIN jugador (consola/RCON): para nutrir el
        // catalogo con muestras del showroom. Uso: /aetheria schem savecube <nombre> <x> <y> <z> [r]
        if (args.length >= 6 && args[0].equalsIgnoreCase("schem")
                && args[1].equalsIgnoreCase("savecube")) {
            handleSaveCube(sender, args);
            return true;
        }
        if (args.length >= 2 && args[0].equalsIgnoreCase("schem")
                && args[1].equalsIgnoreCase("savecatalog")) {
            handleSaveCatalog(sender);
            return true;
        }
        if (args.length >= 2 && args[0].equalsIgnoreCase("schem")
                && args[1].equalsIgnoreCase("pastestreet")) {
            handlePasteStreet(sender);
            return true;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Solo los jugadores pueden usar Aetheria.");
            return true;
        }
        if (args.length < 1) {
            player.sendMessage("Uso: /aetheria <ask|plan|npc> ...");
            return true;
        }

        final String sub = args[0].toLowerCase();
        switch (sub) {
            case "ask" -> {
                if (args.length < 2) { player.sendMessage("Uso: /aetheria ask <mensaje>"); return true; }
                handleAsk(player, join(args, 1));
            }
            case "plan" -> {
                if (args.length < 2) { player.sendMessage("Uso: /aetheria plan <objetivo>"); return true; }
                handlePlan(player, join(args, 1));
            }
            case "npc" -> handleNpc(player, args);
            case "servicio", "service" -> {
                if (args.length < 3) {
                    player.sendMessage("Uso: /aetheria servicio <arquitecto|decorador|urbanista> <que quieres>");
                    return true;
                }
                handleService(player, args[1].toLowerCase(), join(args, 2));
            }
            case "cronica", "crónica" -> handleCronica(player);
            case "schem", "esquematico" -> handleSchem(player, args);
            default -> player.sendMessage(
                    "Subcomando desconocido: " + sub + " (usa ask|plan|npc|servicio|cronica|schem)");
        }
        return true;
    }

    private void handleNpc(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage("Uso: /aetheria npc <spawn|remove> [clave]");
            return;
        }
        final String op = args[1].toLowerCase();
        final String key = args.length >= 3 ? args[2] : defaultNpc;
        switch (op) {
            case "spawn" -> {
                npcs.spawn(key, player.getLocation());
                player.sendMessage("§a[Aetheria] NPC '" + key + "' creado aqui.");
            }
            case "remove" -> {
                final boolean removed = npcs.remove(key);
                player.sendMessage(removed
                        ? "§a[Aetheria] NPC '" + key + "' eliminado."
                        : "§e[Aetheria] no existe el NPC '" + key + "'.");
            }
            default -> player.sendMessage("Uso: /aetheria npc <spawn|remove> [clave]");
        }
    }

    private void handleAsk(Player player, String message) {
        player.sendMessage("§7[Aetheria] pensando...");
        gateway.conversation(defaultNpc, player.getUniqueId().toString(), message, null)
                .whenComplete((json, err) -> Bukkit.getScheduler().runTask(plugin, () -> {
                    if (err != null) {
                        player.sendMessage("§c[Aetheria] error: " + rootMessage(err));
                        return;
                    }
                    final String reply = json.get("reply").getAsString();
                    final int level = json.get("level").getAsInt();
                    player.sendMessage("§a[NPC §7(N" + level + ")§a] §f" + reply);
                }));
    }

    private void handlePlan(Player player, String goal) {
        player.sendMessage("§7[Aetheria] generando plan...");
        gateway.plan("npc", defaultNpc, goal)
                .whenComplete((json, err) -> Bukkit.getScheduler().runTask(plugin, () -> {
                    if (err != null) {
                        player.sendMessage("§c[Aetheria] error: " + rootMessage(err));
                        return;
                    }
                    final String status = json.get("status").getAsString();
                    if (!"approved".equals(status)) {
                        final String reason =
                                json.has("rejection_reason") && !json.get("rejection_reason").isJsonNull()
                                        ? json.get("rejection_reason").getAsString()
                                        : "sin motivo";
                        player.sendMessage("§c[Aetheria] plan RECHAZADO: " + reason);
                        return;
                    }
                    executor.execute(player, defaultNpc, json.getAsJsonArray("actions"));
                }));
    }

    private void handleService(Player player, String service, String description) {
        player.sendMessage("§7[Aetheria] el servicio de §e" + service + " §7esta trabajando...");
        final String world = player.getWorld().getName();
        gateway.service(player.getUniqueId().toString(), service, description, world)
                .whenComplete((json, err) -> Bukkit.getScheduler().runTask(plugin, () -> {
                    if (err != null) {
                        player.sendMessage("§c[Aetheria] error: " + rootMessage(err));
                        return;
                    }
                    // sendCapturing envuelve la respuesta en {ok, error?, data?}.
                    if (!json.get("ok").getAsBoolean()) {
                        player.sendMessage("§c[Aetheria] " + json.get("error").getAsString());
                        return;
                    }
                    final var data = json.getAsJsonObject("data");
                    if (!"approved".equals(data.get("status").getAsString())) {
                        final String reason = data.has("reason") && !data.get("reason").isJsonNull()
                                ? data.get("reason").getAsString()
                                : "no se pudo realizar";
                        player.sendMessage("§c[Aetheria] servicio no realizado: " + reason);
                        return;
                    }
                    final double charged = data.has("charged") ? data.get("charged").getAsDouble() : 0.0;
                    player.sendMessage(String.format("§a[Aetheria] servicio realizado. Se cobraron §e%.0f AET§a.",
                            charged));
                    executor.execute(player, "servicio-" + service, data.getAsJsonArray("actions"));
                }));
    }

    /** Consola/RCON: guarda un cubo del mundo principal de ESTE servidor como .schem. */
    private void handleSaveCube(CommandSender sender, String[] args) {
        try {
            final String name = args[2];
            final int x = Integer.parseInt(args[3]);
            final int y = Integer.parseInt(args[4]);
            final int z = Integer.parseInt(args[5]);
            final int r = args.length >= 7 ? Integer.parseInt(args[6]) : 8;
            final org.bukkit.World w = plugin.getServer().getWorlds().get(0);
            final int width = 2 * r + 1;
            final int length = 2 * r + 1;
            final int height = 2 * r + 2;
            final java.io.File dir = new java.io.File(
                    "plugins/FastAsyncWorldEdit/schematics");   // el catalogo de FAWE
            final java.io.File file = new java.io.File(dir, name + ".schem");
            SchematicWriter.save(w, x - r, y - 1, z - r, width, height, length, file);
            sender.sendMessage("§a[Esquematico] guardado §f" + name + "§a en el catalogo ("
                    + width + "x" + height + "x" + length + ").");
        } catch (Exception e) {
            sender.sendMessage("§c[Esquematico] no pude guardar: " + e.getMessage());
            plugin.getLogger().warning("[Aetheria] savecube: " + e);
        }
    }

    /** Consola: guarda un par de muestras del showroom del creativo como .schem del catalogo.
     *  Usa el mismo layout que CatalogModule (rejilla al norte del spawn). */
    private void handleSaveCatalog(CommandSender sender) {
        try {
            final org.bukkit.World w = plugin.getServer().getWorlds().get(0);
            final org.bukkit.Location sp = w.getSpawnLocation();
            final int baseX = sp.getBlockX() - 24;
            final int baseZ = sp.getBlockZ() - 52;
            final int floorY = sp.getBlockY() - 1;
            // (indice de celda en CatalogModule, nombre) -> casa mediana y herreria
            saveCell(w, baseX, baseZ, floorY, 1, "casa_mediana", sender);
            saveCell(w, baseX, baseZ, floorY, 7, "herreria", sender);
            sender.sendMessage("§a[Esquematico] muestras del showroom guardadas en el catalogo.");
        } catch (Exception e) {
            sender.sendMessage("§c[Esquematico] savecatalog: " + e.getMessage());
            plugin.getLogger().warning("[Aetheria] savecatalog: " + e);
        }
    }

    private void saveCell(org.bukkit.World w, int baseX, int baseZ, int floorY, int i, String name,
            CommandSender sender) throws java.io.IOException {
        final int cx = baseX + (i % 4) * 16;
        final int cz = baseZ + (i / 4) * 16;
        final int r = 8;
        final java.io.File file = new java.io.File(
                "plugins/FastAsyncWorldEdit/schematics", name + ".schem");
        SchematicWriter.save(w, cx - r, floorY - 1, cz - r, 2 * r + 1, 2 * r + 2, 2 * r + 1, file);
        sender.sendMessage("§7  guardado §f" + name + "§7 (celda " + i + ")");
    }

    /** Consola: pega TODOS los esquematicos del catalogo en una "calle de muestra" con carteles. */
    private void handlePasteStreet(CommandSender sender) {
        if (schematics == null) {
            sender.sendMessage("§cFAWE no disponible.");
            return;
        }
        final org.bukkit.World w = plugin.getServer().getWorlds().get(0);
        final org.bukkit.Location sp = w.getSpawnLocation();
        final java.io.File dir = new java.io.File("plugins/FastAsyncWorldEdit/schematics");
        final java.io.File[] files = dir.listFiles(
                (d, n) -> n.endsWith(".schem") || n.endsWith(".schematic"));
        if (files == null || files.length == 0) {
            sender.sendMessage("§7Catalogo vacio.");
            return;
        }
        final int baseX = sp.getBlockX() + 40;   // calle al este del spawn (lejos del showroom)
        final int z = sp.getBlockZ() + 40;
        final int y = sp.getBlockY();
        final int gap = 6;

        // 1) Medir cada esquematico y ORDENAR de menor a mayor anchura.
        record Item(java.io.File f, String name, int width, int length) { }
        final java.util.List<Item> items = new java.util.ArrayList<>();
        for (final java.io.File f : files) {
            final int[] d = schematics.dimensions(f);
            final String name = f.getName().replaceFirst("\\.(schem|schematic)$", "");
            items.add(new Item(f, name, d != null ? d[0] : 16, d != null ? d[1] : 16));
        }
        items.sort(java.util.Comparator.comparingInt(Item::width));

        // 2) Limpiar la banda entera (de cero), dimensionada al total.
        int total = 0;
        int maxLen = 16;
        for (final Item it : items) {
            total += it.width() + gap;
            maxLen = Math.max(maxLen, it.length());
        }
        // Limpia holgado: cubre tanto el nuevo total como cualquier disposicion anterior (mas larga).
        final int clearMaxX = baseX + Math.max(total, items.size() * 100) + 120;
        schematics.fillAir(w, baseX - 20, y, z - 40, clearMaxX, y + 100, z + Math.max(maxLen, 120) + 40);
        sender.sendMessage("§7Calle limpiada; pegando " + items.size()
                + " esquematicos ordenados por tamano...");

        // 3) Pegar alineados por su borde, avanzando segun la anchura de cada uno.
        int cursorX = baseX;
        int done = 0;
        for (final Item it : items) {
            final int[] wl = schematics.pasteAligned(w, it.f(), cursorX, y, z);
            final int width = wl != null ? wl[0] : it.width();
            if (wl != null) {
                streetSign(w, cursorX + width / 2, y, z - 4, it.name());
                done++;
            } else {
                sender.sendMessage("§c  fallo §f" + it.name());
            }
            cursorX += width + gap;
        }
        sender.sendMessage("§a[Esquematico] calle pegada al este del spawn (" + done + "/"
                + items.size() + ", de menor a mayor, separados por su anchura).");
    }

    private void streetSign(org.bukkit.World w, int x, int y, int z, String name) {
        final org.bukkit.block.Block below = w.getBlockAt(x, y, z);
        if (below.getType().isAir()) {
            below.setType(org.bukkit.Material.GRASS_BLOCK, false);
        }
        final org.bukkit.block.Block b = w.getBlockAt(x, y + 1, z);
        b.setType(org.bukkit.Material.OAK_SIGN, false);
        if (b.getBlockData() instanceof org.bukkit.block.data.Rotatable rot) {
            rot.setRotation(org.bukkit.block.BlockFace.SOUTH);
            b.setBlockData(rot, false);
        }
        if (b.getState() instanceof org.bukkit.block.Sign s) {
            s.getSide(org.bukkit.block.sign.Side.FRONT).line(1,
                    net.kyori.adventure.text.Component.text("§6" + name));
            s.update(true);
        }
    }

    private void handleSchem(Player player, String[] args) {
        if (schematics == null) {
            player.sendMessage("§cLos esquematicos necesitan FAWE instalado (aun no disponible aqui).");
            return;
        }
        final String action = args.length >= 2 ? args[1].toLowerCase() : "list";
        switch (action) {
            case "list" -> schematics.list(player);
            case "paste", "pegar" -> {
                if (args.length < 3) {
                    player.sendMessage("Uso: /aetheria schem paste <nombre>");
                    return;
                }
                schematics.paste(player, args[2]);
            }
            case "save", "guardar" -> {
                if (args.length < 3) {
                    player.sendMessage("Uso: /aetheria schem save <nombre> (con una seleccion de //wand)");
                    return;
                }
                schematics.save(player, args[2]);
            }
            default -> player.sendMessage("Uso: /aetheria schem <list|paste <n>|save <n>>");
        }
    }

    private void handleCronica(Player player) {
        player.sendMessage("§7[Aetheria] abriendo la cronica del mundo...");
        gateway.getWorldEvents(150)
                .whenComplete((events, err) -> Bukkit.getScheduler().runTask(plugin, () -> {
                    if (err != null) {
                        player.sendMessage("§c[Aetheria] error: " + rootMessage(err));
                        return;
                    }
                    if (events.isEmpty()) {
                        player.sendMessage("§7El mundo aun no ha vivido nada digno de cronica.");
                        return;
                    }
                    player.getInventory().addItem(chronicleBook(events));
                    player.sendMessage("§a[Aetheria] Toma la §fCronica de Aetheria§a. Abrela para leerla.");
                }));
    }

    /** Construye un libro con la cronica maquetada (portada + sucesos, ~3 por pagina). */
    private org.bukkit.inventory.ItemStack chronicleBook(com.google.gson.JsonArray events) {
        final org.bukkit.inventory.ItemStack book =
                new org.bukkit.inventory.ItemStack(org.bukkit.Material.WRITTEN_BOOK);
        final org.bukkit.inventory.meta.BookMeta meta =
                (org.bukkit.inventory.meta.BookMeta) book.getItemMeta();
        meta.title(net.kyori.adventure.text.Component.text("Cronica de Aetheria"));
        meta.author(net.kyori.adventure.text.Component.text("El pueblo de Aetheria"));

        final java.util.List<net.kyori.adventure.text.Component> pages = new java.util.ArrayList<>();
        pages.add(net.kyori.adventure.text.Component.text(
                "§l  Cronica de\n     Aetheria\n\n§r§7Lo que ha ido viviendo el pueblo, del suceso mas "
                + "reciente al mas antiguo.\n\n§8(pasa la pagina)"));
        final StringBuilder sb = new StringBuilder();
        int inPage = 0;
        for (final var e : events) {
            final var o = e.getAsJsonObject();
            final String kind = o.get("kind").getAsString();
            final String desc = o.get("description").getAsString();
            // En los sucesos destacables (nacimiento, boda, muerte) se anota la fecha.
            String fecha = "";
            if (notable(kind) && o.has("created_at") && !o.get("created_at").isJsonNull()) {
                final String d = shortDate(o.get("created_at").getAsString());
                if (!d.isEmpty()) {
                    fecha = "§7(" + d + ") §0";
                }
            }
            sb.append(iconFor(kind)).append(fecha).append("§0").append(desc).append("\n\n");
            if (++inPage >= 3) {
                pages.add(net.kyori.adventure.text.Component.text(sb.toString()));
                sb.setLength(0);
                inPage = 0;
                if (pages.size() >= 50) {
                    break;   // nunca mas de 50 paginas
                }
            }
        }
        if (sb.length() > 0 && pages.size() < 50) {
            pages.add(net.kyori.adventure.text.Component.text(sb.toString()));
        }
        meta.pages(pages);
        book.setItemMeta(meta);
        return book;
    }

    private static boolean notable(String kind) {
        return switch (kind) {
            case "nacimiento", "boda", "obituario", "festival", "hardship" -> true;
            default -> false;
        };
    }

    /** ISO "2026-07-26T..." -> "26 jul". */
    private static String shortDate(String iso) {
        if (iso == null || iso.length() < 10) {
            return "";
        }
        final String[] mes = {"", "ene", "feb", "mar", "abr", "may", "jun",
                "jul", "ago", "sep", "oct", "nov", "dic"};
        try {
            final int m = Integer.parseInt(iso.substring(5, 7));
            final int d = Integer.parseInt(iso.substring(8, 10));
            return d + " " + (m >= 1 && m <= 12 ? mes[m] : "");
        } catch (Exception ex) {
            return "";
        }
    }

    private static String iconFor(String kind) {
        return switch (kind) {
            case "festival" -> "§2✦ ";        // festival
            case "hardship" -> "§4✦ ";        // penuria
            case "prosperity" -> "§6✦ ";      // rumbo del pueblo (florece/calma/mala racha)
            case "nacimiento" -> "§d✿ ";      // nacimiento
            case "boda" -> "§d❤ ";            // boda
            case "obituario" -> "§8✝ ";       // fallecimiento
            case "jubilacion" -> "§7☕ ";      // jubilacion
            case "relevo" -> "§e⚒ ";          // cambio de oficio (cubrir vacante)
            case "mejora" -> "§a✦ ";          // mejora del pueblo
            case "growth" -> "§2▲ ";          // crece
            case "decline" -> "§4▼ ";         // decae
            default -> "§8• ";                 // otros
        };
    }

    private static String join(String[] args, int from) {
        return String.join(" ", Arrays.copyOfRange(args, from, args.length));
    }

    private static String rootMessage(Throwable err) {
        Throwable t = err;
        while (t.getCause() != null) {
            t = t.getCause();
        }
        return t.getMessage();
    }
}
