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
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerJoinEvent;
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
    // Zona segura de 10x10 alrededor del portal (10 casillas: de -5 a +4 desde el centro).
    private static final int SAFE_MIN = -5;
    private static final int SAFE_MAX = 4;

    private final AetheriaPlugin plugin;
    private final String targetServer;
    private final ConversationManager convo;
    private final Map<UUID, Long> lastJump = new HashMap<>();

    private Location portalCenter;
    private Location safeCenter;   // centro de la zona segura (coordenadas de bloque)
    private Location arrival;      // donde dejar al jugador al llegar (lejos del portal)

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

        // Suelo decorado de todo el 10x10 (tema geoda de amatista): nucleo de amatista con
        // centro luminoso, marco exterior de amatista y relleno de calcita/basalto en tablero.
        for (int dx = SAFE_MIN; dx <= SAFE_MAX; dx++) {
            for (int dz = SAFE_MIN; dz <= SAFE_MAX; dz++) {
                final boolean center = dx == 0 && dz == 0;
                final boolean core = Math.abs(dx) <= 1 && Math.abs(dz) <= 1;
                final boolean edge = dx == SAFE_MIN || dx == SAFE_MAX || dz == SAFE_MIN || dz == SAFE_MAX;
                final Material floor;
                if (center) {
                    floor = Material.GLOWSTONE;          // se pisa para viajar
                } else if (core || edge) {
                    floor = Material.AMETHYST_BLOCK;      // nucleo del portal + marco exterior
                } else {
                    floor = Math.floorMod(dx + dz, 2) == 0 ? Material.CALCITE : Material.SMOOTH_BASALT;
                }
                world.getBlockAt(cx + dx, baseY, cz + dz).setType(floor, false);
                // Despeja por encima (tala arboles/vegetacion) y rellena por debajo si el suelo
                // queda hueco: el portal se apoya A RAS, nunca flotando sobre un pedestal.
                for (int dy = 1; dy <= 5; dy++) {
                    world.getBlockAt(cx + dx, baseY + dy, cz + dz).setType(Material.AIR, false);
                }
                for (int dy = 1; dy <= 8; dy++) {
                    final Block u = world.getBlockAt(cx + dx, baseY - dy, cz + dz);
                    if (u.getType().isAir() || u.isLiquid()) {
                        u.setType(Material.DIRT, false);
                    } else {
                        break;
                    }
                }
            }
        }

        // Cuatro faroles (lampara marina) empotrados en el suelo iluminan todo el 10x10.
        for (final int[] p : new int[][] {{-3, -3}, {-3, 3}, {3, -3}, {3, 3}}) {
            world.getBlockAt(cx + p[0], baseY, cz + p[1]).setType(Material.SEA_LANTERN, false);
        }

        // Pilares de amatista en las cuatro esquinas, coronados con lampara marina.
        for (final int[] c : new int[][] {{SAFE_MIN, SAFE_MIN}, {SAFE_MIN, SAFE_MAX},
                {SAFE_MAX, SAFE_MIN}, {SAFE_MAX, SAFE_MAX}}) {
            world.getBlockAt(cx + c[0], baseY + 1, cz + c[1]).setType(Material.AMETHYST_BLOCK, false);
            world.getBlockAt(cx + c[0], baseY + 2, cz + c[1]).setType(Material.AMETHYST_BLOCK, false);
            world.getBlockAt(cx + c[0], baseY + 3, cz + c[1]).setType(Material.SEA_LANTERN, false);
        }

        // Cartel mirando al jugador (que llega desde el norte del portal).
        placeSign(world.getBlockAt(cx, baseY + 1, cz - 1), BlockFace.NORTH,
                "== LOBBY ==", "Pisa aqui", "para volver", "al lobby");

        this.portalCenter = new Location(world, cx + 0.5, baseY + 1, cz + 0.5);

        // Punto de llegada: al OTRO lado del portal (lado del pueblo), 4 casillas por delante y
        // MIRANDO al pueblo (portal a su espalda), para no volver a entrar al moverse. NO se
        // cambia el world spawn (eso haria "derivar" el portal en cada reinicio): se
        // teletransporta al jugador al llegar (onJoin) si aparece pegado al portal.
        this.arrival = new Location(world, cx + 0.5, baseY + 1, cz + 4 + 0.5, 0f, 0f);

        // Barrido periodico: mantiene la zona libre de monstruos (los que ya haya y los que
        // logren aparecer pese al bloqueo de spawn). Cada 2 s, coste minimo (area pequena).
        sweepMonsters();
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::sweepMonsters, 40L, 40L);

        // Guia conversable junto al portal de vuelta.
        convo.spawnGuide(new Location(world, cx + 2 + 0.5, baseY + 1, cz + 0.5, 90f, 0f),
                "guia-vuelta", "§bGuia del Lobby");

        plugin.getLogger().info("Portal de vuelta al lobby construido cerca del spawn (zona 10x10).");
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

    // --- Indestructibilidad: la zona segura y el portal no se pueden alterar ---

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (inSafeZone(event.getBlock().getLocation())) {
            event.setCancelled(true);
            event.getPlayer().sendMessage("§cEsto es zona protegida del portal: no se puede modificar.");
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (inSafeZone(event.getBlock().getLocation())) {
            event.setCancelled(true);
            event.getPlayer().sendMessage("§cEsto es zona protegida del portal: no se puede construir aqui.");
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBucket(PlayerBucketEmptyEvent event) {
        if (inSafeZone(event.getBlock().getLocation())) {
            event.setCancelled(true);
            event.getPlayer().sendMessage("§cEsto es zona protegida del portal.");
        }
    }

    /** Protege la zona de explosiones (TNT, creepers): no se destruyen sus bloques. */
    @EventHandler(ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        event.blockList().removeIf(b -> inSafeZone(b.getLocation()));
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        event.blockList().removeIf(b -> inSafeZone(b.getLocation()));
    }

    /** Elimina los monstruos que haya dentro de la zona segura del portal. */
    private void sweepMonsters() {
        if (portalCenter == null || portalCenter.getWorld() == null) {
            return;
        }
        // Consulta una caja algo mayor y filtra por la zona exacta (10x10).
        portalCenter.getWorld().getNearbyEntities(portalCenter, 8, 6, 8).stream()
                .filter(e -> e instanceof Monster)
                .filter(e -> inSafeZone(e.getLocation()))
                .forEach(Entity::remove);
    }

    private static boolean isHostile(Entity damager) {
        if (damager instanceof Monster) {
            return true;
        }
        return damager instanceof Projectile proj && proj.getShooter() instanceof Monster;
    }

    /** True si la ubicacion cae dentro del 10x10 (y una banda vertical) del portal. */
    private boolean inSafeZone(Location loc) {
        if (safeCenter == null || loc.getWorld() == null || !loc.getWorld().equals(safeCenter.getWorld())) {
            return false;
        }
        final int dx = loc.getBlockX() - safeCenter.getBlockX();
        final int dz = loc.getBlockZ() - safeCenter.getBlockZ();
        return dx >= SAFE_MIN && dx <= SAFE_MAX && dz >= SAFE_MIN && dz <= SAFE_MAX
                && Math.abs(loc.getBlockY() - safeCenter.getBlockY()) <= 4;
    }

    /** Al llegar al mundo, si el jugador aparece pegado al portal, se le aparta hacia el pueblo. */
    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (arrival == null) {
            return;
        }
        final Player player = event.getPlayer();
        if (player.getWorld().equals(arrival.getWorld())
                && player.getLocation().distanceSquared(portalCenter) < 100) {   // ~10 bloques
            // 1 tick despues: en un cambio de servidor el teleport inmediato puede ignorarse.
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> player.teleport(arrival), 2L);
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
