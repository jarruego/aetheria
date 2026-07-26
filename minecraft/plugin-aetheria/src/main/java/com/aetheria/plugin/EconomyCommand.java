package com.aetheria.plugin;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * Economia (Fase 6):
 *   /balance            -> muestra tu saldo
 *   /pay <jugador> <n>  -> transfiere AET a otro jugador conectado
 * Persistido en la base de datos via el API Gateway.
 */
public final class EconomyCommand implements CommandExecutor {

    private final AetheriaPlugin plugin;
    private final GatewayClient gateway;

    public EconomyCommand(AetheriaPlugin plugin, GatewayClient gateway) {
        this.plugin = plugin;
        this.gateway = gateway;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
            @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Solo los jugadores pueden usar este comando.");
            return true;
        }

        if (command.getName().equalsIgnoreCase("balance")) {
            gateway.getBalance(player.getUniqueId().toString())
                    .whenComplete((json, err) -> runSync(() -> {
                        if (err != null) {
                            player.sendMessage("§c[Aetheria] no pude consultar tu saldo.");
                            return;
                        }
                        player.sendMessage("§a[Aetheria] Tienes §e" + json.get("balance").getAsDouble()
                                + " " + json.get("currency").getAsString());
                    }));
            return true;
        }

        // /pay <jugador> <cantidad>
        if (args.length < 2) {
            player.sendMessage("Uso: /pay <jugador> <cantidad>");
            return true;
        }
        final Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null) {
            player.sendMessage("§e[Aetheria] ese jugador no esta conectado.");
            return true;
        }
        if (target.equals(player)) {
            player.sendMessage("§e[Aetheria] no puedes pagarte a ti mismo.");
            return true;
        }
        final double amount;
        try {
            amount = Double.parseDouble(args[1]);
        } catch (NumberFormatException e) {
            player.sendMessage("§e[Aetheria] cantidad invalida.");
            return true;
        }
        if (amount <= 0) {
            player.sendMessage("§e[Aetheria] la cantidad debe ser positiva.");
            return true;
        }

        gateway.pay(player.getUniqueId().toString(), target.getUniqueId().toString(), amount)
                .whenComplete((json, err) -> runSync(() -> {
                    if (err != null) {
                        player.sendMessage("§c[Aetheria] error al procesar el pago.");
                        return;
                    }
                    if (json.get("ok").getAsBoolean()) {
                        player.sendMessage("§a[Aetheria] Has pagado §e" + amount + " AET§a a " + target.getName());
                        target.sendMessage("§a[Aetheria] " + player.getName() + " te ha pagado §e" + amount + " AET");
                    } else {
                        player.sendMessage("§e[Aetheria] " + json.get("error").getAsString());
                    }
                }));
        return true;
    }

    private void runSync(Runnable task) {
        Bukkit.getScheduler().runTask(plugin, task);
    }
}
