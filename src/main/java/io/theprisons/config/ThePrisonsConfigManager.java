package io.theprisons.config;

import io.theprisons.ThePrisonsClient;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class ThePrisonsConfigManager {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir().resolve("theprisons.json");
    private static final Path LEGACY_CONFIG_PATH = FabricLoader.getInstance().getConfigDir().resolve("theprisons.json");

    private ThePrisonsConfig config = new ThePrisonsConfig();

    public ThePrisonsConfig get() {
        return config;
    }

    public void load() {
        try {
            Path sourcePath = Files.exists(CONFIG_PATH) ? CONFIG_PATH : LEGACY_CONFIG_PATH;
            if (!Files.exists(sourcePath)) {
                save();
                return;
            }

            ThePrisonsConfig loaded = GSON.fromJson(Files.readString(sourcePath), ThePrisonsConfig.class);
            config = loaded == null ? new ThePrisonsConfig() : loaded;
            config.normalize();
        } catch (Exception exception) {
            ThePrisonsClient.LOGGER.warn("Failed to load config", exception);
            config = new ThePrisonsConfig();
        }
    }

    /** Blocking write (first start / shutdown). */
    public void save() {
        config.normalize();
        write(GSON.toJson(config));
    }

    /** Serialises now, writes on the IO executor (GUI clicks, HUD drags). */
    public void saveAsync() {
        config.normalize();
        String json = GSON.toJson(config);
        net.minecraft.util.Util.getIoWorkerExecutor().execute(() -> write(json));
    }

    private static synchronized void write(String json) {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            Files.writeString(CONFIG_PATH, json);
        } catch (IOException exception) {
            ThePrisonsClient.LOGGER.warn("Failed to save config", exception);
        }
    }
}
