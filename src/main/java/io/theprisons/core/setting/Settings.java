package io.theprisons.core.setting;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonPrimitive;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The concrete setting types the GUI knows how to draw.
 */
public final class Settings {
    private Settings() {
    }

    public static final class BoolSetting extends Setting<Boolean> {
        public BoolSetting(String id, String name, boolean defaultValue) {
            super(id, name, defaultValue);
        }

        public boolean on() {
            return get();
        }

        public void toggle() {
            set(!get());
        }

        @Override
        protected Boolean normalize(Boolean raw) {
            return raw;
        }

        @Override
        public JsonElement toJson() {
            return new JsonPrimitive(get());
        }

        @Override
        public void fromJson(JsonElement json) {
            if (!json.isJsonPrimitive() || !json.getAsJsonPrimitive().isBoolean()) {
                throw new IllegalArgumentException("expected true/false");
            }
            set(json.getAsBoolean());
        }

        @Override
        public String display() {
            return get() ? "On" : "Off";
        }
    }

    public static final class IntSetting extends Setting<Integer> {
        private final int min;
        private final int max;
        private final int step;
        private String suffix = "";

        public IntSetting(String id, String name, int defaultValue, int min, int max, int step) {
            super(id, name, defaultValue);
            this.min = min;
            this.max = max;
            this.step = Math.max(1, step);
        }

        public IntSetting suffix(String suffix) {
            this.suffix = suffix;
            return this;
        }

        public int value() {
            return get();
        }

        public int min() {
            return min;
        }

        public int max() {
            return max;
        }

        public int step() {
            return step;
        }

        @Override
        protected Integer normalize(Integer raw) {
            int stepped = min + Math.round((raw - min) / (float) step) * step;
            return Math.max(min, Math.min(max, stepped));
        }

        @Override
        public JsonElement toJson() {
            return new JsonPrimitive(get());
        }

        @Override
        public void fromJson(JsonElement json) {
            set(json.getAsInt());
        }

        @Override
        public String display() {
            return get() + suffix;
        }
    }

    public static final class DoubleSetting extends Setting<Double> {
        private final double min;
        private final double max;
        private final double step;
        private String suffix = "";

        public DoubleSetting(String id, String name, double defaultValue, double min, double max, double step) {
            super(id, name, defaultValue);
            this.min = min;
            this.max = max;
            this.step = step <= 0 ? 0.01D : step;
        }

        public DoubleSetting suffix(String suffix) {
            this.suffix = suffix;
            return this;
        }

        public double value() {
            return get();
        }

        public double min() {
            return min;
        }

        public double max() {
            return max;
        }

        public double step() {
            return step;
        }

        @Override
        protected Double normalize(Double raw) {
            if (raw.isNaN()) {
                return defaultValue();
            }
            double stepped = min + Math.round((raw - min) / step) * step;
            // Kill float noise like 0.30000000000000004.
            stepped = Math.round(stepped * 1_000_000.0D) / 1_000_000.0D;
            return Math.max(min, Math.min(max, stepped));
        }

        @Override
        public JsonElement toJson() {
            return new JsonPrimitive(get());
        }

        @Override
        public void fromJson(JsonElement json) {
            set(json.getAsDouble());
        }

        @Override
        public String display() {
            int decimals = step >= 1 ? 0 : step >= 0.1 ? 1 : 2;
            return String.format(Locale.ROOT, "%." + decimals + "f", get()) + suffix;
        }
    }

    public static final class EnumSetting<E extends Enum<E>> extends Setting<E> {
        private final Class<E> type;
        private final Function<E, String> labels;

        public EnumSetting(String id, String name, E defaultValue, Function<E, String> labels) {
            super(id, name, defaultValue);
            this.type = defaultValue.getDeclaringClass();
            this.labels = labels;
        }

        public List<E> options() {
            return List.of(type.getEnumConstants());
        }

        public String label(E value) {
            return labels.apply(value);
        }

        @Override
        protected E normalize(E raw) {
            return raw;
        }

        @Override
        public JsonElement toJson() {
            return new JsonPrimitive(get().name().toLowerCase(Locale.ROOT));
        }

        @Override
        public void fromJson(JsonElement json) {
            set(Enum.valueOf(type, json.getAsString().toUpperCase(Locale.ROOT)));
        }

        @Override
        public String display() {
            return label(get());
        }
    }

    /**
     * One option id out of a list that changes at runtime (e.g. the saved routes of the current world). The empty id
     * means "off"; an id that is not offered (any more) displays as missing but is kept.
     */
    public static final class ChoiceSetting extends Setting<String> {
        private final Supplier<List<Option>> options;
        private final String offLabel;

        public ChoiceSetting(String id, String name, String offLabel, Supplier<List<Option>> options) {
            super(id, name, "");
            this.offLabel = offLabel;
            this.options = options;
        }

        public List<Option> options() {
            return options.get();
        }

        public String offLabel() {
            return offLabel;
        }

        public boolean off() {
            return get().isEmpty();
        }

        @Override
        protected String normalize(String raw) {
            return raw == null ? "" : raw;
        }

        @Override
        public JsonElement toJson() {
            return new JsonPrimitive(get());
        }

        @Override
        public void fromJson(JsonElement json) {
            set(json.getAsString());
        }

        @Override
        public String display() {
            if (off()) {
                return offLabel;
            }
            for (Option option : options()) {
                if (option.id().equals(get())) {
                    return option.label();
                }
            }
            return get() + " (not here)";
        }
    }

    /** One selectable item of a {@link MultiChoiceSetting}. */
    public record Option(String id, String label, String section, int color) {
    }

    /** A set of option ids (e.g. the ore types to mine). The value is an unmodifiable ordered set. */
    public static final class MultiChoiceSetting extends Setting<Set<String>> {
        private final List<Option> options;

        public MultiChoiceSetting(String id, String name, Collection<String> defaults, List<Option> options) {
            super(id, name, Collections.unmodifiableSet(new LinkedHashSet<>(defaults)));
            this.options = List.copyOf(options);
        }

        public List<Option> options() {
            return options;
        }

        public boolean contains(String optionId) {
            return get().contains(optionId);
        }

        public void toggle(String optionId) {
            Set<String> next = new LinkedHashSet<>(get());
            if (!next.remove(optionId)) {
                next.add(optionId);
            }
            set(next);
        }

        @Override
        protected Set<String> normalize(Set<String> raw) {
            // Keep option order, drop unknown ids.
            Set<String> result = new LinkedHashSet<>();
            for (Option option : options) {
                if (raw.contains(option.id())) {
                    result.add(option.id());
                }
            }
            return Collections.unmodifiableSet(result);
        }

        @Override
        public JsonElement toJson() {
            JsonArray array = new JsonArray();
            get().forEach(array::add);
            return array;
        }

        @Override
        public void fromJson(JsonElement json) {
            Set<String> ids = new LinkedHashSet<>();
            for (JsonElement element : json.getAsJsonArray()) {
                ids.add(element.getAsString());
            }
            set(ids);
        }

        @Override
        public String display() {
            return get().size() + " selected";
        }
    }

    /** GLFW key code, {@link #NONE} when unbound. */
    public static final class KeybindSetting extends Setting<Integer> {
        public static final int NONE = -1;

        public KeybindSetting(String id, String name, int defaultKey) {
            super(id, name, defaultKey);
        }

        public int key() {
            return get();
        }

        public boolean bound() {
            return get() != NONE;
        }

        @Override
        protected Integer normalize(Integer raw) {
            return raw < 0 ? NONE : raw;
        }

        @Override
        public JsonElement toJson() {
            return new JsonPrimitive(get());
        }

        @Override
        public void fromJson(JsonElement json) {
            set(json.getAsInt());
        }
    }

    public static final class TextSetting extends Setting<String> {
        private final int maxLength;

        public TextSetting(String id, String name, String defaultValue, int maxLength) {
            super(id, name, defaultValue);
            this.maxLength = maxLength;
        }

        public int maxLength() {
            return maxLength;
        }

        @Override
        protected String normalize(String raw) {
            String cleaned = raw.replace('\n', ' ').replace('\r', ' ');
            return cleaned.length() > maxLength ? cleaned.substring(0, maxLength) : cleaned;
        }

        @Override
        public JsonElement toJson() {
            return new JsonPrimitive(get());
        }

        @Override
        public void fromJson(JsonElement json) {
            set(json.getAsString());
        }
    }

    /** ARGB colour picked from a palette. */
    public static final class ColorSetting extends Setting<Integer> {
        public static final int[] PALETTE = {
                0xFFF2EEFF, 0xFFA8E8FF, 0xFF00E5FF, 0xFF4D8DFF, 0xFF7B3FFF, 0xFFC084FC,
                0xFFFF3DBA, 0xFFF84EA8, 0xFFFF5E6C, 0xFFFFC14D, 0xFF65F59B, 0xFF9AA0B8
        };

        public ColorSetting(String id, String name, int defaultValue) {
            super(id, name, defaultValue);
        }

        @Override
        protected Integer normalize(Integer raw) {
            // Fully transparent colours are always a mistake in a HUD setting.
            return (raw >>> 24) == 0 ? raw | 0xFF000000 : raw;
        }

        @Override
        public JsonElement toJson() {
            return new JsonPrimitive(String.format(Locale.ROOT, "#%08X", get()));
        }

        @Override
        public void fromJson(JsonElement json) {
            String text = json.getAsString().trim();
            set((int) Long.parseLong(text.startsWith("#") ? text.substring(1) : text, 16));
        }

        @Override
        public String display() {
            return String.format(Locale.ROOT, "#%06X", get() & 0xFFFFFF);
        }
    }

    /** A button in the settings panel. Never persisted. */
    public static final class ActionSetting extends Setting<Boolean> {
        private final String label;
        private final Runnable action;

        public ActionSetting(String id, String name, String label, Runnable action) {
            super(id, name, Boolean.FALSE);
            this.label = label;
            this.action = action;
            bind(() -> Boolean.FALSE, ignored -> {
            });
        }

        public String label() {
            return label;
        }

        public void run() {
            action.run();
        }

        @Override
        protected Boolean normalize(Boolean raw) {
            return Boolean.FALSE;
        }

        @Override
        public JsonElement toJson() {
            return JsonNull.INSTANCE;
        }

        @Override
        public void fromJson(JsonElement json) {
        }

        @Override
        public String display() {
            return label;
        }
    }

    /** Parses comma / space separated ids, trims and lower-cases them. */
    public static List<String> splitIds(String raw) {
        List<String> ids = new ArrayList<>();
        for (String part : raw.split("[,;\\s]+")) {
            String id = part.trim().toLowerCase(Locale.ROOT);
            if (!id.isEmpty()) {
                ids.add(id);
            }
        }
        return ids;
    }
}
