package io.theprisons.gui.hud;

import io.theprisons.ThePrisonsClient;
import io.theprisons.core.ThePrisonsCore;
import io.theprisons.core.module.Module;
import io.theprisons.core.setting.Settings;
import io.theprisons.gui.config.ConfigCategory;

import java.util.ArrayList;
import java.util.List;

/**
 * The one HUD layout service: which elements exist, whether they are shown, and how a dragged element snaps. The
 * snap switch and the grid size are the settings {@code hud_layout.snap} and {@code hud_layout.grid}; the HUD editor
 * reads and writes them, so the config GUI and the editor always agree.
 */
public final class HudLayout {
    public static final int SNAP_THRESHOLD = 4;
    public static final int MIN_GRID = 6;
    public static final int MAX_GRID = 32;

    private HudLayout() {
    }

    // ── Pure snapping (no Minecraft needed) ──────────────────────────────────

    /** The multiple of {@code grid} nearest to {@code value}. */
    public static int snapToGrid(int value, int grid) {
        int g = Math.max(1, grid);
        return Math.round(value / (float) g) * g;
    }

    /**
     * Snaps one axis of a box: to the screen edges and the centre line when within {@code threshold}, else to the
     * grid. The result is kept on screen. {@code guide} is set to {@code true} on the centre line.
     */
    public static int snapAxis(int pos, int size, int screen, int grid, int threshold, boolean[] guide) {
        guide[0] = false;
        int max = Math.max(0, screen - size);
        int centre = (screen - size) / 2;
        int snapped;
        if (Math.abs(pos) <= threshold) {
            snapped = 0;
        } else if (Math.abs(pos - max) <= threshold) {
            snapped = max;
        } else if (Math.abs(pos - centre) <= threshold) {
            snapped = centre;
            guide[0] = true;
        } else {
            snapped = snapToGrid(pos, grid);
        }
        return Math.max(0, Math.min(snapped, max));
    }

    // ── Settings ─────────────────────────────────────────────────────────────

    private static Settings.BoolSetting snapSetting() {
        ThePrisonsCore core = ThePrisonsCore.getOrNull();
        Module layout = core == null ? null : core.modules().get("hud_layout");
        return layout != null && layout.setting("snap") instanceof Settings.BoolSetting bool ? bool : null;
    }

    private static Settings.IntSetting gridSetting() {
        ThePrisonsCore core = ThePrisonsCore.getOrNull();
        Module layout = core == null ? null : core.modules().get("hud_layout");
        return layout != null && layout.setting("grid") instanceof Settings.IntSetting integer ? integer : null;
    }

    public static boolean snapEnabled() {
        Settings.BoolSetting snap = snapSetting();
        return snap == null || snap.on();
    }

    public static void setSnapEnabled(boolean on) {
        Settings.BoolSetting snap = snapSetting();
        if (snap != null) {
            snap.set(on);
        }
    }

    public static int grid() {
        Settings.IntSetting grid = gridSetting();
        return grid == null ? 8 : grid.get();
    }

    public static void setGrid(int size) {
        Settings.IntSetting grid = gridSetting();
        if (grid != null) {
            grid.set(Math.max(MIN_GRID, Math.min(MAX_GRID, size)));
        }
    }

    // ── Elements ─────────────────────────────────────────────────────────────

    /** Every HUD element of this build, shown or not (the editor lists them all so a hidden one can come back). */
    public static List<HudElement> elements(ThePrisonsCore core) {
        List<HudElement> all = new ArrayList<>();
        for (Module module : core.modules().all()) {
            if (module instanceof HudElement element && ConfigCategory.listed(module)) {
                all.add(element);
            }
        }
        for (HudElement element : LegacyHudElements.all()) {
            for (String id : element.moduleIds()) {
                Module owner = core.modules().get(id);
                if (owner != null && ConfigCategory.listed(owner)) {
                    all.add(element);
                    break;
                }
            }
        }
        return all;
    }

    /** True while at least one module behind the element is on. */
    public static boolean visible(ThePrisonsCore core, HudElement element) {
        for (String id : element.moduleIds()) {
            Module module = core.modules().get(id);
            if (module != null && module.enabled()) {
                return true;
            }
        }
        return false;
    }

    /** Shows or hides an element by switching its modules (the same switch as in the config GUI). */
    public static void setVisible(ThePrisonsCore core, HudElement element, boolean on) {
        for (String id : element.moduleIds()) {
            Module module = core.modules().get(id);
            if (module != null && module.enabled() != on) {
                core.modules().toggle(module);
            }
        }
        ThePrisonsClient.CONFIG.saveAsync();
    }
}
