package com.freelocs.theprisons.modules.mining.ore;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

/**
 * What the macro learned about a mine, per area of 8³ blocks ({@link LanePlanner#zoneOf}), kept across sessions
 * (saved per server / world and dimension):
 * <ul>
 *     <li>blocks per second really mined on lanes there (exponential average),</li>
 *     <li>how often it was visited and how often it turned out to be a dead end (the macro had to turn back),</li>
 *     <li>when it was last there.</li>
 * </ul>
 * The planner gets a factor per area: the yield relative to the mine's average ({@value #MIN_FACTOR}…
 * {@value #MAX_FACTOR}), lowered for dead ends. Old knowledge fades towards neutral with a half-life of
 * {@value #HALF_LIFE_HOURS} hours, because the ores respawn. Pure logic apart from the JSON form.
 */
public final class RegionMemory {
    static final double ALPHA = 0.35D;
    static final double MIN_FACTOR = 0.8D;
    static final double MAX_FACTOR = 1.25D;
    static final double DEAD_END_FACTOR = 0.8D;
    static final double HALF_LIFE_HOURS = 6.0D;

    private static final class Area {
        double bps;
        int visits;
        int deadEnds;
        long lastMs;
    }

    private final Long2ObjectOpenHashMap<Area> areas = new Long2ObjectOpenHashMap<>();
    private double average = Double.NaN;
    private boolean dirty;

    /** A lane in this area gave {@code bps} blocks per second. */
    public void recordLane(long zone, double bps, long nowMs) {
        Area area = areas.computeIfAbsent(zone, key -> new Area());
        area.bps = area.visits == 0 ? bps : area.bps + ALPHA * (bps - area.bps);
        area.visits++;
        area.lastMs = nowMs;
        average = Double.isNaN(average) ? bps : average + 0.05D * (bps - average);
        dirty = true;
    }

    /** The way ended here and the macro had to turn back. */
    public void recordDeadEnd(long zone, long nowMs) {
        Area area = areas.computeIfAbsent(zone, key -> new Area());
        area.deadEnds++;
        area.lastMs = nowMs;
        dirty = true;
    }

    public double factor(long zone, long nowMs) {
        Area area = areas.get(zone);
        if (area == null) {
            return 1.0D;
        }
        double factor = 1.0D;
        if (area.visits > 0 && average > 0.0D) {
            factor = Math.max(MIN_FACTOR, Math.min(MAX_FACTOR, area.bps / average));
        }
        if (area.deadEnds >= 2 && area.deadEnds * 2 >= area.visits) {
            factor *= DEAD_END_FACTOR;
        }
        // Fade to neutral with age: the ores have respawned since.
        double hours = Math.max(0.0D, (nowMs - area.lastMs) / 3_600_000.0D);
        double weight = Math.pow(0.5D, hours / HALF_LIFE_HOURS);
        return 1.0D + (factor - 1.0D) * weight;
    }

    /** Copy of the factors that differ from neutral, for a worker job. */
    public Long2DoubleOpenHashMap snapshot(long nowMs) {
        Long2DoubleOpenHashMap copy = new Long2DoubleOpenHashMap();
        copy.defaultReturnValue(1.0D);
        for (long zone : areas.keySet()) {
            double factor = factor(zone, nowMs);
            if (Math.abs(factor - 1.0D) > 1.0E-3D) {
                copy.put(zone, factor);
            }
        }
        return copy;
    }

    public int size() {
        return areas.size();
    }

    public boolean dirty() {
        return dirty;
    }

    public void clear() {
        areas.clear();
        average = Double.NaN;
        dirty = false;
    }

    // ── JSON ─────────────────────────────────────────────────────────────────

    public String toJson() {
        JsonObject root = new JsonObject();
        root.addProperty("schema", 1);
        if (!Double.isNaN(average)) {
            root.addProperty("average", average);
        }
        JsonArray list = new JsonArray();
        areas.long2ObjectEntrySet().forEach(entry -> {
            Area area = entry.getValue();
            JsonObject item = new JsonObject();
            item.addProperty("zone", entry.getLongKey());
            item.addProperty("bps", area.bps);
            item.addProperty("visits", area.visits);
            item.addProperty("deadEnds", area.deadEnds);
            item.addProperty("last", area.lastMs);
            list.add(item);
        });
        root.add("areas", list);
        dirty = false;
        return root.toString();
    }

    /** Replaces the content with the JSON form; ignores anything unreadable. */
    public void fromJson(String json) {
        clear();
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            if (root.has("average")) {
                average = root.get("average").getAsDouble();
            }
            for (var element : root.getAsJsonArray("areas")) {
                JsonObject item = element.getAsJsonObject();
                Area area = new Area();
                area.bps = item.get("bps").getAsDouble();
                area.visits = item.get("visits").getAsInt();
                area.deadEnds = item.get("deadEnds").getAsInt();
                area.lastMs = item.get("last").getAsLong();
                areas.put(item.get("zone").getAsLong(), area);
            }
        } catch (RuntimeException ignored) {
            clear();
        }
        dirty = false;
    }
}
