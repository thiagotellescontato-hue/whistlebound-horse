package net.thbtt.horsewhistle.server;
 
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.serialization.Codec;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.animal.equine.AbstractHorse;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.minecraft.world.level.storage.SavedDataStorage;
import net.minecraft.world.level.storage.TagValueOutput;
import net.thbtt.horsewhistle.HorseWhistle;
 
public final class HorseWhistleStorage extends SavedData {
    private static final Gson GSON = new GsonBuilder().create();
    private static final Codec<HorseWhistleStorage> CODEC = Codec.STRING.xmap(
            HorseWhistleStorage::fromJson,
            HorseWhistleStorage::toJson
    );
    private static final SavedDataType<HorseWhistleStorage> TYPE = new SavedDataType<>(
            HorseWhistle.id("data"),
            HorseWhistleStorage::new,
            CODEC,
            null
    );
    private static HorseWhistleStorage INSTANCE;
 
    private final Map<String, StoredHorse> horses = new HashMap<>();
    private final Set<String> staleHorseUuids = new HashSet<>();
    private final Set<StaleHorse> staleHorses = new HashSet<>();
 
    private HorseWhistleStorage() {
    }
 
    public static void init(ServerLevel world) {
        SavedDataStorage manager = world.getServer().overworld().getDataStorage();
        INSTANCE = manager.computeIfAbsent(TYPE);
    }
 
    private static HorseWhistleStorage fromJson(String json) {
        HorseWhistleStorage storage = new HorseWhistleStorage();
        StorageData data = GSON.fromJson(json, StorageData.class);
        if (data != null) {
            if (data.horses() != null) storage.horses.putAll(data.horses());
            if (data.staleHorseUuids() != null) storage.staleHorseUuids.addAll(data.staleHorseUuids());
            if (data.staleHorses() != null) storage.staleHorses.addAll(data.staleHorses());
        }
        return storage;
    }

    private static String toJson(HorseWhistleStorage storage) {
        return GSON.toJson(new StorageData(storage.horses, storage.staleHorseUuids, storage.staleHorses));
    }

    public void setDirty() {
        this.setDirty(true);
    }

    public static void rememberAsKey(AbstractHorse horse, UUID keyUuid) {
        if (INSTANCE == null) return;

        UUID ownerUuid = horse.getOwnerReference() == null ? null : horse.getOwnerReference().getUUID();
        if (!(horse.level() instanceof ServerLevel world)) return;

        TagValueOutput view = TagValueOutput.createWithoutContext(ProblemReporter.DISCARDING);
        if (!horse.save(view)) return;
        CompoundTag nbt = view.buildResult();

        nbt.remove("Passengers");
        nbt.remove("Leash");
        BlockPos pos = horse.blockPosition();

        INSTANCE.horses.put(keyUuid.toString(), new StoredHorse(
                keyUuid.toString(),
                horse.getStringUUID(),
                ownerUuid == null ? null : ownerUuid.toString(),
                world.dimension().identifier().toString(),
                pos.getX(),
                pos.getY(),
                pos.getZ(),
                horse.getName().getString(),
                nbt.toString()
        ));
        INSTANCE.setDirty();
    }
 
    public static StoredHorse get(UUID keyUuid) {
        return INSTANCE == null ? null : INSTANCE.horses.get(keyUuid.toString());
    }
 
    public static List<StoredHorse> getOwnedBy(UUID ownerUuid) {
        if (INSTANCE == null) return List.of();
        String owner = ownerUuid.toString();
        return INSTANCE.horses.values().stream()
                .filter(horse -> horse.ownerUuid() != null)
                .filter(horse -> horse.ownerUuid().equals(owner))
                .toList();
    }

    public static List<StoredHorse> getAllStored() {
        if (INSTANCE == null) return List.of();
        return List.copyOf(INSTANCE.horses.values());
    }
 
    public static Optional<String> findKeyByActiveUuid(UUID activeUuid) {
        if (INSTANCE == null) return Optional.empty();
        String active = activeUuid.toString();
        return INSTANCE.horses.values().stream()
                .filter(horse -> horse.activeHorseUuid() != null)
                .filter(horse -> horse.activeHorseUuid().equals(active))
                .map(StoredHorse::keyUuid)
                .findFirst();
    }
 
    public static void updateActiveHorse(UUID keyUuid, AbstractHorse horse) {
        rememberAsKey(horse, keyUuid);
    }

    public static void removeByKeyUuid(UUID keyUuid) {
        if (INSTANCE == null) return;
        if (INSTANCE.horses.remove(keyUuid.toString()) != null) {
            INSTANCE.setDirty();
        }
    }

    public static void removeByActiveHorseUuid(UUID horseUuid) {
        if (INSTANCE == null) return;

        String uuid = horseUuid.toString();
        boolean removed = INSTANCE.horses.entrySet().removeIf(entry -> {
            StoredHorse storedHorse = entry.getValue();
            if (storedHorse == null) {
                return false;
            }
            return uuid.equals(entry.getKey()) || uuid.equals(storedHorse.activeHorseUuid());
        });

        if (INSTANCE.staleHorseUuids.remove(uuid)) {
            removed = true;
        }

        if (removed) {
            INSTANCE.setDirty();
        }
    }

    public static void markStale(UUID horseUuid, StoredHorse storedHorse) {
        if (INSTANCE == null) return;
        INSTANCE.staleHorses.add(new StaleHorse(
                horseUuid.toString(),
                storedHorse.dimension(),
                storedHorse.x(),
                storedHorse.y(),
                storedHorse.z()
        ));
        INSTANCE.setDirty();
    }
 
    public static boolean isStale(AbstractHorse horse) {
        if (INSTANCE == null) return false;
        if (INSTANCE.staleHorseUuids.contains(horse.getStringUUID())) return true;
        if (!(horse.level() instanceof ServerLevel world)) return false;

        BlockPos pos = horse.blockPosition();
        return INSTANCE.staleHorses.contains(new StaleHorse(
                horse.getStringUUID(),
                world.dimension().identifier().toString(),
                pos.getX(),
                pos.getY(),
                pos.getZ()
        ));
    }
 
    public static boolean hasStaleHorses() {
        return INSTANCE != null && (!INSTANCE.staleHorseUuids.isEmpty() || !INSTANCE.staleHorses.isEmpty());
    }
 
    public static void removeStale(AbstractHorse horse) {
        if (INSTANCE == null) return;

        boolean removed = INSTANCE.staleHorseUuids.remove(horse.getStringUUID());
        if (horse.level() instanceof ServerLevel world) {
            BlockPos pos = horse.blockPosition();
            removed |= INSTANCE.staleHorses.remove(new StaleHorse(
                    horse.getStringUUID(),
                    world.dimension().identifier().toString(),
                    pos.getX(),
                    pos.getY(),
                    pos.getZ()
            ));
        }

        if (removed) {
            INSTANCE.setDirty();
        }
    }

    private record StaleHorse(
            String uuid,
            String dimension,
            int x,
            int y,
            int z
    ) {
    }
 
    public record StoredHorse(
            String keyUuid,
            String activeHorseUuid,
            String ownerUuid,
            String dimension,
            int x,
            int y,
            int z,
            String name,
            String snbt
    ) {
    }

    private record StorageData(
            Map<String, StoredHorse> horses,
            Set<String> staleHorseUuids,
            Set<StaleHorse> staleHorses
    ) {
    }
}
