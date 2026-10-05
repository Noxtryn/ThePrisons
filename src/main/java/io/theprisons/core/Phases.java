package io.theprisons.core;

/**
 * Listener priorities of {@code CoreEvents.TickEnd}; higher runs first. One tick of automation is:
 * <pre>
 *  WORLD (1000)          world cache scan / invalidation
 *  KEYBINDS (950)        module keybinds
 *  SAFETY (900)          safety rules for the lease holder (may stop it before it acts)
 *  SCHEDULER (800)       periodic tasks
 *  DECIDE (0)            modules: choose targets, request view / keys
 *  CONTROL (-1000)       core applies the winning view request and the keys
 *  ACT (-1100)           modules: act on the view that was just applied (break the block in the crosshair)
 *  HOUSEKEEPING (-2000)  HUD rows, config debounce
 * </pre>
 */
public final class Phases {
    public static final int WORLD = 1000;
    public static final int KEYBINDS = 950;
    public static final int SAFETY = 900;
    public static final int SCHEDULER = 800;
    public static final int DECIDE = 0;
    public static final int CONTROL = -1000;
    public static final int ACT = -1100;
    public static final int HOUSEKEEPING = -2000;

    private Phases() {
    }
}
