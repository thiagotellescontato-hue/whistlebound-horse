package net.thbtt.horsewhistle.server;
 
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.entity.passive.AbstractHorseEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.PersistentState;
import net.minecraft.world.PersistentStateManager;
 
import java.util.*;
 
public final class HorseWhistleStorage extends PersistentState {
    private static final Gson GSON = new GsonBuilder().create();
    private static HorseWhistleStorage INSTANCE;
 
    private final Map<String, StoredHorse> horses = new HashMap<>();
    private final Set<String> staleHorseUuids = new HashSet<>();
 
    private HorseWhistleStorage() {
    }
 
    public static void init(ServerWorld world) {
        PersistentStateManager manager = world.getServer().getOverworld().getPersistentStateManager();
        INSTANCE = manager.getOrCreate(new Type<>(
                HorseWhistleStorage::new,
                (nbt, registries) -> HorseWhistleStorage.fromNbt(nbt),
                null
        ), "horsewhistle_data");
    }
 
    public static HorseWhistleStorage fromNbt(NbtCompound nbt) {
        HorseWhistleStorage storage = new HorseWhistleStorage();
        if (nbt.contains("horses")) {
            NbtCompound horsesNbt = nbt.getCompound("horses");
            for (String key : horsesNbt.getKeys()) {
                String json = horsesNbt.getString(key);
                storage.horses.put(key, GSON.fromJson(json, StoredHorse.class));
            }
        }
        if (nbt.contains("stale_horses")) {
            NbtCompound staleNbt = nbt.getCompound("stale_horses");
            for (String key : staleNbt.getKeys()) {
                storage.staleHorseUuids.add(key);
            }
        }
        return storage;
    }
 
    @Override
    public NbtCompound writeNbt(NbtCompound nbt, net.minecraft.registry.RegistryWrapper.WrapperLookup registries) {
        NbtCompound horsesNbt = new NbtCompound();
        for (Map.Entry<String, StoredHorse> entry : horses.entrySet()) {
            horsesNbt.putString(entry.getKey(), GSON.toJson(entry.getValue()));
        }
        nbt.put("horses", horsesNbt);

        NbtCompound staleNbt = new NbtCompound();
        for (String uuid : staleHorseUuids) {
            staleNbt.putBoolean(uuid, true);
        }
        nbt.put("stale_horses", staleNbt);
        return nbt;
    }

    public void markDirty() {
        this.setDirty(true);
    }

    public static void rememberAsKey(AbstractHorseEntity horse, UUID keyUuid) {
        if (INSTANCE == null) return;

        UUID ownerUuid = horse.getOwnerUuid();
        if (!(horse.getWorld() instanceof ServerWorld world)) return;

        NbtCompound nbt = new NbtCompound();
        if (!horse.saveNbt(nbt)) return;

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

    public static void markStale(UUID horseUuid) {
        if (INSTANCE == null) return;
        INSTANCE.staleHorseUuids.add(horseUuid.toString());
        INSTANCE.markDirty();
    }
 
    public static boolean isStale(UUID horseUuid) {
        return INSTANCE != null && INSTANCE.staleHorseUuids.contains(horseUuid.toString());
    }
 
    public static boolean hasStaleHorses() {
        return INSTANCE != null && !INSTANCE.staleHorseUuids.isEmpty();
    }
 
    public static void removeStale(UUID horseUuid) {
        if (INSTANCE == null) return;
        INSTANCE.staleHorseUuids.remove(horseUuid.toString());
        INSTANCE.markDirty();
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
}