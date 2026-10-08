package io.theprisons.items;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/** Real item stacks the game sent (config/theprisons/market/catalog.json of a play session, masks without their skin profile). */
public final class Samples {
    private Samples() {
    }

    public static Map<String, ItemFacts> catalog() {
        try (InputStream in = Samples.class.getResourceAsStream("/items/catalog-sample.json")) {
            JsonObject root = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            Map<String, ItemFacts> out = new LinkedHashMap<>();
            root.entrySet().forEach(e -> out.put(e.getKey(), ItemFactsJson.read(e.getValue().getAsJsonObject())));
            return out;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public static ItemFacts facts(String name, String vanilla, String customId, Map<String, String> values) {
        java.util.Map<String, String> v = new java.util.HashMap<>(values);
        if (customId != null) {
            v.put("custom_item_id", customId);
        }
        return new ItemFacts(vanilla, name, java.util.List.of(), v, java.util.List.of(), 1);
    }
}
