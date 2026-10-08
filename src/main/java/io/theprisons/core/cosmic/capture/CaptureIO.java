package io.theprisons.core.cosmic.capture;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Reads and writes capture files (JSON). Reading checks kind and schema before binding, so an old or foreign file fails clearly. */
public final class CaptureIO {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().serializeSpecialFloatingPointValues().create();

    private CaptureIO() {
    }

    public static String toJson(Capture capture) {
        return GSON.toJson(capture);
    }

    public static void write(Path file, Capture capture) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, toJson(capture), StandardCharsets.UTF_8);
    }

    public static Capture read(Path file) throws CaptureException {
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return parse(JsonParser.parseReader(reader), file.getFileName().toString());
        } catch (IOException error) {
            throw new CaptureException("cannot read " + file.getFileName() + ": " + error.getMessage(), error);
        } catch (RuntimeException error) {
            throw new CaptureException(file.getFileName() + " is not valid JSON: " + error.getMessage(), error);
        }
    }

    public static Capture read(InputStream in, String what) throws CaptureException {
        try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            return parse(JsonParser.parseReader(reader), what);
        } catch (IOException error) {
            throw new CaptureException("cannot read " + what + ": " + error.getMessage(), error);
        } catch (RuntimeException error) {
            throw new CaptureException(what + " is not valid JSON: " + error.getMessage(), error);
        }
    }

    public static Capture fromJson(String json) throws CaptureException {
        try {
            return parse(JsonParser.parseString(json), "capture");
        } catch (RuntimeException error) {
            throw new CaptureException("capture is not valid JSON: " + error.getMessage(), error);
        }
    }

    private static Capture parse(JsonElement element, String what) throws CaptureException {
        if (element == null || !element.isJsonObject()) {
            throw new CaptureException(what + " is not a capture (not a JSON object)", false);
        }
        JsonObject root = element.getAsJsonObject();
        if (!root.has("kind") || !Capture.KIND.equals(root.get("kind").getAsString())) {
            throw new CaptureException(what + " is not a ThePrisons capture", false);
        }
        int schema = root.has("schema") ? root.get("schema").getAsInt() : 0;
        if (schema < Capture.SCHEMA) {
            throw new CaptureException("stale capture: " + what + " has schema " + schema + ", this build reads schema " + Capture.SCHEMA
                    + " - record it again", true);
        }
        if (schema > Capture.SCHEMA) {
            throw new CaptureException(what + " has schema " + schema + ", newer than this build (" + Capture.SCHEMA + ") - update ThePrisons", false);
        }
        if (!root.has("frame") || !root.get("frame").isJsonObject()) {
            throw new CaptureException(what + " has no frame", false);
        }
        try {
            return GSON.fromJson(root, Capture.class);
        } catch (RuntimeException error) {
            throw new CaptureException(what + " is malformed: " + error.getMessage(), error);
        }
    }
}
