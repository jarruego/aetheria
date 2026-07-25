package com.aetheria.plugin;

import java.util.Objects;

import org.bukkit.plugin.java.JavaPlugin;

/**
 * Plugin Aetheria. El unico componente que EJECUTA cambios en el mundo.
 *
 * <p>Nunca llama a un LLM directamente ni ejecuta acciones fuera de la lista blanca ni
 * planes sin aprobar: solo habla con el API Gateway y ejecuta planes ya validados.
 */
public final class AetheriaPlugin extends JavaPlugin {

    private GatewayClient gateway;
    private NpcManager npcs;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        final String url = getConfig().getString("gateway.url", "http://api-gateway:8080");
        final String configToken =
                getConfig().getString("gateway.token", "changeme-generate-a-long-random-secret");
        final String token = System.getenv().getOrDefault("INTERNAL_SERVICE_TOKEN", configToken);
        final String defaultNpc = getConfig().getString("default-npc", "arquitecto-01");

        this.gateway = new GatewayClient(this, url, token);
        this.npcs = new NpcManager();

        final AetheriaCommand command = new AetheriaCommand(this, gateway, npcs, defaultNpc);
        Objects.requireNonNull(getCommand("aetheria"), "comando 'aetheria' no declarado en plugin.yml")
                .setExecutor(command);

        getLogger().info("Aetheria habilitado. Gateway: " + url);
    }

    @Override
    public void onDisable() {
        getLogger().info("Aetheria deshabilitado.");
    }

    public GatewayClient gateway() {
        return gateway;
    }
}
