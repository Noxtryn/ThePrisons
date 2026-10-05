package com.freelocs.theprisons.core.i18n;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The mod's own language (English / German), switchable live: every text is written in English in the code and looked
 * up here when it is drawn, so a switch shows up on the next frame. German texts live in
 * {@code assets/theprisons/i18n/de.json} (English text → German text); a missing entry stays English.
 */
public final class I18n {
    public enum Lang {
        EN("English", "EN"), DE("Deutsch", "DE");

        private final String label;
        private final String code;

        Lang(String label, String code) {
            this.label = label;
            this.code = code;
        }

        public String label() {
            return label;
        }

        public String code() {
            return code;
        }

        public Lang next() {
            return values()[(ordinal() + 1) % values().length];
        }
    }

    private static final Map<String, String> GERMAN = load("/assets/theprisons/i18n/de.json");
    private static volatile Lang lang = Lang.EN;

    private I18n() {
    }

    public static Lang lang() {
        return lang;
    }

    public static void setLang(Lang newLang) {
        lang = newLang;
    }

    /** The text in the current language (the English text is the key). */
    public static String t(String english) {
        if (lang == Lang.DE) {
            String german = GERMAN.get(english);
            return german != null ? german : english;
        }
        return english;
    }

    /** {@link #t} then {@link String#format} with the arguments. */
    public static String f(String english, Object... args) {
        return String.format(Locale.ROOT, t(english), args);
    }

    /** Whether a German text exists for this key (for tests). */
    public static boolean hasGerman(String english) {
        return GERMAN.containsKey(english);
    }

    private static Map<String, String> load(String resource) {
        Map<String, String> map = new HashMap<>();
        try (InputStream in = I18n.class.getResourceAsStream(resource)) {
            if (in == null) {
                return map;
            }
            JsonObject json = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            for (Map.Entry<String, com.google.gson.JsonElement> entry : json.entrySet()) {
                map.put(entry.getKey(), entry.getValue().getAsString());
            }
        } catch (Exception ignored) {
            // No German texts: everything stays English.
        }
        return map;
    }
}
