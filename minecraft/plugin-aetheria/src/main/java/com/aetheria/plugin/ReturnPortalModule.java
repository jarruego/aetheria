package com.aetheria.plugin;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Bukkit;
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
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;

import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;

import net.kyori.adventure.text.Component;

/**
 * Portal de VUELTA al lobby, para los mundos de juego (main, creative...).
 *
 * <p>Construye un pequeno portal cerca del spawn del mundo; al pisarlo, envia al jugador
 * al servidor lobby via el mensaje BungeeCord "Connect". Se activa en cualquier servidor
 * con rol != lobby (ver AetheriaPlugin).
 */
public final class ReturnPortalModule implements Listener {

    private static final String CHANNEL = "BungeeCord";
    private static final long COOLDOWN_MS = 3000L;

    private final AetheriaPlugin plugin;
    private final String targetServer;
    private final Map<UUID, Long> lastJump = new HashMap<>();

    private Location portalCenter;

    public ReturnPortalModule(AetheriaPlugin plugin, String targetServer) {
        this.plugin = plugin;
        this.targetServer = targetServer;
    }

    /** Construye el portal de vuelta a unos bloques del spawn del mundo. */
    public void build() {
        final World world = Bukkit.getWorlds().get(0);
        final Location spawn = world.getSpawnLocation();
        final int cx = spawn.getBlockX();
        final int cz = spawn.getBlockZ() + 3;      // separado del spawn para no activarlo sin querer
        final int baseY = spawn.getBlockY() - 1;   // nivel del suelo

        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                world.getBlockAt(cx + dx, baseY, cz + dz).setType(Material.AMETHYST_BLOCK, false);
                for (int dy = 1; dy <= 3; dy++) {
                    world.getBlockAt(cx + dx, baseY + dy, cz + dz).setType(Material.AIR, false);
                }
            }
        }
        world.getBlockAt(cx, baseY, cz).setType(Material.GLOWSTONE, false);   // centro luminoso
        // Dos postes de amatista detras, como marco.
        world.getBlockAt(cx - 1, baseY + 1, cz + 1).setType(Material.AMETHYST_BLOCK, false);
        world.getBlockAt(cx - 1, baseY + 2, cz + 1).setType(Material.AMETHYST_BLOCK, false);
        world.getBlockAt(cx + 1, baseY + 1, cz + 1).setType(Material.AMETHYST_BLOCK, false);
        world.getBlockAt(cx + 1, baseY + 2, cz + 1).setType(Material.AMETHYST_BLOCK, false);

        // Cartel mirando al jugador (que llega desde el spawn, a menor Z).
        placeSign(world.getBlockAt(cx, baseY + 1, cz - 1), BlockFace.NORTH,
                "== LOBBY ==", "Pisa aqui", "para volver", "al lobby");

        this.portalCenter = new Location(world, cx + 0.5, baseY + 1, cz + 0.5);
        plugin.getLogger().info("Portal de vuelta al lobby construido cerca del spawn.");
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

    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        if (portalCenter == null || event.getTo() == null) {
            return;
        }
        if (event.getFrom().getBlockX() == event.getTo().getBlockX()
                && event.getFrom().getBlockZ() == event.getTo().getBlockZ()) {
            return;
        }
        if (event.getTo().distanceSquared(portalCenter) > 2.25) {
            return;
        }
        final Player player = event.getPlayer();
        final long now = System.currentTimeMillis();
        final Long last = lastJump.get(player.getUniqueId());
        if (last != null && now - last < COOLDOWN_MS) {
            return;
        }
        lastJump.put(player.getUniqueId(), now);
        player.sendMessage("§a[Aetheria] Volviendo al lobby...");
        final ByteArrayDataOutput out = ByteStreams.newDataOutput();
        out.writeUTF("Connect");
        out.writeUTF(targetServer);
        player.sendPluginMessage(plugin, CHANNEL, out.toByteArray());
    }
}
