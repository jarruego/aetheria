package com.aetheria.plugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
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
import org.bukkit.block.BlockFace;
import org.bukkit.block.Sign;
import org.bukkit.block.data.Rotatable;
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
 * Modulo LOBBY: hub tipico de Minecraft en un mundo void. Sala amplia y decorada
 * (columnas, grandes cristaleras al vacio, faro central con haz de luz) con portales a
 * otros servidores, y protecciones (aventura, invulnerable, sin morir/atacar/hambre/mobs).
 * Solo se activa con rol 'lobby'.
 */
public final class LobbyModule implements Listener {

    private static final String CHANNEL = "BungeeCord";
    private static final long COOLDOWN_MS = 3000L;

    // Sala flotando en el vacio; interior de 15x15 y 6 de alto.
    private static final int OX = 0;
    private static final int FLOOR_Y = 100;
    private static final int OZ = 0;
    private static final int R = 7;
    private static final int WALL_H = 6;

    // Posiciones (dx,dz) de los portales: primero a los lados (X), luego al fondo (Z).
    private static final int[][] OFFSETS = { { 5, 0 }, { -5, 0 }, { 0, -5 }, { 0, 5 } };

    public record PortalDef(String server, Material material, String label) {}

    private record PlacedPortal(String server, Location center) {}

    private final AetheriaPlugin plugin;
    private final List<PortalDef> portalDefs;
    private final ConversationManager convo;
    private final List<PlacedPortal> portals = new ArrayList<>();
    private final Map<UUID, Long> lastJump = new HashMap<>();

    private Location hubSpawn;

    public LobbyModule(AetheriaPlugin plugin, List<PortalDef> portalDefs, ConversationManager convo) {
        this.plugin = plugin;
        this.portalDefs = portalDefs;
        this.convo = convo;
    }

    public static List<PortalDef> readPortals(AetheriaPlugin plugin) {
        final List<PortalDef> defs = new ArrayList<>();
        for (Map<?, ?> raw : plugin.getConfig().getMapList("lobby.portals")) {
            final String server = String.valueOf(raw.get("server"));
            if (server == null || server.isBlank() || "null".equals(server)) {
                continue;
            }
            Material material = Material.matchMaterial(String.valueOf(raw.get("material")));
            if (material == null) {
                material = Material.EMERALD_BLOCK;
            }
            final Object label = raw.get("label");
            defs.add(new PortalDef(server, material,
                    label != null ? String.valueOf(label) : server.toUpperCase()));
        }
        if (defs.isEmpty()) {
            defs.add(new PortalDef("main", Material.EMERALD_BLOCK, "MAIN"));
        }
        return defs;
    }

    public void build() {
        final World world = Bukkit.getWorlds().get(0);
        configureWorld(world);
        buildRoom(world);
        buildCenterpiece(world);

        convo.clearGuides(world);   // evita guias duplicados al reiniciar
        for (int i = 0; i < portalDefs.size() && i < OFFSETS.length; i++) {
            placePortal(world, portalDefs.get(i), OFFSETS[i]);
        }

        // Aparece junto a la pared del fondo, mirando hacia el interior.
        this.hubSpawn = new Location(world, OX + 0.5, FLOOR_Y + 1, OZ + 6.5, 180f, 0f);
        world.setSpawnLocation(hubSpawn);
        plugin.getLogger().info("Lobby: sala construida (" + (2 * R + 1) + "x" + (2 * R + 1)
                + ", " + portals.size() + " portales).");
    }

    private void buildRoom(World world) {
        for (int dx = -R; dx <= R; dx++) {
            for (int dz = -R; dz <= R; dz++) {
                final boolean edge = Math.abs(dx) == R || Math.abs(dz) == R;
                final boolean corner = Math.abs(dx) == R && Math.abs(dz) == R;

                setBlock(world, dx, 0, dz, Material.SMOOTH_QUARTZ);            // suelo
                setBlock(world, dx, WALL_H + 1, dz, Material.SMOOTH_QUARTZ);   // techo

                for (int dy = 1; dy <= WALL_H; dy++) {
                    if (corner) {
                        setBlock(world, dx, dy, dz, Material.QUARTZ_PILLAR);   // columnas
                    } else if (edge) {
                        final boolean band = (dy == 1 || dy == WALL_H);
                        setBlock(world, dx, dy, dz, band ? Material.QUARTZ_BLOCK : Material.GLASS);
                    } else {
                        setBlock(world, dx, dy, dz, Material.AIR);             // interior vacio
                    }
                }
            }
        }
        // Rejilla de sea lanterns en el techo.
        for (int dx = -R + 1; dx <= R - 1; dx += 3) {
            for (int dz = -R + 1; dz <= R - 1; dz += 3) {
                setBlock(world, dx, WALL_H + 1, dz, Material.SEA_LANTERN);
                setBlock(world, dx, WALL_H, dz, Material.END_ROD);            // "lampara" colgante
            }
        }
    }

    private void buildCenterpiece(World world) {
        // Base 3x3 de oro para activar el faro (haz de luz visible en el vacio).
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                setBlock(world, dx, 0, dz, Material.GOLD_BLOCK);
            }
        }
        setBlock(world, 0, 1, 0, Material.BEACON);
        // Hueco de cristal en el techo para que el haz escape al vacio.
        setBlock(world, 0, WALL_H + 1, 0, Material.GLASS);
    }

    private void placePortal(World world, PortalDef def, int[] off) {
        final int cx = off[0];
        final int cz = off[1];
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                setBlock(world, cx + dx, 0, cz + dz, def.material());
            }
        }
        setBlock(world, cx, 0, cz, Material.SEA_LANTERN);         // centro luminoso

        // Cartel entre el centro y el portal, MIRANDO al jugador que llega.
        final int sx = cx - Integer.signum(cx) * 2;
        final int sz = cz - Integer.signum(cz) * 2;
        final BlockFace facing = towardCenter(cx, cz);
        placeSign(world.getBlockAt(OX + sx, FLOOR_Y + 1, OZ + sz), facing,
                "== " + def.label() + " ==", "Pisa el portal", "para viajar", "a " + def.label());

        portals.add(new PlacedPortal(def.server(),
                new Location(world, OX + cx + 0.5, FLOOR_Y + 1, OZ + cz + 0.5)));
        // Los portales ya no llevan guia propio: el lobby tiene UN solo conserje que ronda
        // la sala (LobbyGuideModule).
    }

    /** Centro interior del lobby, a la altura del suelo (para el conserje). */
    public Location interiorCenter() {
        return new Location(Bukkit.getWorlds().get(0), OX + 0.5, FLOOR_Y + 1, OZ + 0.5);
    }

    private static BlockFace towardCenter(int cx, int cz) {
        if (Math.abs(cx) >= Math.abs(cz)) {
            return cx > 0 ? BlockFace.WEST : BlockFace.EAST;
        }
        return cz > 0 ? BlockFace.NORTH : BlockFace.SOUTH;
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
        world.setTime(6000);
    }

    private void setBlock(World world, int dx, int dy, int dz, Material material) {
        world.getBlockAt(OX + dx, FLOOR_Y + dy, OZ + dz).setType(material, false);
    }

    private void placeSign(Block block, BlockFace facing, String l0, String l1, String l2, String l3) {
        block.setType(Material.OAK_SIGN);
        if (block.getBlockData() instanceof Rotatable rot) {
            rot.setRotation(facing);
            block.setBlockData(rot);
        }
        if (block.getState() instanceof Sign sign) {
            final var front = sign.getSide(Side.FRONT);
            front.line(0, Component.text(l0));
            front.line(1, Component.text(l1));
            front.line(2, Component.text(l2));
            front.line(3, Component.text(l3));
            sign.update(true);
        }
    }

    // ---------------- Bienvenida y portales ----------------

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        final Player player = event.getPlayer();
        player.setGameMode(GameMode.ADVENTURE);
        player.setInvulnerable(true);
        player.setFoodLevel(20);
        player.setSaturation(20f);
        player.setHealth(20.0);
        if (hubSpawn != null) {
            player.teleport(hubSpawn);
        }
        player.sendMessage("§b§lBienvenido a Aetheria");
        player.sendMessage("§7Una civilizacion viva gobernada por IA.");
        player.sendMessage("§fPisa un §aportal§f para viajar. Tambien: §f/server <mundo>§7.");
        player.sendMessage("§7Habla con la IA: §f/aetheria ask <mensaje>");
    }

    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        if (event.getTo() == null || portals.isEmpty()) {
            return;
        }
        if (event.getFrom().getBlockX() == event.getTo().getBlockX()
                && event.getFrom().getBlockZ() == event.getTo().getBlockZ()) {
            return;
        }
        final Player player = event.getPlayer();
        for (PlacedPortal portal : portals) {
            if (event.getTo().distanceSquared(portal.center()) <= 2.25) {
                final long now = System.currentTimeMillis();
                final Long last = lastJump.get(player.getUniqueId());
                if (last != null && now - last < COOLDOWN_MS) {
                    return;
                }
                lastJump.put(player.getUniqueId(), now);
                sendToServer(player, portal.server());
                return;
            }
        }
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
            event.setCancelled(true);
            if (event.getCause() == EntityDamageEvent.DamageCause.VOID && hubSpawn != null) {
                player.teleport(hubSpawn);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onAttack(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player) {
            event.setCancelled(true);
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
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent event) {
        if (event.getSpawnReason() != CreatureSpawnEvent.SpawnReason.CUSTOM) {
            event.setCancelled(true);
        }
    }
}
