package net.thbtt.horsewhistle.server;
 
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.serialization.Codec;
import net.minecraft.entity.passive.AbstractHorseEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.storage.NbtWriteView;
import net.minecraft.util.ErrorReporter;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.PersistentState;
import net.minecraft.world.PersistentStateManager;
import net.minecraft.world.PersistentStateType;
 
import java.util.*;
 
public final class HorseWhistleStorage extends PersistentState {
    private static final Gson GSON = new GsonBuilder().create();
    private static final Codec<HorseWhistleStorage> CODEC = Codec.STRING.xmap(
            HorseWhistleStorage::fromJson,
            HorseWhistleStorage::toJson
    );
    private static final PersistentStateType<HorseWhistleStorage> TYPE = new PersistentStateType<>(
            "horsewhistle_data",
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
 
    public static void init(ServerWorld world) {
        PersistentStateManager manager = world.getServer().getOverworld().getPersistentStateManager();
        INSTANCE = manager.getOrCreate(TYPE);
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

    public void markDirty() {
        this.setDirty(true);
    }

    public static void rememberAsKey(AbstractHorseEntity horse, UUID keyUuid) {
        if (INSTANCE == null) return;

        UUID ownerUuid = horse.getOwnerReference() == null ? null : horse.getOwnerReference().getUuid();
        if (!(horse.getEntityWorld() instanceof ServerWorld world)) return;

        NbtWriteView view = NbtWriteView.create(ErrorReporter.EMPTY);
        if (!horse.saveData(view)) return;
        NbtCompound nbt = view.getNbt();

        nbt.remove("Passengers");
        nbt.remove("Leash");
        BlockPos pos = horse.getBlockPos();

        INSTANCE.horses.put(keyUuid.toString(), new StoredHorse(
                keyUuid.toString(),
                horse.getUuidAsString(),
                ownerUuid == null ? null : ownerUuid.toString(),
                world.getRegistryKey().getValue().toString(),
                pos.getX(),
                pos.getY(),
                pos.getZ(),
                horse.getName().getString(),
                nbt.toString()
        ));
        INSTANCE.markDirty();
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
 
    public static void updateActiveHorse(UUID keyUuid, AbstractHorseEntity horse) {
        rememberAsKey(horse, keyUuid);
    }

    public static void removeByKeyUuid(UUID keyUuid) {
        if (INSTANCE == null) return;
        if (INSTANCE.horses.remove(keyUuid.toString()) != null) {
            INSTANCE.markDirty();
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
            INSTANCE.markDirty();
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
        INSTANCE.markDirty();
    }
 
    public static boolean isStale(AbstractHorseEntity horse) {
        if (INSTANCE == null) return false;
        if (INSTANCE.staleHorseUuids.contains(horse.getUuidAsString())) return true;
        if (!(horse.getEntityWorld() instanceof ServerWorld world)) return false;

        BlockPos pos = horse.getBlockPos();
        return INSTANCE.staleHorses.contains(new StaleHorse(
                horse.getUuidAsString(),
                world.getRegistryKey().getValue().toString(),
                pos.getX(),
                pos.getY(),
                pos.getZ()
        ));
    }
 
    public static boolean hasStaleHorses() {
        return INSTANCE != null && (!INSTANCE.staleHorseUuids.isEmpty() || !INSTANCE.staleHorses.isEmpty());
    }
 
    public static void removeStale(AbstractHorseEntity horse) {
        if (INSTANCE == null) return;

        boolean removed = INSTANCE.staleHorseUuids.remove(horse.getUuidAsString());
        if (horse.getEntityWorld() instanceof ServerWorld world) {
            BlockPos pos = horse.getBlockPos();
            removed |= INSTANCE.staleHorses.remove(new StaleHorse(
                    horse.getUuidAsString(),
                    world.getRegistryKey().getValue().toString(),
                    pos.getX(),
                    pos.getY(),
                    pos.getZ()
            ));
        }

        if (removed) {
            INSTANCE.markDirty();
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
