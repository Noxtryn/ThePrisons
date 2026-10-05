package io.theprisons.modules.general.tunnel;

import io.theprisons.modules.mining.ore.OreMacroModule;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.math.MathHelper;
import org.jspecify.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;

/**
 * Watches what the macro does (every tick): how fast the player moves, how hard they steer (the road bends with it),
 * and what the macro is busy with - changes of that become notifications. Pure reading, never touches the game.
 */
final class TunnelScanner {
    /** A notification of the show. */
    record Event(String title, String detail, int color) {
    }

    private double lastX = Double.NaN;
    private double lastZ;
    private float lastYaw = Float.NaN;
    private double speed;
    private double steer;
    private String state = "";
    private String label = "Free roam";
    private final Deque<Event> events = new ArrayDeque<>();

    /** Horizontal speed in blocks per second (smoothed). */
    double speed() {
        return speed;
    }

    /** -1 (hard left) .. 1 (hard right), smoothed; the road bends this way. */
    double steer() {
        return steer;
    }

    /** What the macro does right now, in words. */
    String label() {
        return label;
    }

    @Nullable Event poll() {
        return events.poll();
    }

    void reset() {
        lastX = Double.NaN;
        lastYaw = Float.NaN;
        speed = 0.0D;
        steer = 0.0D;
        state = "";
        events.clear();
    }

    void push(Event event) {
        if (events.size() < 6) {
            events.add(event);
        }
    }

    void tick(MinecraftClient client, @Nullable OreMacroModule macro) {
        ClientPlayerEntity player = client.player;
        if (player == null) {
            return;
        }
        if (!Double.isNaN(lastX)) {
            double v = Math.hypot(player.getX() - lastX, player.getZ() - lastZ) * 20.0D;
            speed += (Math.min(v, 12.0D) - speed) * 0.25D;
            float dyaw = MathHelper.wrapDegrees(player.getYaw() - lastYaw);
            double target = MathHelper.clamp(dyaw * 20.0D / 110.0D, -1.0D, 1.0D);
            steer += (target - steer) * 0.12D;
        }
        lastX = player.getX();
        lastZ = player.getZ();
        lastYaw = player.getYaw();

        String now = macro != null && macro.enabled() ? macro.botState() : "";
        if (!now.equals(state)) {
            Event event = describe(state, now, macro);
            state = now;
            label = event != null ? event.title() : label;
            if (now.isEmpty()) {
                label = "Free roam";
            }
            if (event != null) {
                push(event);
            }
        }
    }

    private static @Nullable Event describe(String before, String now, @Nullable OreMacroModule macro) {
        if (now.isEmpty()) {
            return before.isEmpty() ? null : new Event("Macro stopped", "You have the controls", 0xFFB0B8C8);
        }
        String detail = macro == null ? "" : macro.statusText();
        return switch (now.toUpperCase(Locale.ROOT)) {
            case "MINING" -> new Event("Mining", detail.isEmpty() ? "Digging for ore" : detail, 0xFF4FE8E0);
            case "SORTING LOOT" -> new Event("Sorting loot", "Trip to the vaults", 0xFFFF8A2E);
            case "REDEEMING MONEY" -> new Event("Cashing in", "Redeeming money notes", 0xFFFFC93C);
            case "SELLING" -> new Event("Selling", "Everything must go", 0xFFFFC93C);
            case "EXTRACTING ENERGY" -> new Event("Energy", "Extracting to a vault", 0xFFFF7AC8);
            case "USING ITEM" -> new Event("Power up", "Pet / ability ready", 0xFFA66CFF);
            case "ESCAPING" -> new Event("Escaping!", "Running to a guard", 0xFFF0384C);
            case "DIED: RECOVERING" -> new Event("Recovering", "Back from the dead", 0xFFF0384C);
            case "RETURNING TO GUARDS" -> new Event("Back to the guards", "Staying in the safe zone", 0xFF4CD964);
            case "BREAK" -> new Event("Break", "A short rest", 0xFF3C9CFF);
            case "WAITING" -> new Event("Waiting", detail, 0xFFB0B8C8);
            case "RECOVERING" -> new Event("Recovering", "Finding the way again", 0xFFFFC93C);
            default -> new Event(now.charAt(0) + now.substring(1).toLowerCase(Locale.ROOT), detail, 0xFF4FE8E0);
        };
    }
}
