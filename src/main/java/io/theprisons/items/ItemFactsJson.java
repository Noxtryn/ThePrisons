package io.theprisons.items;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** {@link ItemFacts} from the JSON form of a stack (the item codec output, as the market catalog stores it) - no Minecraft needed. */
public final class ItemFactsJson {
    private ItemFactsJson() {
    }

    public static ItemFacts read(JsonObject stack) {
        String id = stack.has("id") ? stack.get("id").getAsString() : "minecraft:air";
        int count = stack.has("count") ? stack.get("count").getAsInt() : 1;
        JsonObject components = stack.has("components") && stack.get("components").isJsonObject() ? stack.getAsJsonObject("components") : new JsonObject();
        String name = components.has("minecraft:custom_name") ? text(components.get("minecraft:custom_name")) : components.has("minecraft:item_name")
                ? text(components.get("minecraft:item_name")) : "";
        List<String> lore = new ArrayList<>();
        if (components.has("minecraft:lore") && components.get("minecraft:lore").isJsonArray()) {
            for (JsonElement line : components.getAsJsonArray("minecraft:lore")) {
                lore.add(text(line));
            }
        }
        Map<String, String> values = new LinkedHashMap<>();
        if (components.has("minecraft:custom_data") && components.get("minecraft:custom_data").isJsonObject()) {
            JsonObject data = components.getAsJsonObject("minecraft:custom_data");
            if (data.has("PublicBukkitValues") && data.get("PublicBukkitValues").isJsonObject()) {
                for (Map.Entry<String, JsonElement> e : data.getAsJsonObject("PublicBukkitValues").entrySet()) {
                    String key = e.getKey().startsWith("cosmicprisons:") ? e.getKey().substring("cosmicprisons:".length()) : e.getKey();
                    values.put(key, ItemFacts.plain(e.getValue().isJsonPrimitive() ? e.getValue().getAsString() : e.getValue().toString()));
                }
            }
        }
        List<String> enchants = new ArrayList<>();
        if (components.has("minecraft:enchantments") && components.get("minecraft:enchantments").isJsonObject()) {
            JsonObject ench = components.getAsJsonObject("minecraft:enchantments");
            JsonObject levels = ench.has("levels") && ench.get("levels").isJsonObject() ? ench.getAsJsonObject("levels") : ench;
            for (Map.Entry<String, JsonElement> e : levels.entrySet()) {
                if (e.getValue().isJsonPrimitive()) {
                    enchants.add(e.getKey().replace("minecraft:", "") + " " + e.getValue().getAsInt());
                }
            }
        }
        return new ItemFacts(id, name, lore, values, enchants, count);
    }

    /** The plain text of a text component in JSON form (string, object with text / extra, or an array of them). */
    static String text(JsonElement e) {
        StringBuilder sb = new StringBuilder();
        append(sb, e);
        return sb.toString();
    }

    private static void append(StringBuilder sb, JsonElement e) {
        if (e == null || e.isJsonNull()) {
            return;
        }
        if (e.isJsonPrimitive()) {
            sb.append(e.getAsString());
        } else if (e.isJsonArray()) {
            JsonArray a = e.getAsJsonArray();
            for (JsonElement part : a) {
                append(sb, part);
            }
        } else if (e.isJsonObject()) {
            JsonObject o = e.getAsJsonObject();
            if (o.has("text")) {
                sb.append(o.get("text").getAsString());
            }
            if (o.has("extra")) {
                append(sb, o.get("extra"));
            }
        }
    }
}
