package io.theprisons.core.cosmic.model;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.theprisons.core.cosmic.value.GameValue;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;

/** Single Cosmic facts and rules by key ("mining.speedRequirement"), loaded from {@code knowledge.json}. Unlisted = UNKNOWN. */
public final class Knowledge {
    private final Map<String, GameValue<Object>> values;

    public Knowledge(Map<String, GameValue<Object>> values) {
        this.values = Map.copyOf(values);
    }

    public static Knowledge empty() {
        return new Knowledge(Map.of());
    }

    public static Knowledge load(InputStream in, String what) {
        JsonObject root = RegistryData.read(in, what);
        Map<String, GameValue<Object>> map = new LinkedHashMap<>();
        if (root.has("values")) {
            for (JsonElement element : root.getAsJsonArray("values")) {
                JsonObject object = element.getAsJsonObject();
                String key = object.get("key").getAsString();
                if (map.put(key, RegistryData.value(object, "knowledge:" + key)) != null) {
                    throw new IllegalStateException(what + ": duplicate key " + key);
                }
            }
        }
        return new Knowledge(map);
    }

    public GameValue<Object> get(String key) {
        GameValue<Object> value = values.get(key);
        return value != null ? value : GameValue.unknown("knowledge:" + key, "not listed");
    }

    public <T> GameValue<T> get(String key, Class<T> type) {
        GameValue<Object> raw = get(key);
        Object v = raw.value();
        if (v == null) {
            return GameValue.unknown(raw.source(), raw.note());
        }
        if (type.isInstance(v)) {
            return GameValue.of(type.cast(v), raw.confidence(), raw.source(), raw.observedAtMs(), raw.context(), raw.note());
        }
        if (v instanceof Number n && type == Double.class) {
            return GameValue.of(type.cast(n.doubleValue()), raw.confidence(), raw.source(), raw.observedAtMs(), raw.context(), raw.note());
        }
        return GameValue.unknown(raw.source(), "wrong type: " + v.getClass().getSimpleName());
    }

    public java.util.Set<String> keys() {
        return values.keySet();
    }
}
