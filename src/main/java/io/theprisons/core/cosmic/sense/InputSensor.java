package io.theprisons.core.cosmic.sense;

import io.theprisons.core.cosmic.data.Raw;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;

/**
 * The keys the HUMAN holds, read from the keyboard itself (not from {@code KeyBinding.isPressed}, which a macro sets too).
 * Only keyboard keys are read; a movement key bound to a mouse button reads as not pressed. The macro safety rule "manual input
 * stops the macro" uses {@link #anyMovementPhysical}.
 */
public final class InputSensor {
    private InputSensor() {
    }

    public static boolean physical(MinecraftClient client, KeyBinding key) {
        InputUtil.Key bound = InputUtil.fromTranslationKey(key.getBoundKeyTranslationKey());
        return bound.getCategory() == InputUtil.Type.KEYSYM && bound.getCode() > 0
                && InputUtil.isKeyPressed(client.getWindow(), bound.getCode());
    }

    /** True when a forward / back / left / right key is held on the real keyboard. */
    public static boolean anyMovementPhysical(MinecraftClient client) {
        KeyBinding[] keys = {client.options.forwardKey, client.options.backKey, client.options.leftKey, client.options.rightKey};
        for (KeyBinding key : keys) {
            if (physical(client, key)) {
                return true;
            }
        }
        return false;
    }

    public static Raw.Input sample(MinecraftClient client) {
        return new Raw.Input(physical(client, client.options.forwardKey), physical(client, client.options.backKey),
                physical(client, client.options.leftKey), physical(client, client.options.rightKey),
                physical(client, client.options.jumpKey), physical(client, client.options.sneakKey),
                physical(client, client.options.sprintKey), physical(client, client.options.attackKey),
                physical(client, client.options.useKey));
    }
}
