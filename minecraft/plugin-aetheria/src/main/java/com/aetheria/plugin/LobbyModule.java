package com.aetheria.plugin;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Difficulty;
import org.bukkit.GameMode;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.block.sign.Side;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.weather.WeatherChangeEvent;

import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;

import net.kyori.adventure.text.Component;

/**
 * Modulo LOBBY: convierte el servidor en un hub tipico de Minecraft.
 *
 * <p>Sala pequena y cerrada flotando en el vacio, con un portal al servidor principal.
 * Los jugadores estan en modo aventura, son invulnerables, no pueden morir, atacar,
 * recibir dano, pasar hambre ni tocar bloques. Solo se activa con rol 'lobby'; como este
 * plugin corre aqui SOLO en el lobby, las protecciones se aplican a todo el servidor.
 */
public final class LobbyModule implements Listener {

    private static final String CHANNEL = "BungeeCord";
    private static final long COOLDOWN_MS = 3000L;

    // Origen fijo de la sala (flota en el vacio); interior de 11x11.
    private static final int OX = 0;
    private static final int FLOOR_Y = 100;
    private static final int OZ = 0;
    private static final int R = 5;          // radio interior (11x11)
    private static final int WALL_H = 4;     // altura de paredes
    private static final int PORTAL_DZ = 3;  // portal a +3 en Z

    private final AetheriaPlugin plugin;
    private final String targetServer;
    private final Map<UUID, Long> lastJump = new HashMap<>();

    private Location hubSpawn;
    private Location portalCenter;

    public LobbyModule(AetheriaPlugin plugin, String targetServer) {
        this.plugin = plugin;
        this.targetServer = targetServer;
    }

    /** Construye la sala del lobby y ajusta el mundo (reglas, dificultad, hora). */
    public void build() {
        final World world = Bukkit.getWorlds().get(0);
        configureWorld(world);

        // Sala cerrada: suelo, techo, paredes (con cristaleras) de cuarzo.
        for (int dx = -R; dx <= R; dx++) {
            for (int dz = -R; dz <= R; dz++) {
                setBlock(world, dx, 0, dz, Material.QUARTZ_BLOCK);                 // suelo
                setBlock(world, dx, WALL_H + 1, dz, Material.QUARTZ_BLOCK);        // techo
                for (int dy = 1; dy <= WALL_H; dy++) {
                    if (Math.abs(dx) == R || Math.abs(dz) == R) {
                        final boolean window = (dy == 2 || dy == 3);
                        setBlock(world, dx, dy, dz, window ? Material.GLASS : Material.QUARTZ_BLOCK);
                    } else {
                        setBlock(world, dx, dy, dz, Material.AIR);                 // interior vacio
                    }
                }
            }
        }
        // Iluminacion en el techo.
        for (int[] p : new int[][] { { -3, -3 }, { 3, -3 }, { -3, 3 }, { 3, 3 }, { 0, 0 } }) {
            setBlock(world, p[0], WALL_H + 1, p[1], Material.SEA_LANTERN);
        }

        // Portal de esmeralda 3x3 con centro luminoso.
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                setBlock(world, dx, 0, PORTAL_DZ + dz, Material.EMERALD_BLOCK);
            }
        }
        setBlock(world, 0, 0, PORTAL_DZ, Material.SEA_LANTERN);

        // Cartel junto al portal.
        placeSign(world.getBlockAt(OX + 2, FLOOR_Y + 1, OZ + PORTAL_DZ),
                "== AETHERIA ==", "Pisa el portal", "para ir a", targetServer.toUpperCase());

        this.hubSpawn = new Location(world, OX + 0.5, FLOOR_Y + 1, OZ + 0.5);
        this.portalCenter = new Location(world, OX + 0.5, FLOOR_Y + 1, OZ + PORTAL_DZ + 0.5);
        world.setSpawnLocation(OX, FLOOR_Y + 1, OZ);

        plugin.getLogger().info("Lobby: sala construida; portal -> " + targetServer);
    }

    private void configureWorld(World world) {
        world.setDifficulty(Difficulty.PEACEFUL);
        world.setGameRule(GameRule.DO_MOB_SPAWNING, false);
        world.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false);
        world.setGameRule(GameRule.DO_WEATHER_CYCLE, false);
        world.setGameRule(GameRule.KEEP_INVENTORY, true);
        world.setGameRule(GameRule.DO_FIRE_TICK, false);
        world.setGameRule(GameRule.MOB_GRIEFING, false);
        world.setGameRule(GameRule.ANNOUNCE_ADVANCEMENTS, false);
        world.setGameRule(GameRule.FALL_DAMAGE, false);
        world.setGameRule(GameRule.FIRE_DAMAGE, false);
        world.setGameRule(GameRule.DROWNING_DAMAGE, false);
        world.setGameRule(GameRule.FREEZE_DAMAGE, false);
        world.setGameRule(GameRule.SHOW_DEATH_MESSAGES, false);
        world.setGameRule(GameRule.DO_IMMEDIATE_RESPAWN, true);
        world.setGameRule(GameRule.DO_INSOMNIA, false);
        world.setStorm(false);
        world.setThundering(false);
        world.setTime(6000); // mediodia fijo
    }

    private void setBlock(World world, int dx, int dy, int dz, Material material) {
        world.getBlockAt(OX + dx, FLOOR_Y + dy, OZ + dz).setType(material, false);
    }

    private void placeSign(Block block, String l0, String l1, String l2, String l3) {
        block.setType(Material.OAK_SIGN);
        if (block.getState() instanceof Sign sign) {
            final var front = sign.getSide(Side.FRONT);
            front.line(0, Component.text(l0));
            front.line(1, Component.text(l1));
            front.line(2, Component.text(l2));
            front.line(3, Component.text(l3));
            sign.update(true);
        }
    }

    // ---------------- Bienvenida y portal ----------------

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        final Player player = event.getPlayer();
        player.setGameMode(GameMode.ADVENTURE);
        player.setInvulnerable(true);
        player.setFoodLevel(20);
        player.setSaturation(20f);
        player.setHealth(20.0);   // salud maxima por defecto en el lobby
        if (hubSpawn != null) {
            player.teleport(hubSpawn);
        }
        player.sendMessage("§b§lBienvenido a Aetheria");
        player.sendMessage("§7Una civilizacion viva gobernada por IA.");
        player.sendMessage("§fPisa el §aportal de esmeralda§f para entrar al mundo §a" + targetServer + "§f.");
        player.sendMessage("§7O escribe §f/server " + targetServer + "§7. Habla con la IA: §f/aetheria ask <mensaje>");
    }

    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        if (portalCenter == null || event.getTo() == null) {
            return;
        }
        if (event.getFrom().getBlockX() == event.getTo().getBlockX()
                && event.getFrom().getBlockZ() == event.getTo().getBlockZ()) {
            return;
        }
        final Player player = event.getPlayer();
        if (event.getTo().distanceSquared(portalCenter) > 2.25) {
            return;
        }
        final long now = System.currentTimeMillis();
        final Long last = lastJump.get(player.getUniqueId());
        if (last != null && now - last < COOLDOWN_MS) {
            return;
        }
        lastJump.put(player.getUniqueId(), now);
        sendToServer(player, targetServer);
    }

    private void sendToServer(Player player, String server) {
        player.sendMessage("§a[Aetheria] Viajando a " + server + "...");
        final ByteArrayDataOutput out = ByteStreams.newDataOutput();
        out.writeUTF("Connect");
        out.writeUTF(server);
        player.sendPluginMessage(plugin, CHANNEL, out.toByteArray());
    }

    // ---------------- Protecciones del lobby ----------------

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player) {
            event.setCancelled(true);   // no recibes dano (ni caes, ni te ahogas, etc.)
            // Red de seguridad: si de algun modo cae al vacio, vuelve al spawn.
            if (event.getCause() == EntityDamageEvent.DamageCause.VOID && hubSpawn != null) {
                player.teleport(hubSpawn);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onAttack(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player) {
            event.setCancelled(true);   // no puedes atacar
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onHunger(FoodLevelChangeEvent event) {
        event.setCancelled(true);
        if (event.getEntity() instanceof Player player) {
            player.setFoodLevel(20);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onWeather(WeatherChangeEvent event) {
        if (event.toWeatherState()) {
            event.setCancelled(true);   // nunca llueve
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent event) {
        if (event.getSpawnReason() != CreatureSpawnEvent.SpawnReason.CUSTOM) {
            event.setCancelled(true);   // sin mobs (salvo los que ponga el plugin)
        }
    }
}
