package com.aetheria.plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Merchant;
import org.bukkit.inventory.MerchantRecipe;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import net.kyori.adventure.text.Component;

/**
 * Ejecuta las acciones de un plan YA aprobado por el validador del backend, sobre
 * entidades y bloques reales.
 *
 * <p>Defensa en profundidad: el plugin mantiene su PROPIA lista blanca y valida cada
 * parametro (material real, blueprint conocido...). Cualquier cosa fuera de norma se
 * ignora y se registra: el mundo nunca ejecuta algo inesperado.
 */
public final class PlanExecutor {

    private static final Set<String> WHITELIST =
            Set.of("SAY", "MOVE_TO", "PLACE_BLUEPRINT", "GIVE_ITEM", "OPEN_TRADE");
    private static final int MAX_GIVE_AMOUNT = 64;

    private final AetheriaPlugin plugin;
    private final NpcManager npcs;

    public PlanExecutor(AetheriaPlugin plugin, NpcManager npcs) {
        this.plugin = plugin;
        this.npcs = npcs;
    }

    /** Debe llamarse en el hilo principal del servidor. */
    public void execute(Player player, String npcKey, JsonArray actions) {
        player.sendMessage("§7[Aetheria] plan aprobado con " + actions.size() + " accion(es).");

        for (JsonElement element : actions) {
            final JsonObject action = element.getAsJsonObject();
            final String type = action.get("type").getAsString();

            if (!WHITELIST.contains(type)) {
                plugin.getLogger().warning("Accion fuera de la lista blanca, ignorada: " + type);
                continue;
            }

            final JsonObject params =
                    action.has("params") ? action.getAsJsonObject("params") : new JsonObject();

            switch (type) {
                case "SAY" -> doSay(player, params);
                case "MOVE_TO" -> doMoveTo(player, npcKey, params);
                case "GIVE_ITEM" -> doGiveItem(player, params);
                case "PLACE_BLUEPRINT" -> doPlaceBlueprint(player, params);
                case "OPEN_TRADE" -> doOpenTrade(player, params);
                default -> plugin.getLogger().warning("Accion en lista blanca sin manejador: " + type);
            }
        }
    }

    private void doSay(Player player, JsonObject params) {
        final String txt = params.has("text") ? params.get("text").getAsString() : "";
        player.sendMessage("§a[NPC] §f" + txt);
    }

    private void doMoveTo(Player player, String npcKey, JsonObject params) {
        final String target = params.has("target") ? params.get("target").getAsString() : "player";
        if (!"player".equals(target)) {
            plugin.getLogger().warning("MOVE_TO destino no soportado: " + target);
            return;
        }
        final Entity entity = npcs.get(npcKey);
        if (entity instanceof Mob mob) {
            mob.getPathfinder().moveTo(player.getLocation());
            player.sendMessage("§a[NPC] voy hacia ti.");
        } else {
            player.sendMessage("§e[Aetheria] no hay NPC '" + npcKey
                    + "' en el mundo. Crealo con /aetheria npc spawn " + npcKey);
        }
    }

    private void doGiveItem(Player player, JsonObject params) {
        final String materialName = params.has("material") ? params.get("material").getAsString() : "";
        final int amount = clamp(params.has("amount") ? params.get("amount").getAsInt() : 1, 1, MAX_GIVE_AMOUNT);
        final Material material = Material.matchMaterial(materialName);
        if (material == null || !material.isItem()) {
            plugin.getLogger().warning("GIVE_ITEM material invalido, ignorado: " + materialName);
            player.sendMessage("§e[Aetheria] no puedo darte '" + materialName + "'.");
            return;
        }
        player.getInventory().addItem(new ItemStack(material, amount));
        player.sendMessage("§a[NPC] §ftoma " + amount + " x " + material.name().toLowerCase());
    }

    private void doPlaceBlueprint(Player player, JsonObject params) {
        final String name = params.has("blueprint") ? params.get("blueprint").getAsString() : "";
        // Anti-solape: no se construye encima de algo ya puesto (por otro o por la partida).
        final int[] region = Blueprint.buildRegion(player, name, 3);
        if (plugin.buildRegistry().overlaps(region)) {
            player.sendMessage("§e[Aetheria] ahi ya hay algo construido; no pongo nada encima.");
            return;
        }
        final int placed = Blueprint.place(player, name);
        if (placed < 0) {
            plugin.getLogger().warning("PLACE_BLUEPRINT desconocido, ignorado: " + name);
            player.sendMessage("§e[Aetheria] no conozco el plano '" + name + "'.");
            return;
        }
        plugin.buildRegistry().add(region);   // registrado: nadie lo pisara despues
        final String what = switch (name) {
            case "house" -> "tu casa";
            case "fountain" -> "una fuente";
            case "platform" -> "una plataforma";
            default -> "'" + name + "'";
        };
        player.sendMessage("§a[NPC] §fHe construido " + what + " aqui mismo, frente a ti ("
                + placed + " bloques).");
    }

    private void doOpenTrade(Player player, JsonObject params) {
        if (!params.has("offers") || !params.get("offers").isJsonArray()) {
            plugin.getLogger().warning("OPEN_TRADE sin ofertas, ignorado.");
            return;
        }
        final List<MerchantRecipe> recipes = new ArrayList<>();
        for (JsonElement el : params.getAsJsonArray("offers")) {
            final JsonObject offer = el.getAsJsonObject();
            final Material give = Material.matchMaterial(offer.get("give").getAsString());
            final Material forMat = Material.matchMaterial(offer.get("for").getAsString());
            if (give == null || forMat == null) {
                continue;
            }
            final int giveAmount = clamp(offer.has("amount") ? offer.get("amount").getAsInt() : 1, 1, 64);
            final int forAmount = clamp(offer.has("for_amount") ? offer.get("for_amount").getAsInt() : 1, 1, 64);
            final MerchantRecipe recipe = new MerchantRecipe(new ItemStack(give, giveAmount), Integer.MAX_VALUE);
            recipe.addIngredient(new ItemStack(forMat, forAmount));
            recipes.add(recipe);
        }
        if (recipes.isEmpty()) {
            player.sendMessage("§e[Aetheria] no tengo ofertas validas ahora mismo.");
            return;
        }
        final Merchant merchant = Bukkit.createMerchant(Component.text("Comercio de Aetheria"));
        merchant.setRecipes(recipes);
        player.openMerchant(merchant, true);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
