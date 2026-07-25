package com.aetheria.plugin;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

/**
 * Registra a los jugadores en la base de datos al entrar (Fase 5). Silencioso: un fallo
 * de red no debe molestar al jugador. Es el primer evento del juego que persiste "verdad"
 * del mundo (la tabla players deja de ser solo la semilla).
 */
public final class PlayerSyncListener implements Listener {

    private final AetheriaPlugin plugin;
    private final GatewayClient gateway;

    public PlayerSyncListener(AetheriaPlugin plugin, GatewayClient gateway) {
        this.plugin = plugin;
        this.gateway = gateway;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        final Player player = event.getPlayer();
        gateway.upsertPlayer(player.getUniqueId().toString(), player.getName())
                .exceptionally(ex -> {
                    plugin.getLogger().warning("No pude registrar al jugador " + player.getName()
                            + ": " + ex.getMessage());
                    return null;
                });
    }
}
