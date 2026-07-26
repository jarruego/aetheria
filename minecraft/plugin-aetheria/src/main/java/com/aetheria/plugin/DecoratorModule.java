package com.aetheria.plugin;

import java.util.LinkedHashMap;
import java.util.Map;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;

/**
 * Decorador: servicio guiado para embellecer TU parcela con pequenas estructuras
 * (jardin, farola, estatua, gran fuente). Eliges en un menu, se cobra segun la pieza y solo
 * se construye si eres dueno del terreno. Complementa al arquitecto (que hace casas).
 */
public final class DecoratorModule implements CommandExecutor {

    private static final String BANCO = "00000000-0000-0000-0000-000000000000";

    private record Deco(String blueprint, int price, String label) {}

    // clave -> (blueprint, precio, etiqueta)
    private static final Map<String, Deco> CATALOG = new LinkedHashMap<>();

    static {
        CATALOG.put("jardin", new Deco("garden", 40, "Jardin con flores"));
        CATALOG.put("farola", new Deco("lamppost", 15, "Farola"));
        CATALOG.put("estatua", new Deco("statue", 50, "Estatua"));
        CATALOG.put("fuente", new Deco("bigfountain", 60, "Gran fuente"));
    }

    private final AetheriaPlugin plugin;
    private final GatewayClient gateway;
    private final ClaimModule claims;

    public DecoratorModule(AetheriaPlugin plugin, GatewayClient gateway, ClaimModule claims) {
        this.plugin = plugin;
        this.gateway = gateway;
        this.claims = claims;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
            @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Solo los jugadores pueden usar el decorador.");
            return true;
        }
        if (args.length == 0) {
            menu(player);
            return true;
        }
        final Deco deco = CATALOG.get(args[0].toLowerCase());
        if (deco == null) {
            menu(player);
            return true;
        }
        build(player, deco);
        return true;
    }

    private void menu(Player player) {
        player.sendMessage("§6[Decorador] §fEmbellezco tu parcela. Elige (se cobra al instante):");
        Component line = Component.text("  ");
        for (final var e : CATALOG.entrySet()) {
            line = line.append(Component.text("§a[" + e.getValue().label() + " · " + e.getValue().price() + "]")
                    .clickEvent(ClickEvent.runCommand("/decorador " + e.getKey())))
                    .append(Component.text("  "));
        }
        player.sendMessage(line);
        player.sendMessage("§7Se construye frente a ti, sobre tu parcela (usa §f/claim§7 primero).");
    }

    private void build(Player player, Deco deco) {
        final int chunkX = player.getLocation().getBlockX() >> 4;
        final int chunkZ = player.getLocation().getBlockZ() >> 4;
        if (!claims.ownsChunk(player.getUniqueId(), chunkX, chunkZ)) {
            player.sendMessage("§c[Decorador] Solo decoro en tu parcela. Reclama este sitio con "
                    + "§f/claim§c y vuelve a intentarlo.");
            return;
        }
        gateway.pay(player.getUniqueId().toString(), BANCO, deco.price())
                .whenComplete((json, err) -> Bukkit.getScheduler().runTask(plugin, () -> {
                    if (err != null || !json.get("ok").getAsBoolean()) {
                        final String why = (err == null && json.has("error"))
                                ? json.get("error").getAsString() : "no se pudo cobrar";
                        player.sendMessage("§c[Decorador] " + why + ". No he construido nada.");
                        return;
                    }
                    final int blocks = Blueprint.place(player, deco.blueprint());
                    player.sendMessage(String.format("§a[Decorador] Listo: §f%s§a, frente a ti (%d "
                            + "bloques). Se cobraron §e%d AET§a.", deco.label(), blocks, deco.price()));
                }));
    }
}
