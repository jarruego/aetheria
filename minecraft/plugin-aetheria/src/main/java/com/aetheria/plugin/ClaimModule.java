package com.aetheria.plugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.jetbrains.annotations.NotNull;

/**
 * Fase 9 - Estructuras sociales: parcelas reclamables por chunk, con propietario y
 * PROTECCION. Un jugador reclama el chunk donde esta ({@code /claim}, cuesta AET), y a
 * partir de ahi nadie mas puede romper ni poner bloques dentro.
 *
 * <p>La verdad vive en la DB (via gateway/world-state). El plugin mantiene una CACHE en
 * memoria (chunk -> propietario) para decidir la proteccion sin una llamada de red por
 * cada bloque; la cache se carga al arrancar y se actualiza al reclamar/liberar.
 */
public final class ClaimModule implements CommandExecutor, Listener {

    // La proteccion cubre una banda vertical de +/-25 bloques alrededor de la altura a la
    // que se reclamo (no toda la columna): asi se puede minar muy por debajo o volar por encima.
    private static final int PROTECT_VERTICAL = 25;

    /** Una parcela en la cache: dueno, altura de referencia y su caja (para liberar y solapes). */
    private record Claim(UUID owner, int baseY, int minX, int minZ, int maxX, int maxZ) {}

    private final AetheriaPlugin plugin;
    private final GatewayClient gateway;
    private final String worldKey;
    // chunk (empaquetado) -> parcela (propietario + altura de referencia).
    private final Map<Long, Claim> owners = new ConcurrentHashMap<>();
    private QuestModule quests;              // opcional: encargos de "reclama una parcela"
    private SettlementModule settlement;     // para saber en que aldea se reclamo

    public ClaimModule(AetheriaPlugin plugin, GatewayClient gateway, String worldKey) {
        this.plugin = plugin;
        this.gateway = gateway;
        this.worldKey = worldKey;
    }

    /** Engancha las misiones y el pueblo (se inyectan despues; ambos son opcionales). */
    public void setQuests(QuestModule quests, SettlementModule settlement) {
        this.quests = quests;
        this.settlement = settlement;
    }

    /** Carga las parcelas del mundo en la cache (asincrono; no bloquea el arranque). */
    public void loadClaims() {
        gateway.getClaims(worldKey).whenComplete((arr, err) -> {
            if (err != null || arr == null) {
                plugin.getLogger().warning("[Aetheria] no pude cargar parcelas: "
                        + (err != null ? err.getMessage() : "sin datos"));
                return;
            }
            arr.forEach(e -> {
                final var o = e.getAsJsonObject();
                if (o.get("owner_uuid").isJsonNull()) {
                    return;
                }
                final int baseY = o.has("base_y") && !o.get("base_y").isJsonNull()
                        ? o.get("base_y").getAsInt() : 64;
                final int minX = o.get("min_x").getAsInt();
                final int minZ = o.get("min_z").getAsInt();
                final int maxX = o.get("max_x").getAsInt();
                final int maxZ = o.get("max_z").getAsInt();
                final Claim c = new Claim(UUID.fromString(o.get("owner_uuid").getAsString()),
                        baseY, minX, minZ, maxX, maxZ);
                for (int chx = minX >> 4; chx <= maxX >> 4; chx++) {
                    for (int chz = minZ >> 4; chz <= maxZ >> 4; chz++) {
                        owners.put(key(chx, chz), c);
                    }
                }
            });
            plugin.getLogger().info("[Aetheria] Fase 9: " + owners.size()
                    + " parcelas cargadas en '" + worldKey + "'.");
        });
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
            @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Solo los jugadores pueden reclamar parcelas.");
            return true;
        }
        final Location loc = player.getLocation();
        final int chunkX = loc.getBlockX() >> 4;
        final int chunkZ = loc.getBlockZ() >> 4;

        if (command.getName().equalsIgnoreCase("unclaim")) {
            handleUnclaim(player, chunkX, chunkZ);
            return true;
        }
        // /claim  (o  /claim info)
        if (args.length >= 1 && args[0].equalsIgnoreCase("info")) {
            final Claim c = owners.get(key(chunkX, chunkZ));
            if (c == null) {
                player.sendMessage("§7Esta parcela esta libre. Usa §f/claim§7 para reclamarla.");
            } else if (c.owner().equals(player.getUniqueId())) {
                player.sendMessage("§aEsta parcela es tuya. Protegida entre Y " + (c.baseY() - PROTECT_VERTICAL)
                        + " y " + (c.baseY() + PROTECT_VERTICAL) + ".");
            } else {
                player.sendMessage("§eEsta parcela pertenece a otra persona.");
            }
            return true;
        }
        if (args.length >= 1 && args[0].equalsIgnoreCase("comprar")) {
            doClaim(player, chunkX, chunkZ, size(args), false);
        } else if (args.length >= 1 && args[0].equalsIgnoreCase("alquilar")) {
            doClaim(player, chunkX, chunkZ, size(args), true);
        } else {
            showClaimMenu(player, chunkX, chunkZ);
        }
        return true;
    }

    /** Lado de la parcela en chunks segun el argumento (pequena=1, mediana=2, grande=3). */
    private static int size(String[] args) {
        if (args.length < 2) {
            return 1;
        }
        return switch (args[1].toLowerCase()) {
            case "mediana", "2" -> 2;
            case "grande", "3" -> 3;
            default -> 1;
        };
    }

    private void showClaimMenu(Player player, int chunkX, int chunkZ) {
        if (owners.containsKey(key(chunkX, chunkZ))) {
            player.sendMessage("§eEsta parcela (o parte de ella) ya esta reclamada.");
            return;
        }
        player.sendMessage("§6[Parcela] §fElige tamano y modo (compra fija o alquiler):");
        addLine(player, "Pequena", "1x1 chunk", 1);
        addLine(player, "Mediana", "2x2 chunks", 2);
        addLine(player, "Grande", "3x3 chunks", 3);
    }

    private void addLine(Player player, String name, String desc, int n) {
        final int buy = 50 * n * n;
        player.sendMessage(net.kyori.adventure.text.Component.text(
                        String.format("§e%s §7(%s): ", name, desc))
                .append(opt("§a[Comprar " + buy + "]", "/claim comprar " + n))
                .append(net.kyori.adventure.text.Component.text("  "))
                .append(opt("§b[Alquilar]", "/claim alquilar " + n)));
    }

    private net.kyori.adventure.text.Component opt(String label, String cmd) {
        return net.kyori.adventure.text.Component.text(label)
                .clickEvent(net.kyori.adventure.text.event.ClickEvent.runCommand(cmd));
    }

    private void doClaim(Player player, int chunkX, int chunkZ, int n, boolean rental) {
        // Caja de NxN chunks centrada en el jugador (grande centra; mediana ancla al SO).
        final int startX = chunkX - (n - 1) / 2;
        final int startZ = chunkZ - (n - 1) / 2;
        final int endX = startX + n - 1;
        final int endZ = startZ + n - 1;
        for (int chx = startX; chx <= endX; chx++) {
            for (int chz = startZ; chz <= endZ; chz++) {
                if (owners.containsKey(key(chx, chz))) {
                    player.sendMessage("§eNo cabe: se solapa con una parcela ya reclamada.");
                    return;
                }
            }
        }
        final int minX = startX * 16;
        final int minZ = startZ * 16;
        final int maxX = endX * 16 + 15;
        final int maxZ = endZ * 16 + 15;
        final int baseY = player.getLocation().getBlockY();
        player.sendMessage("§7[Aetheria] " + (rental ? "alquilando" : "comprando") + " una parcela de "
                + n + "x" + n + " chunks...");
        gateway.claimPlot(player.getUniqueId().toString(), player.getName(), worldKey,
                        minX, minZ, maxX, maxZ, baseY, rental)
                .whenComplete((json, err) -> Bukkit.getScheduler().runTask(plugin, () -> {
                    if (err != null) {
                        player.sendMessage("§c[Aetheria] error: " + err.getMessage());
                        return;
                    }
                    if (!json.get("ok").getAsBoolean()) {
                        player.sendMessage("§c[Aetheria] " + json.get("error").getAsString());
                        return;
                    }
                    final Claim c = new Claim(player.getUniqueId(), baseY, minX, minZ, maxX, maxZ);
                    for (int chx = startX; chx <= endX; chx++) {
                        for (int chz = startZ; chz <= endZ; chz++) {
                            owners.put(key(chx, chz), c);
                        }
                    }
                    // Hay encargos de "echa raices en el pueblo": reclamar cuenta como avance.
                    if (quests != null && settlement != null) {
                        final int vid = settlement.townAt(player);
                        if (vid >= 0) {
                            quests.onClaimed(player, settlement.townName(vid));
                        }
                    }
                    final var data = json.has("data") ? json.getAsJsonObject("data") : null;
                    final double price = data != null && data.has("price") ? data.get("price").getAsDouble() : 0.0;
                    if (rental) {
                        final double rent = data != null && data.has("rent") ? data.get("rent").getAsDouble() : 0.0;
                        player.sendMessage(String.format("§a[Aetheria] parcela alquilada (deposito §e%.0f "
                                + "AET§a). Renta §e%.0f AET§a por periodo; si no puedes pagarla, se libera. "
                                + "Ya esta protegida.", price, rent));
                    } else {
                        player.sendMessage(String.format("§a[Aetheria] parcela comprada por §e%.0f AET§a. "
                                + "Es tuya y esta protegida.", price));
                    }
                }));
    }

    private void handleUnclaim(Player player, int chunkX, int chunkZ) {
        final Claim c = owners.get(key(chunkX, chunkZ));
        if (c == null || !c.owner().equals(player.getUniqueId())) {
            player.sendMessage("§eNo tienes una parcela aqui.");
            return;
        }
        gateway.unclaimPlot(player.getUniqueId().toString(), worldKey, c.minX(), c.minZ())
                .whenComplete((json, err) -> Bukkit.getScheduler().runTask(plugin, () -> {
                    if (err != null || !json.get("ok").getAsBoolean()) {
                        player.sendMessage("§c[Aetheria] no se pudo liberar la parcela.");
                        return;
                    }
                    for (int chx = c.minX() >> 4; chx <= c.maxX() >> 4; chx++) {
                        for (int chz = c.minZ() >> 4; chz <= c.maxZ() >> 4; chz++) {
                            owners.remove(key(chx, chz));
                        }
                    }
                    player.sendMessage("§aParcela liberada. Ya no esta protegida.");
                }));
    }

    @EventHandler
    public void onBreak(BlockBreakEvent event) {
        if (isProtectedFromOthers(event.getBlock(), event.getPlayer())) {
            event.setCancelled(true);
            event.getPlayer().sendMessage("§cEsta parcela pertenece a otra persona.");
        }
    }

    @EventHandler
    public void onPlace(BlockPlaceEvent event) {
        if (isProtectedFromOthers(event.getBlock(), event.getPlayer())) {
            event.setCancelled(true);
            event.getPlayer().sendMessage("§cEsta parcela pertenece a otra persona.");
        }
    }

    /** True si el bloque esta en la banda protegida de una parcela de OTRO jugador. */
    private boolean isProtectedFromOthers(Block block, Player player) {
        final Claim c = owners.get(key(block.getX() >> 4, block.getZ() >> 4));
        if (c == null || c.owner().equals(player.getUniqueId())) {
            return false;
        }
        return Math.abs(block.getY() - c.baseY()) <= PROTECT_VERTICAL;   // solo dentro de la banda
    }

    /** True si ese jugador es el dueno de la parcela (chunk) indicada. */
    public boolean ownsChunk(UUID player, int chunkX, int chunkZ) {
        final Claim c = owners.get(key(chunkX, chunkZ));
        return c != null && c.owner().equals(player);
    }

    private static long key(int chunkX, int chunkZ) {
        return (((long) chunkX) << 32) | (chunkZ & 0xffffffffL);
    }
}
