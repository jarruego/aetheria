package com.aetheria.plugin;

import java.util.List;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.jetbrains.annotations.NotNull;

import net.kyori.adventure.text.Component;

/**
 * Interfaz de vida del server: marcador lateral con tu saldo y el estado del mundo,
 * bienvenida al entrar, y una GUIA (libro) con todo lo que se puede hacer. Que al entrar
 * se note que el server esta vivo y sepas por donde empezar.
 */
public final class HudModule implements Listener, CommandExecutor {

    private final AetheriaPlugin plugin;
    private final GatewayClient gateway;

    public HudModule(AetheriaPlugin plugin, GatewayClient gateway) {
        this.plugin = plugin;
        this.gateway = gateway;
    }

    /** Refresca el marcador de todos los jugadores cada 5 s. */
    public void start() {
        plugin.getServer().getScheduler().runTaskTimer(plugin,
                () -> Bukkit.getOnlinePlayers().forEach(this::updateSidebar), 60L, 100L);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        final Player player = event.getPlayer();
        player.sendMessage("§6✦ §eBienvenido a §6Aetheria§e, " + player.getName() + " §6✦");
        player.sendMessage("§7Un pueblo con vida propia. Escribe §f/guia§7 para saber que puedes hacer.");
        updateSidebar(player);
        if (!player.hasPlayedBefore()) {
            player.getInventory().addItem(guideBook());
            player.sendMessage("§aTe he dado la §fGuia de Aetheria§a. Ábrela para empezar.");
        }
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
            @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Solo los jugadores tienen guia.");
            return true;
        }
        player.getInventory().addItem(guideBook());
        player.sendMessage("§aAqui tienes la §fGuia de Aetheria§a.");
        return true;
    }

    private void updateSidebar(Player player) {
        gateway.getBalance(player.getUniqueId().toString()).whenComplete((bal, e1) ->
            gateway.getProsperity().whenComplete((pros, e2) -> Bukkit.getScheduler().runTask(plugin, () -> {
                if (!player.isOnline()) {
                    return;
                }
                final double balance = (e1 == null && bal != null) ? bal.get("balance").getAsDouble() : 0.0;
                final String level = (e2 == null && pros != null) ? pros.get("level").getAsString() : "estable";
                render(player, balance, level);
            })));
    }

    private void render(Player player, double balance, String prosperity) {
        Scoreboard board = player.getScoreboard();
        if (board == null || board == Bukkit.getScoreboardManager().getMainScoreboard()) {
            board = Bukkit.getScoreboardManager().getNewScoreboard();
            player.setScoreboard(board);
        }
        final Objective old = board.getObjective("aetheria");
        if (old != null) {
            old.unregister();
        }
        final Objective obj = board.registerNewObjective("aetheria", "dummy",
                Component.text("§6✦ §eAetheria §6✦"));
        obj.setDisplaySlot(DisplaySlot.SIDEBAR);
        // Lineas de mayor (arriba) a menor (abajo).
        line(obj, "§eTu saldo:", 7);
        line(obj, String.format("§a%.2f AET", balance), 6);
        line(obj, "§0", 5);
        line(obj, "§ePueblo: §f" + prosperity, 4);
        line(obj, "§eJugadores: §f" + Bukkit.getOnlinePlayers().size(), 3);
        line(obj, "§1", 2);
        line(obj, "§7gana AET: /sell · ayuda: /guia", 1);
    }

    private void line(Objective obj, String text, int score) {
        obj.getScore(text).setScore(score);
    }

    private ItemStack guideBook() {
        final ItemStack book = new ItemStack(Material.WRITTEN_BOOK);
        final BookMeta meta = (BookMeta) book.getItemMeta();
        meta.title(Component.text("Guia de Aetheria"));
        meta.author(Component.text("El pueblo de Aetheria"));
        meta.pages(List.of(
                Component.text("§6Bienvenido a Aetheria\n\n§0Un pueblo de Minecraft con vida propia: "
                        + "vecinos con rutina, economia y una sociedad que prospera... o decae.\n\n"
                        + "§0Pasa la pagina para saber que puedes hacer."),
                Component.text("§6Gana dinero (AET)\n\n§0- Trabaja: mina, tala, cosecha o caza. "
                        + "Cobras solo por hacerlo.\n\n§0- Vende: §7/sell§0 lo que lleves en la mano, "
                        + "§7/sell all§0 todo el tipo. §7/worth§0 mira su valor."),
                Component.text("§6Tu dinero\n\n§0- §7/balance§0 tu saldo.\n- §7/pay <jugador> <n>§0 "
                        + "pagar a otro.\n- §7/shop§0 precios del mercado.\n\nLo ves siempre en el "
                        + "marcador de la derecha."),
                Component.text("§6Servicios de la IA\n\n§0Contrata a los maestros del pueblo:\n"
                        + "§7/aetheria servicio arquitecto <que quieres>§0\ntambien decorador y "
                        + "urbanista. La IA construye por ti y te cobra solo si lo logra."),
                Component.text("§6Tu hogar y tierras\n\n§0- §7/sethome§0 y §7/home§0.\n- §7/claim§0 "
                        + "reclama la parcela (chunk) donde estas: es tuya y nadie mas puede tocarla. "
                        + "§7/unclaim§0 para soltarla."),
                Component.text("§6El pueblo vive\n\n§0- Habla con los vecinos (clic derecho).\n"
                        + "- §7/aetheria cronica§0: que ha pasado mientras no estabas.\n\n"
                        + "§0La economia del pueblo evoluciona sola, dia y noche.")));
        book.setItemMeta(meta);
        return book;
    }
}
