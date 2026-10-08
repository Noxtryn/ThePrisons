package io.theprisons.core.control;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.GameOptions;
import net.minecraft.client.option.KeyBinding;

/**
 * The only writer of the movement keys. The lease holder sets the wanted state every tick; {@link #apply} presses
 * exactly those keys through the normal key bindings, so vanilla movement and collision do the actual motion (the
 * player position or velocity is never set).
 *
 * <p>While any screen is open nothing is pressed (the player is typing / looking at an inventory); the holder sees
 * {@link #paused()}. Releasing only lifts keys this controller pressed, so keys the player really holds stay down.
 */
public final class InputController {
    /** Wanted key state for one tick. */
    public record Keys(boolean forward, boolean back, boolean left, boolean right, boolean jump, boolean sprint, boolean sneak) {
        public static final Keys NONE = new Keys(false, false, false, false, false, false, false);
    }

    private Keys wanted = Keys.NONE;
    private Keys applied = Keys.NONE;
    private boolean paused;
    private IntentPriority wantedPriority = IntentPriority.IDLE;
    private String wantedSource = "";
    private IntentPriority winnerPriority = IntentPriority.IDLE;
    private String winnerSource = "";
    private int rejected;

    /**
     * The macro's own wish at PATHFINDING priority. Among requests of the same priority the last one wins, exactly like the old
     * "last set wins"; only a higher priority ({@link #request}) can override it within a tick.
     */
    public void set(Keys keys) {
        request(IntentPriority.PATHFINDING, "macro", keys);
    }

    /**
     * Asks for a key state this tick. The highest priority wins, the later request among equals. Returns false when a higher
     * priority already holds the keys this tick. The winner stays wanted until somebody asks again (as before).
     */
    public boolean request(IntentPriority priority, String source, Keys keys) {
        if (priority.rank() < wantedPriority.rank()) {
            rejected++;
            return false;
        }
        wanted = keys;
        wantedPriority = priority;
        wantedSource = source;
        return true;
    }

    public Keys wanted() {
        return wanted;
    }

    /** Who won the keys in the last applied tick ("macro", "unstuck" ...), for the telemetry. */
    public String winnerSource() {
        return winnerSource;
    }

    public IntentPriority winnerPriority() {
        return winnerPriority;
    }

    /** Requests that lost against a higher priority since start. */
    public int rejectedRequests() {
        return rejected;
    }

    public void clear() {
        wanted = Keys.NONE;
        wantedPriority = IntentPriority.IDLE;
        wantedSource = "";
    }

    public boolean paused() {
        return paused;
    }

    /** Presses the wanted keys (end of tick, after all modules decided). */
    public void apply(MinecraftClient client, boolean active) {
        paused = client.currentScreen != null;
        Keys target = !active || paused ? Keys.NONE : wanted;
        // Pressed every tick: a key event from the real keyboard may have lifted one of ours.
        press(client.options, target);
        applied = target;
        winnerPriority = wantedPriority;
        winnerSource = wantedSource;
        wantedPriority = IntentPriority.IDLE; // next tick everybody may ask again; the wanted keys themselves stay
    }

    /** Lifts the keys this controller pressed. */
    public void release(MinecraftClient client) {
        wanted = Keys.NONE;
        GameOptions options = client.options;
        if (applied.forward()) {
            options.forwardKey.setPressed(false);
        }
        if (applied.back()) {
            options.backKey.setPressed(false);
        }
        if (applied.left()) {
            options.leftKey.setPressed(false);
        }
        if (applied.right()) {
            options.rightKey.setPressed(false);
        }
        if (applied.jump()) {
            options.jumpKey.setPressed(false);
        }
        if (applied.sprint()) {
            options.sprintKey.setPressed(false);
        }
        if (applied.sneak()) {
            options.sneakKey.setPressed(false);
        }
        applied = Keys.NONE;
    }

    private void press(GameOptions options, Keys keys) {
        set(options.forwardKey, keys.forward(), applied.forward());
        set(options.backKey, keys.back(), applied.back());
        set(options.leftKey, keys.left(), applied.left());
        set(options.rightKey, keys.right(), applied.right());
        set(options.jumpKey, keys.jump(), applied.jump());
        set(options.sprintKey, keys.sprint(), applied.sprint());
        set(options.sneakKey, keys.sneak(), applied.sneak());
    }

    private static void set(KeyBinding key, boolean pressed, boolean wasOurs) {
        if (pressed) {
            key.setPressed(true);
        } else if (wasOurs) {
            key.setPressed(false);
        }
    }
}
