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

    public void set(Keys keys) {
        wanted = keys;
    }

    public Keys wanted() {
        return wanted;
    }

    public void clear() {
        wanted = Keys.NONE;
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
