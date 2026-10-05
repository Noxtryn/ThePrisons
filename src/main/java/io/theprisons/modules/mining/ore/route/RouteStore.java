package io.theprisons.modules.mining.ore.route;

import io.theprisons.ThePrisonsClient;
import io.theprisons.core.setting.Settings;
import io.theprisons.modules.mining.ore.OreCatalog;
import io.theprisons.modules.mining.ore.OreMacroModule;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.MinecraftClient;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The saved routes of the current server and dimension ({@code config/theprisons/routes/<server>_<dimension>.json}).
 * Reloaded automatically when the world changes.
 */
public final class RouteStore {
    private final List<Route> routes = new ArrayList<>();
    private @Nullable Path file;

    public List<Route> all(MinecraftClient client) {
        sync(client);
        return routes;
    }

    public @Nullable Route find(MinecraftClient client, String name) {
        for (Route route : all(client)) {
            if (route.name().equals(name)) {
                return route;
            }
        }
        return null;
    }

    /** Saves the route; a name that exists already gets " (2)", " (3)", ... appended. @return the saved route */
    public Route add(MinecraftClient client, Route route) {
        sync(client);
        String base = route.name().isBlank() ? "Route" : route.name().strip();
        String name = base;
        for (int n = 2; find(client, name) != null; n++) {
            name = base + " (" + n + ")";
        }
        Route saved = new Route(name, route.ore(), route.mined(), route.waypoints());
        routes.add(saved);
        save();
        return saved;
    }

    /** Replaces the waypoints of a saved route. @return false when there is no such route */
    public boolean updateWaypoints(MinecraftClient client, String name, List<int[]> waypoints) {
        sync(client);
        for (int i = 0; i < routes.size(); i++) {
            Route route = routes.get(i);
            if (route.name().equals(name)) {
                routes.set(i, new Route(route.name(), route.ore(), route.mined(), List.copyOf(waypoints)));
                save();
                return true;
            }
        }
        return false;
    }

    public boolean delete(MinecraftClient client, String name) {
        sync(client);
        boolean removed = routes.removeIf(route -> route.name().equals(name));
        if (removed) {
            save();
        }
        return removed;
    }

    /** Dropdown entries: grouped by ore (section = ore name), label = route name and waypoint count. */
    public List<Settings.Option> options(MinecraftClient client) {
        List<Route> sorted = new ArrayList<>(all(client));
        sorted.sort(Comparator.comparing((Route route) -> oreLabel(route.ore())).thenComparing(Route::name));
        List<Settings.Option> options = new ArrayList<>();
        for (Route route : sorted) {
            options.add(new Settings.Option(route.name(), route.name() + " (" + route.waypoints().size() + " WP)",
                    oreLabel(route.ore()), route.ore().isEmpty() ? 0xFF9AA0B8 : OreCatalog.color(route.ore())));
        }
        return options;
    }

    public static String oreLabel(String ore) {
        return ore.isEmpty() ? "Other" : OreCatalog.label(ore);
    }

    // ── File ─────────────────────────────────────────────────────────────────

    private void sync(MinecraftClient client) {
        Path current = OreMacroModule.worldFile(client, "routes");
        if (Objects.equals(current, file)) {
            return;
        }
        file = current;
        routes.clear();
        if (current == null || !Files.exists(current)) {
            return;
        }
        try {
            for (JsonElement element : JsonParser.parseString(Files.readString(current)).getAsJsonArray()) {
                routes.add(fromJson(element.getAsJsonObject()));
            }
        } catch (IOException | RuntimeException e) {
            ThePrisonsClient.LOGGER.warn("[routes] could not read {}", current, e);
        }
    }

    private void save() {
        Path target = file;
        if (target == null) {
            return;
        }
        JsonArray all = new JsonArray();
        for (Route route : routes) {
            all.add(toJson(route));
        }
        try {
            Files.createDirectories(target.getParent());
            Files.writeString(target, all.toString());
        } catch (IOException e) {
            ThePrisonsClient.LOGGER.warn("[routes] could not save {}", target, e);
        }
    }

    static JsonObject toJson(Route route) {
        JsonObject json = new JsonObject();
        json.addProperty("name", route.name());
        json.addProperty("ore", route.ore());
        JsonObject mined = new JsonObject();
        route.mined().forEach(mined::addProperty);
        json.add("mined", mined);
        JsonArray points = new JsonArray();
        for (int[] p : route.waypoints()) {
            JsonArray xyz = new JsonArray();
            xyz.add(p[0]);
            xyz.add(p[1]);
            xyz.add(p[2]);
            points.add(xyz);
        }
        json.add("waypoints", points);
        return json;
    }

    static Route fromJson(JsonObject json) {
        Map<String, Integer> mined = new LinkedHashMap<>();
        if (json.has("mined")) {
            json.getAsJsonObject("mined").entrySet().forEach(e -> mined.put(e.getKey(), e.getValue().getAsInt()));
        }
        List<int[]> points = new ArrayList<>();
        for (JsonElement element : json.getAsJsonArray("waypoints")) {
            JsonArray xyz = element.getAsJsonArray();
            points.add(new int[]{xyz.get(0).getAsInt(), xyz.get(1).getAsInt(), xyz.get(2).getAsInt()});
        }
        return new Route(json.get("name").getAsString(), json.has("ore") ? json.get("ore").getAsString() : Route.NO_ORE, mined, points);
    }
}
