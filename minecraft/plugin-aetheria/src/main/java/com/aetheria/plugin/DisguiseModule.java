package com.aetheria.plugin;

import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;

/**
 * Da ASPECTO HUMANO a los NPC (colonos, tabernero, mercader, Aeon): disfraza el aldeano de JUGADOR
 * con una skin, usando LibsDisguises POR REFLEXION (dependencia BLANDA). Si el plugin no esta
 * instalado, no hace nada y los NPC siguen siendo aldeanos normales — NUNCA rompe el servidor.
 *
 * <p>El aldeano sigue siendo un aldeano por dentro (pathfinding, conversacion, rutinas, proteccion):
 * solo cambia lo que VE el jugador. La skin viene de un SET DE ARRANQUE (libre) por sexo; se puede
 * ampliar/cambiar por skins de oficio editando {@link #MALE}/{@link #FEMALE}.
 */
public final class DisguiseModule {

    private DisguiseModule() {
    }

    private static Boolean available;

    // Set de arranque (libre) por SEXO. MHF_Steve/MHF_Alex son las skins clasicas (garantizadas);
    // el resto son cuentas publicas conocidas. LibsDisguises cae a Steve/Alex si alguna no resuelve.
    // Sustituibles por skins de oficio cuando se quiera (p. ej. una por profesion).
    private static final String[] MALE = {"MHF_Steve", "Notch", "jeb_", "Dinnerbone", "MHF_Villager"};
    private static final String[] FEMALE = {"MHF_Alex", "MHF_Alex", "Alex", "MHF_Alex"};

    public static boolean available() {
        if (available == null) {
            available = Bukkit.getPluginManager().getPlugin("LibsDisguises") != null;
        }
        return available;
    }

    /** Disfraza al NPC de humano con una skin acorde a su sexo. Silencioso si no hay LibsDisguises
     *  o si algo falla (mejor un aldeano que un crash). */
    public static void humanize(Entity npc, String gender, String displayName) {
        if (npc == null || !available()) {
            return;
        }
        try {
            final String[] pool = "f".equalsIgnoreCase(gender) ? FEMALE : MALE;
            final String skin = pool[Math.floorMod(displayNameKey(displayName), pool.length)];
            final Class<?> pd = Class.forName("me.libraryaddict.disguise.disguisetypes.PlayerDisguise");
            final Object disguise = pd.getConstructor(String.class).newInstance(displayName);
            pd.getMethod("setSkin", String.class).invoke(disguise, skin);
            final Class<?> api = Class.forName("me.libraryaddict.disguise.DisguiseAPI");
            final Class<?> disg = Class.forName("me.libraryaddict.disguise.disguisetypes.Disguise");
            api.getMethod("disguiseToAll", Entity.class, disg).invoke(null, npc, disguise);
        } catch (Throwable t) {
            // dependencia blanda: si LibsDisguises no esta o cambia su API, se queda de aldeano.
        }
    }

    private static int displayNameKey(String name) {
        return name == null ? 0 : (name.hashCode() & 0x7fffffff);
    }
}
