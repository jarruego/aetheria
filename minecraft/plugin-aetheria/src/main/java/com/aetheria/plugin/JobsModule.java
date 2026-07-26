package com.aetheria.plugin;

import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Material;
import org.bukkit.block.data.Ageable;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import net.kyori.adventure.text.Component;

/**
 * Trabajos: el jugador gana AET por TAREAS reales (minar, cosechar, cazar). Da la sensacion
 * de que hacer cosas merece la pena y alimenta la economia.
 *
 * <p>Las recompensas se acumulan en memoria y se pagan por LOTES cada pocos segundos (una
 * sola llamada por jugador), para no golpear al backend en cada bloque. El pago real lo hace
 * el world-state (dinero desde la cuenta del sistema); aqui solo se decide cuanto.
 */
public final class JobsModule implements Listener {

    private static final Map<Material, Double> BLOCK_REWARD = new EnumMap<>(Material.class);
    private static final Map<EntityType, Double> MOB_REWARD = new EnumMap<>(EntityType.class);

    static {
        // Cosecha (solo cultivos maduros, ver onBreak).
        for (final Material m : new Material[] {Material.WHEAT, Material.CARROTS, Material.POTATOES,
                Material.BEETROOTS, Material.NETHER_WART}) {
            BLOCK_REWARD.put(m, 0.6);
        }
        // Mineria.
        put(0.8, Material.COAL_ORE, Material.DEEPSLATE_COAL_ORE, Material.COPPER_ORE,
                Material.DEEPSLATE_COPPER_ORE, Material.NETHER_QUARTZ_ORE);
        put(1.0, Material.REDSTONE_ORE, Material.DEEPSLATE_REDSTONE_ORE, Material.LAPIS_ORE,
                Material.DEEPSLATE_LAPIS_ORE);
        put(1.6, Material.IRON_ORE, Material.DEEPSLATE_IRON_ORE);
        put(2.5, Material.GOLD_ORE, Material.DEEPSLATE_GOLD_ORE, Material.NETHER_GOLD_ORE);
        put(6.0, Material.DIAMOND_ORE, Material.DEEPSLATE_DIAMOND_ORE);
        put(8.0, Material.EMERALD_ORE, Material.DEEPSLATE_EMERALD_ORE);
        put(12.0, Material.ANCIENT_DEBRIS);
        // Tala.
        put(0.3, Material.OAK_LOG, Material.SPRUCE_LOG, Material.BIRCH_LOG, Material.JUNGLE_LOG,
                Material.ACACIA_LOG, Material.DARK_OAK_LOG, Material.MANGROVE_LOG, Material.CHERRY_LOG);

        // Caza de hostiles.
        for (final EntityType t : new EntityType[] {EntityType.ZOMBIE, EntityType.SKELETON,
                EntityType.SPIDER, EntityType.CREEPER, EntityType.HUSK, EntityType.STRAY,
                EntityType.DROWNED, EntityType.ZOMBIE_VILLAGER}) {
            MOB_REWARD.put(t, 1.5);
        }
        MOB_REWARD.put(EntityType.ENDERMAN, 3.0);
        MOB_REWARD.put(EntityType.WITCH, 4.0);
        MOB_REWARD.put(EntityType.BLAZE, 4.0);
        MOB_REWARD.put(EntityType.PIGLIN_BRUTE, 6.0);
    }

    private static void put(double value, Material... mats) {
        for (final Material m : mats) {
            BLOCK_REWARD.put(m, value);
        }
    }

    private final AetheriaPlugin plugin;
    private final GatewayClient gateway;
    private final Map<UUID, Double> pending = new ConcurrentHashMap<>();

    public JobsModule(AetheriaPlugin plugin, GatewayClient gateway) {
        this.plugin = plugin;
        this.gateway = gateway;
    }

    /** Arranca el pago periodico por lotes (cada 20 s). */
    public void start() {
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::flushAll, 400L, 400L);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        final Material type = event.getBlock().getType();
        final Double base = BLOCK_REWARD.get(type);
        if (base == null) {
            return;
        }
        // Los cultivos solo pagan si estan MADUROS (evita el farmeo de plantar y romper).
        if (event.getBlock().getBlockData() instanceof Ageable age && age.getAge() < age.getMaximumAge()) {
            return;
        }
        award(event.getPlayer(), base, jobName(type));
    }

    @EventHandler(ignoreCancelled = true)
    public void onKill(EntityDeathEvent event) {
        final Player killer = event.getEntity().getKiller();
        if (killer == null || !(event.getEntity() instanceof Monster)) {
            return;
        }
        final Double base = MOB_REWARD.getOrDefault(event.getEntityType(), 1.0);
        award(killer, base, "caza");
    }

    private void award(Player player, double amount, String job) {
        pending.merge(player.getUniqueId(), amount, Double::sum);
        player.sendActionBar(Component.text("§a+" + trim(amount) + " AET §7(" + job + ")"));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        flush(event.getPlayer().getUniqueId());
    }

    private void flushAll() {
        for (final UUID id : pending.keySet()) {
            flush(id);
        }
    }

    private void flush(UUID id) {
        final Double amount = pending.remove(id);
        if (amount == null || amount < 0.01) {
            return;
        }
        final double rounded = Math.round(amount * 100.0) / 100.0;
        gateway.reward(id.toString(), rounded, "trabajo").whenComplete((json, err) -> {
            if (err != null || (json != null && !json.get("ok").getAsBoolean())) {
                pending.merge(id, rounded, Double::sum);   // reintenta en el siguiente lote
            }
        });
    }

    private static String jobName(Material m) {
        final String n = m.name();
        if (n.endsWith("_ORE") || n.equals("ANCIENT_DEBRIS")) {
            return "mineria";
        }
        if (n.endsWith("_LOG")) {
            return "tala";
        }
        return "cosecha";
    }

    private static String trim(double v) {
        return String.valueOf(Math.round(v * 100.0) / 100.0);
    }
}
