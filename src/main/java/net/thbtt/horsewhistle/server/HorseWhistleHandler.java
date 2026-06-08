package net.thbtt.horsewhistle.server;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.passive.AbstractHorseEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.StringNbtReader;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.thbtt.horsewhistle.HorseWhistleSounds;
import net.thbtt.horsewhistle.network.HorseListPayload;

import java.util.*;

public final class HorseWhistleHandler {
    private static boolean debugEnabled = false;

    private HorseWhistleHandler() {
    }

    public static boolean isDebugEnabled() {
        return debugEnabled;
    }

    public static void setDebugEnabled(boolean enabled) {
        debugEnabled = enabled;
    }

    private static void playWhistleSound(ServerPlayerEntity player) {
        ServerWorld world = player.getServerWorld();

        world.playSound(
                null,
                player.getX(),
                player.getY(),
                player.getZ(),
                HorseWhistleSounds.random(world.getRandom()),
                SoundCategory.PLAYERS,
                1.0F,
                1.0F
        );
    }

    public static void teleportSelectedHorse(ServerPlayerEntity player, UUID keyUuid) {
        debug(player, "Whistle pressed for horse key UUID: " + keyUuid);
        playWhistleSound(player);

        HorseWhistleStorage.StoredHorse storedHorse = HorseWhistleStorage.get(keyUuid);

        if (storedHorse == null || storedHorse.snbt() == null || storedHorse.snbt().isBlank()) {
            AbstractHorseEntity nearbyHorse = findHorse(player.getServer(), keyUuid);

            if (nearbyHorse != null) {
                debug(player, "No snapshot found, but horse is loaded. Saving snapshot now.");
                HorseWhistleStorage.rememberAsKey(nearbyHorse, keyUuid);
                storedHorse = HorseWhistleStorage.get(keyUuid);
            }
        }

        if (storedHorse == null || storedHorse.snbt() == null || storedHorse.snbt().isBlank()) {
            debug(player, "No saved horse snapshot. Refresh near the horse first.");
            player.sendMessage(Text.literal("No Saved Horse"), true);
            return;
        }

        ServerWorld targetWorld = player.getServerWorld();
        BlockPos targetPos = findSafePositionInFrontOfPlayer(targetWorld, player);

        if (targetPos == null) {
            debug(player, "No safe position found. Horse was not moved and original was not deleted.");
            player.sendMessage(Text.literal("No Safe Place For Horse"), true);
            return;
        }

        UUID activeHorseUuid = parseUuidOrNull(storedHorse.activeHorseUuid());

        AbstractHorseEntity originalHorse = activeHorseUuid == null
                ? null
                : findHorse(player.getServer(), activeHorseUuid);

        if (originalHorse != null) {
            debug(player, "Original horse located while loaded.");

            if (!validateHorse(player, originalHorse)) {
                return;
            }

            HorseWhistleStorage.rememberAsKey(originalHorse, keyUuid);
            storedHorse = HorseWhistleStorage.get(keyUuid);

            originalHorse.discard();
            debug(player, "Original horse located and deleted.");
        } else {
            debug(player, "Original horse not located. Spawning from saved snapshot anyway.");

            if (activeHorseUuid != null) {
                HorseWhistleStorage.markStale(activeHorseUuid);
                debug(player, "Old original UUID marked for cleanup: " + activeHorseUuid);
            }
        }

        Entity clonedEntity = createCloneFromStoredHorse(player, storedHorse, targetWorld, targetPos);

        if (!(clonedEntity instanceof AbstractHorseEntity clonedHorse)) {
            debug(player, "Saved NBT did not create a horse entity. Clone cancelled.");
            player.sendMessage(Text.literal("Could Not Call Horse"), true);
            return;
        }

        if (!validateHorseAfterClone(player, clonedHorse)) {
            debug(player, "Clone validation failed.");
            player.sendMessage(Text.literal("Could Not Call Horse"), true);
            return;
        }

        boolean spawned = targetWorld.spawnNewEntityAndPassengers(clonedHorse);

        if (!spawned) {
            debug(player, "Clone spawn failed.");
            player.sendMessage(Text.literal("Could Not Call Horse"), true);
            return;
        }

        HorseWhistleStorage.updateActiveHorse(keyUuid, clonedHorse);
        clonedHorse.playAmbientSound();
        spawnTeleportParticles(targetWorld, clonedHorse);

        debug(player, "Clone spawned in front of player.");
        debug(player, "New active horse UUID: " + clonedHorse.getUuidAsString());

        player.sendMessage(Text.literal(clonedHorse.getName().getString() + " came to you."), true);
    }

    public static List<HorseListPayload.HorseEntry> collectOwnedHorses(ServerPlayerEntity player) {
        List<HorseListPayload.HorseEntry> horses = new ArrayList<>();
        Set<UUID> alreadyAddedKeys = new HashSet<>();
        UUID playerUuid = player.getUuid();

        MinecraftServer server = player.getServer();
        if (server == null) return List.of();

        for (ServerWorld world : server.getWorlds()) {
            String dimension = world.getRegistryKey().getValue().toString();

            for (Entity entity : world.iterateEntities()) {
                if (!(entity instanceof AbstractHorseEntity horse)) {
                    continue;
                }

                if (!horse.isAlive()) {
                    continue;
                }

                if (!horse.isTame()) {
                    continue;
                }

                UUID ownerUuid = horse.getOwnerUuid();
                if (ownerUuid == null || !ownerUuid.equals(playerUuid)) {
                    continue;
                }

                Optional<String> existingKey = HorseWhistleStorage.findKeyByActiveUuid(horse.getUuid());
                UUID keyUuid = existingKey.map(UUID::fromString).orElse(horse.getUuid());

                HorseWhistleStorage.rememberAsKey(horse, keyUuid);

                int distance = player.getWorld() == world
                        ? (int) Math.round(Math.sqrt(horse.squaredDistanceTo(player)))
                        : -1;

                horses.add(new HorseListPayload.HorseEntry(
                        keyUuid,
                        horse.getName().getString(),
                        dimension,
                        distance,
                        HorseWhistleStorage.get(keyUuid).snbt()
                ));

                alreadyAddedKeys.add(keyUuid);
            }
        }

        for (HorseWhistleStorage.StoredHorse storedHorse : HorseWhistleStorage.getAllStored()) {
            UUID keyUuid;

            try {
                keyUuid = UUID.fromString(storedHorse.keyUuid());
            } catch (IllegalArgumentException ignored) {
                continue;
            }

            if (alreadyAddedKeys.contains(keyUuid)) {
                continue;
            }

            UUID storedOwnerUuid = parseUuidOrNull(storedHorse.ownerUuid());
            if (storedOwnerUuid == null || !storedOwnerUuid.equals(playerUuid)) {
                HorseWhistleStorage.removeByKeyUuid(keyUuid);
                continue;
            }

            UUID activeHorseUuid = parseUuidOrNull(storedHorse.activeHorseUuid());
            if (activeHorseUuid != null) {
                AbstractHorseEntity activeHorse = findHorse(server, activeHorseUuid);
                if (activeHorse != null && (!activeHorse.isAlive() || !activeHorse.isTame() || !storedOwnerUuid.equals(activeHorse.getOwnerUuid()))) {
                    HorseWhistleStorage.removeByKeyUuid(keyUuid);
                    continue;
                }
            }

            horses.add(new HorseListPayload.HorseEntry(
                    keyUuid,
                    storedHorse.name() == null || storedHorse.name().isBlank() ? "Saved Horse" : storedHorse.name(),
                    storedHorse.dimension() == null ? "unknown" : storedHorse.dimension(),
                    -1,
                    storedHorse.snbt()
            ));
        }

        horses.sort(Comparator
                .comparingInt((HorseListPayload.HorseEntry entry) ->
                        entry.distance() < 0 ? Integer.MAX_VALUE : entry.distance())
                .thenComparing(HorseListPayload.HorseEntry::name));

        return horses;
    }

    private static Entity createCloneFromStoredHorse(
            ServerPlayerEntity player,
            HorseWhistleStorage.StoredHorse storedHorse,
            ServerWorld targetWorld,
            BlockPos targetPos
    ) {
        try {
            NbtCompound nbt = StringNbtReader.parse(storedHorse.snbt());

            nbt.remove("UUID");

            nbt.remove("Pos");
            nbt.remove("Motion");
            nbt.remove("Rotation");
            nbt.remove("Passengers");
            nbt.remove("Leash");

            double x = targetPos.getX() + 0.5D;
            double y = targetPos.getY();
            double z = targetPos.getZ() + 0.5D;

            return EntityType.loadEntityWithPassengers(nbt, targetWorld, entity -> {
                entity.refreshPositionAndAngles(x, y, z, player.getYaw(), entity.getPitch());
                entity.setVelocity(Vec3d.ZERO);
                return entity;
            });
        } catch (Exception exception) {
            debug(player, "Failed to parse saved horse NBT: " + exception.getMessage());
            return null;
        }
    }

    private static boolean validateHorse(ServerPlayerEntity player, AbstractHorseEntity horse) {
        if (!horse.isAlive()) {
            debug(player, "Original horse located but is not alive.");
            player.sendMessage(Text.literal("Original horse is not alive."), true);
            return false;
        }

        if (!horse.isTame()) {
            debug(player, "Original horse located, but tame validation failed.");
            player.sendMessage(Text.literal("Original horse is not tame."), true);
            return false;
        }

        if (horse.hasPassengers()) {
            debug(player, "Original horse located, but it has passengers.");
            player.sendMessage(Text.literal("Original horse is being ridden."), true);
            return false;
        }

        return true;
    }

    private static boolean validateHorseAfterClone(ServerPlayerEntity player, AbstractHorseEntity horse) {
        return horse.isAlive() && horse.isTame();
    }

    private static AbstractHorseEntity findHorse(MinecraftServer server, UUID horseUuid) {
        for (ServerWorld world : server.getWorlds()) {
            Entity entity = world.getEntity(horseUuid);

            if (entity instanceof AbstractHorseEntity horse) {
                return horse;
            }
        }

        return null;
    }

    private static UUID parseUuidOrNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }

        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static void spawnTeleportParticles(ServerWorld world, AbstractHorseEntity horse) {
        world.spawnParticles(
                ParticleTypes.CLOUD,
                horse.getX(),
                horse.getY() + 1.0D,
                horse.getZ(),
                26,
                0.7D,
                0.4D,
                0.7D,
                0.02D
        );
    }

    private static BlockPos findSafePositionInFrontOfPlayer(ServerWorld world, ServerPlayerEntity player) {
        Vec3d look = player.getRotationVec(1.0F).normalize();
        Vec3d forward = new Vec3d(look.x, 0.0D, look.z);

        if (forward.lengthSquared() < 1.0E-6D) {
            forward = new Vec3d(0.0D, 0.0D, 1.0D);
        } else {
            forward = forward.normalize();
        }

        Vec3d right = new Vec3d(-forward.z, 0.0D, forward.x).normalize();

        BlockPos playerPos = player.getBlockPos();

        int[] distances = {2, 3, 4, 5};
        int[] sideOffsets = {0, -1, 1, -2, 2};
        int[] yOffsets = {0, 1, -1, 2, -2};

        for (int distance : distances) {
            for (int sideOffset : sideOffsets) {
                for (int yOffset : yOffsets) {
                    Vec3d candidateVec = player.getPos()
                            .add(forward.multiply(distance))
                            .add(right.multiply(sideOffset));

                    BlockPos candidate = BlockPos.ofFloored(
                            candidateVec.x,
                            playerPos.getY() + yOffset,
                            candidateVec.z
                    );

                    if (isSafeHorseSpawnPosition(world, candidate, playerPos)) {
                        return candidate;
                    }
                }
            }
        }

        // Fallback pequeno perto do player, mas ainda sem aceitar locais ruins.
        for (int xOffset = -2; xOffset <= 2; xOffset++) {
            for (int zOffset = -2; zOffset <= 2; zOffset++) {
                for (int yOffset : yOffsets) {
                    BlockPos candidate = playerPos.add(xOffset, yOffset, zOffset);

                    if (isSafeHorseSpawnPosition(world, candidate, playerPos)) {
                        return candidate;
                    }
                }
            }
        }

        return null;
    }

    private static boolean isSafeHorseSpawnPosition(ServerWorld world, BlockPos feet, BlockPos playerPos) {
        if (!world.getWorldBorder().contains(feet)) {
            return false;
        }

        if (Math.abs(feet.getY() - playerPos.getY()) > 2) {
            return false;
        }

        BlockPos groundPos = feet.down();
        BlockState groundState = world.getBlockState(groundPos);

        if (groundState.isAir()) {
            return false;
        }

        if (!groundState.isSideSolidFullSquare(world, groundPos, Direction.UP)) {
            return false;
        }

        if (isDangerousBlock(world, groundPos)
                || isDangerousBlock(world, feet)
                || isDangerousBlock(world, feet.up())) {
            return false;
        }

        if (!world.getFluidState(feet).isEmpty()
                || !world.getFluidState(feet.up()).isEmpty()
                || world.getFluidState(groundPos).isIn(FluidTags.LAVA)) {
            return false;
        }

        double x = feet.getX() + 0.5D;
        double y = feet.getY();
        double z = feet.getZ() + 0.5D;

        Box horseBox = new Box(
                x - 0.75D,
                y,
                z - 0.75D,
                x + 0.75D,
                y + 2.0D,
                z + 0.75D
        );

        return world.isSpaceEmpty(horseBox);
    }

    private static boolean isDangerousBlock(ServerWorld world, BlockPos pos) {
        Block block = world.getBlockState(pos).getBlock();

        return block == Blocks.LAVA
                || block == Blocks.FIRE
                || block == Blocks.SOUL_FIRE
                || block == Blocks.MAGMA_BLOCK
                || block == Blocks.CACTUS
                || block == Blocks.CAMPFIRE
                || block == Blocks.SOUL_CAMPFIRE
                || block == Blocks.SWEET_BERRY_BUSH
                || block == Blocks.POWDER_SNOW;
    }

    private static void debug(ServerPlayerEntity player, String message) {
        if (!debugEnabled) {
            return;
        }
        player.sendMessage(Text.literal("§e[Horse Whistle Debug] §f" + message), false);
        player.sendMessage(Text.literal(message), true);
    }
}