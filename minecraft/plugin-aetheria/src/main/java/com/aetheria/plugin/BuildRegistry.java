package com.aetheria.plugin;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * Registro PERSISTENTE de las construcciones ya colocadas en el mundo (aldea autonoma, arquitecto,
 * decorador, blueprint por chat y esquematicos), como cajas 3D. Sirve para que NINGUN camino de
 * construccion pise algo ya construido: antes de aceptar una construccion se comprueba si su caja
 * interseca con alguna registrada.
 *
 * <p>Es la version compartida de la lista {@code placed} que solo tenia SettlementModule.
 */
public final class BuildRegistry {

    private final File file;
    private final List<int[]> regions = new ArrayList<>();   // {minX,minY,minZ,maxX,maxY,maxZ}

    public BuildRegistry(AetheriaPlugin plugin) {
        plugin.getDataFolder().mkdirs();
        this.file = new File(plugin.getDataFolder(), "regions.txt");
        load();
    }

    /** True si dos cajas 3D se intersecan (inclusive). */
    public static boolean intersect(int[] a, int[] b) {
        return a[0] <= b[3] && a[3] >= b[0]
                && a[1] <= b[4] && a[4] >= b[1]
                && a[2] <= b[5] && a[5] >= b[2];
    }

    /** True si la region choca con alguna ya registrada. */
    public synchronized boolean overlaps(int[] region) {
        for (final int[] e : regions) {
            if (intersect(e, region)) {
                return true;
            }
        }
        return false;
    }

    public synchronized void add(int[] region) {
        regions.add(region.clone());
        save();
    }

    /** Libera las regiones que contienen (x,z) — al demoler una casa, su solar vuelve a estar libre. */
    public synchronized void removeAt(int x, int z) {
        if (regions.removeIf(e -> x >= e[0] && x <= e[3] && z >= e[2] && z <= e[5])) {
            save();
        }
    }

    /** Region a partir de centro + medios + rango vertical. */
    public static int[] box(int cx, int minY, int cz, int halfX, int halfZ, int height) {
        return new int[] {cx - halfX, minY, cz - halfZ, cx + halfX, minY + height, cz + halfZ};
    }

    private void load() {
        if (!file.exists()) {
            return;
        }
        try (BufferedReader r = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = r.readLine()) != null) {
                final String[] f = line.split(";", -1);
                if (f.length >= 6) {
                    final int[] box = new int[6];
                    for (int i = 0; i < 6; i++) {
                        box[i] = Integer.parseInt(f[i].trim());
                    }
                    regions.add(box);
                }
            }
        } catch (Exception ignored) {
            // un registro corrupto no debe tumbar el plugin
        }
    }

    private void save() {
        try (FileWriter w = new FileWriter(file, false)) {
            for (final int[] e : regions) {
                w.write(e[0] + ";" + e[1] + ";" + e[2] + ";" + e[3] + ";" + e[4] + ";" + e[5] + "\n");
            }
        } catch (Exception ignored) {
            // ignora fallos de escritura (AV de Windows, etc.)
        }
    }
}
