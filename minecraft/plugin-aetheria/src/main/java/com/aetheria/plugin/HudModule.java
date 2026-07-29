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

    // Prosperidad GLOBAL cacheada: es la MISMA para todos, asi que se pide UNA vez por ciclo (no
    // una por jugador cada 5 s, que multiplicaba las llamadas al backend).
    private volatile String cachedLevel = "estable";
    private volatile String cachedNext = "estable";

    /** Refresca el marcador de todos los jugadores cada 8 s. */
    public void start() {
        plugin.getServer().getScheduler().runTaskTimer(plugin, () ->
            gateway.getProsperity().whenComplete((pros, e) -> Bukkit.getScheduler().runTask(plugin, () -> {
                if (e == null && pros != null) {
                    cachedLevel = pros.get("level").getAsString();
                    cachedNext = pros.has("next_level")
                            ? pros.get("next_level").getAsString() : cachedLevel;
                }
                Bukkit.getOnlinePlayers().forEach(this::updateSidebar);
            })), 60L, 160L);
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
        // Solo el SALDO es por jugador; la prosperidad ya viene cacheada (global, una vez por ciclo).
        gateway.getBalance(player.getUniqueId().toString()).whenComplete((bal, e1) ->
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!player.isOnline()) {
                    return;
                }
                final double balance = (e1 == null && bal != null) ? bal.get("balance").getAsDouble() : 0.0;
                render(player, balance, cachedLevel, cachedNext);
            }));
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
            // Lo que la aldea lleva ahorrado para el PROXIMO vecino: al 100% llega uno.
            line(obj, "§7 Proximo vecino: §f" + (int) tp + "%", i--);
            line(obj, "§7 " + bar(tp) + " §8" + town[6], i--);
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
                Component.text("§6Bienvenido a Aetheria\n\n§0Un mundo de Minecraft con vida "
                        + "propia: aldeas con vecinos que trabajan de verdad, economia y una "
                        + "sociedad que prospera... o decae.\n\n§0Pasa la pagina."),
                Component.text("§6Gana dinero (AET)\n\n§0- Trabaja: mina, tala, cosecha o caza. "
                        + "Cobras solo por hacerlo.\n\n§0- Vende: §7/sell§0 lo que lleves en la "
                        + "mano, §7/sell all§0 todo el tipo. §7/worth§0 mira su valor."),
                Component.text("§6Tu dinero\n\n§0- §7/balance§0 tu saldo.\n- §7/pay <jugador> "
                        + "<n>§0 pagar a otro.\n- §7/shop§0 precios.\n\n§0En el pueblo hay un "
                        + "MERCADO: haz clic al mercader y compra o vende en la ventana."),
                Component.text("§6Servicios\n\n§0§7/arquitecto§0 (o §7/arq§0) te hace una casa: "
                        + "elige en la ventana si la quieres §0a medida o tipo Minecraft.\n\n"
                        + "§7/decorador§0 (§7/dec§0): jardin, farola, estatua o fuente.\n\n"
                        + "§0Se construye en TU parcela."),
                Component.text("§6Tu hogar y tierras\n\n§0- §7/sethome§0 y §7/home§0.\n\n"
                        + "- §7/claim§0 reclama el terreno (chunk) donde estas: es tuyo y nadie "
                        + "mas puede tocarlo. §7/unclaim§0 para soltarlo.\n\n§0- §7/deshacer§0 "
                        + "revierte lo ultimo que te construyeron, con reembolso."),
                Component.text("§6Como crece una aldea\n\n§0Cada aldea AHORRA lo que producen "
                        + "sus vecinos. Cuando junta lo que cuesta el siguiente, nace o llega "
                        + "uno.\n\n§0Lo ves en el marcador: §7Proximo vecino: 62%§0.\n\n"
                        + "§0Cada vecino cuesta EL DOBLE que el anterior, asi que hacia los 6 o "
                        + "7 el pueblo se atasca solo."),
                Component.text("§6Ayuda a un pueblo\n\n§0Ahi entras tu. Puedes aportar dinero "
                        + "al fondo de la aldea que quieras:\n\n§0- El §7ARCA§0 de la plaza: "
                        + "haz clic y elige cuanto.\n- El §7ALCALDE§0: agachate y haz clic "
                        + "derecho.\n- §7/donar <cantidad>§0.\n\n§0Crecera antes gracias a ti."),
                Component.text("§6El granero\n\n§0Cada aldea guarda en su granero lo que sus "
                        + "vecinos cosechan, pescan, funden o fabrican.\n\n§0Si se llena, el "
                        + "pueblo vende el excedente y ese dinero va a su fondo: cuanto mas "
                        + "trabajan, mas rapido crecen."),
                Component.text("§6El pueblo vive\n\n§0- Habla con los vecinos (clic derecho). "
                        + "Cada uno tiene su edad, oficio y familia, y te recuerda.\n\n"
                        + "§0- §7/aetheria cronica§0: que ha pasado mientras no estabas.\n\n"
                        + "§0- §7/warps§0: viaje rapido por el pueblo.")));
        book.setItemMeta(meta);
        return book;
    }
}
