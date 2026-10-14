package net.thbtt.horsewhistle.network;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.thbtt.horsewhistle.HorseWhistle;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public record HorseListPayload(List<HorseEntry> horses) implements CustomPayload {
    private static final int MAX_HORSES = 128;

    public static final CustomPayload.Id<HorseListPayload> ID =
            new CustomPayload.Id<>(HorseWhistle.id("horse_list"));

    public static final PacketCodec<RegistryByteBuf, HorseListPayload> CODEC =
            PacketCodec.of(HorseListPayload::write, HorseListPayload::new);

    public HorseListPayload {
        horses = List.copyOf(horses);
    }

    private HorseListPayload(RegistryByteBuf buf) {
        this(readEntries(buf));
    }

    private void write(RegistryByteBuf buf) {
        int size = Math.min(this.horses.size(), MAX_HORSES);
        buf.writeVarInt(size);

        for (int i = 0; i < size; i++) {
            HorseEntry entry = this.horses.get(i);

            buf.writeUuid(entry.uuid());
            buf.writeString(entry.name());
            buf.writeString(entry.dimension());
            buf.writeVarInt(entry.distance());
            buf.writeString(entry.snbt());
        }
    }

    private static List<HorseEntry> readEntries(RegistryByteBuf buf) {
        int size = Math.min(buf.readVarInt(), MAX_HORSES);
        List<HorseEntry> entries = new ArrayList<>(size);

        for (int i = 0; i < size; i++) {
            entries.add(new HorseEntry(
                    buf.readUuid(),
                    buf.readString(128),
                    buf.readString(128),
                    buf.readVarInt(),
                    buf.readString(32767)
            ));
        }

        return entries;
    }

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }

    public record HorseEntry(UUID uuid, String name, String dimension, int distance, String snbt) {
    }
}