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
        // HTTP/1.1 FORZADO, no es cosmetico.
        //
        // HttpClient de Java negocia HTTP/2 por defecto y, sobre HTTP sin cifrar, manda
        // una cabecera "Upgrade: h2c". Uvicorn (el servidor del gateway) no soporta ese
        // upgrade y DESCARTA EL CUERPO de la peticion: FastAPI recibe body=null y
        // responde 422, asi que toda llamada al gateway fallaba en silencio y los NPC
        // contestaban "(no puedo responder ahora)".
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5))
                .build();
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

    // --- Jugadores y casas (Fase 5: escritura a la DB) ---

    /** Registra/actualiza al jugador. */
    public CompletableFuture<JsonObject> upsertPlayer(String uuid, String username) {
        final JsonObject body = new JsonObject();
        body.addProperty("uuid", uuid);
        body.addProperty("username", username);
        return post("/v1/players", body);
    }

    /** Guarda la casa del jugador en este servidor. */
    public CompletableFuture<Void> setHome(String uuid, String username, String server, String world,
            double x, double y, double z, float yaw, float pitch) {
        final JsonObject body = new JsonObject();
        body.addProperty("uuid", uuid);
        body.addProperty("username", username);
        body.addProperty("server", server);
        body.addProperty("world", world);
        body.addProperty("x", x);
        body.addProperty("y", y);
        body.addProperty("z", z);
        body.addProperty("yaw", yaw);
        body.addProperty("pitch", pitch);
        final HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/v1/homes"))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + token)
                .PUT(HttpRequest.BodyPublishers.ofString(gson.toJson(body)))
                .build();
        return http.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(resp -> {
                    if (resp.statusCode() / 100 != 2) {
                        throw new RuntimeException("Gateway HTTP " + resp.statusCode() + ": " + resp.body());
                    }
                    return null;
                });
    }

    /** Devuelve la casa del jugador en este servidor, o null si no tiene. */
    public CompletableFuture<JsonObject> getHome(String uuid, String server) {
        final HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/v1/homes/" + uuid + "?server=" + server))
                .timeout(Duration.ofSeconds(20))
                .header("Authorization", "Bearer " + token)
                .GET()
                .build();
        return http.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(resp -> {
                    if (resp.statusCode() == 404) {
                        return null;
                    }
                    if (resp.statusCode() / 100 != 2) {
                        throw new RuntimeException("Gateway HTTP " + resp.statusCode() + ": " + resp.body());
                    }
                    return JsonParser.parseString(resp.body()).getAsJsonObject();
                });
    }

    // --- Economia (Fase 6) ---

    private CompletableFuture<JsonObject> getJson(String path) {
        final HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(20))
                .header("Authorization", "Bearer " + token)
                .GET()
                .build();
        return http.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(resp -> {
                    if (resp.statusCode() / 100 != 2) {
                        throw new RuntimeException("Gateway HTTP " + resp.statusCode() + ": " + resp.body());
                    }
                    return JsonParser.parseString(resp.body()).getAsJsonObject();
                });
    }

    /** Saldo del jugador. */
    public CompletableFuture<JsonObject> getBalance(String uuid) {
        return getJson("/v1/balance/" + uuid);
    }

    /** Recompensa a un jugador (trabajo/venta). No lanza en 4xx: devuelve {ok, ...}. */
    public CompletableFuture<JsonObject> reward(String uuid, double amount, String reason) {
        final JsonObject body = new JsonObject();
        body.addProperty("uuid", uuid);
        body.addProperty("amount", amount);
        body.addProperty("reason", reason);
        return sendCapturing("/v1/reward", gson.toJson(body));
    }

    /** Paga a otro jugador. Devuelve {ok:bool, error?:string} (no lanza por saldo insuficiente). */
    public CompletableFuture<JsonObject> pay(String fromUuid, String toUuid, double amount) {
        final JsonObject body = new JsonObject();
        body.addProperty("from_uuid", fromUuid);
        body.addProperty("to_uuid", toUuid);
        body.addProperty("amount", amount);
        return sendCapturing("/v1/pay", gson.toJson(body));
    }

    /**
     * Contrata un servicio inteligente de PAGO (Arquitecto IA, etc.). La IA valida el plan
     * y SOLO cobra si es aprobado. Devuelve el plan validado en {ok, data:{status,...}}.
     */
    public CompletableFuture<JsonObject> service(String playerUuid, String service, String description,
            String world) {
        final JsonObject body = new JsonObject();
        body.addProperty("player_uuid", playerUuid);
        body.addProperty("service", service);
        body.addProperty("description", description);
        body.addProperty("world", world);
        return sendCapturing("/v1/service", gson.toJson(body));
    }

    /** Cronica del mundo (Fase 8): sucesos autonomos recientes, del mas nuevo al mas viejo. */
    public CompletableFuture<com.google.gson.JsonArray> getWorldEvents(int limit) {
        final HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/v1/world-events?limit=" + limit))
                .timeout(Duration.ofSeconds(20))
                .header("Authorization", "Bearer " + token)
                .GET()
                .build();
        return http.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(resp -> {
                    if (resp.statusCode() / 100 != 2) {
                        throw new RuntimeException("Gateway HTTP " + resp.statusCode() + ": " + resp.body());
                    }
                    return JsonParser.parseString(resp.body()).getAsJsonArray();
                });
    }

    // --- Estructuras sociales: parcelas (Fase 9) ---

    /** Reclama una parcela (un chunk). Devuelve {ok, error?, data:{price}} (no lanza en 4xx). */
    public CompletableFuture<JsonObject> claimPlot(String ownerUuid, String ownerName, String world,
            int minX, int minZ, int maxX, int maxZ) {
        final JsonObject body = new JsonObject();
        body.addProperty("owner_uuid", ownerUuid);
        body.addProperty("owner_name", ownerName);
        body.addProperty("world", world);
        body.addProperty("min_x", minX);
        body.addProperty("min_z", minZ);
        body.addProperty("max_x", maxX);
        body.addProperty("max_z", maxZ);
        return sendCapturing("/v1/claims", gson.toJson(body));
    }

    /** Libera una parcela propia (por su esquina min). Devuelve {ok, error?}. */
    public CompletableFuture<JsonObject> unclaimPlot(String ownerUuid, String world, int minX, int minZ) {
        final JsonObject body = new JsonObject();
        body.addProperty("owner_uuid", ownerUuid);
        body.addProperty("world", world);
        body.addProperty("min_x", minX);
        body.addProperty("min_z", minZ);
        return sendCapturing("/v1/claims/unclaim", gson.toJson(body));
    }

    /** Todas las parcelas de un mundo (para la cache de proteccion del plugin). */
    public CompletableFuture<com.google.gson.JsonArray> getClaims(String world) {
        final HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/v1/claims?world=" + world))
                .timeout(Duration.ofSeconds(20))
                .header("Authorization", "Bearer " + token)
                .GET()
                .build();
        return http.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(resp -> {
                    if (resp.statusCode() / 100 != 2) {
                        throw new RuntimeException("Gateway HTTP " + resp.statusCode() + ": " + resp.body());
                    }
                    return JsonParser.parseString(resp.body()).getAsJsonArray();
                });
    }

    /** POST que NO lanza en 4xx: devuelve {ok:bool, error?:string} para mensajes limpios. */
    CompletableFuture<JsonObject> sendCapturing(String path, String jsonBody) {
        final HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + token)
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();
        return http.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(resp -> {
                    final JsonObject out = new JsonObject();
                    if (resp.statusCode() / 100 == 2) {
                        out.addProperty("ok", true);
                        try {
                            out.add("data", JsonParser.parseString(resp.body()).getAsJsonObject());
                        } catch (Exception ignored) {
                            // sin cuerpo util
                        }
                    } else {
                        out.addProperty("ok", false);
                        try {
                            out.addProperty("error",
                                    JsonParser.parseString(resp.body()).getAsJsonObject().get("detail").getAsString());
                        } catch (Exception e) {
                            out.addProperty("error", "error " + resp.statusCode());
                        }
                    }
                    return out;
                });
    }
}
