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

    public AetheriaCommand(AetheriaPlugin plugin, GatewayClient gateway, NpcManager npcs,
            String defaultNpc) {
        this.plugin = plugin;
        this.gateway = gateway;
        this.npcs = npcs;
        this.defaultNpc = defaultNpc;
        this.executor = new PlanExecutor(plugin, npcs);
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
            @NotNull String label, @NotNull String[] args) {
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
            default -> player.sendMessage(
                    "Subcomando desconocido: " + sub + " (usa ask|plan|npc|servicio)");
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
