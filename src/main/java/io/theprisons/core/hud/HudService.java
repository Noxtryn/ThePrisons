package io.theprisons.core.hud;

import io.theprisons.core.module.Module;
import io.theprisons.core.module.ModuleManager;

import java.util.ArrayList;
import java.util.List;

/**
 * Collects the HUD rows of enabled modules every {@value #REFRESH_TICKS} ticks into an immutable list. The HUD
 * renderer only draws that list, so no module code runs per frame and the renderer allocates nothing from modules.
 */
public final class HudService {
    public static final int REFRESH_TICKS = 5;

    /** Rows of one module, with a header. */
    public record Block(String title, int color, List<HudLine> lines) {
    }

    private final ModuleManager modules;
    private volatile List<Block> blocks = List.of();
    private int ticks;

    public HudService(ModuleManager modules) {
        this.modules = modules;
    }

    public void tick() {
        if (++ticks % REFRESH_TICKS != 0) {
            return;
        }
        List<Block> next = new ArrayList<>();
        List<HudLine> scratch = new ArrayList<>();
        for (Module module : modules.all()) {
            if (!module.enabled()) {
                continue;
            }
            scratch.clear();
            module.collectHud(scratch);
            if (!scratch.isEmpty()) {
                next.add(new Block(module.name(), 0xFFC084FC, List.copyOf(scratch)));
            }
        }
        blocks = List.copyOf(next);
    }

    /** Current rows; safe to call from the render thread every frame. */
    public List<Block> blocks() {
        return blocks;
    }
}
