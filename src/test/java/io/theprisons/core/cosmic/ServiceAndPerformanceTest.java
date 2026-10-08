package io.theprisons.core.cosmic;

import io.theprisons.core.cosmic.data.CosmicContextSnapshot;
import io.theprisons.core.cosmic.data.Raw;
import io.theprisons.core.cosmic.data.SnapshotBuilder;
import io.theprisons.core.cosmic.model.CosmicGameModel;
import io.theprisons.core.cosmic.state.CosmicStateService;
import io.theprisons.core.event.CoreEvents;
import io.theprisons.core.event.EventBus;
import io.theprisons.core.profiling.Profiler;
import io.theprisons.testing.TestFrames;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServiceAndPerformanceTest {
    @Test
    void theServiceStartsLoadsTheModelAndSurvivesAWorldChange() {
        CosmicStateService service = new CosmicStateService(new Profiler());
        assertTrue(service.model().ores().size() >= 7, "the shipped data loads");
        EventBus bus = new EventBus((owner, event, error) -> {
            throw new AssertionError(error);
        });
        service.attach(bus);
        service.store().publish(SnapshotBuilder.build(TestFrames.simple(), SnapshotBuilder.Memory.NONE, service.model()));
        assertNotNull(service.latest());
        service.onMessageForTest("You entered a Diamond Zone");
        assertEquals("diamond", service.zone());
        bus.post(new CoreEvents.WorldChanged(null, null));
        assertNull(service.latest(), "the store starts empty after a world change");
        assertEquals("diamond", service.zone(), "the zone survives a world swap, as lastZone() always did");
    }

    @Test
    void buildingASnapshotIsCheap() {
        CosmicGameModel model = CosmicGameModel.loadDefault();
        List<Raw.Entity> entities = new ArrayList<>();
        for (int i = 0; i < 96; i++) { // the sensor's cap
            entities.add(TestFrames.entity(i, i % 5 == 0 ? "minecraft:player" : "minecraft:zombie", i % 5 == 0 ? "bandit_ae_" + String.format("%06x", i) : "Zombie", i % 5 == 0, i % 5 != 0, i));
        }
        Raw.Frame frame = TestFrames.frame(TestFrames.player("Steve", TestFrames.stack("minecraft:diamond_pickaxe", "Pickaxe",
                List.of("Cosmic Energy", "|||", "(242,159 / 293,135)"))), entities, List.of("Account Steve", "Guard XP Tax 10%", "Cosmic Energy", "34% (level 71)",
                "(456,410 / 1,259,523)"));
        for (int i = 0; i < 300; i++) { // warm up
            SnapshotBuilder.build(frame, SnapshotBuilder.Memory.NONE, model);
        }
        int runs = 2_000;
        long start = System.nanoTime();
        CosmicContextSnapshot last = null;
        for (int i = 0; i < runs; i++) {
            last = SnapshotBuilder.build(frame, SnapshotBuilder.Memory.NONE, model);
        }
        double perSampleMs = (System.nanoTime() - start) / 1e6 / runs;
        System.out.printf("[cosmic] snapshot build with 96 entities: %.4f ms per sample (5 samples per second in game)%n", perSampleMs);
        assertNotNull(last);
        assertEquals(64, last.combat().entities().size());
        // Generous: the real budget is a fraction of a millisecond; this only catches an accidental O(n^2) or allocation storm.
        assertTrue(perSampleMs < 2.0D, "snapshot build too slow: " + perSampleMs + " ms");
    }
}
