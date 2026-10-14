package net.thbtt.horsewhistle.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.thbtt.horsewhistle.HorseWhistle;

public record RequestHorseListPayload() implements CustomPacketPayload {
    public static final RequestHorseListPayload INSTANCE = new RequestHorseListPayload();

    public static final CustomPacketPayload.Type<RequestHorseListPayload> ID =
            new CustomPacketPayload.Type<>(HorseWhistle.id("request_horse_list"));

    public static final StreamCodec<RegistryFriendlyByteBuf, RequestHorseListPayload> CODEC =
            StreamCodec.unit(INSTANCE);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return ID;
    }
}