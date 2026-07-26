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

        // NPC guias conversables (junto a los portales, en cualquier servidor).
        final ConversationManager convo = new ConversationManager(this, gateway);
        getServer().getPluginManager().registerEvents(convo, this);

        // Fase 5: registrar a los jugadores en la DB al entrar.
        getServer().getPluginManager().registerEvents(new PlayerSyncListener(this, gateway), this);

        // Rol del servidor: 'lobby' activa el hub con portales; por defecto 'main'.
        final String role = System.getenv().getOrDefault("AETHERIA_ROLE",
                getConfig().getString("role", "main")).toLowerCase();
        if (role.equals("lobby")) {
            getServer().getMessenger().registerOutgoingPluginChannel(this, "BungeeCord");
            final LobbyModule lobby = new LobbyModule(this, LobbyModule.readPortals(this), convo);
            getServer().getPluginManager().registerEvents(lobby, this);
            // El mundo ya esta cargado cuando se habilitan los plugins.
            lobby.build();
        } else {
            // En los mundos de juego: comandos de casa (/home, /sethome) sobre la DB.
            final HomeCommand homeCmd = new HomeCommand(this, gateway, role);
            Objects.requireNonNull(getCommand("home")).setExecutor(homeCmd);
            Objects.requireNonNull(getCommand("sethome")).setExecutor(homeCmd);

            // Economia (Fase 6): saldo y pagos.
            final EconomyCommand ecoCmd = new EconomyCommand(this, gateway);
            Objects.requireNonNull(getCommand("balance")).setExecutor(ecoCmd);
            Objects.requireNonNull(getCommand("pay")).setExecutor(ecoCmd);

            // Portal de vuelta al lobby (con su guia).
            if (getConfig().getBoolean("return-portal.enabled", true)) {
                getServer().getMessenger().registerOutgoingPluginChannel(this, "BungeeCord");
                final ReturnPortalModule ret = new ReturnPortalModule(this,
                        getConfig().getString("return-portal.target", "lobby"), convo);
                getServer().getPluginManager().registerEvents(ret, this);
                ret.build();
            }
        }

        getLogger().info("Aetheria habilitado (rol: " + role + "). Gateway: " + url);
    }

    @Override
    public void onDisable() {
        getLogger().info("Aetheria deshabilitado.");
    }

    public GatewayClient gateway() {
        return gateway;
    }
}
