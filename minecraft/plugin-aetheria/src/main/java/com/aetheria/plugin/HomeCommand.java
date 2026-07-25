package com.aetheria.plugin;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * Comandos de casa, persistidos en la BASE DE DATOS (Fase 5) via el API Gateway:
 *   /sethome  -> guarda tu posicion actual (para este servidor)
 *   /home     -> te teletransporta a tu casa guardada
 */
public final class HomeCommand implements CommandExecutor {

    private final AetheriaPlugin plugin;
    private final GatewayClient gateway;
    private final String server;

    public HomeCommand(AetheriaPlugin plugin, GatewayClient gateway, String server) {
        this.plugin = plugin;
        this.gateway = gateway;
        this.server = server;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
            @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Solo los jugadores pueden usar este comando.");
            return true;
        }

        if (command.getName().equalsIgnoreCase("sethome")) {
            handleSetHome(player);
        } else {
            handleHome(player);
        }
        return true;
    }

    private void handleSetHome(Player player) {
        final Location l = player.getLocation();
        gateway.setHome(player.getUniqueId().toString(), player.getName(), server,
                        l.getWorld().getName(), l.getX(), l.getY(), l.getZ(), l.getYaw(), l.getPitch())
                .whenComplete((ok, err) -> runSync(() -> {
                    if (err != null) {
                        player.sendMessage("§c[Aetheria] no pude guardar tu casa ahora mismo.");
                        return;
                    }
                    player.sendMessage("§a[Aetheria] Casa guardada. Usa §f/home§a para volver.");
                }));
    }

    private void handleHome(Player player) {
        gateway.getHome(player.getUniqueId().toString(), server)
                .whenComplete((json, err) -> runSync(() -> {
                    if (err != null) {
                        player.sendMessage("§c[Aetheria] no pude consultar tu casa ahora mismo.");
                        return;
                    }
                    if (json == null) {
                        player.sendMessage("§e[Aetheria] Aun no tienes casa aqui. Usa §f/sethome§e.");
                        return;
                    }
                    final World world = Bukkit.getWorld(json.get("world").getAsString());
                    if (world == null) {
                        player.sendMessage("§e[Aetheria] tu casa esta en otro mundo.");
                        return;
                    }
                    final Location home = new Location(
                            world,
                            json.get("x").getAsDouble(),
                            json.get("y").getAsDouble(),
                            json.get("z").getAsDouble(),
                            json.get("yaw").getAsFloat(),
                            json.get("pitch").getAsFloat());
                    player.teleport(home);
                    player.sendMessage("§a[Aetheria] En casa, dulce hogar.");
                }));
    }

    private void runSync(Runnable task) {
        Bukkit.getScheduler().runTask(plugin, task);
    }
}
