package io.theprisons.core.cosmic.capture;

import io.theprisons.core.cosmic.data.CosmicContextSnapshot;
import io.theprisons.core.cosmic.data.SnapshotBuilder;
import io.theprisons.core.cosmic.model.CosmicGameModel;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/** Feeds a capture through the current parsers and compares with what was recorded: the regression test for every parser. */
public final class Replay {
    /** @param differences "key: expected X, got Y" for every changed summary entry (empty when the capture has no expectation or all matches) */
    public record Result(boolean matches, List<String> differences, CosmicContextSnapshot snapshot, Map<String, String> actual) {
    }

    private Replay() {
    }

    public static Result run(Capture capture, CosmicGameModel model) {
        SnapshotBuilder.Memory memory = new SnapshotBuilder.Memory(capture.memory().zone(), capture.memory().event(), capture.frame().actionBar());
        CosmicContextSnapshot snapshot = SnapshotBuilder.build(capture.frame(), memory, model);
        Map<String, String> actual = SnapshotSummary.of(snapshot);
        List<String> differences = new ArrayList<>();
        Map<String, String> expected = capture.expected();
        if (expected != null) {
            for (String key : new TreeSet<>(union(expected, actual))) {
                String want = expected.get(key);
                String got = actual.get(key);
                if (want == null ? got != null : !want.equals(got)) {
                    differences.add(key + ": expected " + want + ", got " + got);
                }
            }
        }
        return new Result(differences.isEmpty(), differences, snapshot, actual);
    }

    private static java.util.Set<String> union(Map<String, String> a, Map<String, String> b) {
        java.util.Set<String> keys = new java.util.HashSet<>(a.keySet());
        keys.addAll(b.keySet());
        return keys;
    }
}
