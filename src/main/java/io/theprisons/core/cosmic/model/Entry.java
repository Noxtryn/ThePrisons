package io.theprisons.core.cosmic.model;

import io.theprisons.core.cosmic.value.GameValue;

import java.util.Map;

/** One thing a registry knows (an ore, a zone, a bandit kind ...) with its attributes, each a {@link GameValue}. */
public record Entry(String id, String name, Map<String, GameValue<Object>> attributes) {
    public Entry {
        attributes = Map.copyOf(attributes);
    }

    /** The placeholder for something no registry knows: it has no attributes, every lookup is UNKNOWN. */
    public static Entry unknown(String id) {
        return new Entry(id, id.isEmpty() ? "Unknown" : id, Map.of());
    }

    /** True for entries made by {@link #unknown(String)} and entries without any attribute that were declared as unknown. */
    public boolean isPlaceholder() {
        return attributes.isEmpty();
    }

    /** The attribute, or UNKNOWN when it is not listed. */
    public GameValue<Object> attribute(String key) {
        GameValue<Object> value = attributes.get(key);
        return value != null ? value : GameValue.unknown("registry:" + id + "." + key, "not listed");
    }

    /** The attribute as a typed value; UNKNOWN when it is missing or of another type (numbers convert between Long / Double). */
    public <T> GameValue<T> attribute(String key, Class<T> type) {
        GameValue<Object> raw = attribute(key);
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
        if (v instanceof Number n && type == Long.class && n.doubleValue() == Math.rint(n.doubleValue())) {
            return GameValue.of(type.cast(n.longValue()), raw.confidence(), raw.source(), raw.observedAtMs(), raw.context(), raw.note());
        }
        return GameValue.unknown(raw.source(), "wrong type: " + v.getClass().getSimpleName());
    }
}
