package io.theprisons.modules.qol.bandit.combat;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.theprisons.testing.CombatScenario;
import io.theprisons.testing.CosmicFixtures;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Replays every synthetic combat scenario under src/test/resources/cosmic/bandits/combat and checks what each one says must hold. */
class CombatScenarioTest {
    private static final Path ROOT = CosmicFixtures.root().resolve("bandits").resolve("combat");

    @Test
    void theRequiredScenariosExist() throws Exception {
        List<String> names = CombatScenario.all(ROOT).stream().map(p -> p.getFileName().toString().replace(".scenario.json", "")).toList();
        for (String needed : List.of("single_bandit", "two_bandits", "blocked_left_orbit", "blocked_right_orbit", "wall_pressure", "target_lost",
                "manual_input", "low_health", "unknown_spear_state")) {
            assertTrue(names.contains(needed), needed + " missing from " + names);
        }
    }

    @Test
    void everyScenarioHoldsWhatItSays() throws Exception {
        for (Path file : CombatScenario.all(ROOT)) {
            CombatScenario.Outcome out = CombatScenario.run(file);
            JsonObject expect = CombatScenario.expect(file);
            String name = file.getFileName().toString();
            String ctx = name + " history=" + out.sim().history + " trace=" + out.sim().traceLog.size();
            if (expect.has("statesSeen")) {
                for (JsonElement e : expect.getAsJsonArray("statesSeen")) {
                    assertTrue(out.statesSeen().contains(e.getAsString()), ctx + ": expected state " + e.getAsString() + " in " + out.statesSeen());
                }
            }
            if (expect.has("statesSeenAnyOf")) {
                boolean any = false;
                for (JsonElement e : expect.getAsJsonArray("statesSeenAnyOf")) {
                    any |= out.statesSeen().contains(e.getAsString());
                }
                assertTrue(any, ctx + ": none of " + expect.get("statesSeenAnyOf") + " in " + out.statesSeen());
            }
            if (expect.has("statesNotSeen")) {
                for (JsonElement e : expect.getAsJsonArray("statesNotSeen")) {
                    assertFalse(out.statesSeen().contains(e.getAsString()), ctx + ": unexpected state " + e.getAsString() + " in " + out.statesSeen());
                }
            }
            if (expect.has("finalState")) {
                boolean ok = false;
                for (JsonElement e : expect.getAsJsonArray("finalState")) {
                    ok |= out.sim().brain.state().name().equals(e.getAsString());
                }
                assertTrue(ok, ctx + ": final state " + out.sim().brain.state() + " not in " + expect.get("finalState"));
            }
            if (expect.has("finalTarget")) {
                JsonElement t = expect.get("finalTarget");
                if (t.isJsonNull()) {
                    assertNull(out.sim().brain.targetId(), ctx + ": expected no target");
                } else {
                    assertEquals(t.getAsString(), out.sim().brain.targetId(), ctx + ": target");
                }
            }
            if (expect.has("orbitDirection")) {
                assertEquals(expect.get("orbitDirection").getAsString(), String.valueOf(out.sim().brain.orbitDir()), ctx + ": orbit direction");
            }
            if (expect.has("minOrbitDegrees")) {
                double deg = Double.parseDouble(out.sim().brain.summary(out.sim().now).get("bandit.orbitProgressDeg"));
                assertTrue(deg >= expect.get("minOrbitDegrees").getAsDouble(), ctx + ": orbit progress " + deg);
            }
            if (expect.has("attackAllowedSeen")) {
                assertEquals(expect.get("attackAllowedSeen").getAsBoolean(), out.sim().attackAllowedSeen, ctx + ": attack allowed");
            }
            if (expect.has("stopReasonStartsWith")) {
                assertTrue(out.sim().stopReason != null && out.sim().stopReason.startsWith(expect.get("stopReasonStartsWith").getAsString()),
                        ctx + ": stop reason " + out.sim().stopReason);
            }
            if (expect.has("lastDecisionIdle") && expect.get("lastDecisionIdle").getAsBoolean()) {
                assertEquals(Decision.Move.Kind.NONE, out.sim().last.move().kind(), ctx);
                assertEquals(Decision.Look.NONE, out.sim().last.look(), ctx);
                assertFalse(out.sim().last.attackAllowed(), ctx);
            }
            if (expect.has("retreatReason")) {
                assertEquals(expect.get("retreatReason").getAsString(), out.sim().brain.retreatReason(), ctx + ": retreat reason");
            }
            if (expect.has("maxStateChanges")) {
                assertTrue(out.stateChanges() <= expect.get("maxStateChanges").getAsInt(), ctx + ": " + out.stateChanges() + " state changes");
            }
            if (expect.has("maxRecoverEntries")) {
                assertTrue(out.recoverEntries() <= expect.get("maxRecoverEntries").getAsInt(), ctx + ": " + out.recoverEntries() + " recover entries");
            }
            if (expect.has("minThreatsSeen")) {
                assertTrue(out.maxThreats() >= expect.get("minThreatsSeen").getAsInt(), ctx + ": threats " + out.maxThreats());
            }
            if (expect.has("minX")) {
                assertTrue(out.sim().x >= expect.get("minX").getAsDouble(), ctx + ": walked into the wall, x=" + out.sim().x);
            }
            if (expect.has("maxX")) {
                assertTrue(out.sim().x <= expect.get("maxX").getAsDouble(), ctx + ": walked into the wall, x=" + out.sim().x);
            }
        }
    }
}
