package net.thbtt.horsewhistle.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.thbtt.horsewhistle.HorseWhistle;

import java.util.UUID;

public record WhistleHorsePayload(UUID horseUuid) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<WhistleHorsePayload> ID =
            new CustomPacketPayload.Type<>(HorseWhistle.id("whistle_horse"));

    public static final StreamCodec<RegistryFriendlyByteBuf, WhistleHorsePayload> CODEC =
            StreamCodec.ofMember(WhistleHorsePayload::write, WhistleHorsePayload::new);

    private WhistleHorsePayload(RegistryFriendlyByteBuf buf) {
        this(buf.readUUID());
    }

    private void write(RegistryFriendlyByteBuf buf) {
        buf.writeUUID(this.horseUuid);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return ID;
    }
}