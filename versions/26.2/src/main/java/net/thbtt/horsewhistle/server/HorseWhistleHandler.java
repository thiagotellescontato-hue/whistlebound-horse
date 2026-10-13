package net.thbtt.horsewhistle.server;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnRequest;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.equine.AbstractHorse;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
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

    private static void playWhistleSound(ServerPlayer player) {
        ServerLevel world = (ServerLevel) player.level();

        world.playSound(
                null,
                player.getX(),
                player.getY(),
                player.getZ(),
                HorseWhistleSounds.random(world.getRandom()),
                SoundSource.PLAYERS,
                1.0F,
                1.0F
        );
    }

    public static void teleportSelectedHorse(ServerPlayer player, UUID keyUuid) {
        debug(player, "Whistle pressed for horse key UUID: " + keyUuid);
        playWhistleSound(player);

        HorseWhistleStorage.StoredHorse storedHorse = HorseWhistleStorage.get(keyUuid);

        if (storedHorse == null || storedHorse.snbt() == null || storedHorse.snbt().isBlank()) {
            AbstractHorse nearbyHorse = findHorse(player.level().getServer(), keyUuid);

            if (nearbyHorse != null) {
                debug(player, "No snapshot found, but horse is loaded. Saving snapshot now.");
                HorseWhistleStorage.rememberAsKey(nearbyHorse, keyUuid);
                storedHorse = HorseWhistleStorage.get(keyUuid);
            }
        }

        if (storedHorse == null || storedHorse.snbt() == null || storedHorse.snbt().isBlank()) {
            debug(player, "No saved horse snapshot. Refresh near the horse first.");
            player.sendSystemMessage(Component.literal("No Saved Horse"), true);
            return;
        }

        ServerLevel targetWorld = (ServerLevel) player.level();
        BlockPos targetPos = findSafePositionInFrontOfPlayer(targetWorld, player);

        if (targetPos == null) {
            debug(player, "No safe position found. Horse was not moved and original was not deleted.");
            player.sendSystemMessage(Component.literal("No Safe Place For Horse"), true);
            return;
        }

        UUID activeHorseUuid = parseUuidOrNull(storedHorse.activeHorseUuid());

        AbstractHorse originalHorse = activeHorseUuid == null
                ? null
                : findHorse(player.level().getServer(), activeHorseUuid);

        if (originalHorse != null) {
            debug(player, "Original horse located while loaded.");

            if (!validateHorse(player, originalHorse)) {
                return;
            }

            HorseWhistleStorage.rememberAsKey(originalHorse, keyUuid);
            storedHorse = HorseWhistleStorage.get(keyUuid);
        } else {
            debug(player, "Original horse not located. Spawning from saved snapshot anyway.");
        }

        Entity clonedEntity = createCloneFromStoredHorse(player, storedHorse, targetWorld, targetPos);

        if (!(clonedEntity instanceof AbstractHorse clonedHorse)) {
            debug(player, "Saved NBT did not create a horse entity. Clone cancelled.");
            player.sendSystemMessage(Component.literal("Could Not Call Horse"), true);
            return;
        }

        if (!validateHorseAfterClone(player, clonedHorse)) {
            debug(player, "Clone validation failed.");
            player.sendSystemMessage(Component.literal("Could Not Call Horse"), true);
            return;
        }

        ServerLevel originalWorld = null;
        if (originalHorse != null) {
            originalWorld = (ServerLevel) originalHorse.level();
            originalHorse.discard();
            debug(player, "Original horse located and deleted.");
        }

        boolean spawned = targetWorld.tryAddFreshEntityWithPassengers(clonedHorse);

        if (!spawned) {
            if (originalWorld != null && restoreStoredHorse(storedHorse, originalWorld)) {
                debug(player, "Clone spawn failed. Original horse was restored.");
            }
            debug(player, "Clone spawn failed.");
            player.sendSystemMessage(Component.literal("Could Not Call Horse"), true);
            return;
        }

        if (originalHorse == null && activeHorseUuid != null) {
            HorseWhistleStorage.markStale(activeHorseUuid, storedHorse);
            debug(player, "Old original UUID marked for cleanup: " + activeHorseUuid);
        }

        HorseWhistleStorage.updateActiveHorse(keyUuid, clonedHorse);
        clonedHorse.playAmbientSound();
        spawnTeleportParticles(targetWorld, clonedHorse);

        debug(player, "Clone spawned in front of player.");
        debug(player, "New active horse UUID: " + clonedHorse.getStringUUID());

        player.sendSystemMessage(Component.literal(clonedHorse.getName().getString() + " came to you."), true);
    }

    public static List<HorseListPayload.HorseEntry> collectOwnedHorses(ServerPlayer player) {
        List<HorseListPayload.HorseEntry> horses = new ArrayList<>();
        Set<UUID> alreadyAddedKeys = new HashSet<>();
        UUID playerUuid = player.getUUID();

        MinecraftServer server = player.level().getServer();
        if (server == null) return List.of();

        for (ServerLevel world : server.getAllLevels()) {
            String dimension = world.dimension().identifier().toString();

            for (Entity entity : world.getAllEntities()) {
                if (!(entity instanceof AbstractHorse horse)) {
                    continue;
                }

                if (!horse.isAlive()) {
                    continue;
                }

                if (!horse.isTamed()) {
                    continue;
                }

                UUID ownerUuid = horse.getOwnerReference().getUUID();
                if (ownerUuid == null || !ownerUuid.equals(playerUuid)) {
                    continue;
                }

                Optional<String> existingKey = HorseWhistleStorage.findKeyByActiveUuid(horse.getUUID());
                UUID keyUuid = existingKey.map(UUID::fromString).orElse(horse.getUUID());

                HorseWhistleStorage.rememberAsKey(horse, keyUuid);

                int distance = player.level() == world
                        ? (int) Math.round(Math.sqrt(horse.distanceToSqr(player)))
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
                AbstractHorse activeHorse = findHorse(server, activeHorseUuid);
                if (activeHorse != null && (!activeHorse.isAlive() || !activeHorse.isTamed() || !storedOwnerUuid.equals(activeHorse.getOwnerReference().getUUID()))) {
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
            ServerPlayer player,
            HorseWhistleStorage.StoredHorse storedHorse,
            ServerLevel targetWorld,
            BlockPos targetPos
    ) {
        try {
            CompoundTag nbt = TagParser.parseCompoundFully(storedHorse.snbt());
            UUID storedUuid = parseUuidOrNull(storedHorse.activeHorseUuid());

            if (storedUuid != null) {
                nbt.putIntArray("UUID", UUIDUtil.uuidToIntArray(storedUuid));
            }

            nbt.remove("Pos");
            nbt.remove("Motion");
            nbt.remove("Rotation");
            nbt.remove("Passengers");
            nbt.remove("Leash");

            double x = targetPos.getX() + 0.5D;
            double y = targetPos.getY();
            double z = targetPos.getZ() + 0.5D;

            return EntityType.loadEntityRecursive(nbt, targetWorld, new EntitySpawnRequest(EntitySpawnReason.LOAD, false), entity -> {
                entity.snapTo(x, y, z, player.getYRot(), entity.getXRot());
                entity.setDeltaMovement(Vec3.ZERO);
                return entity;
            });
        } catch (Exception exception) {
            debug(player, "Failed to parse saved horse NBT: " + exception.getMessage());
            return null;
        }
    }

    private static boolean restoreStoredHorse(
            HorseWhistleStorage.StoredHorse storedHorse,
            ServerLevel originalWorld
    ) {
        try {
            CompoundTag nbt = TagParser.parseCompoundFully(storedHorse.snbt());
            Entity restoredEntity = EntityType.loadEntityRecursive(
                    nbt,
                    originalWorld,
                    new EntitySpawnRequest(EntitySpawnReason.LOAD, false),
                    entity -> entity
            );
            return restoredEntity instanceof AbstractHorse
                    && originalWorld.tryAddFreshEntityWithPassengers(restoredEntity);
        } catch (Exception ignored) {
            return false;
        }
    }

    private static boolean validateHorse(ServerPlayer player, AbstractHorse horse) {
        if (!horse.isAlive()) {
            debug(player, "Original horse located but is not alive.");
            player.sendSystemMessage(Component.literal("Original horse is not alive."), true);
            return false;
        }

        if (!horse.isTamed()) {
            debug(player, "Original horse located, but tame validation failed.");
            player.sendSystemMessage(Component.literal("Original horse is not tame."), true);
            return false;
        }

        if (horse.isVehicle()) {
            debug(player, "Original horse located, but it has passengers.");
            player.sendSystemMessage(Component.literal("Original horse is being ridden."), true);
            return false;
        }

        return true;
    }

    private static boolean validateHorseAfterClone(ServerPlayer player, AbstractHorse horse) {
        return horse.isAlive() && horse.isTamed();
    }

    private static AbstractHorse findHorse(MinecraftServer server, UUID horseUuid) {
        for (ServerLevel world : server.getAllLevels()) {
            Entity entity = world.getEntity(horseUuid);

            if (entity instanceof AbstractHorse horse) {
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

    private static void spawnTeleportParticles(ServerLevel world, AbstractHorse horse) {
        world.sendParticles(
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

    private static BlockPos findSafePositionInFrontOfPlayer(ServerLevel world, ServerPlayer player) {
        Vec3 look = player.getViewVector(1.0F).normalize();
        Vec3 forward = new Vec3(look.x, 0.0D, look.z);

        if (forward.lengthSqr() < 1.0E-6D) {
            forward = new Vec3(0.0D, 0.0D, 1.0D);
        } else {
            forward = forward.normalize();
        }

        Vec3 right = new Vec3(-forward.z, 0.0D, forward.x).normalize();

        BlockPos playerPos = player.blockPosition();

        int[] distances = {2, 3, 4, 5};
        int[] sideOffsets = {0, -1, 1, -2, 2};
        int[] yOffsets = {0, 1, -1, 2, -2};

        for (int distance : distances) {
            for (int sideOffset : sideOffsets) {
                for (int yOffset : yOffsets) {
                    Vec3 candidateVec = player.position()
                            .add(forward.scale(distance))
                            .add(right.scale(sideOffset));

                    BlockPos candidate = BlockPos.containing(
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
                    BlockPos candidate = playerPos.offset(xOffset, yOffset, zOffset);

                    if (isSafeHorseSpawnPosition(world, candidate, playerPos)) {
                        return candidate;
                    }
                }
            }
        }

        return null;
    }

    private static boolean isSafeHorseSpawnPosition(ServerLevel world, BlockPos feet, BlockPos playerPos) {
        if (!world.getWorldBorder().isWithinBounds(feet)) {
            return false;
        }

        if (Math.abs(feet.getY() - playerPos.getY()) > 2) {
            return false;
        }

        BlockPos groundPos = feet.below();
        BlockState groundState = world.getBlockState(groundPos);

        if (groundState.isAir()) {
            return false;
        }

        if (!groundState.isFaceSturdy(world, groundPos, Direction.UP)) {
            return false;
        }

        if (isDangerousBlock(world, groundPos)
                || isDangerousBlock(world, feet)
                || isDangerousBlock(world, feet.above())) {
            return false;
        }

        if (!world.getFluidState(feet).isEmpty()
                || !world.getFluidState(feet.above()).isEmpty()
                || world.getFluidState(groundPos).is(FluidTags.LAVA)) {
            return false;
        }

        double x = feet.getX() + 0.5D;
        double y = feet.getY();
        double z = feet.getZ() + 0.5D;

        AABB horseBox = new AABB(
                x - 0.75D,
                y,
                z - 0.75D,
                x + 0.75D,
                y + 2.0D,
                z + 0.75D
        );

        return world.noCollision(horseBox);
    }

    private static boolean isDangerousBlock(ServerLevel world, BlockPos pos) {
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

    private static void debug(ServerPlayer player, String message) {
        if (!debugEnabled) {
            return;
        }
        player.sendSystemMessage(Component.literal("ÃƒÆ’Ã†â€™Ãƒâ€ Ã¢â‚¬â„¢ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬Ãƒâ€¦Ã‚Â¡ÃƒÆ’Ã†â€™ÃƒÂ¢Ã¢â€šÂ¬Ã…Â¡ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â§e[Whistlebound Horse Debug] ÃƒÆ’Ã†â€™Ãƒâ€ Ã¢â‚¬â„¢ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬Ãƒâ€¦Ã‚Â¡ÃƒÆ’Ã†â€™ÃƒÂ¢Ã¢â€šÂ¬Ã…Â¡ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â§f" + message), false);
        player.sendSystemMessage(Component.literal(message), true);
    }
}
