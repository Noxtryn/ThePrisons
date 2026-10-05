package io.theprisons.modules.mining.ore.route;

import io.theprisons.core.event.CoreEvents;
import io.theprisons.core.hud.HudLine;
import io.theprisons.core.module.Category;
import io.theprisons.core.module.Module;
import io.theprisons.core.module.ModuleManager;
import io.theprisons.core.render.Overlay;
import io.theprisons.core.setting.Settings;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import org.jspecify.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Edits a saved route in the world. Pick the route in the GUI and press "Start editing": the route is shown with its
 * numbered waypoints; <b>left click</b> on a block adds a waypoint there (its number follows from where it fits into the
 * route, see {@link WaypointMath#insertionIndex}), <b>right click</b> on a waypoint deletes it, <b>Enter</b> saves,
 * <b>Backspace</b> throws the changes away. While editing, clicks do not break or use blocks.
 */
public final class WaypointEditorModule extends Module {
    private static final double LOOK_DISTANCE = 64.0D;
    private static final int EDIT_COLOR = 0xFFFFC14D;

    private final RouteStore routes;
    private final Settings.ChoiceSetting route;
    private final List<int[]> points = new ArrayList<>();
    private @Nullable String editing;
    private boolean changed;
    private int looked = -1;
    private boolean enterDown;
    private boolean backspaceDown;
    private int ticks;

    public WaypointEditorModule(RouteStore routes, ModuleManager modules) {
        super("waypoint_editor", "Waypoint Editor", Category.MINING, "Macros",
                "Shows a saved route with its numbered waypoints: left click adds a waypoint (numbered automatically by "
                        + "where it fits in the route), right click on a waypoint deletes it, Enter saves, Backspace discards.",
                Settings.KeybindSetting.NONE);
        this.routes = routes;
        route = add(new Settings.ChoiceSetting("route", "Route", "Pick a route",
                () -> routes.options(MinecraftClient.getInstance())))
                .description("Only saved routes (record one with the \"Start/Stop route recording\" keys first).").group("Route");
        action("start", "Editor", "Start editing", () -> {
            MinecraftClient.getInstance().setScreen(null);
            if (!enabled()) {
                modules.enable(this);
            }
        }).group("Route");
    }

    @Override
    public boolean persistEnabled() {
        return false;
    }

    @Override
    public @Nullable String canEnable() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) {
            return "Join a world first.";
        }
        if (route.off()) {
            return "Pick a route first.";
        }
        return routes.find(client, route.get()) == null ? "Route \"" + route.get() + "\" is not saved in this world." : null;
    }

    @Override
    protected void onEnable() {
        MinecraftClient client = MinecraftClient.getInstance();
        Route r = routes.find(client, route.get());
        if (r == null) {
            disableSelf("Route not found");
            return;
        }
        editing = r.name();
        points.clear();
        for (int[] p : r.waypoints()) {
            points.add(p.clone());
        }
        changed = false;
        looked = -1;
        enterDown = true;
        backspaceDown = true;
        ticks = 0;
        on(CoreEvents.TickStart.class, event -> tick(event.client()));
        on(CoreEvents.WorldRender.class, this::render);
        message(client, "Editing \"" + editing + "\": left click = add, right click on a waypoint = delete, Enter = save, "
                + "Backspace = discard.", Formatting.AQUA);
    }

    @Override
    protected void onDisable() {
        editing = null;
        points.clear();
        looked = -1;
    }

    /** Runs before Minecraft handles input, so the clicks used here never break or use a block. */
    private void tick(MinecraftClient client) {
        ClientPlayerEntity player = client.player;
        if (player == null || client.world == null) {
            disableSelf("Left the world");
            return;
        }
        var attack = client.options.attackKey;
        var use = client.options.useKey;
        if (client.currentScreen != null) {
            // Enter / Backspace typed in a screen (e.g. the chat) must be released before they count here.
            enterDown = true;
            backspaceDown = true;
            return;
        }
        looked = lookedAt(player);
        while (attack.wasPressed()) {
            add(client, player);
        }
        while (use.wasPressed()) {
            delete(client);
        }
        attack.setPressed(false);
        use.setPressed(false);

        boolean enter = InputUtil.isKeyPressed(client.getWindow(), GLFW.GLFW_KEY_ENTER)
                || InputUtil.isKeyPressed(client.getWindow(), GLFW.GLFW_KEY_KP_ENTER);
        boolean backspace = InputUtil.isKeyPressed(client.getWindow(), GLFW.GLFW_KEY_BACKSPACE);
        if (enter && !enterDown) {
            save(client);
        } else if (backspace && !backspaceDown) {
            message(client, "Changes to \"" + editing + "\" discarded.", Formatting.GRAY);
            disableSelf("Discarded");
        }
        enterDown = enter;
        backspaceDown = backspace;
        if (++ticks % 20 == 0 && enabled()) {
            player.sendMessage(Text.literal(String.format(Locale.ROOT, "✎ %s: %d waypoints%s · L add · R delete · Enter save · Backspace discard",
                    editing, points.size(), changed ? " (changed)" : "")).formatted(Formatting.GOLD), true);
        }
    }

    private void add(MinecraftClient client, ClientPlayerEntity player) {
        HitResult hit = player.raycast(LOOK_DISTANCE, 1.0F, false);
        if (!(hit instanceof BlockHitResult block) || hit.getType() != HitResult.Type.BLOCK || client.world == null) {
            message(client, "Look at a block to add a waypoint there.", Formatting.RED);
            return;
        }
        // The waypoint is where the feet stand: on top of the block looked at (or next to it, down to the floor).
        BlockPos feet = block.getSide() == Direction.UP ? block.getBlockPos().up() : block.getBlockPos().offset(block.getSide());
        for (int i = 0; i < 4 && client.world.getBlockState(feet.down()).isAir(); i++) {
            feet = feet.down();
        }
        int[] point = {feet.getX(), feet.getY(), feet.getZ()};
        for (int[] p : points) {
            if (p[0] == point[0] && p[1] == point[1] && p[2] == point[2]) {
                message(client, "There is a waypoint there already.", Formatting.GRAY);
                return;
            }
        }
        int index = WaypointMath.insertionIndex(points, point);
        points.add(index, point);
        changed = true;
        message(client, "Waypoint " + (index + 1) + " added" + (index + 1 < points.size()
                ? " (the ones after it are now " + (index + 2) + "-" + points.size() + ")." : "."), Formatting.GREEN);
    }

    private void delete(MinecraftClient client) {
        if (looked < 0) {
            message(client, "Look at a waypoint to delete it.", Formatting.RED);
            return;
        }
        if (points.size() <= 2) {
            message(client, "A route needs at least 2 waypoints.", Formatting.RED);
            return;
        }
        points.remove(looked);
        changed = true;
        message(client, "Waypoint " + (looked + 1) + " deleted.", Formatting.YELLOW);
        looked = -1;
    }

    private void save(MinecraftClient client) {
        String name = editing;
        if (name != null && routes.updateWaypoints(client, name, points)) {
            message(client, "Route \"" + name + "\" saved with " + points.size() + " waypoints.", Formatting.GREEN);
        } else {
            message(client, "Could not save: the route is not in this world any more.", Formatting.RED);
        }
        disableSelf("Saved");
    }

    private int lookedAt(ClientPlayerEntity player) {
        Vec3d eye = player.getEyePos();
        Vec3d dir = player.getRotationVec(1.0F);
        return WaypointMath.lookedAt(points, eye.x, eye.y, eye.z, dir.x, dir.y, dir.z, LOOK_DISTANCE);
    }

    private void render(CoreEvents.WorldRender event) {
        Overlay overlay = Overlay.begin(event.context());
        if (overlay == null) {
            return;
        }
        RouteRecorder.drawRoute(overlay, points, looked);
        if (looked >= 0) {
            int[] p = points.get(looked);
            overlay.label(p[0] + 0.5D, p[1] + 2.6D, p[2] + 0.5D, "right click = delete", EDIT_COLOR);
        }
    }

    @Override
    public void collectHud(List<HudLine> out) {
        if (editing != null) {
            out.add(new HudLine("Editing", editing + " · " + points.size() + " waypoints" + (changed ? " (changed)" : ""), EDIT_COLOR));
        }
    }

    private static void message(MinecraftClient client, String text, Formatting color) {
        if (client.player != null) {
            client.player.sendMessage(Text.literal(text).formatted(color), false);
        }
    }
}
