package net.thbtt.horsewhistle.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.thbtt.horsewhistle.network.HorseListPayload;
import net.thbtt.horsewhistle.network.WhistleHorsePayload;
import org.lwjgl.glfw.GLFW;
import com.mojang.blaze3d.platform.InputConstants;
import java.util.UUID;

public class HorseWhistleClient implements ClientModInitializer {
    private static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("horsewhistle", "general"));
    private static KeyMapping whistleKey;
    private static KeyMapping openSelectionMenuKey;

    @Override
    public void onInitializeClient() {
        HorseWhistleClientConfig.load();

        whistleKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.horsewhistle.whistle",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_H,
                CATEGORY
        ));

        openSelectionMenuKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.horsewhistle.open_selection_menu",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_J,
                CATEGORY
        ));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (whistleKey.consumeClick()) {
                whistle(client);
            }

            while (openSelectionMenuKey.consumeClick()) {
                openSelectionMenu(client);
            }
        });

        ClientPlayNetworking.registerGlobalReceiver(HorseListPayload.ID, (payload, context) ->
                context.client().execute(() -> {
                    if (context.client().screen instanceof HorseWhistleConfigScreen screen) {
                        screen.setHorses(payload.horses());
                    }
                })
        );
    }

    private static void whistle(Minecraft client) {
        if (client.player == null) {
            return;
        }

        UUID selectedHorseUuid = HorseWhistleClientConfig.INSTANCE.selectedHorseUuid;

        if (selectedHorseUuid == null) {
            client.player.sendSystemMessage(Component.translatable("message.horsewhistle.no_selected_horse"));
            return;
        }

        if (!ClientPlayNetworking.canSend(WhistleHorsePayload.ID)) {
            client.player.sendSystemMessage(Component.translatable("message.horsewhistle.server_missing"));
            return;
        }

        ClientPlayNetworking.send(new WhistleHorsePayload(selectedHorseUuid));
    }

    private static void openSelectionMenu(Minecraft client) {
        if (client.player == null) {
            return;
        }

        if (client.screen instanceof HorseWhistleConfigScreen) {
            return;
        }

        client.setScreen(new HorseWhistleConfigScreen(client.screen));
    }
}
