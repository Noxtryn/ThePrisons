package com.freelocs.theprisons.update;

import com.freelocs.theprisons.ThePrisonsClient;
import com.freelocs.theprisons.config.ThePrisonsConfig;
import com.freelocs.theprisons.ui.ThePrisonsColors;
import com.freelocs.theprisons.ui.ThePrisonsHudRenderer;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public final class ThePrisonsUpdateChecker {
    private static final URI MODRINTH_VERSIONS = URI.create("https://api.modrinth.com/v2/project/theprisons/version?loaders=%5B%22fabric%22%5D");
    private static final URI GITHUB_LATEST_RELEASE = URI.create("https://api.github.com/repos/olb-freelocs/ThePrisons/releases/latest");
    private static final String MODRINTH_PAGE = "https://modrinth.com/mod/theprisons";
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private static CompletableFuture<UpdateResult> pending;
    private static long lastCheckAtMs;
    private static boolean notifiedThisSession;

    private ThePrisonsUpdateChecker() {
    }

    public static void tick(MinecraftClient client) {
        ThePrisonsConfig config = ThePrisonsClient.CONFIG.get();
        if (!config.general.autoUpdaterEnabled) {
            return;
        }

        long now = System.currentTimeMillis();
        long intervalMs = Math.max(1L, config.general.updateCheckIntervalHours) * 3_600_000L;
        if (pending == null && (lastCheckAtMs == 0L || now - lastCheckAtMs >= intervalMs)) {
            lastCheckAtMs = now;
            pending = CompletableFuture.supplyAsync(ThePrisonsUpdateChecker::fetchLatest);
        }

        if (pending != null && pending.isDone()) {
            try {
                UpdateResult result = pending.join();
                if (result.updateAvailable && !notifiedThisSession) {
                    notifiedThisSession = true;
                    ThePrisonsHudRenderer.pushNotification("Update Available", result.latestVersion + " is available on Modrinth.", ThePrisonsColors.ACCENT_LIME);
                    if (client != null && client.player != null) {
                        client.player.sendMessage(Text.literal("[ThePrisons] Update available: " + result.latestVersion + " - " + MODRINTH_PAGE), false);
                    }
                }
            } catch (RuntimeException exception) {
                ThePrisonsClient.LOGGER.debug("ThePrisons update check failed", exception);
            } finally {
                pending = null;
            }
        }
    }

    private static UpdateResult fetchLatest() {
        UpdateResult modrinth = fetchModrinthLatest();
        if (modrinth.hasLatest()) {
            return modrinth;
        }
        return fetchGithubLatest();
    }

    private static UpdateResult fetchModrinthLatest() {
        try {
            HttpRequest request = HttpRequest.newBuilder(MODRINTH_VERSIONS)
                    .header("User-Agent", "ThePrisons/" + currentVersion())
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();
            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return UpdateResult.none();
            }

            JsonElement root = JsonParser.parseString(response.body());
            if (!root.isJsonArray()) {
                return UpdateResult.none();
            }
            JsonArray versions = root.getAsJsonArray();
            for (JsonElement element : versions) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject object = element.getAsJsonObject();
                String versionType = getString(object, "version_type").orElse("release");
                if (!"release".equalsIgnoreCase(versionType)) {
                    continue;
                }
                String latest = getString(object, "version_number").orElse("");
                if (latest.isBlank()) {
                    continue;
                }
                return new UpdateResult(isNewer(latest, currentVersion()), latest);
            }
        } catch (IOException | InterruptedException exception) {
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            ThePrisonsClient.LOGGER.debug("ThePrisons update check failed", exception);
        } catch (RuntimeException exception) {
            ThePrisonsClient.LOGGER.debug("ThePrisons update response could not be parsed", exception);
        }
        return UpdateResult.none();
    }

    private static UpdateResult fetchGithubLatest() {
        try {
            HttpRequest request = HttpRequest.newBuilder(GITHUB_LATEST_RELEASE)
                    .header("User-Agent", "ThePrisons/" + currentVersion())
                    .header("Accept", "application/vnd.github+json")
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();
            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return UpdateResult.none();
            }

            JsonElement root = JsonParser.parseString(response.body());
            if (!root.isJsonObject()) {
                return UpdateResult.none();
            }
            JsonObject object = root.getAsJsonObject();
            String latest = getString(object, "tag_name")
                    .or(() -> getString(object, "name"))
                    .orElse("");
            if (latest.isBlank() || normalizedVersion(latest).isBlank()) {
                return UpdateResult.none();
            }
            return new UpdateResult(isNewer(latest, currentVersion()), latest);
        } catch (IOException | InterruptedException exception) {
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            ThePrisonsClient.LOGGER.debug("ThePrisons GitHub update check failed", exception);
        } catch (RuntimeException exception) {
            ThePrisonsClient.LOGGER.debug("ThePrisons GitHub update response could not be parsed", exception);
        }
        return UpdateResult.none();
    }

    private static Optional<String> getString(JsonObject object, String key) {
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonPrimitive()) {
            return Optional.empty();
        }
        return Optional.ofNullable(value.getAsString());
    }

    private static String currentVersion() {
        return FabricLoader.getInstance()
                .getModContainer(ThePrisonsClient.MOD_ID)
                .map(container -> container.getMetadata().getVersion().getFriendlyString())
                .orElse("0.0.0");
    }

    private static boolean isNewer(String latest, String current) {
        if (normalizedVersion(latest).isBlank()) {
            return false;
        }
        int[] latestParts = numericParts(latest);
        int[] currentParts = numericParts(current);
        int length = Math.max(latestParts.length, currentParts.length);
        for (int i = 0; i < length; i++) {
            int left = i < latestParts.length ? latestParts[i] : 0;
            int right = i < currentParts.length ? currentParts[i] : 0;
            if (left != right) {
                return left > right;
            }
        }
        return !normalizedVersion(latest).equals(normalizedVersion(current));
    }

    private static int[] numericParts(String version) {
        String normalized = normalizedVersion(version);
        String[] parts = normalized.split("\\.");
        int[] numbers = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                numbers[i] = Integer.parseInt(parts[i].replaceAll("[^0-9].*$", ""));
            } catch (NumberFormatException exception) {
                numbers[i] = 0;
            }
        }
        return numbers;
    }

    private static String normalizedVersion(String version) {
        String normalized = version == null ? "" : version.toLowerCase(Locale.ROOT).trim();
        int separator = normalized.indexOf(" - ");
        if (separator >= 0) {
            normalized = normalized.substring(0, separator);
        }
        return normalized.replaceFirst("^[^0-9]*", "");
    }

    private record UpdateResult(boolean updateAvailable, String latestVersion) {
        private boolean hasLatest() {
            return latestVersion != null && !latestVersion.isBlank();
        }

        private static UpdateResult none() {
            return new UpdateResult(false, "");
        }
    }
}
