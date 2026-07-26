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

    private final AetheriaPlugin plugin;
    private final GatewayClient gateway;
    private final String worldKey;
    // chunk (empaquetado) -> UUID del propietario.
    private final Map<Long, UUID> owners = new ConcurrentHashMap<>();

    public ClaimModule(AetheriaPlugin plugin, GatewayClient gateway, String worldKey) {
        this.plugin = plugin;
        this.gateway = gateway;
        this.worldKey = worldKey;
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
                final int chunkX = o.get("min_x").getAsInt() >> 4;
                final int chunkZ = o.get("min_z").getAsInt() >> 4;
                owners.put(key(chunkX, chunkZ), UUID.fromString(o.get("owner_uuid").getAsString()));
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
            final UUID owner = owners.get(key(chunkX, chunkZ));
            if (owner == null) {
                player.sendMessage("§7Esta parcela esta libre. Usa §f/claim§7 para reclamarla.");
            } else if (owner.equals(player.getUniqueId())) {
                player.sendMessage("§aEsta parcela es tuya.");
            } else {
                player.sendMessage("§eEsta parcela pertenece a otra persona.");
            }
            return true;
        }
        handleClaim(player, chunkX, chunkZ);
        return true;
    }

    private void handleClaim(Player player, int chunkX, int chunkZ) {
        if (owners.containsKey(key(chunkX, chunkZ))) {
            player.sendMessage("§eEsta parcela ya esta reclamada.");
            return;
        }
        player.sendMessage("§7[Aetheria] reclamando esta parcela...");
        gateway.claimPlot(player.getUniqueId().toString(), player.getName(), worldKey,
                        chunkX * 16, chunkZ * 16, chunkX * 16 + 15, chunkZ * 16 + 15)
                .whenComplete((json, err) -> Bukkit.getScheduler().runTask(plugin, () -> {
                    if (err != null) {
                        player.sendMessage("§c[Aetheria] error: " + err.getMessage());
                        return;
                    }
                    if (!json.get("ok").getAsBoolean()) {
                        player.sendMessage("§c[Aetheria] " + json.get("error").getAsString());
                        return;
                    }
                    owners.put(key(chunkX, chunkZ), player.getUniqueId());
                    final double price = json.has("data") && json.getAsJsonObject("data").has("price")
                            ? json.getAsJsonObject("data").get("price").getAsDouble() : 0.0;
                    player.sendMessage(String.format(
                            "§a[Aetheria] parcela reclamada. Se cobraron §e%.0f AET§a. Ya esta protegida.",
                            price));
                }));
    }

    private void handleUnclaim(Player player, int chunkX, int chunkZ) {
        final UUID owner = owners.get(key(chunkX, chunkZ));
        if (owner == null || !owner.equals(player.getUniqueId())) {
            player.sendMessage("§eNo tienes una parcela aqui.");
            return;
        }
        gateway.unclaimPlot(player.getUniqueId().toString(), worldKey, chunkX * 16, chunkZ * 16)
                .whenComplete((json, err) -> Bukkit.getScheduler().runTask(plugin, () -> {
                    if (err != null || !json.get("ok").getAsBoolean()) {
                        player.sendMessage("§c[Aetheria] no se pudo liberar la parcela.");
                        return;
                    }
                    owners.remove(key(chunkX, chunkZ));
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

    /** True si el bloque esta en una parcela de OTRO jugador (no del que actua). */
    private boolean isProtectedFromOthers(Block block, Player player) {
        final UUID owner = owners.get(key(block.getX() >> 4, block.getZ() >> 4));
        return owner != null && !owner.equals(player.getUniqueId());
    }

    private static long key(int chunkX, int chunkZ) {
        return (((long) chunkX) << 32) | (chunkZ & 0xffffffffL);
    }
}
