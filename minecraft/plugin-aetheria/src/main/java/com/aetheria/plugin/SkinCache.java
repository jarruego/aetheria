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
    // Skin POR OFICIO (clave = profWord, p.ej. "granjero"): {value, signature}. Tiene prioridad
    // sobre la de sexo. Se rellena con las skins que va pasando el dueno (player_head/NameMC).
    private final java.util.Map<String, String[]> byProf = new java.util.concurrent.ConcurrentHashMap<>();

    /** Registra la skin de un oficio (value base64; signature puede ir vacia = sin firmar). */
    public void putProfSkin(String profKey, String value, String signature) {
        byProf.put(profKey, new String[] {value, signature == null ? "" : signature});
    }

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

    /** Skins de OFICIO conocidas (las que ha ido pasando el dueno, player_head/NameMC). Value base64
     *  sin firmar (signature vacia). Clave = profWord. */
    public void loadProfSkins() {
        // Clave = nombre en INGLES del oficio (Villager.Profession en minusculas).
        putProfSkin("farmer", "e3RleHR1cmVzOntTS0lOOnt1cmw6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvY2FmZjM4NTcyZmY4MDdkMzcwNDY2NjgwOWYwZTU3NmZjNmZiM2VjNDE3ODkzYmQ3Nzg3ZDUxNmEwYjJiODc4YiJ9fX0=", "");
        putProfSkin("toolsmith", "e3RleHR1cmVzOntTS0lOOnt1cmw6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvYmM0YzFkYzkxZjU0ZGU2ODFlNjNiZmIyZGRiNTA1MDMwMjI3YmE0NWFiOTFkZGY1NzcxMGVmNjFkZWEwNjZkYyJ9fX0=", "");
        putProfSkin("fisherman", "e3RleHR1cmVzOntTS0lOOnt1cmw6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvOGJlMzk5OTg2ZWNmZjFjZGQxMmEyNGNjZTc3Y2U4ZWZjMGM3OWYyNGEyMzE3YmFlMzRiNWU1N2RmYmRmNmRjNSJ9fX0=", "");
        putProfSkin("shepherd", "e3RleHR1cmVzOntTS0lOOnt1cmw6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvOWE1Y2QxOGZlN2Y1MjczMmVhYTM5NTM1NWYzMmJkNzgzZjI2OWY1YTQyZmRiNTQzNzQzZGEwNjU5M2RjZWJlMCJ9fX0=", "");
        putProfSkin("mason", "e3RleHR1cmVzOntTS0lOOnt1cmw6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvMThhMjg5ZGNhMDUxZTAwMTlmMDczOWNkNDUxYzAzNDdiNjA1MTc3YmMyZTI4OTg4YTRhNzFmMGVmZWE4ZjYyYiJ9fX0=", "");
        putProfSkin("librarian", "e3RleHR1cmVzOntTS0lOOnt1cmw6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvNDFmNDMzYjg3OWNkYzYxOTEyYjAxMjk3OTU0MWY1MzM5NjE2MTM2YTg5MzJjYjQ2MjJkNmE4NmU5ZWE2ZGI4In19fQ==", "");
        putProfSkin("butcher", "e3RleHR1cmVzOntTS0lOOnt1cmw6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvNjY0YjBjMTIyODNlZDc1YjljYzg2N2YzNDZiMjQ2OWNiMTkyNTg0ZTE1ZGEyMjU0ZTljNDljZDViMzNkZSJ9fX0=", "");
        putProfSkin("fletcher", "e3RleHR1cmVzOntTS0lOOnt1cmw6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvZDgzMTgzMGE3YmQzYjFhYjA1YmViOThkYzJmOWZjNWVhNTUwYjNjZjY0OWZkOTRkNDgzZGE3Y2QzOWY3YzA2MyJ9fX0=", "");
    }

    /** Skin {value, signature}: primero por OFICIO; si no hay, por sexo. Null si aun no hay ninguna. */
    public String[] skinFor(String profKey, String gender, String key) {
        if (profKey != null) {
            final String[] p = byProf.get(profKey);
            if (p != null) {
                return p;
            }
        }
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
