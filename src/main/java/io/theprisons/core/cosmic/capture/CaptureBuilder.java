package io.theprisons.core.cosmic.capture;

import io.theprisons.core.cosmic.data.Raw;
import io.theprisons.core.cosmic.data.SnapshotBuilder;
import io.theprisons.core.cosmic.model.CosmicGameModel;
import io.theprisons.core.cosmic.parse.PrivacyFilter;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.List;

/** Builds a {@link Capture} from a frame: anonymise, then run the parsers on the anonymised frame to record the expected result. */
public final class CaptureBuilder {
    private CaptureBuilder() {
    }

    public static Capture build(Raw.Frame frame, String zone, @Nullable String event, List<Raw.Line> systemLines, CosmicGameModel model,
                                String modVersion, String minecraftVersion, String note, String category) {
        Anonymizer anonymizer = new Anonymizer(frame);
        Raw.Frame safe = anonymizer.frame(frame);
        List<String> lines = systemLines.stream().map(Raw.Line::text).filter(l -> !PrivacyFilter.isPrivateMessage(l)).map(anonymizer::text).toList();
        Capture.Mem memory = new Capture.Mem(anonymizer.text(zone), event, lines);
        SnapshotBuilder.Memory view = new SnapshotBuilder.Memory(memory.zone(), memory.event(), safe.actionBar());
        var summary = SnapshotSummary.of(SnapshotBuilder.build(safe, view, model));
        Capture.Meta meta = new Capture.Meta(Instant.now().toString(), modVersion, minecraftVersion, PrivacyFilter.redact(note == null ? "" : note),
                false, category == null ? "" : category);
        return new Capture(Capture.SCHEMA, Capture.KIND, meta, safe, memory, summary);
    }
}
