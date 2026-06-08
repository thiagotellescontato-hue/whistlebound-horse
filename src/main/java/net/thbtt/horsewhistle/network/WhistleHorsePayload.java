package net.thbtt.horsewhistle.network;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.thbtt.horsewhistle.HorseWhistle;

import java.util.UUID;

public record WhistleHorsePayload(UUID horseUuid) implements CustomPayload {
    public static final CustomPayload.Id<WhistleHorsePayload> ID =
            new CustomPayload.Id<>(HorseWhistle.id("whistle_horse"));

    public static final PacketCodec<RegistryByteBuf, WhistleHorsePayload> CODEC =
            PacketCodec.of(WhistleHorsePayload::write, WhistleHorsePayload::new);

    private WhistleHorsePayload(RegistryByteBuf buf) {
        this(buf.readUuid());
    }

    private void write(RegistryByteBuf buf) {
        buf.writeUuid(this.horseUuid);
    }

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}