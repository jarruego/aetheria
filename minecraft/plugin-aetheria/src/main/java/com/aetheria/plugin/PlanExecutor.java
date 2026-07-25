package com.aetheria.plugin;

import java.util.Set;

import org.bukkit.entity.Player;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Ejecuta las acciones de un plan YA aprobado por el validador del backend.
 *
 * <p>Defensa en profundidad: aunque el backend ya valida, el plugin mantiene su PROPIA
 * lista blanca. Cualquier accion desconocida se ignora y se registra: el mundo nunca
 * ejecuta algo fuera de esta lista.
 */
public final class PlanExecutor {

    private static final Set<String> WHITELIST =
            Set.of("SAY", "MOVE_TO", "PLACE_BLUEPRINT", "GIVE_ITEM", "OPEN_TRADE");

    private final AetheriaPlugin plugin;

    public PlanExecutor(AetheriaPlugin plugin) {
        this.plugin = plugin;
    }

    /** Debe llamarse en el hilo principal del servidor. */
    public void execute(Player player, JsonArray actions) {
        player.sendMessage("§7[Aetheria] plan aprobado con " + actions.size() + " accion(es).");

        for (JsonElement element : actions) {
            final JsonObject action = element.getAsJsonObject();
            final String type = action.get("type").getAsString();

            if (!WHITELIST.contains(type)) {
                plugin.getLogger().warning("Accion fuera de la lista blanca, ignorada: " + type);
                continue;
            }

            final JsonObject params =
                    action.has("params") ? action.getAsJsonObject("params") : new JsonObject();

            switch (type) {
                case "SAY" -> {
                    final String txt = params.has("text") ? params.get("text").getAsString() : "";
                    player.sendMessage("§a[NPC] §f" + txt);
                }
                // Las siguientes se implementaran con NPC/entidades reales en iteraciones
                // posteriores. De momento se registran (sin tocar el mundo) para no fingir
                // efectos que aun no existen.
                case "MOVE_TO", "PLACE_BLUEPRINT", "GIVE_ITEM", "OPEN_TRADE" ->
                        plugin.getLogger().info("Accion '" + type + "' aun no implementada en el mundo: " + params);
                default ->
                        plugin.getLogger().warning("Accion en lista blanca sin manejador: " + type);
            }
        }
    }
}
