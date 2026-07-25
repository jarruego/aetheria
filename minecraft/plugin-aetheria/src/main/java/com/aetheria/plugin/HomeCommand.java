package com.aetheria.plugin;

import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * Comandos de casa:
 *   /sethome  -> guarda tu posicion actual
 *   /home     -> te teletransporta a tu casa guardada
 */
public final class HomeCommand implements CommandExecutor {

    private final HomeManager homes;

    public HomeCommand(HomeManager homes) {
        this.homes = homes;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
            @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Solo los jugadores pueden usar este comando.");
            return true;
        }

        if (command.getName().equalsIgnoreCase("sethome")) {
            homes.setHome(player);
            player.sendMessage("§a[Aetheria] Casa guardada en tu posicion actual. Usa §f/home§a para volver.");
            return true;
        }

        // /home
        final Location home = homes.getHome(player);
        if (home == null) {
            player.sendMessage("§e[Aetheria] Aun no tienes casa. Ponte donde quieras y usa §f/sethome§e.");
            return true;
        }
        player.teleport(home);
        player.sendMessage("§a[Aetheria] En casa, dulce hogar.");
        return true;
    }
}
