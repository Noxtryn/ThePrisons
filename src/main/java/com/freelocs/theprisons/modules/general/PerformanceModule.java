package com.freelocs.theprisons.modules.general;

import com.freelocs.theprisons.core.hud.HudLine;
import com.freelocs.theprisons.core.module.Category;
import com.freelocs.theprisons.core.module.Module;
import com.freelocs.theprisons.core.profiling.Profiler;
import com.freelocs.theprisons.core.setting.Settings;
import com.freelocs.theprisons.core.world.WorldCache;

import java.util.List;
import java.util.Locale;

/** Shows the busiest profiler sections on the HUD; {@code /prisons perf} prints the full table. */
public final class PerformanceModule extends Module {
    private final Profiler profiler;
    private final WorldCache world;
    private final Settings.IntSetting rows;
    private final Settings.BoolSetting showMax;

    public PerformanceModule(Profiler profiler, WorldCache world) {
        super("performance", "Performance Monitor", Category.GENERAL, "Core",
                "Live cost of every core service and module (average / worst case per tick).",
                Settings.KeybindSetting.NONE);
        this.profiler = profiler;
        this.world = world;
        rows = integer("rows", "Rows", 6, 2, 14, 1).group("Display");
        showMax = bool("show_max", "Show worst case", true).group("Display");
        action("reset", "Reset measurements", "Reset", profiler::reset).group("Display");
    }

    @Override
    public void collectHud(List<HudLine> out) {
        out.add(new HudLine("Cache", world.store().size() + " sections, " + world.store().ores().size() + " targets", 0xFF9AA0B8));
        int shown = 0;
        for (Profiler.Section section : profiler.sections()) {
            if (section.calls() == 0) {
                continue;
            }
            String value = showMax.on()
                    ? String.format(Locale.ROOT, "%.3f / %.2f ms", section.avgMs(), section.maxMs())
                    : String.format(Locale.ROOT, "%.3f ms", section.avgMs());
            int color = section.maxMs() > 5.0D ? 0xFFFF5E6C : section.avgMs() > 0.5D ? 0xFFFFC14D : 0xFF65F59B;
            out.add(new HudLine(section.name(), value, color));
            if (++shown >= rows.value()) {
                break;
            }
        }
    }
}
