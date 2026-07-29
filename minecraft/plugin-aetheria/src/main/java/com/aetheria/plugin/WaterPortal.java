package com.aetheria.plugin;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Sign;
import org.bukkit.block.data.type.WallSign;

import net.kyori.adventure.text.Component;

/**
 * Portal VERTICAL con forma de portal al Nether (marco 4x5, hueco interior 2x3) pero RELLENO DE
 * AGUA en vez de la textura morada. Se atraviesa por cualquiera de los dos lados (la deteccion es
 * por proximidad al centro del hueco). Un cartel de pared, pegado al marco, mira al jugador.
 *
 * <p>Nota: el agua vertical en vanilla FLUYE (no se queda quieta); aqui se colocan bloques FUENTE en
 * el hueco para que se vea la lamina, aunque gotee un poco por los lados.
 */
final class WaterPortal {

    private WaterPortal() {
    }

    /**
     * Construye el portal y devuelve el CENTRO del hueco (para detectar el paso).
     *
     * @param cx,floorY,cz  esquina de referencia: el UMBRAL del hueco queda en (cx, floorY, cz).
     * @param alongX        si true el marco se extiende en X (se cruza andando en Z); si false, al
     *                      reves. La anchura del hueco (2) va por ese eje.
     * @param frameMat      material del marco (las esquinas van de obsidiana, para el look nether).
     * @param signFace      hacia donde mira el cartel (el lado por el que llega el jugador).
     * @param signLines     hasta 4 lineas del cartel.
     */
    static Location build(World world, int cx, int floorY, int cz, boolean alongX, Material frameMat,
            BlockFace signFace, String[] signLines) {
        // Marco 4 de ancho (postes en w=-1 y w=2; hueco en w=0,1) x 5 de alto (umbral h=0, dintel h=4).
        for (int h = 0; h <= 4; h++) {
            for (int w = -1; w <= 2; w++) {
                final int x = cx + (alongX ? w : 0);
                final int z = cz + (alongX ? 0 : w);
                final int y = floorY + h;
                final boolean post = w == -1 || w == 2;
                final boolean cap = h == 0 || h == 4;
                if (post || cap) {
                    final boolean corner = post && cap;
                    world.getBlockAt(x, y, z).setType(corner ? Material.OBSIDIAN : frameMat, false);
                } else {
                    world.getBlockAt(x, y, z).setType(Material.WATER, false);   // lamina del portal
                }
            }
        }
        // Centro del hueco (mismo en ambos ejes: interior en +0/+1 -> +0.5; alto 1..3 -> +2).
        final Location center = new Location(world, cx + 0.5, floorY + 2.0, cz + 0.5);

        // CARTEL de pared pegado al marco, mirando al jugador. Se apoya en el poste del lado de la
        // llegada, a la altura de los ojos (floorY+2), sobresaliendo hacia signFace.
        placeWallSign(world, cx, floorY, cz, alongX, signFace, signLines);
        return center;
    }

    /** Cartel de pared sobre el poste del marco, mirando a {@code face} (el lado del jugador). */
    private static void placeWallSign(World world, int cx, int floorY, int cz, boolean alongX,
            BlockFace face, String[] lines) {
        // El poste que da al lado de la llegada. El cartel va PEGADO a su cara, un bloque hacia face.
        final int postX = cx + (alongX ? 2 : 0);
        final int postZ = cz + (alongX ? 0 : 2);
        final Block signBlock = world.getBlockAt(postX + face.getModX(), floorY + 2,
                postZ + face.getModZ());
        signBlock.setType(Material.OAK_WALL_SIGN, false);
        if (signBlock.getBlockData() instanceof WallSign ws) {
            ws.setFacing(face);
            signBlock.setBlockData(ws, false);
        }
        if (signBlock.getState() instanceof Sign sign) {
            final org.bukkit.block.sign.SignSide front = sign.getSide(org.bukkit.block.sign.Side.FRONT);
            for (int i = 0; i < 4; i++) {
                front.line(i, Component.text(i < lines.length ? lines[i] : ""));
            }
            sign.update();
        }
    }
}
