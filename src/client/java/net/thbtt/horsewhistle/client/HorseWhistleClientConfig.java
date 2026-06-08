package net.thbtt.horsewhistle.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

public final class HorseWhistleClientConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static final Path CONFIG_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("horsewhistle.json");

    public static final HorseWhistleClientConfig INSTANCE = new HorseWhistleClientConfig();

    public UUID selectedHorseUuid = null;
    public String selectedHorseName = "";

    private HorseWhistleClientConfig() {
    }

    public static void load() {
        if (!Files.exists(CONFIG_PATH)) {
            return;
        }

        try (Reader reader = Files.newBufferedReader(CONFIG_PATH)) {
            HorseWhistleClientConfig loaded = GSON.fromJson(reader, HorseWhistleClientConfig.class);

            if (loaded != null) {
                INSTANCE.selectedHorseUuid = loaded.selectedHorseUuid;
                INSTANCE.selectedHorseName = loaded.selectedHorseName == null ? "" : loaded.selectedHorseName;
            }
        } catch (IOException ignored) {
        }
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());

            try (Writer writer = Files.newBufferedWriter(CONFIG_PATH)) {
                GSON.toJson(this, writer);
            }
        } catch (IOException ignored) {
        }
    }

    public void selectHorse(UUID uuid, String name) {
        this.selectedHorseUuid = uuid;
        this.selectedHorseName = name == null ? "" : name;
        save();
    }

    public void clearHorse() {
        this.selectedHorseUuid = null;
        this.selectedHorseName = "";
        save();
    }
}