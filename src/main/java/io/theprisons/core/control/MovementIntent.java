package io.theprisons.core.control;

/**
 * What a system wants the movement keys to be this tick. Systems only describe the wish; {@link ControlService#submit(MovementIntent)}
 * keeps the highest {@link IntentPriority} (the later one among equals) and the control layer presses that one state at the end of the tick.
 */
public record MovementIntent(IntentPriority priority, String source, InputController.Keys keys) {
    public static MovementIntent none(IntentPriority priority, String source) {
        return new MovementIntent(priority, source, InputController.Keys.NONE);
    }
}
