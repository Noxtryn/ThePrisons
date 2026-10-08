package io.theprisons.testing;

import io.theprisons.core.cosmic.capture.Capture;
import io.theprisons.core.cosmic.capture.CaptureException;
import io.theprisons.core.cosmic.capture.CaptureIO;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/** Finds and loads the capture fixtures under {@code src/test/resources/cosmic/<category>/*.json}. */
public final class CosmicFixtures {
    public static final List<String> CATEGORIES = List.of("ores", "pickaxes", "bandits", "spears", "zones", "items", "masks", "ah",
            "energy-extractor", "events");

    private CosmicFixtures() {
    }

    public static Path root() {
        try {
            return Path.of(CosmicFixtures.class.getResource("/cosmic").toURI());
        } catch (URISyntaxException error) {
            throw new IllegalStateException(error);
        }
    }

    /** Every fixture file, sorted. */
    public static List<Path> all() throws IOException {
        try (Stream<Path> walk = Files.walk(root())) {
            return walk.filter(p -> p.getFileName().toString().endsWith(".json")).sorted().toList();
        }
    }

    public static Capture load(String category, String name) throws CaptureException {
        return CaptureIO.read(root().resolve(category).resolve(name + ".json"));
    }
}
