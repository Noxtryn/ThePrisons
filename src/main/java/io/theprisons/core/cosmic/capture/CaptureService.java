package io.theprisons.core.cosmic.capture;

import io.theprisons.core.cosmic.data.Raw;
import io.theprisons.core.cosmic.state.CosmicStateService;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Util;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Capture mode: writes the current moment (anonymised) to {@code config/theprisons/captures/}. Manual only, one capture per command,
 * and the command exists only when capture mode is on: a developer build, {@code -Dtheprisons.capture=true}, or an (empty) file
 * {@code config/theprisons/capture.enabled}. Nothing is sent anywhere; the file stays on this computer until you share it.
 */
public final class CaptureService {
    private static final Logger LOGGER = LoggerFactory.getLogger("theprisons/capture");
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private CaptureService() {
    }

    public static boolean enabled(Path dataDir) {
        return Boolean.getBoolean("theprisons.dev") || Boolean.getBoolean("theprisons.capture") || Files.exists(dataDir.resolve("capture.enabled"));
    }

    /** Takes the capture now (client thread) and writes it on the IO thread. Returns the file the capture goes to. */
    public static Path capture(MinecraftClient client, CosmicStateService cosmic, Path dataDir, String note) {
        long now = System.currentTimeMillis();
        Raw.Frame frame = cosmic.captureFrame(client);
        Capture capture = CaptureBuilder.build(frame, cosmic.memory().zone(), cosmic.memory().event(now), cosmic.memory().systemLines(),
                cosmic.model(), version("theprisons"), version("minecraft"), note, "capture");
        Path file = dataDir.resolve("captures").resolve("capture-" + STAMP.format(LocalDateTime.now()) + ".json");
        Util.getIoWorkerExecutor().execute(() -> {
            try {
                CaptureIO.write(file, capture);
                LOGGER.info("Capture written: {}", file.getFileName());
                client.execute(() -> tell(client, Text.literal("Capture saved: " + file.getFileName() + " (config/theprisons/captures)").formatted(Formatting.GREEN)));
            } catch (IOException error) {
                LOGGER.warn("Could not write the capture: {}", error.toString());
                client.execute(() -> tell(client, Text.literal("Capture failed: " + error.getMessage()).formatted(Formatting.RED)));
            }
        });
        return file;
    }

    private static void tell(MinecraftClient client, Text text) {
        if (client.player != null) {
            client.player.sendMessage(text, false);
        }
    }

    private static String version(String modId) {
        return FabricLoader.getInstance().getModContainer(modId).map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("unknown");
    }
}
