package net.thbtt.horsewhistle.network;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.thbtt.horsewhistle.HorseWhistle;

public record RequestHorseListPayload() implements CustomPayload {
    public static final RequestHorseListPayload INSTANCE = new RequestHorseListPayload();

    public static final CustomPayload.Id<RequestHorseListPayload> ID =
            new CustomPayload.Id<>(HorseWhistle.id("request_horse_list"));

    public static final PacketCodec<RegistryByteBuf, RequestHorseListPayload> CODEC =
            PacketCodec.unit(INSTANCE);

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}