package com.aetheria.plugin;

import java.util.UUID;

import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.GameMode;
import com.github.retrooper.packetevents.protocol.player.TextureProperty;
import com.github.retrooper.packetevents.protocol.player.UserProfile;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoUpdate;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;

/**
 * Hace que los NPC (aldeanos marcados en {@link DisguiseModule}) se vean como JUGADORES con skin,
 * interceptando sus paquetes con packetevents:
 *   - SPAWN_ENTITY de un NPC nuestro -> se manda ADD_PLAYER (perfil con skin) y se reescribe el
 *     tipo de entidad a PLAYER.
 *   - ENTITY_METADATA de un NPC disfrazado -> se quita la metadata especifica del aldeano (indices
 *     > 7), que a un jugador no le vale; se conserva la base (nombre/flags) para no perder el
 *     nametag flotante.
 * El aldeano sigue existiendo por dentro (IA, conversacion, rutinas): solo cambia lo que VE el
 * cliente.
 */
public final class HumanSkinListener extends PacketListenerAbstract {

    private final SkinCache skins;

    public HumanSkinListener(SkinCache skins) {
        super(PacketListenerPriority.HIGH);
        this.skins = skins;
    }

    @Override
    public void onPacketSend(PacketSendEvent event) {
        if (event.getPacketType() == PacketType.Play.Server.SPAWN_ENTITY) {
            final WrapperPlayServerSpawnEntity spawn = new WrapperPlayServerSpawnEntity(event);
            final UUID uuid = spawn.getUUID().orElse(null);
            if (uuid == null) {
                return;
            }
            final String gender = DisguiseModule.genderOf(uuid);
            if (gender == null) {
                return;   // no es un NPC nuestro
            }
            // 1) ADD_PLAYER con la skin (el cliente lo necesita para renderizar el jugador).
            final UserProfile profile = new UserProfile(uuid, npcName(uuid));
            final String[] tex = skins.skinFor(DisguiseModule.profOf(uuid), gender, uuid.toString());
            if (tex != null) {
                profile.getTextureProperties().add(new TextureProperty("textures", tex[0], tex[1]));
            }
            final WrapperPlayServerPlayerInfoUpdate.PlayerInfo info =
                    new WrapperPlayServerPlayerInfoUpdate.PlayerInfo(profile, true, 0,
                            GameMode.SURVIVAL, null, null);
            event.getUser().sendPacketSilently(new WrapperPlayServerPlayerInfoUpdate(
                    WrapperPlayServerPlayerInfoUpdate.Action.ADD_PLAYER, info));
            // 2) reescribir el spawn a JUGADOR.
            spawn.setEntityType(EntityTypes.PLAYER);
            DisguiseModule.trackEntity(spawn.getEntityId(), uuid);
        } else if (event.getPacketType() == PacketType.Play.Server.ENTITY_METADATA) {
            final WrapperPlayServerEntityMetadata meta = new WrapperPlayServerEntityMetadata(event);
            if (DisguiseModule.isDisguised(meta.getEntityId())) {
                meta.getEntityMetadata().removeIf(d -> d.getIndex() > 7);
            }
        }
    }

    /** Nombre de perfil VALIDO (<=16, [a-zA-Z0-9_]) a partir del nombre real del NPC: es el que un
     *  cliente muestra ENCIMA de un jugador. Los espacios no valen en nombres de jugador -> guion
     *  bajo ("Francisco Ramos" -> "Francisco_Ramos"). */
    private static String npcName(UUID uuid) {
        final String real = DisguiseModule.nameOf(uuid);
        if (real == null || real.isBlank()) {
            return ("A" + Long.toHexString(uuid.getMostSignificantBits()) + "0000000000000000")
                    .substring(0, 16);
        }
        String s = real.trim().replace(' ', '_').replaceAll("[^A-Za-z0-9_]", "");
        if (s.isEmpty()) {
            s = "NPC";
        }
        return s.length() > 16 ? s.substring(0, 16) : s;
    }
}
