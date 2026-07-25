package com.aetheria.plugin;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Cliente HTTP hacia el API Gateway. Es la UNICA via de la que dispone el plugin para
 * hablar con el backend: nunca contacta con el LLM ni con la base de datos directamente.
 */
public final class GatewayClient {

    private final AetheriaPlugin plugin;
    private final String baseUrl;
    private final String token;
    private final HttpClient http;
    private final Gson gson = new Gson();

    public GatewayClient(AetheriaPlugin plugin, String baseUrl, String token) {
        this.plugin = plugin;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.token = token;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    private CompletableFuture<JsonObject> post(String path, JsonObject body) {
        final HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + token)
                .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(body)))
                .build();

        return http.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    if (response.statusCode() / 100 != 2) {
                        throw new RuntimeException(
                                "Gateway HTTP " + response.statusCode() + ": " + response.body());
                    }
                    return JsonParser.parseString(response.body()).getAsJsonObject();
                });
    }

    /** Envia un mensaje conversacional a un NPC. Devuelve {reply, level}. */
    public CompletableFuture<JsonObject> conversation(String npcId, String playerId, String message) {
        final JsonObject body = new JsonObject();
        body.addProperty("npc_id", npcId);
        body.addProperty("player_id", playerId);
        body.addProperty("message", message);
        body.addProperty("world", "main");
        return post("/v1/conversation", body);
    }

    /** Solicita un plan para un actor. La respuesta ya viene validada (approved|rejected). */
    public CompletableFuture<JsonObject> plan(String actorType, String actorId, String goal) {
        final JsonObject actor = new JsonObject();
        actor.addProperty("type", actorType);
        actor.addProperty("id", actorId);

        final JsonObject body = new JsonObject();
        body.add("actor", actor);
        body.addProperty("goal", goal);
        body.addProperty("world", "main");
        return post("/v1/plans", body);
    }
}
