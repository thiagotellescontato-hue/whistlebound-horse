package net.thbtt.horsewhistle.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import net.thbtt.horsewhistle.network.HorseListPayload;
import net.thbtt.horsewhistle.network.WhistleHorsePayload;
import org.lwjgl.glfw.GLFW;

import java.util.UUID;

public class HorseWhistleClient implements ClientModInitializer {
    private static KeyBinding whistleKey;
    private static KeyBinding openSelectionMenuKey;

    @Override
    public void onInitializeClient() {
        HorseWhistleClientConfig.load();

        whistleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.horsewhistle.whistle",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_H,
                "category.horsewhistle"
        ));

        openSelectionMenuKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.horsewhistle.open_selection_menu",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_J,
                "category.horsewhistle"
        ));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (whistleKey.wasPressed()) {
                whistle(client);
            }

            while (openSelectionMenuKey.wasPressed()) {
                openSelectionMenu(client);
            }
        });

        ClientPlayNetworking.registerGlobalReceiver(HorseListPayload.ID, (payload, context) ->
                context.client().execute(() -> {
                    if (context.client().currentScreen instanceof HorseWhistleConfigScreen screen) {
                        screen.setHorses(payload.horses());
                    }
                })
        );
    }

    private static void whistle(MinecraftClient client) {
        if (client.player == null) {
            return;
        }

        UUID selectedHorseUuid = HorseWhistleClientConfig.INSTANCE.selectedHorseUuid;

        if (selectedHorseUuid == null) {
            client.player.sendMessage(Text.translatable("message.horsewhistle.no_selected_horse"), true);
            return;
        }

        if (!ClientPlayNetworking.canSend(WhistleHorsePayload.ID)) {
            client.player.sendMessage(Text.translatable("message.horsewhistle.server_missing"), true);
            return;
        }

        ClientPlayNetworking.send(new WhistleHorsePayload(selectedHorseUuid));
    }

    private static void openSelectionMenu(MinecraftClient client) {
        if (client.player == null) {
            return;
        }

        if (client.currentScreen instanceof HorseWhistleConfigScreen) {
            return;
        }

        client.setScreen(new HorseWhistleConfigScreen(client.currentScreen));
    }
}