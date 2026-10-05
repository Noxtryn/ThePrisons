package io.theprisons.modules.mining.ore;

import io.theprisons.core.nav.Pos;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * The {@code routes_memory} file format (pure conversion, no I/O). Version 2, compact JSON:
 * <pre>
 * {"version":2,"routes":[{"route_id":104,"mine_zone":"Redstone Mine","kind":"plan","efficiency_score":92.5,
 *   "guarded":true,"width_radius":3.5,"waypoints":[[10,64,-20],[10,64,-5],[15,64,5]],
 *   "ores":41,"blocks":44.3,"runs":3,"last_run":1759450000000}]}
 * </pre>
 * {@code efficiency_score} = ores per 100 blocks (written for reading the file; the ores and blocks are what counts).
 * A version 1 file (every node of the line under "line") is read too: its line is compressed on loading.
 */
public final class RouteCodec {
    static final int VERSION = 2;

    private RouteCodec() {
    }

    public static String encode(Collection<RouteMemory.Route> routes) {
        JsonArray list = new JsonArray();
        for (RouteMemory.Route r : routes) {
            JsonObject o = new JsonObject();
            o.addProperty("route_id", r.id);
            o.addProperty("mine_zone", r.mineZone);
            o.addProperty("kind", r.kind);
            o.addProperty("efficiency_score", Math.round(r.efficiency() * 10.0D) / 10.0D);
            o.addProperty("guarded", true);
            o.addProperty("width_radius", Math.round(r.widthRadius * 10.0D) / 10.0D);
            JsonArray wp = new JsonArray();
            for (int[] w : r.waypoints) {
                JsonArray p = new JsonArray();
                p.add(w[0]);
                p.add(w[1]);
                p.add(w[2]);
                wp.add(p);
            }
            o.add("waypoints", wp);
            o.addProperty("ores", r.ores);
            o.addProperty("blocks", Math.round(r.blocks * 10.0D) / 10.0D);
            o.addProperty("runs", r.runs);
            o.addProperty("last_run", r.lastRunMs);
            list.add(o);
        }
        JsonObject root = new JsonObject();
        root.addProperty("version", VERSION);
        root.add("routes", list);
        return root.toString();
    }

    /** The routes of a file (version 1 or 2); broken entries are skipped. */
    public static List<RouteMemory.Route> decode(String json) {
        List<RouteMemory.Route> out = new ArrayList<>();
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        if (!root.has("routes")) {
            return out;
        }
        int nextId = 1;
        for (JsonElement e : root.getAsJsonArray("routes")) {
            try {
                JsonObject o = e.getAsJsonObject();
                int[][] waypoints;
                if (o.has("waypoints")) {
                    waypoints = points(o.getAsJsonArray("waypoints"));
                } else if (o.has("line")) {
                    // Version 1: every node - compressed now.
                    int[][] line = points(o.getAsJsonArray("line"));
                    long[] nodes = new long[line.length];
                    for (int i = 0; i < line.length; i++) {
                        nodes[i] = Pos.pack(line[i][0], line[i][1], line[i][2]);
                    }
                    waypoints = RoutePath.compress(nodes);
                } else {
                    continue;
                }
                if (waypoints.length < 2) {
                    continue;
                }
                int id = o.has("route_id") ? o.get("route_id").getAsInt() : nextId;
                nextId = Math.max(nextId, id + 1);
                out.add(new RouteMemory.Route(id,
                        o.has("kind") ? o.get("kind").getAsString() : "plan",
                        o.has("mine_zone") ? o.get("mine_zone").getAsString() : "unknown",
                        waypoints,
                        o.has("width_radius") ? o.get("width_radius").getAsDouble() : RoutePath.OPEN_RADIUS,
                        o.has("ores") ? o.get("ores").getAsInt() : 0,
                        o.has("blocks") ? o.get("blocks").getAsDouble() : RoutePath.length(waypoints),
                        o.has("runs") ? o.get("runs").getAsInt() : 1,
                        o.has("last_run") ? o.get("last_run").getAsLong() : o.has("lastRunMs") ? o.get("lastRunMs").getAsLong() : 0L));
            } catch (RuntimeException ignored) {
                // A broken entry: the others still count.
            }
        }
        return out;
    }

    private static int[][] points(JsonArray array) {
        int[][] out = new int[array.size()][];
        for (int i = 0; i < out.length; i++) {
            JsonArray p = array.get(i).getAsJsonArray();
            out[i] = new int[]{p.get(0).getAsInt(), p.get(1).getAsInt(), p.get(2).getAsInt()};
        }
        return out;
    }
}
