package net.thbtt.horsewhistle.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.thbtt.horsewhistle.HorseWhistle;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public record HorseListPayload(List<HorseEntry> horses) implements CustomPacketPayload {
    private static final int MAX_HORSES = 128;

    public static final CustomPacketPayload.Type<HorseListPayload> ID =
            new CustomPacketPayload.Type<>(HorseWhistle.id("horse_list"));

    public static final StreamCodec<RegistryFriendlyByteBuf, HorseListPayload> CODEC =
            StreamCodec.ofMember(HorseListPayload::write, HorseListPayload::new);

    public HorseListPayload {
        horses = List.copyOf(horses);
    }

    private HorseListPayload(RegistryFriendlyByteBuf buf) {
        this(readEntries(buf));
    }

    private void write(RegistryFriendlyByteBuf buf) {
        int size = Math.min(this.horses.size(), MAX_HORSES);
        buf.writeVarInt(size);

        for (int i = 0; i < size; i++) {
            HorseEntry entry = this.horses.get(i);

            buf.writeUUID(entry.uuid());
            buf.writeUtf(entry.name());
            buf.writeUtf(entry.dimension());
            buf.writeVarInt(entry.distance());
            buf.writeUtf(entry.snbt());
        }
    }

    private static List<HorseEntry> readEntries(RegistryFriendlyByteBuf buf) {
        int size = Math.min(buf.readVarInt(), MAX_HORSES);
        List<HorseEntry> entries = new ArrayList<>(size);

        for (int i = 0; i < size; i++) {
            entries.add(new HorseEntry(
                    buf.readUUID(),
                    buf.readUtf(128),
                    buf.readUtf(128),
                    buf.readVarInt(),
                    buf.readUtf(32767)
            ));
        }

        return entries;
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return ID;
    }

    public record HorseEntry(UUID uuid, String name, String dimension, int distance, String snbt) {
    }
}