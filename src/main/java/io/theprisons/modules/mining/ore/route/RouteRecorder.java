package io.theprisons.modules.mining.ore.route;

import io.theprisons.core.ThePrisonsCore;
import io.theprisons.core.command.CommandService;
import io.theprisons.core.event.CoreEvents;
import io.theprisons.core.event.EventBus;
import io.theprisons.core.render.Overlay;
import io.theprisons.modules.mining.ore.OreCatalog;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.jspecify.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Records a route while the player walks it. Start and stop are two keys in the Minecraft controls (category
 * ThePrisons, unbound by default). While recording, only the corner points become waypoints ({@link RouteTracker})
 * and the mined ores are counted; stopping opens {@link RouteNameScreen}, and the route is saved under the ore mined
 * most.
 *
 * <p>Commands: {@code /theprisons routes} (list), {@code /theprisons route delete <name>}.</p>
 */
public final class RouteRecorder {
    private static final int WAYPOINT_COLOR = 0xFF00E5FF;
    private static final int LINE_COLOR = 0xFF4DD8FF;

    private final RouteStore store;
    private final KeyBinding startKey;
    private final KeyBinding stopKey;
    private final RouteTracker tracker = new RouteTracker();
    private final Map<String, Integer> mined = new LinkedHashMap<>();
    private boolean recording;
    private @Nullable ClientWorld recordingWorld;
    private int ticks;

    public RouteRecorder(RouteStore store) {
        this.store = store;
        startKey = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.theprisons.route_record_start", InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_UNKNOWN, ThePrisonsCore.KEY_CATEGORY));
        stopKey = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.theprisons.route_record_stop", InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_UNKNOWN, ThePrisonsCore.KEY_CATEGORY));
    }

    public void register(EventBus bus, CommandService commands) {
        bus.subscribe(CoreEvents.TickEnd.class, this, event -> tick(event.client()));
        bus.subscribe(CoreEvents.PlayerBrokeBlock.class, this, event -> {
            if (recording) {
                String pack = OreCatalog.packOf(event.state().getBlock());
                if (pack != null) {
                    mined.merge(pack, 1, Integer::sum);
                }
            }
        });
        bus.subscribe(CoreEvents.WorldRender.class, this, this::render);
        commands.contribute(root -> root
                .then(ClientCommandManager.literal("routes").executes(ctx -> {
                    List<Route> routes = store.all(ctx.getSource().getClient());
                    if (routes.isEmpty()) {
                        ctx.getSource().sendFeedback(Text.literal("No routes in this world yet. Bind \"Start route recording\" "
                                + "and \"Stop route recording\" in Options > Controls.").formatted(Formatting.GRAY));
                    }
                    for (Route route : routes) {
                        ctx.getSource().sendFeedback(Text.literal(RouteStore.oreLabel(route.ore()) + " › " + route.name() + " ("
                                + route.waypoints().size() + " waypoints)").formatted(Formatting.GRAY));
                    }
                    return 1;
                }))
                .then(ClientCommandManager.literal("route")
                        .then(ClientCommandManager.literal("delete")
                                .then(ClientCommandManager.argument("name", StringArgumentType.greedyString())
                                        .suggests((ctx, builder) -> {
                                            store.all(ctx.getSource().getClient()).forEach(route -> builder.suggest(route.name()));
                                            return builder.buildFuture();
                                        })
                                        .executes(ctx -> {
                                            String name = StringArgumentType.getString(ctx, "name");
                                            if (store.delete(ctx.getSource().getClient(), name)) {
                                                ctx.getSource().sendFeedback(Text.literal("Route \"" + name + "\" deleted.").formatted(Formatting.AQUA));
                                                return 1;
                                            }
                                            ctx.getSource().sendError(Text.literal("No route \"" + name + "\" in this world."));
                                            return 0;
                                        })))));
    }

    public boolean recording() {
        return recording;
    }

    private void tick(MinecraftClient client) {
        ClientPlayerEntity player = client.player;
        while (startKey.wasPressed()) {
            if (player != null && !recording) {
                start(client, player);
            }
        }
        while (stopKey.wasPressed()) {
            if (player != null && recording) {
                stop(client, player);
            }
        }
        if (!recording) {
            return;
        }
        if (player == null || client.world != recordingWorld) {
            recording = false;
            if (player != null) {
                player.sendMessage(Text.literal("Route recording cancelled (world changed).").formatted(Formatting.RED), false);
            }
            return;
        }
        tracker.update(player.getX(), player.getY(), player.getZ(), player.getYaw(), player.isOnGround());
        if (++ticks % 20 == 0) {
            String ore = Route.mostMined(mined);
            player.sendMessage(Text.literal(String.format(Locale.ROOT, "● Recording route: %d waypoints · %s",
                    tracker.waypoints().size(), ore.isEmpty() ? "no ore mined yet" : OreCatalog.label(ore) + " (" + mined.get(ore) + ")"))
                    .formatted(Formatting.RED), true);
        }
    }

    private void start(MinecraftClient client, ClientPlayerEntity player) {
        recording = true;
        recordingWorld = client.world;
        ticks = 0;
        mined.clear();
        tracker.start(player.getX(), player.getY(), player.getZ(), player.getYaw());
        player.sendMessage(Text.literal("Route recording started - walk the route and mine; press the stop key when done.")
                .formatted(Formatting.GREEN), false);
    }

    private void stop(MinecraftClient client, ClientPlayerEntity player) {
        recording = false;
        tracker.finish(player.getX(), player.getY(), player.getZ());
        List<int[]> points = List.copyOf(tracker.waypoints());
        Map<String, Integer> counts = new LinkedHashMap<>(mined);
        String ore = Route.mostMined(counts);
        if (points.size() < 2) {
            player.sendMessage(Text.literal("Route discarded: it needs at least 2 waypoints - walk further next time.")
                    .formatted(Formatting.RED), false);
            return;
        }
        int number = 1;
        for (Route route : store.all(client)) {
            if (route.ore().equals(ore)) {
                number++;
            }
        }
        String defaultName = RouteStore.oreLabel(ore) + " " + number;
        int total = counts.values().stream().mapToInt(Integer::intValue).sum();
        String summary = points.size() + " waypoints · " + RouteStore.oreLabel(ore) + (total > 0 ? " (" + total + " ores mined)" : "");
        client.setScreen(new RouteNameScreen(defaultName, summary, name -> {
            Route saved = store.add(client, new Route(name, ore, counts, points));
            if (client.player != null) {
                client.player.sendMessage(Text.literal("Route \"" + saved.name() + "\" saved under " + RouteStore.oreLabel(ore)
                        + ". Pick it in Ore Macro › Route.").formatted(Formatting.GREEN), false);
            }
        }, () -> {
            if (client.player != null) {
                client.player.sendMessage(Text.literal("Route discarded.").formatted(Formatting.GRAY), false);
            }
        }));
    }

    private void render(CoreEvents.WorldRender event) {
        if (!recording) {
            return;
        }
        Overlay overlay = Overlay.begin(event.context());
        if (overlay != null) {
            drawRoute(overlay, tracker.waypoints(), -1);
        }
    }

    /**
     * Waypoints as block outlines joined by lines, each with its name (its number 1, 2, 3, ...) above it;
     * {@code target} (if ≥ 0) is drawn brighter.
     */
    public static void drawRoute(Overlay overlay, List<int[]> points, int target) {
        drawRoute(overlay, points, target, null);
    }

    /** As above for a part of a route: {@code numbers[i]} is the label of {@code points.get(i)} (null = 1, 2, 3, ...). */
    public static void drawRoute(Overlay overlay, List<int[]> points, int target, int @org.jspecify.annotations.Nullable [] numbers) {
        for (int i = 0; i < points.size(); i++) {
            int[] p = points.get(i);
            overlay.blockOutline(p[0], p[1] - 1, p[2], i == target ? 0xFFFFFFFF : WAYPOINT_COLOR, i == target ? 3.5F : 2.5F);
            if (i + 1 < points.size()) {
                int[] q = points.get(i + 1);
                overlay.line(p[0] + 0.5D, p[1] + 0.1D, p[2] + 0.5D, q[0] + 0.5D, q[1] + 0.1D, q[2] + 0.5D, LINE_COLOR, 2.0F);
            }
        }
        for (int i = 0; i < points.size(); i++) {
            int[] p = points.get(i);
            overlay.line(p[0] + 0.5D, p[1] + 0.1D, p[2] + 0.5D, p[0] + 0.5D, p[1] + 1.6D, p[2] + 0.5D,
                    i == target ? 0xFFFFFFFF : WAYPOINT_COLOR, 1.5F);
        }
        // Labels last: each one switches the buffer.
        for (int i = 0; i < points.size(); i++) {
            int[] p = points.get(i);
            overlay.label(p[0] + 0.5D, p[1] + 2.0D, p[2] + 0.5D, String.valueOf(numbers != null ? numbers[i] : i + 1), i == target ? 0xFFFFFFFF : WAYPOINT_COLOR);
        }
    }
}
