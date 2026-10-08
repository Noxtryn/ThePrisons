package io.theprisons.testing;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.theprisons.modules.qol.bandit.combat.CombatConfig;
import io.theprisons.modules.qol.bandit.combat.CombatState;
import io.theprisons.modules.qol.bandit.combat.SpearStatus;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A data-driven combat scenario (a {@code *.scenario.json}): an arena, where everybody stands, a short script (a bandit vanishes, a key
 * is pressed, health drops) and what must hold afterwards. The runner uses {@link CombatSim}; owner captures can be turned into these.
 */
public final class CombatScenario {
    private static final Pattern STATE = Pattern.compile("STATE (\\w+) -> (\\w+)");

    private CombatScenario() {
    }

    /** What the run produced. */
    public record Outcome(CombatSim sim, Set<String> statesSeen, int stateChanges, int recoverEntries, int maxThreats) {
    }

    public static Outcome run(Path file) throws IOException {
        JsonObject root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
        JsonArray rows = root.getAsJsonArray("arena");
        String[] arena = new String[rows.size()];
        for (int i = 0; i < arena.length; i++) {
            arena[i] = rows.get(i).getAsString();
        }
        JsonObject me = root.getAsJsonObject("me");
        CombatConfig cfg = new CombatConfig();
        CombatSim sim = new CombatSim(cfg, new GridTerrain(arena), me.get("x").getAsDouble(), me.get("z").getAsDouble());
        sim.yaw = me.has("yaw") ? me.get("yaw").getAsFloat() : 0.0F;
        sim.health = me.has("health") ? me.get("health").getAsDouble() : 20.0D;
        sim.spear = SpearStatus.valueOf(root.has("spear") ? root.get("spear").getAsString() : "UNKNOWN");
        for (JsonElement b : root.getAsJsonArray("bandits")) {
            JsonObject o = b.getAsJsonObject();
            sim.bandit(o.get("id").getAsString(), o.get("x").getAsDouble(), o.get("z").getAsDouble());
        }
        if (root.has("players")) {
            for (JsonElement p : root.getAsJsonArray("players")) {
                JsonObject o = p.getAsJsonObject();
                sim.player(o.get("id").getAsString(), o.get("x").getAsDouble(), o.get("z").getAsDouble());
            }
        }
        sim.start();
        int ticks = root.get("ticks").getAsInt();
        int maxThreats = 0;
        for (int t = 1; t <= ticks; t++) {
            if (root.has("script")) {
                for (JsonElement e : root.getAsJsonArray("script")) {
                    JsonObject ev = e.getAsJsonObject();
                    if (ev.get("tick").getAsInt() != t) {
                        continue;
                    }
                    if (ev.has("removeBandit")) {
                        sim.bandits.remove(ev.get("removeBandit").getAsString());
                    }
                    if (ev.has("manual")) {
                        sim.manual = ev.get("manual").getAsBoolean();
                    }
                    if (ev.has("health")) {
                        sim.health = ev.get("health").getAsDouble();
                    }
                }
            }
            sim.tick();
            maxThreats = Math.max(maxThreats, sim.brain.threats().count());
        }
        Set<String> seen = new HashSet<>();
        int changes = 0;
        int recovers = 0;
        for (String line : sim.traceLog) {
            Matcher m = STATE.matcher(line);
            if (m.find()) {
                seen.add(m.group(1));
                seen.add(m.group(2));
                changes++;
                if (m.group(2).equals("RECOVER")) {
                    recovers++;
                }
            }
        }
        return new Outcome(sim, seen, changes, recovers, maxThreats);
    }

    public static JsonObject expect(Path file) throws IOException {
        return JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonObject("expect");
    }

    public static List<Path> all(Path root) throws IOException {
        try (var walk = Files.walk(root)) {
            return new ArrayList<>(walk.filter(p -> p.getFileName().toString().endsWith(".scenario.json")).sorted().toList());
        }
    }

    public static CombatState state(String name) {
        return CombatState.valueOf(name);
    }
}
