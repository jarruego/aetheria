package com.aetheria.plugin;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * Deshacer construcciones: antes de que el arquitecto o el decorador construyan, se
 * FOTOGRAFIA el terreno de la zona. Con /deshacer, el jugador revierte su ultima
 * construccion (se restaura el terreno original) y recupera el dinero menos una pequena
 * tasa de demolicion. Asi puede rehacerla en otro sitio.
 *
 * <p>El historial es en memoria (por sesion): pensado para "acabo de construir y no me
 * gusta donde". Un reinicio del servidor lo vacia.
 */
public final class UndoModule implements CommandExecutor {

    private static final double REFUND_PCT = 0.9;   // se devuelve el 90% (10% demolicion)
    private static final int MAX_HISTORY = 8;

    private record Snap(World world, int[] region, BlockData[] blocks, double refund, String what) {}

    private final AetheriaPlugin plugin;
    private final GatewayClient gateway;
    private final Map<UUID, Deque<Snap>> history = new ConcurrentHashMap<>();

    public UndoModule(AetheriaPlugin plugin, GatewayClient gateway) {
        this.plugin = plugin;
        this.gateway = gateway;
    }

    /** Fotografia la region ANTES de construir y guarda cuanto se reembolsaria. */
    public void snapshot(Player player, int[] r, double price, String what) {
        final World w = player.getWorld();
        final int nx = r[3] - r[0] + 1;
        final int ny = r[4] - r[1] + 1;
        final int nz = r[5] - r[2] + 1;
        final BlockData[] blocks = new BlockData[nx * ny * nz];
        int i = 0;
        for (int x = r[0]; x <= r[3]; x++) {
            for (int y = r[1]; y <= r[4]; y++) {
                for (int z = r[2]; z <= r[5]; z++) {
                    blocks[i++] = w.getBlockAt(x, y, z).getBlockData();
                }
            }
        }
        final double refund = Math.round(price * REFUND_PCT * 100.0) / 100.0;
        final Deque<Snap> stack = history.computeIfAbsent(player.getUniqueId(), k -> new ArrayDeque<>());
        stack.push(new Snap(w, r, blocks, refund, what));
        while (stack.size() > MAX_HISTORY) {
            stack.removeLast();
        }
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
            @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Solo los jugadores pueden deshacer.");
            return true;
        }
        final Deque<Snap> stack = history.get(player.getUniqueId());
        if (stack == null || stack.isEmpty()) {
            player.sendMessage("§7No tienes construcciones recientes que deshacer.");
            return true;
        }
        final Snap s = stack.pop();
        // Restaura el terreno original.
        final int[] r = s.region();
        int i = 0;
        for (int x = r[0]; x <= r[3]; x++) {
            for (int y = r[1]; y <= r[4]; y++) {
                for (int z = r[2]; z <= r[5]; z++) {
                    s.world().getBlockAt(x, y, z).setBlockData(s.blocks()[i++], false);
                }
            }
        }
        // Devuelve el dinero (menos la tasa de demolicion).
        gateway.reward(player.getUniqueId().toString(), s.refund(), "demolicion")
                .whenComplete((json, err) -> Bukkit.getScheduler().runTask(plugin, () ->
                        player.sendMessage(String.format("§a[Deshacer] He retirado %s y te devuelvo "
                                + "§e%.0f AET§a (90%%). Ya puedes rehacerla donde quieras.",
                                s.what(), s.refund()))));
        return true;
    }
}
