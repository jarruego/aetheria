package com.aetheria.plugin;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.block.sign.Side;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;

import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;

import net.kyori.adventure.text.Component;

/**
 * Modulo LOBBY: convierte el servidor en un hub pequeno con bienvenida, instrucciones y
 * un portal fisico que envia al jugador a otro servidor via Velocity.
 *
 * <p>Solo se activa cuando el plugin corre con rol 'lobby' (AETHERIA_ROLE=lobby). El
 * salto de servidor usa el mensaje BungeeCord "Connect" que Velocity entiende.
 */
public final class LobbyModule implements Listener {

    private static final String CHANNEL = "BungeeCord";
    private static final long COOLDOWN_MS = 3000L;

    private final AetheriaPlugin plugin;
    private final String targetServer;
    private final Map<UUID, Long> lastJump = new HashMap<>();

    private Location hubSpawn;      // donde aparece el jugador
    private Location portalCenter;  // centro del portal (pisar aqui = saltar)

    public LobbyModule(AetheriaPlugin plugin, String targetServer) {
        this.plugin = plugin;
        this.targetServer = targetServer;
    }

    /** Construye el hub en el mundo principal del lobby y fija el spawn. */
    public void build() {
        final World world = Bukkit.getWorlds().get(0);
        final Location spawn = world.getSpawnLocation();
        final int ox = spawn.getBlockX();
        final int oy = spawn.getBlockY();
        final int oz = spawn.getBlockZ();

        // Plataforma 7x7 de cuarzo (suelo en oy-1) y aire despejado encima.
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                world.getBlockAt(ox + dx, oy - 1, oz + dz).setType(Material.QUARTZ_BLOCK);
                for (int dy = 0; dy <= 2; dy++) {
                    world.getBlockAt(ox + dx, oy + dy, oz + dz).setType(Material.AIR);
                }
            }
        }

        // Portal: pad 3x3 de esmeralda a +6 en Z, con centro luminoso.
        final int pz = oz + 6;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                world.getBlockAt(ox + dx, oy - 1, pz + dz).setType(Material.EMERALD_BLOCK);
                for (int dy = 0; dy <= 2; dy++) {
                    world.getBlockAt(ox + dx, oy + dy, pz + dz).setType(Material.AIR);
                }
            }
        }
        world.getBlockAt(ox, oy - 1, pz).setType(Material.SEA_LANTERN);

        // Cartel indicativo delante del portal.
        placeSign(world.getBlockAt(ox, oy, oz + 4),
                "== AETHERIA ==", "Pisa el portal", "para ir a", targetServer.toUpperCase());

        this.hubSpawn = new Location(world, ox + 0.5, oy, oz + 0.5);
        this.portalCenter = new Location(world, ox + 0.5, oy, pz + 0.5);
        world.setSpawnLocation(ox, oy, oz);

        plugin.getLogger().info("Lobby: hub construido; portal -> " + targetServer);
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

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        final Player player = event.getPlayer();
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
        // Solo comprobamos al cambiar de bloque (barato).
        if (event.getFrom().getBlockX() == event.getTo().getBlockX()
                && event.getFrom().getBlockZ() == event.getTo().getBlockZ()) {
            return;
        }
        final Player player = event.getPlayer();
        if (event.getTo().distanceSquared(portalCenter) > 2.25) {  // radio ~1.5 bloques
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
}
