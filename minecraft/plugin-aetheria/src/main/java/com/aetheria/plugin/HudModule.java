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
    /** Pueblo vivo (puede ser null en el mundo creativo): da los datos POR ALDEA del marcador. */
    private final SettlementModule settlement;

    public HudModule(AetheriaPlugin plugin, GatewayClient gateway, SettlementModule settlement) {
        this.plugin = plugin;
        this.gateway = gateway;
        this.settlement = settlement;
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
                final boolean okPros = e2 == null && pros != null;
                final String level = okPros ? pros.get("level").getAsString() : "estable";
                // A donde va la economia del MUNDO (el detalle de progreso se ve por aldea).
                final String next = okPros && pros.has("next_level")
                        ? pros.get("next_level").getAsString() : level;
                render(player, balance, level, next);
            })));
    }

    private void render(Player player, double balance, String prosperity, String nextLevel) {
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
        // #15 - Datos de la ALDEA en cuyo radio esta el jugador (cambian al cambiar de aldea),
        // con SU progreso de prosperidad; debajo, el total del MUNDO de Aetheria (todas las
        // aldeas juntas). A campo abierto solo se ven los totales del mundo.
        final String[] town = settlement != null ? settlement.townInfo(player) : null;
        // Lineas de mayor (arriba) a menor (abajo).
        int i = 13;
        line(obj, "§eTu saldo:", i--);
        line(obj, String.format("§a%.2f AET", balance), i--);
        line(obj, "§eHora: §f" + worldTime(player.getWorld().getTime()), i--);
        line(obj, "§0", i--);
        if (town != null) {
            final double tp = Double.parseDouble(town[5]);
            line(obj, "§6▸ " + town[0], i--);
            line(obj, "§7 Vecinos: §f" + town[1] + " §7· §f" + town[2] + " AET", i--);
            line(obj, "§7 Va §f" + town[3], i--);
            // El progreso de crecimiento es de la ALDEA que pisas, no del mundo entero.
            line(obj, "§7 " + bar(tp) + " §f" + (int) tp + "% §7→ " + town[6], i--);
            if (!town[4].isEmpty()) {
                line(obj, "§7 Alcalde: §f" + town[4], i--);
            }
        } else {
            line(obj, "§6▸ A campo abierto", i--);
            line(obj, "§7 (entra en una aldea)", i--);
        }
        line(obj, "§1", i--);
        line(obj, "§6▸ Aetheria §8(el mundo)", i--);
        line(obj, "§7 " + (settlement != null ? settlement.townCount() : 0) + " aldeas · §f"
                + (settlement != null ? settlement.totalPopulation() : 0) + " §7habitantes", i--);
        line(obj, "§7 Economia: §f" + prosperity + " §8→ " + nextLevel, i--);
        line(obj, "§2", i--);
        line(obj, "§7Jugadores: §f" + Bukkit.getOnlinePlayers().size(), i--);
    }

    /** Barra de progreso de 10 casillas (lo que le falta al pueblo para el siguiente escalon). */
    private static String bar(double pct) {
        final int full = (int) Math.round(Math.max(0, Math.min(100, pct)) / 10.0);
        return "§a" + "▉".repeat(full) + "§8" + "▉".repeat(10 - full);
    }

    /** Hora del mundo en formato HH:MM con icono de la parte del dia (tick 0 = 06:00). */
    private static String worldTime(long ticks) {
        final long t = ((ticks % 24000) + 24000) % 24000;
        final int hour = (int) (((t / 1000) + 6) % 24);
        final int minute = (int) ((t % 1000) * 60 / 1000);
        final String fase;
        if (t < 1000 || t >= 23000) {
            fase = "§6(alba)";
        } else if (t < 11000) {
            fase = "§e(dia)";
        } else if (t < 13500) {
            fase = "§6(atardecer)";
        } else {
            fase = "§9(noche)";
        }
        return String.format("%02d:%02d %s", hour, minute, fase);
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
                Component.text("§6Servicios del pueblo\n\n§0Los maestros construyen para ti (sobre "
                        + "tu parcela):\n§7/arquitecto§0 casa a medida (guiada).\n§7/decorador§0 "
                        + "jardin, farola, estatua o fuente.\n\nPuedes deshacer con §7/deshacer§0."),
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
