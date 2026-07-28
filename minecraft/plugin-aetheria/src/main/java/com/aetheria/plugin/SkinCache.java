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
        // Skins por oficio FIRMADAS (MineSkin) -> renderizan en el cliente. Clave = nombre
        // del Villager.Profession en minusculas. Al anadir una nueva hay que FIRMARLA.
        putProfSkin("farmer", "ewogICJ0aW1lc3RhbXAiIDogMTc3ODI5NDYyMDgyNSwKICAicHJvZmlsZUlkIiA6ICI1MzgyNzM1OGIzOTc0ZmJiOTg0OTY5MWM5Yzg3NTA1YiIsCiAgInByb2ZpbGVOYW1lIiA6ICJPdmVyQmlnYm95MTIzIiwKICAic2lnbmF0dXJlUmVxdWlyZWQiIDogdHJ1ZSwKICAidGV4dHVyZXMiIDogewogICAgIlNLSU4iIDogewogICAgICAidXJsIiA6ICJodHRwOi8vdGV4dHVyZXMubWluZWNyYWZ0Lm5ldC90ZXh0dXJlL2NhZmYzODU3MmZmODA3ZDM3MDQ2NjY4MDlmMGU1NzZmYzZmYjNlYzQxNzg5M2JkNzc4N2Q1MTZhMGIyYjg3OGIiCiAgICB9CiAgfQp9", "TWRmnPLh9OsXzrAmYLJvHWA0nRjg/fF+ChL0q3kLbCviaZIgqaLdzkv2jae28N+twcMGPcepZMT+JVSO61qBUea8mz6XY3YRWrkUdjnlEGELRr8hPG4Ht+lcWg6O8SWSS1LwmnMFDCcRCq1LIN419W/jaVzOqVVSEng988m6cHD8q8x8FQNDL5GOp6plkN7FmyRr1a9UDTykx8nONPRCPn4UWoj2IuvEZL2JZ6H8f5Ho5MeB09WEFyuobzUyLawuM4TPHcOuhAjlsmTPMV9sYmWDs/jY53dkqWuvr9lILABxqqG6Q/aXxVbz44lzXO57cHVAR32D1P7l36CGy0v3pqrwhDGPeyYGWM4xyr6wq2awTBII9OKaWp92Ambk8cmfB50OpdPlR5elWlqgVLTmI8Xp+e0V3EaZk/SMe/+JNW1MKIq5r3eOqJp+05qrwKAQq9t5uqzBSHJh6bzYON+mHGqU3QvV5z2PNY7gFCwi/m+gHpYLz87MAv/qS7qEpYu5tqKLy0Akc72eQ8kqy1NEUjeqQUV9M72JWNV6lgKY/xb6LqKxrTVlYVe2p7h0VAMY/VJAgb79NW25dZTCgIlo75DAXZJqpt/ZtcXhZBOOrPIkXZESuIkvKl6PMTrh/fm+jQzlTLfDUebOMB0J0+yhx8SXl8VIRqRMnl9grAMzD8k=");
        putProfSkin("toolsmith", "ewogICJ0aW1lc3RhbXAiIDogMTcyMzk0NDcwNDAwNywKICAicHJvZmlsZUlkIiA6ICJiNzRlYjViMTc5OTc0YzZjODk3ZTgwNTM4Y2M1NmYwMSIsCiAgInByb2ZpbGVOYW1lIiA6ICJQYW5kYUNoYW4yOCIsCiAgInNpZ25hdHVyZVJlcXVpcmVkIiA6IHRydWUsCiAgInRleHR1cmVzIiA6IHsKICAgICJTS0lOIiA6IHsKICAgICAgInVybCIgOiAiaHR0cDovL3RleHR1cmVzLm1pbmVjcmFmdC5uZXQvdGV4dHVyZS9iYzRjMWRjOTFmNTRkZTY4MWU2M2JmYjJkZGI1MDUwMzAyMjdiYTQ1YWI5MWRkZjU3NzEwZWY2MWRlYTA2NmRjIiwKICAgICAgIm1ldGFkYXRhIiA6IHsKICAgICAgICAibW9kZWwiIDogInNsaW0iCiAgICAgIH0KICAgIH0KICB9Cn0=", "vMZLXK8ryc1lqYVLolvRC7m/YZ3ZFJd7SfXNv+pfSijNKu/3/tkD2mcs70KeGQOP5ZD/yyz0JugBXAEL92VUq8qdGDaTbdkLy/D3cp/8r7uE6+tPy2sEwMPlpAb4S0pvFZs2GYTFUhepENShkjKwyDyFmUKWdJ2HLtKwLM2WwiCURRcZyyXexku5pHnk6GU322PXkletaUQcI7HX8GhplxaMiRs7F6J9CPXIjIw2Kxh7zHTx9Jqww6Ae0Abcq44MXdFi0wF6RxgZsGlezB+AA5UDKrKt7fn0QKwtdRYmxeVxie5uP56lXzuVDghoTpLiXbFYFrQ1M5w5Lh1xnW7UrOpxVcLbvrWhffwim24tEdeLNMEwj03zESY5oSvjpwF0SBQs9kw2C85BB2YZjy5cxf7gjIZcLx/zbRhaRQk5FoV7B+kSBBMfmE1vn66T08ldNaBRbZ+mkk+eMPzhpSpcdrkYoLDLaFmr77i2D71afo9YBUv7UXwFSsd2lxjQadr3JDCrhOMnqYS2yPVTUs/EjB11Nbs7wnMDxn14/fORLWFGF4KcB8+o5m5x/zFKetE3HuAiCvowiuuMDVuUjWih5elK9xjctSRKFxyjeDDYannSim9JYLq8FWnGsqD1x0cEn6Fgimb/9WhMnV8LlBdA0Tw3FfQNwwkb40ZX9+rZjew=");
        putProfSkin("fisherman", "ewogICJ0aW1lc3RhbXAiIDogMTczODI5NzQ3NTk1MywKICAicHJvZmlsZUlkIiA6ICI1MTAwZGZmZDI0NDI0M2I0OGQxMmVkZTVkMjgxMzk2ZSIsCiAgInByb2ZpbGVOYW1lIiA6ICJFbGVjdHJvbmljU2V4IiwKICAic2lnbmF0dXJlUmVxdWlyZWQiIDogdHJ1ZSwKICAidGV4dHVyZXMiIDogewogICAgIlNLSU4iIDogewogICAgICAidXJsIiA6ICJodHRwOi8vdGV4dHVyZXMubWluZWNyYWZ0Lm5ldC90ZXh0dXJlLzhiZTM5OTk4NmVjZmYxY2RkMTJhMjRjY2U3N2NlOGVmYzBjNzlmMjRhMjMxN2JhZTM0YjVlNTdkZmJkZjZkYzUiLAogICAgICAibWV0YWRhdGEiIDogewogICAgICAgICJtb2RlbCIgOiAic2xpbSIKICAgICAgfQogICAgfQogIH0KfQ==", "PEgYneCTiIdQMi2+eZC/7vglPPHQXsL+Vop7uACvaUf6xPuQ9GiJtD866FGioe8jrLYD/Kc5sXG8ZK1p5jQDdTgma/9x9pdvbsUN67A+0IuWlEKEjNDSBpini5V21brFoaudWTbKP+Ga67wfg5ydP/m30JNSAuzyIEMsgNwUcO/2uk1x2DmlXnCbXXfsWLojpD7ox5bLiP7eUmPx1VtNpbsFE8rd2sdB+ERgF1c6PZJ7pO3i1UsMr1HSGL5dX5esB5lLCRZbz+2DQaiAf4Sp2RfSGx1F4E7Q/g4XvS6favkVc7HBkwI47SsnNQJBJ6h1Z1q238Nw/1vthcVeqi+Io+/As8Z1n97zQ3TektyNeJd7bQl7EIiCB+WPGBNlz6NDhtdC+hGdC7XPfJ1fj83MBjD1CaG6Y+Xf7YHA0I0nZH3zjn6DSs4AOL8TgLZPGLzrD6jNLmQagdg1iM3vld2Ko+3R/E2jP88aTdMG4ZdYc5Qk0oyjez1k4yyiyfVbuPHEF/gXR1jBKNc6mVp5LclVuG7LFfqYGFODeBC7tzfzaQWV+219JJ03n598+9TVXuegycjC1W6T1ohMs6Q6LD3fkCeNW5TWQRLUmur6eMZcaurE0i0IXhEPB4YdmI5PUQQ8PPOW9In7cdSXhIGQQCBf+GT3lOPkVPsRXKt5UQN/VaQ=");
        putProfSkin("shepherd", "ewogICJ0aW1lc3RhbXAiIDogMTc4MjQwNDQyMjg0NiwKICAicHJvZmlsZUlkIiA6ICI0ZWEwN2YwODlmN2U0MWZhYmMwNjRhMjZlNWM1OWU2ZSIsCiAgInByb2ZpbGVOYW1lIiA6ICJzcGlmZnRvcGlhMiIsCiAgInNpZ25hdHVyZVJlcXVpcmVkIiA6IHRydWUsCiAgInRleHR1cmVzIiA6IHsKICAgICJTS0lOIiA6IHsKICAgICAgInVybCIgOiAiaHR0cDovL3RleHR1cmVzLm1pbmVjcmFmdC5uZXQvdGV4dHVyZS85YTVjZDE4ZmU3ZjUyNzMyZWFhMzk1MzU1ZjMyYmQ3ODNmMjY5ZjVhNDJmZGI1NDM3NDNkYTA2NTkzZGNlYmUwIiwKICAgICAgIm1ldGFkYXRhIiA6IHsKICAgICAgICAibW9kZWwiIDogInNsaW0iCiAgICAgIH0KICAgIH0KICB9Cn0=", "iHwE8noxg6PvG9ZimCEJlD3hjp3y8wDTKagdtgCPD7egfgvwwsYJ2sLWkK+3+643RI1H4XJLEOkvdll1yh6cFXSig6jpTiEy8VASVwpp42wuk+l3ft5b6C9O2UBK2bOFe9y0ryMr2WDsC+J6uu+WBD93DFFdCYlSwzjfMsGGJ88K6UZzqwUuCLgrAsM43EwQ1C4w1gwSCpC+iDAViYeiWLqWroM2actL3YZnmikTZy+Xc+xw5mkwdA1iTpWmkhcmKMpoHDKLOukGapZyDek1/DqY/1Z6Q3gsBDlndyZacIitAPV2k/MOsJxOp9atvLVREPex7VbPlrQXudWIB7MCK4mecxJIjSD/JG4cfuUT0w0CzvX7EYi61dYugiXQuTjK6mxEPz2gH+OdA/brp7NHMo0efRSzETC7ho4UAfgEjJ8kvfPw77/r6r4ViCJBXJ59DqkmSatd8E7mjUzD0VmtQJBoO7ExktvgBeZE+5zo5xyJqwnhh7H0/WkbqEgduDHuG4NoBnRfH6n+ujHPJWZ5pdQ0zqRj3gxrCg4NF/x0+eHSCOED2wdYBCz/s+FqyIIYvO6WcVV0FJJ5aV2L+MkVtpgbOc0dAe2WuP/9/xHd7d+UzF6ZZNJuE7UDloW0YuiDcX1v10E9I0XJqwByiBvMfXJHOiLLKe+a8EXEfw71NPc=");
        putProfSkin("mason", "ewogICJ0aW1lc3RhbXAiIDogMTYzNDE5MjIwNDM1NSwKICAicHJvZmlsZUlkIiA6ICI0OWIzODUyNDdhMWY0NTM3YjBmN2MwZTFmMTVjMTc2NCIsCiAgInByb2ZpbGVOYW1lIiA6ICJiY2QyMDMzYzYzZWM0YmY4IiwKICAic2lnbmF0dXJlUmVxdWlyZWQiIDogdHJ1ZSwKICAidGV4dHVyZXMiIDogewogICAgIlNLSU4iIDogewogICAgICAidXJsIiA6ICJodHRwOi8vdGV4dHVyZXMubWluZWNyYWZ0Lm5ldC90ZXh0dXJlLzE4YTI4OWRjYTA1MWUwMDE5ZjA3MzljZDQ1MWMwMzQ3YjYwNTE3N2JjMmUyODk4OGE0YTcxZjBlZmVhOGY2MmIiCiAgICB9CiAgfQp9", "dSUI7+v0UHneELxd+BSmZO+k30F6tUXYo8njR8CYfnsttHW89MOua2hDIQbQvCRwJC9p86UrD/YKu+Qv+ch0VEUZmSaIqaSOQasnbmZ0L6lrOY70KmlVYLxa/ijL8jBSjZngfK/IMl5rMC9WHde9PdmSs2RiN1xClG4Ltrtx4KAvnljajUZfzPjPzDiOq7hXFM+4ul3VjrGENaQrd10tU/cxJ1J+D+XcLusq2M69F1O2cS20MtAImHwyAjLeGu29pV0UWzN5Nk6SFK+nt0+uhYhw0z54EKWDHslsZ7Uks0KSPMRA+iPylG+Y1MMEnWe3mI8gLkTmuJ0wVo4Et4wxULygW1qMkUpl/RpTau+6Ny4iBsY7uR7e8L4ktpPVHNp7TIykr6Fo1GUozQnyBjtxDyYc7FfVJCx1vRadKrazcG7m3ZDuewWCEy1ej/tMTwcw02S8haKTyKa/Ln7xu2oHWU6agqCXG6Gk1B2AU686NX5lykh/JbfQXMKV8KqXEsuu4t0g355RkNTGqEH3MYJhfQ+VWm1HTGpre1UyqcqCeHtEFaufUBNJHXgeZyxQkkHbj0IVh01baFOOkk++diZXewsHk2fbDezYZNfSvqUDR4KBnIJi7CjHYq8yh5HIbG5AgO2pJ9UxFsJ4VLhoCzeX1uPdsl5y3/7AZleSs/FeRWc=");
        putProfSkin("librarian", "ewogICJ0aW1lc3RhbXAiIDogMTYxOTY1OTQ4OTY5OCwKICAicHJvZmlsZUlkIiA6ICJiYTliMmJhZmY4MjQ0NjcwYWZkNmQwNDM3ZjczZWFkYyIsCiAgInByb2ZpbGVOYW1lIiA6ICJfRGFzQXVnZV8iLAogICJzaWduYXR1cmVSZXF1aXJlZCIgOiB0cnVlLAogICJ0ZXh0dXJlcyIgOiB7CiAgICAiU0tJTiIgOiB7CiAgICAgICJ1cmwiIDogImh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvNThiMTU3ZmRmMWM0MWNlNjhiM2UxZGVmZTNmNTlhZjJhMjgwMTFmMzgwOTAyMzA0ZTI5YTY1NTc4ZjQ0NGIyZCIKICAgIH0KICB9Cn0=", "COVs9GEc1gM2HcaAGAAJzNhLG97JNXzZ7KVv/jyoFQhTviHl6895Hm5WC3uKOOV5PPQB17PBc+zYyO3fzFic66jB4cAAGN3m5LeGaqwQouRoMsj/H38sne+yEJ7S5rgJ2tQZmM0yqu/0v29QJ/H7CTCel/XKFz8nLq4Zcz0NG3hORbaY1KcMM9QrDjixIrb65NZESYIbmJJbl9S51jVE0lkb6mzb3mdGW/YzA5//9MsQUk1qhv+MinF/y2b4ZGxk8a0QZG1vCOiacG9by0P3ixgqSqvzdak8T/lRG2RGlhiuLKzCRUNck96RB7kqZKWIdLQoRxAK9aY2dYranle6e8hZN+FjaxA7kzAZ0sCz9f5pftuZETeM7XlSeMx80aIbcGY933B4evTMLtkn9/OU63cgTX1A1A+Nr9eO3b78w0nrxVASebm+3joNQhJK7E+lgWaDPr/F/rK1Mnrp/CuC8k+jhxe87y0UksUx42scTA2IFlCKwgwFIQf4SKjjDAh6oclsZ15RZM+PbT+yI+PArXzhBquZz2a2jfUWg++vXkLgJXH8dF/DUta0c4zRfCGYc5Aucuo99CYylmLH01V3Y/UXPJTP49EO2Ybi5QP5IZueiDIjcKpyysu2BbJzCNClOy+c2ySa61AqUwxneE4RkQ1K81FKSz0gK0q2M2eljsI=");
        putProfSkin("butcher", "ewogICJ0aW1lc3RhbXAiIDogMTczMTAwNTY4NjMwNiwKICAicHJvZmlsZUlkIiA6ICI0M2NmNWJkNjUyMDM0YzU5ODVjMDIwYWI3NDE0OGQxYiIsCiAgInByb2ZpbGVOYW1lIiA6ICJrYW1pbDQ0NSIsCiAgInNpZ25hdHVyZVJlcXVpcmVkIiA6IHRydWUsCiAgInRleHR1cmVzIiA6IHsKICAgICJTS0lOIiA6IHsKICAgICAgInVybCIgOiAiaHR0cDovL3RleHR1cmVzLm1pbmVjcmFmdC5uZXQvdGV4dHVyZS82Mjc1NGFkNTczY2U5YTA5Njc4NTBjNDZjYTBmMjVhMmI2MDU3YmNlZDVhMTdkNmVkYTBlZTlkMWRiMmRhZTAxIgogICAgfQogIH0KfQ==", "QQv4xUlGSfldFyoStx/i6/TdCwCWgdEj8rNl8b6zAYAb8B/kf9PBbakPHxdhskkEX1A3jLRMC+iMKF0invXoxAAnyldLRpkBMjXuua02YiF3gq/m5ljGVS95f3mrQ/n+oKxNIrkKO6X2IRyfQUtIbwSS+1+ePr/8E+tyLQkV/L22XMyhAk0/kDW4YAaL48G8KUp7LzMaI8IZGNdYVtdJs1qU8UBciWIwRT6JYYE4Q3W01VOnNbIHd7ps5WjZ8Rqn6T9mM4wQdym1T5ROjJOIMgOSE13j/JLq3LoANV6bOq0v8DVkrUziNtgliD/7F6hYIYD1/ImJ/x6AMLmqfNUq328tTKsxpD2EDjzm2ULmR+fYCS6+HUh0CaDITxhUL7mOAEZcrlgPkDZ70/VB5Y6J4HTnB5wjssAfLo7hMEvznwUxvOHkoDK6bxK/NPNNx5k4SoL0fXfm0x/TOkaVRkWo38dnXxBPCzxtlYkOcyzyDaaoZORPTcT/rGcbrAHlgZn4sfhHLWhrxWjyxcw4ihnD6SwKi+STPBevyzrgoXm7p5ghNFtdPjY72cF0wuCGVISgQ2VZ1m7Ukt0qy3oaR59q/Xic4RvH4mWnVy1oh+5f9EeFkMABS3Hf20efxoQYfgWTAGbkOUaVJZKCtzqXVqL8CCnxmU3/eNRPE1Jv7Y6kkUE=");
        putProfSkin("fletcher", "ewogICJ0aW1lc3RhbXAiIDogMTYyMzc1MTUzNTYxMCwKICAicHJvZmlsZUlkIiA6ICJkYmNlZjMyZjI5ZDc0Y2UzOTUzOWMwYjBhMTE1YjZiZCIsCiAgInByb2ZpbGVOYW1lIiA6ICJyYW1waXJlIiwKICAic2lnbmF0dXJlUmVxdWlyZWQiIDogdHJ1ZSwKICAidGV4dHVyZXMiIDogewogICAgIlNLSU4iIDogewogICAgICAidXJsIiA6ICJodHRwOi8vdGV4dHVyZXMubWluZWNyYWZ0Lm5ldC90ZXh0dXJlL2Q4MzE4MzBhN2JkM2IxYWIwNWJlYjk4ZGMyZjlmYzVlYTU1MGIzY2Y2NDlmZDk0ZDQ4M2RhN2NkMzlmN2MwNjMiCiAgICB9CiAgfQp9", "DXgZH+9eP6oE956BP4PNef2Go0Dvfzh7oaIX7/ErKIMmcUHy3vB3UMQ+N3yFjI/eQk8+HmF8b88L6obCH7ELiWLc89m74wd1lNBBvAkifN4gW9LCmiN81gEf0qR2H3hePjnA2V/1O+9jk8tNIlOjvT3+3xqV3wGv96+dR7BCepfwQ67iYejMklvYDqc+qjnpe6OC7mE8HRx3eDzxrh9PfbkLxRwfxeTniyzNOfnN9pRw1+6hp/bbrN+kBIoXVGOhC4FhMVaPzg/Vssinf8kgvCvJs7KvGiHlWB5nZx7j/j0v71BTIuBY8+c8qiDphccsLUHst/fnScJi44XNMcgsCIRQyHw6+qjRxUBsv7f0r+c3DO1L5NZeazH+BToAH7Z3tvw4gIy7qrnJhI25jknRQzjDgoDSIHCkAO6eUDeUl6eYPks2xChcBbRufiuQBiMFmhh0nq5OxY6ZsJtBP4qBnB3wajWwefifhFGs+ZGsSivdYxEdduPEnsm2i+uCS5j+pgJdrcAT1rby6GcGS0MYXgau3n2A8LN9K0EIqmQFAJKOLebeA69ebPEDUuSDWpdPMaEEAHjxthK9mto84TtOAh9y5cBpPSHpTDupoSHBq2deX5Z15iJyvrkJ1BthdGVEc/X3LqOY04Y9s5asL+GVkI3OYM/mlqxDK4tOQs31/9I=");
        putProfSkin("leatherworker", "ewogICJ0aW1lc3RhbXAiIDogMTc3MDkxODg1OTg0MSwKICAicHJvZmlsZUlkIiA6ICI1MzE4YWJhNDJiMTk0ODNiODFiMWY2N2Y1ODVjNDdkNSIsCiAgInByb2ZpbGVOYW1lIiA6ICJocHllZiIsCiAgInNpZ25hdHVyZVJlcXVpcmVkIiA6IHRydWUsCiAgInRleHR1cmVzIiA6IHsKICAgICJTS0lOIiA6IHsKICAgICAgInVybCIgOiAiaHR0cDovL3RleHR1cmVzLm1pbmVjcmFmdC5uZXQvdGV4dHVyZS9iYzJiYWZlYTE4NmQzZWY3YzQ0YTI4YTIzNjRkNWI3MTQ0MzdlNTk1NjQwNDQ0YjE1Y2VhYTlhOTY0OWExYWU2IgogICAgfQogIH0KfQ==", "NQbxCeTvvV7AdSS1AjAt1gSpIJmlagAt+ViXfZurAotvW6Mkvbgh//5hlzcEfbIIplYMfBehAij889nRoEmmQpUREUYwk70AcbXk6r7uGO+9SfnoZY+8fB/6xPYD8+7Tq6JvJznKBWq8A3gX2z0lYBrBl1LDfcbZuFYJdTSAm1ENTDoW/8l8bSBuP94xSuOsNIfsllWyk9yh2y0ndVX5GNqDMLzgvk/btkw/RvEakLH4MRZy9E5kdi8ootsEJaOmvNOaTC9AAbDrlvcFgxGCxtGLFSryb+8FxfCp5p/jcJg8tUT4hbcvmpQkXW5dX6nTZaSV9GySbYPANCZxny1z75niwm4xCAQl7qxvlnN3IC6sXdLUmJxB6fOUq1SRpFfJtRP3R8jNn1TgX1PqTc7AfECkD+uLmRsrr5yLyrPvVQonAO8Z1YsclAHF02fk1vsbY9ukolcvONbuKuZwa/nXtIEJVZss7uNp6tJHjbcOdtQTT+Lxokx7In7FiMS466v+HFTEfFxa/EP9PomcwYZp8g9cVahDUzycPPtCVteRdpx3cEbonb6LZqTZOjRPsLaPgHcUnI4GQOyjg4R2ZvOp7CW5Auq/GsrfVZi1BVy5+z9eePhU0j9PN5yX97iw2k7IlXRmok3P2fVcAgjoWMLEsm84ab38pubT8b4fsoalhcU=");
        putProfSkin("trader", "ewogICJ0aW1lc3RhbXAiIDogMTc2ODEyMzY3MzQ3MSwKICAicHJvZmlsZUlkIiA6ICI0YTQ1ZmY3NDM2Njk0YTNiYTc3OTdhYTgyN2IwNDM1NiIsCiAgInByb2ZpbGVOYW1lIiA6ICJzcGlmZnRvcGlhNSIsCiAgInNpZ25hdHVyZVJlcXVpcmVkIiA6IHRydWUsCiAgInRleHR1cmVzIiA6IHsKICAgICJTS0lOIiA6IHsKICAgICAgInVybCIgOiAiaHR0cDovL3RleHR1cmVzLm1pbmVjcmFmdC5uZXQvdGV4dHVyZS9jZjEwYTY4NTU5ZTFhMGU4M2M1ODgxMjQ5ZjdiOTI2ZDE4ZDIxZTJlNzU3NzNkNjI0NmZhYTRlZjQ3YWFmMDU4IgogICAgfQogIH0KfQ==", "YfsGJDT86v2SHIvAW27zjolV1QLDmgFLvUDPGmc6A+j+mixEKbsUOM4mL9yEVzi6PQsxLBl4vcYnBYnlfmFDiMMVC9kIcYhfKIPOQaZUs/s3X3Fqu/8KoQePlCmbLuX5yVeT7s7p42DoARX2IeDt5qWWWuZ0M6VQ13kHEsinq86s8WCtekr6r3yr5lKrnC8PwkzAvNlFTuVq1flA6fyvjaGo/ldrt8+831cSxtc0DolgAAfB9g/VsWn01FW4fnm74dB0J5/icc2b2eJlmtAWZc35mqj2ruSUU/RoHwVmJntUCCJgsYs+tfyySHhT+O6xlx1uQdHJU3b+BCRBkVjIKtt3JsksKIttkqIoOJjTJq00AYnu8rhV9GJkcgK73QVNGQOQENhKEUJQijZ+zTezf3+WC2URXftUkbW5EP645goLRsPSazNynzSx0Ur+we8Q56rNeQDN1truc+GBtlnFkMmF3WErDCbAxk6k/yGgD05O9YAuTE6ZCtuWX9A5pGXFpA0dl/FFvH9ixEpXpzZuUb+l/91MUz2OfVm80nbrcKmRvqIOJPilqVXQzEduEUvhhbiYEg95p7Chmdw3AwWPVygEQsiV0a7/jKwYlCoPwx/Lyeg6OBwmNcv2lXJRxcelsWsN7HAcaHtYLqASCrVucqvfP5DEpj+vncvRJwOqDEQ=");
        putProfSkin("concierge", "ewogICJ0aW1lc3RhbXAiIDogMTYyMzQ1MTkwNjA5OSwKICAicHJvZmlsZUlkIiA6ICJiNzQ3OWJhZTI5YzQ0YjIzYmE1NjI4MzM3OGYwZTNjNiIsCiAgInByb2ZpbGVOYW1lIiA6ICJTeWxlZXgiLAogICJzaWduYXR1cmVSZXF1aXJlZCIgOiB0cnVlLAogICJ0ZXh0dXJlcyIgOiB7CiAgICAiU0tJTiIgOiB7CiAgICAgICJ1cmwiIDogImh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvZGViMDI1NGZmNTNmYmI1OTVjMmFjYjhkOGU3MjNlZjI1NjU4Mzk4ZmJlZWIyMDU5ZWM0NWVlMjQxMWJkZmI0NCIKICAgIH0KICB9Cn0=", "mX4jokoYfiRqNTF3SwXTf+L/mUJPPm32GvAN5V8YjZsyi97zNrkGtgYdvlpWDR+mfj5SwQPY7ZIZLi9stMpBqRua142totRfCKpJTMSaAswjnk+7lHGQWzRq1Pt7jhcOEmqpjaGOclnKFeKKSDLC1zBDZ+/UTp5ZHXX6dzQosqYVrp7PdWF++dD4S1JCQDrAbB6hBYliwjmkUq3e4VPd3y8HK+GEwAAfmNOlR0NlZqMISdubl8B5Ka/HjBjDKrXIGMVq9T+t7BLUOe6Inulv9WOe86aQ9mCnI7yc0nUxqjAMmOso45bW3GUt+m5Aeh8SmEkCLc4jw7egNLd/DDE4Ui+96H9Ia7bq3crWajwf+Jjmmx2k+JonVhSaWd7uDUBSBIIcdv1m/AK8wLYD+hBi5SOPuhAB+tRrctGDeh2Dsd4F3hj+bioZarXGPPPito0Nr+KU89mMDxWR6Glm/DAu0UXZk+jemF3VX+OEknMUcx7D5jrtJnbfDiA+o10wzuwsMEP3wAk5mnDEMkoS9xmaDcZ3vtngp+f2FjA8INcUtCMBwdWblS4orqx5HAXHe7P0M9S0znU66sIsB2QIAGFepRnvBGgYtc65IMI1dmnzNSBBC7RkGEiD3mT8BjRvEtLt7uQhPjnj7X+bMOrPIJlW4Nm3BhG4ZYNHLQ/QTq0XpWM=");
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
