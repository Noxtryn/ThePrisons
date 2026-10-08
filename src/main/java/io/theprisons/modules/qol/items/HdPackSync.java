package io.theprisons.modules.qol.items;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * Keeps the optional HD V2 item pack and the setting in step with ONE resource reload (pure logic, no Minecraft classes).
 *
 * <p>What the player chose ({@link #wanted}) and what the resource packs were last built with ({@link #applied}) are two different things: the pack manager
 * builds its list at its own moments (the first load at start, every reload), the setting is loaded from the config file at another one. A reload is
 * requested only when the two differ, only after the pack manager built a list at all, and never while a reload is already under way - so loading the
 * config at start, changing the setting, or listing the packs can never cause a loop or a second reload.
 */
public final class HdPackSync {
    /** The built-in pack, relative to the mod's root. */
    public static final String PACK_PATH = "resourcepacks/theprisons_items_hd";
    public static final String PACK_ID = "theprisons_items_hd";
    public static final String PACK_NAME = "ThePrisons HD V2 Items";

    /** The setting's current choice; false until the item look module exists (so nothing is appended too early). */
    private static volatile boolean chosen;

    /** What actually reloads the resources; set by the module (the client may not exist yet). */
    private static volatile Runnable reloader;
    private static final HdPackSync SHARED = new HdPackSync(() -> chosen, () -> {
        Runnable r = reloader;
        if (r != null) {
            r.run();
        }
    });

    private final BooleanSupplier wanted;
    private final Runnable reload;
    private boolean computedOnce;
    private boolean applied;
    private boolean pending;
    private int reloads;

    public HdPackSync(BooleanSupplier wanted, Runnable reload) {
        this.wanted = wanted;
        this.reload = reload;
    }

    /** The one instance of the game: the module reconciles it, the resource-pack mixin reports to it. */
    public static HdPackSync shared() {
        return SHARED;
    }

    public static void setReloader(Runnable r) {
        reloader = r;
    }

    /** The module sets this when the setting exists / changes (it is the only writer). */
    public static void choose(boolean hdV2) {
        chosen = hdV2;
    }

    /** Whether HD V2 is chosen; false when the module is not initialised yet. */
    public static boolean hdV2Chosen() {
        return chosen;
    }

    public boolean wanted() {
        return wanted.getAsBoolean();
    }

    /**
     * The pack manager has built its list. {@code included} = the HD pack is in it. When it was wanted but could not be added (the folder is missing from
     * the jar) the state counts as applied: a missing pack must not cause a reload on every tick.
     */
    public synchronized void packsComputed(boolean wantedAtBuild, boolean included) {
        computedOnce = true;
        applied = included || wantedAtBuild;
        pending = false;
    }

    /**
     * Called on the client thread (after a setting change and every tick, it is two comparisons): reloads once when what is wanted is not what the
     * packs were built with.
     *
     * @return true when a reload was requested now
     */
    public synchronized boolean reconcile() {
        if (!computedOnce || pending || wanted.getAsBoolean() == applied) {
            return false;
        }
        pending = true;
        reloads++;
        reload.run();
        return true;
    }

    public synchronized int reloads() {
        return reloads;
    }

    public synchronized boolean applied() {
        return applied;
    }

    /**
     * The pack list the manager returns: what it built, then the HD pack (when wanted and available), then the look pack last (it stays on top, as
     * before). Never adds the HD pack twice.
     */
    public static <P> List<P> assemble(List<P> built, boolean hdWanted, P hd, P look) {
        List<P> out = new ArrayList<>(built);
        if (hdWanted && hd != null && !out.contains(hd)) {
            out.add(hd);
        }
        if (look != null && !out.contains(look)) {
            out.add(look);
        }
        return out;
    }
}
