package io.theprisons.core.cosmic.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import io.theprisons.core.cosmic.value.Confidence;
import io.theprisons.core.cosmic.value.GameValue;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The JSON of the registries and of the knowledge file. Both are plain data the owner can extend without code:
 * <pre>{"schema":1,"entries":[{"id":"redstone","name":"Redstone","attributes":{"key":{"value":1,"confidence":"OBSERVED","source":"..","note":".."}}}]}</pre>
 * A missing "value" means UNKNOWN; an unrecognised confidence is an error (data must not guess).
 */
public final class RegistryData {
    public static final int SCHEMA = 1;

    private RegistryData() {
    }

    public static JsonObject read(InputStream in, String what) {
        if (in == null) {
            throw new IllegalStateException("missing resource: " + what);
        }
        try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            int schema = root.has("schema") ? root.get("schema").getAsInt() : 0;
            if (schema != SCHEMA) {
                throw new IllegalStateException(what + ": unsupported schema " + schema + " (expected " + SCHEMA + ")");
            }
            return root;
        } catch (IOException | RuntimeException error) {
            if (error instanceof IllegalStateException state) {
                throw state;
            }
            throw new IllegalStateException(what + ": " + error.getMessage(), error);
        }
    }

    public static List<Entry> entries(JsonObject root, String what) {
        List<Entry> list = new ArrayList<>();
        JsonArray array = root.has("entries") ? root.getAsJsonArray("entries") : new JsonArray();
        for (JsonElement element : array) {
            JsonObject object = element.getAsJsonObject();
            String id = object.get("id").getAsString();
            String name = object.has("name") ? object.get("name").getAsString() : id;
            Map<String, GameValue<Object>> attributes = new LinkedHashMap<>();
            if (object.has("attributes")) {
                for (Map.Entry<String, JsonElement> attribute : object.getAsJsonObject("attributes").entrySet()) {
                    attributes.put(attribute.getKey(), value(attribute.getValue().getAsJsonObject(), what + ":" + id + "." + attribute.getKey()));
                }
            }
            list.add(new Entry(id, name, attributes));
        }
        return list;
    }

    public static GameValue<Object> value(JsonObject object, String defaultSource) {
        Confidence confidence = object.has("confidence") ? Confidence.parse(object.get("confidence").getAsString()) : Confidence.UNKNOWN;
        Object value = object.has("value") && !object.get("value").isJsonNull() ? plain(object.get("value")) : null;
        String source = object.has("source") ? object.get("source").getAsString() : defaultSource;
        String context = object.has("context") ? object.get("context").getAsString() : "";
        String note = object.has("note") ? object.get("note").getAsString() : null;
        if (value != null && confidence == Confidence.UNKNOWN) {
            throw new IllegalStateException(defaultSource + ": a value needs a confidence other than UNKNOWN");
        }
        return GameValue.of(value, confidence, source, 0L, context, note);
    }

    private static @Nullable Object plain(JsonElement element) {
        if (element.isJsonPrimitive()) {
            JsonPrimitive p = element.getAsJsonPrimitive();
            if (p.isBoolean()) {
                return p.getAsBoolean();
            }
            if (p.isNumber()) {
                double d = p.getAsDouble();
                return d == Math.rint(d) && Math.abs(d) < 9e15 ? (Object) (long) d : (Object) d;
            }
            return p.getAsString();
        }
        if (element.isJsonArray()) {
            List<Object> list = new ArrayList<>();
            for (JsonElement item : element.getAsJsonArray()) {
                list.add(plain(item));
            }
            return List.copyOf(list);
        }
        return null;
    }
}
