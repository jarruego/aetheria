package com.aetheria.plugin;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

import org.bukkit.World;

/**
 * Escribe una region del mundo como esquematico en formato SPONGE v2 (.schem), leyendo los
 * bloques con la API de Bukkit y codificando el NBT (gzip) a mano. Asi podemos crear
 * esquematicos de NUESTRAS propias construcciones sin depender de la API de WorldEdit al
 * compilar; FAWE los carga con //schematic load igual que cualquier otro .schem.
 */
public final class SchematicWriter {

    private static final int DATA_VERSION = 4189;   // Minecraft 1.21.4

    private SchematicWriter() {
    }

    /** Guarda [minX..minX+width) x [minY..minY+height) x [minZ..minZ+length) en {@code file}. */
    public static void save(World w, int minX, int minY, int minZ,
            int width, int height, int length, File file) throws IOException {
        final Map<String, Integer> palette = new LinkedHashMap<>();
        final java.io.ByteArrayOutputStream blockData = new java.io.ByteArrayOutputStream();
        // Orden Sponge: index = (y * Length + z) * Width + x
        for (int y = 0; y < height; y++) {
            for (int z = 0; z < length; z++) {
                for (int x = 0; x < width; x++) {
                    final String state = w.getBlockAt(minX + x, minY + y, minZ + z)
                            .getBlockData().getAsString();
                    Integer id = palette.get(state);
                    if (id == null) {
                        id = palette.size();
                        palette.put(state, id);
                    }
                    writeVarInt(blockData, id);
                }
            }
        }

        file.getParentFile().mkdirs();
        try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(
                new BufferedOutputStream(new FileOutputStream(file))))) {
            out.writeByte(10);                 // TAG_Compound (raiz)
            out.writeUTF("Schematic");
            intTag(out, "Version", 2);
            intTag(out, "DataVersion", DATA_VERSION);
            shortTag(out, "Width", width);
            shortTag(out, "Height", height);
            shortTag(out, "Length", length);
            intArrayTag(out, "Offset", 0, 0, 0);
            intTag(out, "PaletteMax", palette.size());
            // Palette (compound de estado -> id)
            out.writeByte(10);
            out.writeUTF("Palette");
            for (final Map.Entry<String, Integer> e : palette.entrySet()) {
                out.writeByte(3);
                out.writeUTF(e.getKey());
                out.writeInt(e.getValue());
            }
            out.writeByte(0);                  // fin Palette
            // BlockData (byte array de varints)
            final byte[] bd = blockData.toByteArray();
            out.writeByte(7);
            out.writeUTF("BlockData");
            out.writeInt(bd.length);
            out.write(bd);
            out.writeByte(0);                  // fin raiz
        }
    }

    private static void intTag(DataOutputStream out, String name, int value) throws IOException {
        out.writeByte(3);
        out.writeUTF(name);
        out.writeInt(value);
    }

    private static void shortTag(DataOutputStream out, String name, int value) throws IOException {
        out.writeByte(2);
        out.writeUTF(name);
        out.writeShort(value);
    }

    private static void intArrayTag(DataOutputStream out, String name, int... vals) throws IOException {
        out.writeByte(11);
        out.writeUTF(name);
        out.writeInt(vals.length);
        for (final int v : vals) {
            out.writeInt(v);
        }
    }

    private static void writeVarInt(OutputStream o, int value) throws IOException {
        int v = value;
        while ((v & ~0x7F) != 0) {
            o.write((v & 0x7F) | 0x80);
            v >>>= 7;
        }
        o.write(v & 0x7F);
    }
}
