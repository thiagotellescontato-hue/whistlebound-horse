package net.thbtt.horsewhistle;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.command.CommandManager;
import net.minecraft.entity.Entity;
import net.minecraft.entity.passive.AbstractHorseEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.thbtt.horsewhistle.network.HorseListPayload;
import net.thbtt.horsewhistle.network.RequestHorseListPayload;
import net.thbtt.horsewhistle.network.WhistleHorsePayload;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerWorldEvents;
import net.thbtt.horsewhistle.server.HorseWhistleHandler;
import net.thbtt.horsewhistle.server.HorseWhistleStorage;

import java.util.ArrayList;
import java.util.List;

public class HorseWhistle implements ModInitializer {
	public static final String MOD_ID = "horsewhistle";

	private static int staleCleanupTicker = 0;

	public static Identifier id(String path) {
		return Identifier.of(MOD_ID, path);
	}

	@Override
	public void onInitialize() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
				dispatcher.register(CommandManager.literal("horsewhistle")
						.then(CommandManager.literal("commands")
								.then(CommandManager.literal("true").executes(context -> {
									HorseWhistleHandler.setDebugEnabled(true);
									context.getSource().sendFeedback(() -> Text.literal("HorseWhistle debug commands enabled."), false);
									return 1;
								}))
								.then(CommandManager.literal("false").executes(context -> {
									HorseWhistleHandler.setDebugEnabled(false);
									context.getSource().sendFeedback(() -> Text.literal("HorseWhistle debug commands disabled."), false);
									return 1;
								}))))
		);

		ServerWorldEvents.LOAD.register((server, world) -> {
			if (world.getRegistryKey() == ServerWorld.OVERWORLD) {
				HorseWhistleStorage.init(world);
			}
		});
		HorseWhistleSounds.init();

		ServerEntityEvents.ENTITY_LOAD.register((entity, world) -> {
			cleanupStaleHorse(entity);
		});

		ServerLivingEntityEvents.AFTER_DEATH.register((entity, damageSource) -> {
			if (entity instanceof AbstractHorseEntity horse) {
				HorseWhistleStorage.removeByActiveHorseUuid(horse.getUuid());
			}
		});

		ServerTickEvents.END_SERVER_TICK.register(server -> {
			staleCleanupTicker++;

			if (staleCleanupTicker < 100) {
				return;
			}

			staleCleanupTicker = 0;
			cleanupLoadedStaleHorses(server);
		});

		PayloadTypeRegistry.playC2S().register(WhistleHorsePayload.ID, WhistleHorsePayload.CODEC);
		PayloadTypeRegistry.playC2S().register(RequestHorseListPayload.ID, RequestHorseListPayload.CODEC);
		PayloadTypeRegistry.playS2C().register(HorseListPayload.ID, HorseListPayload.CODEC);

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

	private static void cleanupLoadedStaleHorses(MinecraftServer server) {
		if (!HorseWhistleStorage.hasStaleHorses()) {
			return;
		}

		List<Entity> staleEntities = new ArrayList<>();

		for (ServerWorld world : server.getWorlds()) {
			for (Entity entity : world.iterateEntities()) {
				if (!(entity instanceof AbstractHorseEntity horse)) {
					continue;
				}

				if (HorseWhistleStorage.isStale(horse.getUuid())) {
					staleEntities.add(entity);
				}
			}
		}

		for (Entity entity : staleEntities) {
			cleanupStaleHorse(entity);
		}
	}

	private static void cleanupStaleHorse(Entity entity) {
		if (!(entity instanceof AbstractHorseEntity horse)) {
			return;
		}

		if (!HorseWhistleStorage.isStale(horse.getUuid())) {
			return;
		}

		ServerPlayerEntity owner = null;

		if (horse.getOwnerUuid() != null && entity.getServer() != null) {
			owner = entity.getServer().getPlayerManager().getPlayer(horse.getOwnerUuid());
		}

		String deletedUuid = horse.getUuidAsString();
		String horseName = horse.getName().getString();

		horse.discard();
		HorseWhistleStorage.removeStale(horse.getUuid());

		if (owner != null && HorseWhistleHandler.isDebugEnabled()) {
			owner.sendMessage(Text.literal("§e[Horse Whistle Debug] §fStale original horse loaded and deleted: " + horseName + " / " + deletedUuid), false);
			owner.sendMessage(Text.literal("Stale original horse deleted."), true);
		}
	}
}