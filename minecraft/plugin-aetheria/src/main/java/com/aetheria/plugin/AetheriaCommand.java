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
 *   /aetheria ask  &lt;mensaje&gt;   -> conversacion con un NPC (3 niveles en el backend)
 *   /aetheria plan &lt;objetivo&gt;  -> pide un plan; si se aprueba, se ejecuta (lista blanca)
 */
public final class AetheriaCommand implements CommandExecutor {

    private final AetheriaPlugin plugin;
    private final GatewayClient gateway;
    private final PlanExecutor executor;
    private final String defaultNpc;

    public AetheriaCommand(AetheriaPlugin plugin, GatewayClient gateway, String defaultNpc) {
        this.plugin = plugin;
        this.gateway = gateway;
        this.defaultNpc = defaultNpc;
        this.executor = new PlanExecutor(plugin);
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
            @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Solo los jugadores pueden usar Aetheria.");
            return true;
        }
        if (args.length < 2) {
            player.sendMessage("Uso: /aetheria <ask|plan> <texto>");
            return true;
        }

        final String sub = args[0].toLowerCase();
        final String text = String.join(" ", Arrays.copyOfRange(args, 1, args.length));

        switch (sub) {
            case "ask" -> handleAsk(player, text);
            case "plan" -> handlePlan(player, text);
            default -> player.sendMessage("Subcomando desconocido: " + sub + " (usa ask|plan)");
        }
        return true;
    }

    private void handleAsk(Player player, String message) {
        player.sendMessage("§7[Aetheria] pensando...");
        gateway.conversation(defaultNpc, player.getUniqueId().toString(), message)
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
                    executor.execute(player, json.getAsJsonArray("actions"));
                }));
    }

    private static String rootMessage(Throwable err) {
        Throwable t = err;
        while (t.getCause() != null) {
            t = t.getCause();
        }
        return t.getMessage();
    }
}
