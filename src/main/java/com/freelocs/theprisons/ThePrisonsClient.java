package com.freelocs.theprisons;

import com.freelocs.theprisons.cache.ThePrisonsCache;
import com.freelocs.theprisons.config.ThePrisonsConfigManager;
import com.freelocs.theprisons.bandit.ThePrisonsBanditManager;
import com.freelocs.theprisons.feature.ThePrisonsFeatureManager;
import com.freelocs.theprisons.state.ThePrisonsTracker;
import com.freelocs.theprisons.ui.ThePrisonsHudRenderer;
import com.freelocs.theprisons.update.ThePrisonsUpdateChecker;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public final class ThePrisonsClient implements ClientModInitializer {
    public static final String MOD_ID = "theprisons";
    public static final Logger LOGGER = LoggerFactory.getLogger("ThePrisons");
    private static final String PACK_RESOURCE_PATH = "/assets/theprisons/texturepacks/§7§lPVP-Cosmic (1.21.11).zip";
    private static final String PACK_DIRECTORY_NAME = "theprisons-pvp-cosmic-1.21.11";
    private static final String PACK_REFERENCE = "file/" + PACK_DIRECTORY_NAME;
    private static final Set<String> LEGACY_PACK_REFERENCES = Set.of(
            "file/pvp-cosmic-1.21.11.zip",
            "file/§7§lPVP-Cosmic (1.21.11).zip"
    );

    public static final ThePrisonsConfigManager CONFIG = new ThePrisonsConfigManager();
    public static final ThePrisonsCache CACHE = new ThePrisonsCache();
    public static final ThePrisonsTracker TRACKER = new ThePrisonsTracker();

    @Override
    public void onInitializeClient() {
        CONFIG.load();
        CACHE.load();
        installBundledTexturePack();
        ThePrisonsFeatureManager.register();

        ClientTickEvents.END_CLIENT_TICK.register(TRACKER::tick);
        ClientTickEvents.END_CLIENT_TICK.register(ThePrisonsFeatureManager::tick);
        ClientTickEvents.END_CLIENT_TICK.register(ThePrisonsUpdateChecker::tick);
        ClientTickEvents.END_CLIENT_TICK.register(ThePrisonsBanditManager::tick);
        HudRenderCallback.EVENT.register(ThePrisonsHudRenderer::render);
        WorldRenderEvents.END_MAIN.register(ThePrisonsBanditManager::renderWorld);
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            ThePrisonsBanditManager.onGameMessage(message, overlay);
            ThePrisonsFeatureManager.onGameMessage(message, overlay);
        });
        ClientReceiveMessageEvents.CHAT.register((message, signedMessage, sender, params, receptionTimestamp) ->
        {
            ThePrisonsBanditManager.onChatMessage(message, signedMessage, sender, params, receptionTimestamp);
            ThePrisonsFeatureManager.onChatMessage(message, sender, receptionTimestamp);
        });

        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            CACHE.save();
            CONFIG.save();
        });

        LOGGER.info("ThePrisons initialized");
    }

    private static void installBundledTexturePack() {
        Path resourcePacksDir = FabricLoader.getInstance().getGameDir().resolve("resourcepacks");
        Path targetPack = resourcePacksDir.resolve(PACK_DIRECTORY_NAME);
        try {
            Files.createDirectories(resourcePacksDir);
            cleanupLegacyPackArtifacts(resourcePacksDir);
            try (InputStream in = ThePrisonsClient.class.getResourceAsStream(PACK_RESOURCE_PATH)) {
                if (in == null) {
                    LOGGER.warn("Bundled texturepack resource not found: {}", PACK_RESOURCE_PATH);
                    return;
                }
                extractBundledTexturePack(in, targetPack);
                LOGGER.info("Installed bundled texturepack to {}", targetPack);
            }

            enableBundledTexturePack();
        } catch (IOException exception) {
            LOGGER.warn("Failed to install bundled texturepack", exception);
        }
    }

    private static void cleanupLegacyPackArtifacts(Path resourcePacksDir) throws IOException {
        Files.deleteIfExists(resourcePacksDir.resolve("pvp-cosmic-1.21.11.zip"));
        Files.deleteIfExists(resourcePacksDir.resolve("§7§lPVP-Cosmic (1.21.11).zip"));
    }

    private static void extractBundledTexturePack(InputStream inputStream, Path targetDirectory) throws IOException {
        deletePathIfExists(targetDirectory);
        Files.createDirectories(targetDirectory);

        List<String> entryNames = new ArrayList<>();
        try (ZipInputStream zipInputStream = new ZipInputStream(inputStream)) {
            ZipEntry entry;
            while ((entry = zipInputStream.getNextEntry()) != null) {
                if (!entry.isDirectory()) {
                    entryNames.add(entry.getName());
                }
            }
        }

        String commonPrefix = detectCommonTopLevelFolder(entryNames);

        try (InputStream extractionInput = ThePrisonsClient.class.getResourceAsStream(PACK_RESOURCE_PATH)) {
            if (extractionInput == null) {
                throw new IOException("Bundled texturepack resource disappeared during extraction: " + PACK_RESOURCE_PATH);
            }

            try (ZipInputStream zipInputStream = new ZipInputStream(extractionInput)) {
                ZipEntry entry;
                while ((entry = zipInputStream.getNextEntry()) != null) {
                    Path outputPath = resolvePackEntry(targetDirectory, entry.getName(), commonPrefix);
                    if (outputPath == null) {
                        continue;
                    }

                    if (entry.isDirectory()) {
                        Files.createDirectories(outputPath);
                        continue;
                    }

                    Files.createDirectories(outputPath.getParent());
                    Files.copy(zipInputStream, outputPath, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }

        if (!Files.exists(targetDirectory.resolve("pack.mcmeta"))) {
            throw new IOException("Extracted texturepack is missing pack.mcmeta at " + targetDirectory);
        }
    }

    private static String detectCommonTopLevelFolder(List<String> entryNames) {
        if (entryNames.isEmpty()) {
            return "";
        }

        String firstSegment = null;
        for (String entryName : entryNames) {
            String normalized = entryName.replace('\\', '/');
            int slashIndex = normalized.indexOf('/');
            if (slashIndex <= 0) {
                return "";
            }

            String currentSegment = normalized.substring(0, slashIndex);
            if (firstSegment == null) {
                firstSegment = currentSegment;
            } else if (!firstSegment.equals(currentSegment)) {
                return "";
            }
        }

        return firstSegment == null ? "" : firstSegment;
    }

    private static Path resolvePackEntry(Path targetDirectory, String entryName, String commonPrefix) {
        String normalized = entryName.replace('\\', '/');
        if (!commonPrefix.isEmpty()) {
            String prefixWithSlash = commonPrefix + "/";
            if (!normalized.startsWith(prefixWithSlash)) {
                return null;
            }
            normalized = normalized.substring(prefixWithSlash.length());
        }

        if (normalized.isEmpty()) {
            return null;
        }

        Path resolved = targetDirectory.resolve(normalized).normalize();
        if (!resolved.startsWith(targetDirectory)) {
            return null;
        }

        return resolved;
    }

    private static void deletePathIfExists(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }

        try (var paths = Files.walk(path)) {
            paths.sorted((left, right) -> right.compareTo(left))
                    .forEach(current -> {
                        try {
                            Files.deleteIfExists(current);
                        } catch (IOException exception) {
                            throw new UncheckedIOException(exception);
                        }
                    });
        } catch (UncheckedIOException exception) {
            throw exception.getCause();
        }
    }

    private static void enableBundledTexturePack() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.options == null) {
            LOGGER.warn("Minecraft client options are not available, skipping resource pack enablement");
            return;
        }

        List<String> resourcePacks = client.options.resourcePacks;
        List<String> incompatibleResourcePacks = client.options.incompatibleResourcePacks;
        boolean changed = false;

        if (resourcePacks.removeIf(PACK_REFERENCE::equals)) {
            changed = true;
        }
        if (resourcePacks.removeIf(LEGACY_PACK_REFERENCES::contains)) {
            changed = true;
        }
        if (incompatibleResourcePacks.removeIf(PACK_REFERENCE::equals)) {
            changed = true;
        }
        if (incompatibleResourcePacks.removeIf(LEGACY_PACK_REFERENCES::contains)) {
            changed = true;
        }

        if (!resourcePacks.contains(PACK_REFERENCE)) {
            resourcePacks.add(0, PACK_REFERENCE);
            changed = true;
        }

        if (changed) {
            client.options.write();
            client.reloadResources();
            LOGGER.info("Enabled bundled texturepack reference {}", PACK_REFERENCE);
        } else {
            LOGGER.info("Bundled texturepack reference {} already active", PACK_REFERENCE);
        }
    }
}
