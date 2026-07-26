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
import org.bukkit.entity.Entity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
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
    private static final int SAFE_RADIUS = 2;   // 2 -> zona segura de 5x5 alrededor del portal

    private final AetheriaPlugin plugin;
    private final String targetServer;
    private final ConversationManager convo;
    private final Map<UUID, Long> lastJump = new HashMap<>();

    private Location portalCenter;
    private Location safeCenter;   // centro de la zona segura (coordenadas de bloque)

    public ReturnPortalModule(AetheriaPlugin plugin, String targetServer, ConversationManager convo) {
        this.plugin = plugin;
        this.targetServer = targetServer;
        this.convo = convo;
    }

    /** Construye el portal de vuelta a unos bloques del spawn del mundo. */
    public void build() {
        final World world = Bukkit.getWorlds().get(0);
        final Location spawn = world.getSpawnLocation();
        final int cx = spawn.getBlockX();
        final int cz = spawn.getBlockZ() + 3;      // separado del spawn para no activarlo sin querer
        final int baseY = spawn.getBlockY() - 1;   // nivel del suelo

        convo.clearGuides(world);                  // evita guias duplicados al reiniciar
        this.safeCenter = new Location(world, cx, baseY, cz);

        // Suelo decorado de 5x5 (tema geoda de amatista): nucleo de amatista con centro
        // luminoso y borde de calcita/basalto. Bien iluminado = zona sin monstruos.
        for (int dx = -SAFE_RADIUS; dx <= SAFE_RADIUS; dx++) {
            for (int dz = -SAFE_RADIUS; dz <= SAFE_RADIUS; dz++) {
                final int ring = Math.max(Math.abs(dx), Math.abs(dz));
                final Material floor = switch (ring) {
                    case 0 -> Material.GLOWSTONE;        // centro: se pisa para viajar
                    case 1 -> Material.AMETHYST_BLOCK;   // nucleo del portal
                    default -> Math.floorMod(dx + dz, 2) == 0 ? Material.CALCITE : Material.SMOOTH_BASALT;
                };
                world.getBlockAt(cx + dx, baseY, cz + dz).setType(floor, false);
                for (int dy = 1; dy <= 3; dy++) {
                    world.getBlockAt(cx + dx, baseY + dy, cz + dz).setType(Material.AIR, false);
                }
            }
        }

        // Cuatro pilares de amatista en las esquinas, coronados con lampara marina (luz).
        for (final int[] c : new int[][] {{-2, -2}, {-2, 2}, {2, -2}, {2, 2}}) {
            world.getBlockAt(cx + c[0], baseY + 1, cz + c[1]).setType(Material.AMETHYST_BLOCK, false);
            world.getBlockAt(cx + c[0], baseY + 2, cz + c[1]).setType(Material.AMETHYST_BLOCK, false);
            world.getBlockAt(cx + c[0], baseY + 3, cz + c[1]).setType(Material.SEA_LANTERN, false);
        }

        // Cartel mirando al jugador (que llega desde el spawn, a menor Z).
        placeSign(world.getBlockAt(cx, baseY + 1, cz - 1), BlockFace.NORTH,
                "== LOBBY ==", "Pisa aqui", "para volver", "al lobby");

        this.portalCenter = new Location(world, cx + 0.5, baseY + 1, cz + 0.5);

        // Barrido periodico: mantiene la zona libre de monstruos (los que ya haya y los que
        // logren aparecer pese al bloqueo de spawn). Cada 2 s, coste minimo (area pequena).
        sweepMonsters();
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::sweepMonsters, 40L, 40L);

        // Guia conversable junto al portal de vuelta.
        convo.spawnGuide(new Location(world, cx + 2 + 0.5, baseY + 1, cz + 0.5, 90f, 0f),
                "guia-vuelta", "§bGuia del Lobby");

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

    /** Impide que aparezcan monstruos dentro de la zona segura del portal. */
    @EventHandler
    public void onSafeSpawn(CreatureSpawnEvent event) {
        if (event.getEntity() instanceof Monster && inSafeZone(event.getLocation())) {
            event.setCancelled(true);
        }
    }

    /** Anula el dano que un monstruo (cuerpo a cuerpo o flecha) haga a un jugador en la zona. */
    @EventHandler(ignoreCancelled = true)
    public void onSafeDamage(EntityDamageByEntityEvent event) {
        if (event.getEntity() instanceof Player && inSafeZone(event.getEntity().getLocation())
                && isHostile(event.getDamager())) {
            event.setCancelled(true);
        }
    }

    /** Elimina los monstruos que haya dentro de la zona segura del portal. */
    private void sweepMonsters() {
        if (portalCenter == null || portalCenter.getWorld() == null) {
            return;
        }
        portalCenter.getWorld()
                .getNearbyEntities(portalCenter, SAFE_RADIUS + 0.5, 5, SAFE_RADIUS + 0.5).stream()
                .filter(e -> e instanceof Monster)
                .forEach(Entity::remove);
    }

    private static boolean isHostile(Entity damager) {
        if (damager instanceof Monster) {
            return true;
        }
        return damager instanceof Projectile proj && proj.getShooter() instanceof Monster;
    }

    /** True si la ubicacion cae dentro del 5x5 (y una banda vertical) del portal. */
    private boolean inSafeZone(Location loc) {
        if (safeCenter == null || loc.getWorld() == null || !loc.getWorld().equals(safeCenter.getWorld())) {
            return false;
        }
        return Math.abs(loc.getBlockX() - safeCenter.getBlockX()) <= SAFE_RADIUS
                && Math.abs(loc.getBlockZ() - safeCenter.getBlockZ()) <= SAFE_RADIUS
                && Math.abs(loc.getBlockY() - safeCenter.getBlockY()) <= 4;
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
