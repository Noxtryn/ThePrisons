package io.theprisons.core.module;

import io.theprisons.core.concurrent.Worker;
import io.theprisons.core.event.EventBus;
import io.theprisons.core.profiling.Profiler;
import io.theprisons.core.tick.TickScheduler;

/** The kernel services every module gets; everything else is injected explicitly into the module's constructor. */
public interface ModuleHost {
    EventBus bus();

    TickScheduler scheduler();

    Worker worker();

    Profiler profiler();

    void disable(Module module, String reason);

    void notify(Notice notice);

    /** Short user-facing message. */
    record Notice(String title, String body, Level level) {
    }

    enum Level { INFO, SUCCESS, WARNING, ERROR }
}
