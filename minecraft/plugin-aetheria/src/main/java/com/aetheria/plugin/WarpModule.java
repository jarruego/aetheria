package com.aetheria.plugin;

import java.util.LinkedHashMap;
import java.util.Map;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;

/**
 * Viaje rapido por los puntos de interes del mundo: plaza, mercado, taberna y spawn.
 * Hace que un mundo mas grande siga siendo comodo de recorrer.
 *   /warps        -> lista los destinos (clicables)
 *   /warp <nombre> -> te lleva alli
 */
public final class WarpModule implements CommandExecutor {

    private final Map<String, Location> warps = new LinkedHashMap<>();

    public WarpModule(VillageModule village, World world) {
        warps.put("plaza", village.plaza());
        warps.put("mercado", village.mercaderWork());
        warps.put("taberna", village.tavern());
        warps.put("spawn", world.getSpawnLocation());
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
            @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Solo los jugadores pueden viajar.");
            return true;
        }
        if (command.getName().equalsIgnoreCase("warps") || args.length == 0) {
            list(player);
            return true;
        }
        final Location dest = warps.get(args[0].toLowerCase());
        if (dest == null) {
            player.sendMessage("§7No conozco ese sitio. Mira §f/warps§7.");
            return true;
        }
        player.teleport(dest);
        player.sendMessage("§a[Viaje] Te has movido a §f" + args[0].toLowerCase() + "§a.");
        return true;
    }

    private void list(Player player) {
        player.sendMessage("§6=== Destinos ===");
        Component line = Component.text("  ");
        for (final String name : warps.keySet()) {
            line = line.append(Component.text("§a[" + name + "]")
                    .clickEvent(ClickEvent.runCommand("/warp " + name)))
                    .append(Component.text("  "));
        }
        player.sendMessage(line);
        player.sendMessage("§7Haz clic en un destino o escribe §f/warp <nombre>§7.");
    }
}
