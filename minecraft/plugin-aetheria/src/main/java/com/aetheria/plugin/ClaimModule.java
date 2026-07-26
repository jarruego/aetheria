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

    /** Una parcela en la cache: quien es el dueno y a que altura se reclamo. */
    private record Claim(UUID owner, int baseY) {}

    private final AetheriaPlugin plugin;
    private final GatewayClient gateway;
    private final String worldKey;
    // chunk (empaquetado) -> parcela (propietario + altura de referencia).
    private final Map<Long, Claim> owners = new ConcurrentHashMap<>();

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
                final int baseY = o.has("base_y") && !o.get("base_y").isJsonNull()
                        ? o.get("base_y").getAsInt() : 64;
                owners.put(key(chunkX, chunkZ),
                        new Claim(UUID.fromString(o.get("owner_uuid").getAsString()), baseY));
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
            doClaim(player, chunkX, chunkZ, false);
        } else if (args.length >= 1 && args[0].equalsIgnoreCase("alquilar")) {
            doClaim(player, chunkX, chunkZ, true);
        } else {
            showClaimMenu(player, chunkX, chunkZ);
        }
        return true;
    }

    private void showClaimMenu(Player player, int chunkX, int chunkZ) {
        if (owners.containsKey(key(chunkX, chunkZ))) {
            player.sendMessage("§eEsta parcela ya esta reclamada.");
            return;
        }
        player.sendMessage("§6[Parcela] §f¿Como quieres esta parcela? Elige:");
        player.sendMessage(net.kyori.adventure.text.Component.text("  ")
                .append(opt("§a[Comprar (50 AET, para siempre)]", "/claim comprar"))
                .append(net.kyori.adventure.text.Component.text("   "))
                .append(opt("§b[Alquilar (10 AET + renta)]", "/claim alquilar")));
    }

    private net.kyori.adventure.text.Component opt(String label, String cmd) {
        return net.kyori.adventure.text.Component.text(label)
                .clickEvent(net.kyori.adventure.text.event.ClickEvent.runCommand(cmd));
    }

    private void doClaim(Player player, int chunkX, int chunkZ, boolean rental) {
        if (owners.containsKey(key(chunkX, chunkZ))) {
            player.sendMessage("§eEsta parcela ya esta reclamada.");
            return;
        }
        final int baseY = player.getLocation().getBlockY();   // altura de referencia de la parcela
        player.sendMessage("§7[Aetheria] " + (rental ? "alquilando" : "comprando") + " esta parcela...");
        gateway.claimPlot(player.getUniqueId().toString(), player.getName(), worldKey,
                        chunkX * 16, chunkZ * 16, chunkX * 16 + 15, chunkZ * 16 + 15, baseY, rental)
                .whenComplete((json, err) -> Bukkit.getScheduler().runTask(plugin, () -> {
                    if (err != null) {
                        player.sendMessage("§c[Aetheria] error: " + err.getMessage());
                        return;
                    }
                    if (!json.get("ok").getAsBoolean()) {
                        player.sendMessage("§c[Aetheria] " + json.get("error").getAsString());
                        return;
                    }
                    owners.put(key(chunkX, chunkZ), new Claim(player.getUniqueId(), baseY));
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
