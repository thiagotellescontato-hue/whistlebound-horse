package net.thbtt.horsewhistle;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.equine.AbstractHorse;
import net.thbtt.horsewhistle.network.HorseListPayload;
import net.thbtt.horsewhistle.network.RequestHorseListPayload;
import net.thbtt.horsewhistle.network.WhistleHorsePayload;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLevelEvents;
import net.thbtt.horsewhistle.server.HorseWhistleHandler;
import net.thbtt.horsewhistle.server.HorseWhistleStorage;

import java.util.ArrayList;
import java.util.List;

public class HorseWhistle implements ModInitializer {
	public static final String MOD_ID = "horsewhistle";

	private static int staleCleanupTicker = 0;
	private static final List<Entity> pendingStaleEntities = new ArrayList<>();

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}

	@Override
	public void onInitialize() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
				dispatcher.register(Commands.literal("horsewhistle")
						.then(Commands.literal("commands")
								.then(Commands.literal("true").executes(context -> {
									HorseWhistleHandler.setDebugEnabled(true);
									context.getSource().sendSuccess(() -> Component.literal("HorseWhistle debug commands enabled."), false);
									return 1;
								}))
								.then(Commands.literal("false").executes(context -> {
									HorseWhistleHandler.setDebugEnabled(false);
									context.getSource().sendSuccess(() -> Component.literal("HorseWhistle debug commands disabled."), false);
									return 1;
								}))))
		);

		ServerLevelEvents.LOAD.register((server, world) -> {
			if (world.dimension() == ServerLevel.OVERWORLD) {
				HorseWhistleStorage.init(world);
			}
		});
		HorseWhistleSounds.init();

		ServerEntityEvents.ENTITY_LOAD.register((entity, world) -> {
			if (entity instanceof AbstractHorse horse && HorseWhistleStorage.isStale(horse)) {
				pendingStaleEntities.add(entity);
			}
	});

		ServerLivingEntityEvents.AFTER_DEATH.register((entity, damageSource) -> {
			if (entity instanceof AbstractHorse horse) {
				HorseWhistleStorage.removeByActiveHorseUuid(horse.getUUID());
			}
		});

		ServerTickEvents.END_SERVER_TICK.register(server -> {
			cleanupPendingStaleHorses();
			staleCleanupTicker++;

			if (staleCleanupTicker < 100) {
				return;
			}

			staleCleanupTicker = 0;
			cleanupLoadedStaleHorses(server);
		});

		PayloadTypeRegistry.serverboundPlay().register(WhistleHorsePayload.ID, WhistleHorsePayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(RequestHorseListPayload.ID, RequestHorseListPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(HorseListPayload.ID, HorseListPayload.CODEC);

		ServerPlayNetworking.registerGlobalReceiver(WhistleHorsePayload.ID, (payload, context) ->
				HorseWhistleHandler.teleportSelectedHorse(context.player(), payload.horseUuid())
		);

		ServerPlayNetworking.registerGlobalReceiver(RequestHorseListPayload.ID, (payload, context) -> {
			if (ServerPlayNetworking.canSend(context.player(), HorseListPayload.ID)) {
				ServerPlayNetworking.send(
						context.player(),
						new HorseListPayload(HorseWhistleHandler.collectOwnedHorses(context.player()))
				);
			}
		});
	}

	private static void cleanupPendingStaleHorses() {
		if (pendingStaleEntities.isEmpty()) {
			return;
		}

		List<Entity> entities = new ArrayList<>(pendingStaleEntities);
		pendingStaleEntities.clear();

		for (Entity entity : entities) {
			cleanupStaleHorse(entity);
		}
	}

	private static void cleanupLoadedStaleHorses(MinecraftServer server) {
		if (!HorseWhistleStorage.hasStaleHorses()) {
			return;
		}

		List<Entity> staleEntities = new ArrayList<>();

		for (ServerLevel world : server.getAllLevels()) {
			for (Entity entity : world.getAllEntities()) {
				if (!(entity instanceof AbstractHorse horse)) {
					continue;
				}

				if (HorseWhistleStorage.isStale(horse)) {
					staleEntities.add(entity);
				}
			}
		}

		for (Entity entity : staleEntities) {
			cleanupStaleHorse(entity);
		}
	}

	private static void cleanupStaleHorse(Entity entity) {
		if (!(entity instanceof AbstractHorse horse)) {
			return;
		}

		if (!HorseWhistleStorage.isStale(horse)) {
			return;
		}

		ServerPlayer owner = null;

		if (horse.getOwnerReference().getUUID() != null && entity.level().getServer() != null) {
			owner = entity.level().getServer().getPlayerList().getPlayer(horse.getOwnerReference().getUUID());
		}

		String deletedUuid = horse.getStringUUID();
		String horseName = horse.getName().getString();

		horse.discard();
		HorseWhistleStorage.removeStale(horse);

		if (owner != null && HorseWhistleHandler.isDebugEnabled()) {
			owner.sendSystemMessage(Component.literal("ÃƒÆ’Ã†â€™Ãƒâ€ Ã¢â‚¬â„¢ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬Ãƒâ€¦Ã‚Â¡ÃƒÆ’Ã†â€™ÃƒÂ¢Ã¢â€šÂ¬Ã…Â¡ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â§e[Whistlebound Horse Debug] ÃƒÆ’Ã†â€™Ãƒâ€ Ã¢â‚¬â„¢ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬Ãƒâ€¦Ã‚Â¡ÃƒÆ’Ã†â€™ÃƒÂ¢Ã¢â€šÂ¬Ã…Â¡ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â§fStale original horse loaded and deleted: " + horseName + " / " + deletedUuid), false);
			owner.sendSystemMessage(Component.literal("Stale original horse deleted."), true);
		}
	}
}
