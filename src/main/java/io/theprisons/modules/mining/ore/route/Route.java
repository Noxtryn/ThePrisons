package io.theprisons.modules.mining.ore.route;

import java.util.List;
import java.util.Map;

/**
 * A recorded route: its waypoints (block positions of the player's feet) in walking order and the ore package it
 * belongs to (the one mined most while recording; {@code ""} when nothing was mined).
 *
 * @param mined ores mined per package while recording
 */
public record Route(String name, String ore, Map<String, Integer> mined, List<int[]> waypoints) {
    public static final String NO_ORE = "";

    /** The package mined most (ties: the first one); {@link #NO_ORE} when nothing was mined. */
    public static String mostMined(Map<String, Integer> mined) {
        String best = NO_ORE;
        int count = 0;
        for (Map.Entry<String, Integer> entry : mined.entrySet()) {
            if (entry.getValue() > count) {
                best = entry.getKey();
                count = entry.getValue();
            }
        }
        return best;
    }
}
