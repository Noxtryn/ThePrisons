package io.theprisons.core.cosmic.capture;

import io.theprisons.core.cosmic.data.Raw;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Map;

/**
 * One recorded Cosmic Prisons situation: the raw client readings of a single moment (anonymised) plus what the parsers made of
 * them at the time. A capture file is a deterministic test fixture: {@link Replay} feeds {@link #frame()} through the same
 * code and compares with {@link #expected()}.
 *
 * <p>Nothing in a capture comes from the launcher, the account or the network: it is built from
 * {@link io.theprisons.core.cosmic.data.Raw} (what the client displays) only.
 *
 * @param schema   file format version; a capture with an older schema is "stale" and is refused with a clear message
 * @param frame    the readings
 * @param memory   what the model remembered (zone, event, system lines)
 * @param expected the parsers' result when it was captured (see {@link SnapshotSummary}); null = not checked
 * @param extras   what modules add about their own state at that moment (the bandit macro: FSM state, target, threats, orbit side, terrain
 *                 probes ...). Plain key / value text, anonymised like everything else; informational, not replayed. Missing in older files.
 */
public record Capture(int schema, String kind, Meta meta, Raw.Frame frame, Mem memory, @Nullable Map<String, String> expected,
                      Map<String, String> extras) {
    public static final int SCHEMA = 1;
    public static final String KIND = "theprisons-capture";

    /** Hand-edited or sparse files miss parts: they load as empty parts, not as a crash. */
    public Capture {
        meta = meta == null ? new Meta("", "", "", "", false, "") : meta;
        memory = memory == null ? new Mem("", null, List.of()) : memory;
        extras = extras == null ? Map.of() : java.util.Collections.unmodifiableMap(new java.util.TreeMap<>(extras));
    }

    /** Without extras. */
    public Capture(int schema, String kind, Meta meta, Raw.Frame frame, Mem memory, @Nullable Map<String, String> expected) {
        this(schema, kind, meta, frame, memory, expected, Map.of());
    }

    /**
     * @param synthetic true for hand-made fixtures (the formats in them are copied from known game text, not recorded live)
     * @param category  the fixture folder it belongs to ("pickaxes", "bandits", ...), free text for real captures
     */
    public record Meta(String createdAt, String modVersion, String minecraftVersion, String note, boolean synthetic, String category) {
    }

    public record Mem(String zone, @Nullable String event, List<String> systemLines) {
        public Mem {
            zone = zone == null ? "" : zone;
            systemLines = systemLines == null ? List.of() : List.copyOf(systemLines);
        }
    }
}
