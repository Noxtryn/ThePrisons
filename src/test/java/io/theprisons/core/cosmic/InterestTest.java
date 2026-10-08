package io.theprisons.core.cosmic;

import io.theprisons.core.cosmic.state.CosmicStateService;
import io.theprisons.core.profiling.Profiler;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class InterestTest {
    @Test
    void consumersWidenAndSpeedUpTheSamplingAndGiveItBack() {
        CosmicStateService service = new CosmicStateService(new Profiler());
        assertEquals(CosmicStateService.ENTITY_RADIUS, service.entityRadius());
        assertEquals(CosmicStateService.SAMPLE_EVERY_TICKS, service.sampleEveryTicks());
        Object macro = new Object();
        Object other = new Object();
        service.interest(macro, 60.0D, 1);
        assertEquals(60.0D, service.entityRadius());
        assertEquals(1, service.sampleEveryTicks());
        service.interest(other, 200.0D, 2);
        assertEquals(CosmicStateService.MAX_ENTITY_RADIUS, service.entityRadius(), "capped");
        assertEquals(1, service.sampleEveryTicks(), "the fastest wish wins");
        service.release(macro);
        assertEquals(CosmicStateService.MAX_ENTITY_RADIUS, service.entityRadius());
        assertEquals(2, service.sampleEveryTicks());
        service.release(other);
        assertEquals(CosmicStateService.ENTITY_RADIUS, service.entityRadius());
        assertEquals(CosmicStateService.SAMPLE_EVERY_TICKS, service.sampleEveryTicks());
        service.release(new Object()); // releasing something unknown is harmless
    }
}
