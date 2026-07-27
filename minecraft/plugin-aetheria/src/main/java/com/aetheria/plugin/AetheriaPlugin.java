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
    private BuildRegistry registry;

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
        this.registry = new BuildRegistry(this);   // registro compartido de construcciones (anti-solape)

        final AetheriaCommand command = new AetheriaCommand(this, gateway, npcs, defaultNpc);
        Objects.requireNonNull(getCommand("aetheria"), "comando 'aetheria' no declarado en plugin.yml")
                .setExecutor(command);

        // Catalogo de esquematicos: solo si FAWE/WorldEdit esta instalado (si no, queda inactivo).
        if (getServer().getPluginManager().getPlugin("FastAsyncWorldEdit") != null
                || getServer().getPluginManager().getPlugin("WorldEdit") != null) {
            command.setSchematics(new SchematicModule(this));
            getLogger().info("Aetheria: catalogo de esquematicos activo (FAWE/WorldEdit detectado).");
        }

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

            // Un unico conserje que ronda el lobby y conoce todo el server.
            new LobbyGuideModule(this, convo, lobby.interiorCenter()).start();
        } else {
            // El mundo main debe ser lo mas "normal" posible: si el spawn cae en bioma raro
            // (hielo/desierto/mucha agua) se reubica ANTES de construir el portal y la aldea.
            if (role.equals("main")) {
                VillageModule.relocateSpawnToGoodBiome(this, getServer().getWorlds().get(0));
            }
            // En los mundos de juego: comandos de casa (/home, /sethome) sobre la DB.
            final HomeCommand homeCmd = new HomeCommand(this, gateway, role);
            Objects.requireNonNull(getCommand("home")).setExecutor(homeCmd);
            Objects.requireNonNull(getCommand("sethome")).setExecutor(homeCmd);

            // Economia (Fase 6): saldo y pagos.
            final EconomyCommand ecoCmd = new EconomyCommand(this, gateway);
            Objects.requireNonNull(getCommand("balance")).setExecutor(ecoCmd);
            Objects.requireNonNull(getCommand("pay")).setExecutor(ecoCmd);

            // Portal de vuelta al lobby (con su guia).
            org.bukkit.Location aeonSpot = null;   // centro del cuadrado decorado del spawn
            if (getConfig().getBoolean("return-portal.enabled", true)) {
                getServer().getMessenger().registerOutgoingPluginChannel(this, "BungeeCord");
                final ReturnPortalModule ret = new ReturnPortalModule(this,
                        getConfig().getString("return-portal.target", "lobby"), convo);
                getServer().getPluginManager().registerEvents(ret, this);
                ret.build();
                aeonSpot = ret.safeCenter();
            }

            // Conserje Aeon PASEANDO junto al spawn del mundo principal (en vez de un guia inmovil
            // de portal): recibe y orienta al que llega. Mismo personaje/persona que el del lobby.
            if (role.equals("main")) {
                final org.bukkit.World gw = getServer().getWorlds().get(0);
                // DENTRO del cuadrado 10x10 decorado del portal (centrado en spawn.z+3), no en el
                // spawn a secas: con centro = spawn y radio 8 rondaba por FUERA de la zona dibujada.
                final org.bukkit.Location c = aeonSpot != null ? aeonSpot : gw.getSpawnLocation();
                new LobbyGuideModule(this, convo, c, 3).start();   // radio 3: cabe de sobra en el 10x10
            }

            // Mundo CREATIVO: en vez de la aldea viva, un CATALOGO (galeria rotulada de todo lo
            // que sabemos construir). El resto del creativo se queda igual.
            if (role.equals("creative")) {
                new CatalogModule(this, getServer().getWorlds().get(0)).build();
            }

            // Fase 7: aldea fisica + vecinos con rutina diaria en el mundo principal (no en creativo).
            if (!role.equals("creative") && getConfig().getBoolean("npc-routines.enabled", true)) {
                final org.bukkit.World gameWorld = getServer().getWorlds().get(0);
                final VillageModule village = new VillageModule(this, gameWorld);
                village.build();
                final NpcRoutineModule routines =
                        new NpcRoutineModule(this, convo, gameWorld, village);
                routines.start();

                // Mercado FISICO con menu de inventario (mercader clicable). Se registra como
                // listener; SettlementModule spawnea el mercader cuando la aldea llega a 6 vecinos.
                final MarketModule market = new MarketModule(this, gateway);
                getServer().getPluginManager().registerEvents(market, this);

                // Pueblo vivo: crece (casas + colonos) o mengua (emigracion) segun prosperidad.
                final SettlementModule settlement =
                        new SettlementModule(this, gateway, village, routines, convo, gameWorld, market);
                getServer().getPluginManager().registerEvents(settlement, this);   // protege sus casas
                settlement.start();

                // #11: los aldeanos TRABAJAN fisicamente (cosechan, talan, pican, funden) y de
                // ese trabajo real vive la economia del pueblo.
                new LaborModule(this, gameWorld, gateway, routines).start(settlement);

                // Viaje rapido por los puntos de interes (plaza, mercado, taberna, spawn).
                final WarpModule warps = new WarpModule(village, gameWorld);
                Objects.requireNonNull(getCommand("warp")).setExecutor(warps);
                Objects.requireNonNull(getCommand("warps")).setExecutor(warps);
            }

            // Fase 9: parcelas reclamables con propietario y proteccion.
            final ClaimModule claims = new ClaimModule(this, gateway, role);
            if (getConfig().getBoolean("claims.enabled", true)) {
                Objects.requireNonNull(getCommand("claim")).setExecutor(claims);
                Objects.requireNonNull(getCommand("unclaim")).setExecutor(claims);
                getServer().getPluginManager().registerEvents(claims, this);
                claims.loadClaims();
            }

            // Arquitecto guiado (casa a medida, cobra por spec) + guia de servicios.
            // Deshacer construcciones (arquitecto/decorador) con reembolso.
            final UndoModule undo = new UndoModule(this, gateway);
            Objects.requireNonNull(getCommand("deshacer")).setExecutor(undo);

            final ArchitectModule architect = new ArchitectModule(this, gateway, claims, undo);
            Objects.requireNonNull(getCommand("arquitecto")).setExecutor(architect);
            Objects.requireNonNull(getCommand("servicios")).setExecutor(architect);
            getServer().getPluginManager().registerEvents(architect, this);   // clic para colocar

            // Decorador guiado: pequenas estructuras (jardin, farola, estatua, fuente).
            final DecoratorModule decorator = new DecoratorModule(this, gateway, claims, undo);
            Objects.requireNonNull(getCommand("decorador")).setExecutor(decorator);

            // Vida del server: trabajos (ganar AET por tareas), mercado y HUD/guia.
            final JobsModule jobs = new JobsModule(this, gateway);
            getServer().getPluginManager().registerEvents(jobs, this);
            jobs.start();

            final ShopModule shop = new ShopModule(this, gateway);
            Objects.requireNonNull(getCommand("sell")).setExecutor(shop);
            Objects.requireNonNull(getCommand("worth")).setExecutor(shop);
            Objects.requireNonNull(getCommand("shop")).setExecutor(shop);

            final HudModule hud = new HudModule(this, gateway);
            getServer().getPluginManager().registerEvents(hud, this);
            Objects.requireNonNull(getCommand("guia")).setExecutor(hud);
            hud.start();
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

    public BuildRegistry buildRegistry() {
        return registry;
    }
}
