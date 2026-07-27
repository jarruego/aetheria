package com.aetheria.plugin;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandlers;
import java.util.ArrayList;
import java.util.List;

import org.bukkit.plugin.Plugin;

import com.google.gson.JsonParser;

/**
 * Cache de SKINS para las skins humanas de los NPC. Baja de Mojang (una vez, en segundo plano) la
 * textura {value, signature} de un pequeno SET DE ARRANQUE por sexo y la guarda. El listener de
 * packetevents la aplica al disfrazar cada aldeano de jugador. Si Mojang falla, el disfraz sigue
 * (skin por defecto Steve/Alex segun UUID). Sustituible por skins de OFICIO cambiando las listas.
 */
public final class SkinCache {

    // Cuentas publicas de arranque (skins libres). MHF_Steve/MHF_Alex son las clasicas.
    private static final String[] MALE = {"MHF_Steve", "Notch", "Jeb_", "Dinnerbone"};
    private static final String[] FEMALE = {"MHF_Alex", "Alex"};

    private final List<String[]> male = new ArrayList<>();     // cada uno: {value, signature}
    private final List<String[]> female = new ArrayList<>();

    /** Baja las skins en segundo plano (no bloquea el arranque del servidor). */
    public void loadAsync(Plugin plugin) {
        final Thread t = new Thread(() -> {
            for (final String u : MALE) {
                final String[] s = fetch(u);
                if (s != null) {
                    synchronized (male) {
                        male.add(s);
                    }
                }
            }
            for (final String u : FEMALE) {
                final String[] s = fetch(u);
                if (s != null) {
                    synchronized (female) {
                        female.add(s);
                    }
                }
            }
            plugin.getLogger().info("[Aetheria] SkinCache: " + skinCount(male) + " skins masc., "
                    + skinCount(female) + " fem.");
        }, "aetheria-skins");
        t.setDaemon(true);
        t.start();
    }

    private int skinCount(List<String[]> list) {
        synchronized (list) {
            return list.size();
        }
    }

    /** Skin {value, signature} para ese sexo (estable por clave), o null si aun no hay. */
    public String[] skinFor(String gender, String key) {
        final List<String[]> pool = "f".equalsIgnoreCase(gender) ? female : male;
        synchronized (pool) {
            if (pool.isEmpty()) {
                return null;
            }
            return pool.get(Math.floorMod(key.hashCode(), pool.size()));
        }
    }

    private static String[] fetch(String username) {
        try {
            final HttpClient http = HttpClient.newHttpClient();
            final var r1 = http.send(HttpRequest.newBuilder(
                    URI.create("https://api.mojang.com/users/profiles/minecraft/" + username)).build(),
                    BodyHandlers.ofString());
            if (r1.statusCode() != 200) {
                return null;
            }
            final String id = JsonParser.parseString(r1.body()).getAsJsonObject().get("id").getAsString();
            final var r2 = http.send(HttpRequest.newBuilder(URI.create(
                    "https://sessionserver.mojang.com/session/minecraft/profile/" + id
                            + "?unsigned=false")).build(), BodyHandlers.ofString());
            if (r2.statusCode() != 200) {
                return null;
            }
            final var props = JsonParser.parseString(r2.body()).getAsJsonObject()
                    .getAsJsonArray("properties").get(0).getAsJsonObject();
            return new String[] {props.get("value").getAsString(), props.get("signature").getAsString()};
        } catch (Exception e) {
            return null;
        }
    }
}
