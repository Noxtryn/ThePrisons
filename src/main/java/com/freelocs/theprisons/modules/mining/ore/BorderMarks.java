package com.freelocs.theprisons.modules.mining.ore;

import com.freelocs.theprisons.ThePrisonsClient;
import com.freelocs.theprisons.core.command.CommandService;
import com.freelocs.theprisons.core.event.CoreEvents;
import com.freelocs.theprisons.core.event.EventBus;
import com.freelocs.theprisons.core.render.Overlay;
import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Borders the user places by hand: {@code /theprisons set border} marks the block the player is looking at, and no
 * macro walks or mines within {@value #RADIUS} blocks of it. The marks are saved per server and dimension and always
 * drawn in the world (block outline and a ring of the radius), also while no macro runs.
 *
 * <p>Other commands: {@code remove border} (the mark nearest to the looking point, within the radius),
 * {@code clear borders} (all marks of this world), {@code borders} (list).</p>
 */
public final class BorderMarks {
    public static final double RADIUS = 5.0D;
    private static final double LOOK_DISTANCE = 64.0D;
    private static final int COLOR = 0xFFFF3B3B;
    private static final int RING_SEGMENTS = 48;

    private final List<BlockPos> marks = new ArrayList<>();
    private BorderZones zones = new BorderZones();
    private @Nullable Path file;

    /** Commands and the always-on highlight (not tied to a running macro). */
    public void register(EventBus bus, CommandService commands) {
        bus.subscribe(CoreEvents.WorldRender.class, this, this::render);
        commands.contribute(root -> root
                .then(ClientCommandManager.literal("set")
                        .then(ClientCommandManager.literal("border").executes(ctx -> set(ctx.getSource()))))
                .then(ClientCommandManager.literal("remove")
                        .then(ClientCommandManager.literal("border").executes(ctx -> remove(ctx.getSource()))))
                .then(ClientCommandManager.literal("clear")
                        .then(ClientCommandManager.literal("borders").executes(ctx -> clear(ctx.getSource()))))
                .then(ClientCommandManager.literal("borders").executes(ctx -> list(ctx.getSource()))));
    }

    /** The areas of the current world for walking and mining checks. */
    public BorderZones zones(MinecraftClient client) {
        sync(client);
        return zones;
    }

    // ── Commands ─────────────────────────────────────────────────────────────

    private int set(FabricClientCommandSource source) {
        MinecraftClient client = source.getClient();
        BlockPos pos = lookedAt(client);
        if (pos == null) {
            source.sendError(Text.literal("Look at a block (up to " + (int) LOOK_DISTANCE + " blocks away)."));
            return 0;
        }
        sync(client);
        if (marks.contains(pos)) {
            source.sendFeedback(Text.literal("There is a border at " + pos.toShortString() + " already.").formatted(Formatting.GRAY));
            return 1;
        }
        marks.add(pos);
        changed();
        source.sendFeedback(Text.literal("Border set at " + pos.toShortString() + " - no macro within " + (int) RADIUS
                + " blocks of it.").formatted(Formatting.GREEN));
        return 1;
    }

    private int remove(FabricClientCommandSource source) {
        MinecraftClient client = source.getClient();
        sync(client);
        BlockPos pos = lookedAt(client);
        if (pos == null && client.player != null) {
            pos = client.player.getBlockPos();
        }
        BlockPos nearest = null;
        double best = RADIUS * RADIUS;
        for (BlockPos mark : marks) {
            double d = pos == null ? Double.MAX_VALUE : mark.getSquaredDistance(pos);
            if (d <= best) {
                best = d;
                nearest = mark;
            }
        }
        if (nearest == null) {
            source.sendError(Text.literal("No border within " + (int) RADIUS + " blocks of where you look."));
            return 0;
        }
        marks.remove(nearest);
        changed();
        source.sendFeedback(Text.literal("Border at " + nearest.toShortString() + " removed.").formatted(Formatting.AQUA));
        return 1;
    }

    private int clear(FabricClientCommandSource source) {
        sync(source.getClient());
        int count = marks.size();
        marks.clear();
        changed();
        source.sendFeedback(Text.literal(count + " border(s) removed.").formatted(Formatting.AQUA));
        return 1;
    }

    private int list(FabricClientCommandSource source) {
        sync(source.getClient());
        if (marks.isEmpty()) {
            source.sendFeedback(Text.literal("No borders in this world. Look at a block and use /theprisons set border.")
                    .formatted(Formatting.GRAY));
        }
        for (BlockPos mark : marks) {
            source.sendFeedback(Text.literal("Border " + mark.toShortString() + " (radius " + (int) RADIUS + ")").formatted(Formatting.GRAY));
        }
        return 1;
    }

    private static @Nullable BlockPos lookedAt(MinecraftClient client) {
        if (client.player == null) {
            return null;
        }
        HitResult hit = client.player.raycast(LOOK_DISTANCE, 1.0F, false);
        return hit instanceof BlockHitResult block && hit.getType() == HitResult.Type.BLOCK ? block.getBlockPos() : null;
    }

    // ── Highlight ────────────────────────────────────────────────────────────

    private void render(CoreEvents.WorldRender event) {
        MinecraftClient client = MinecraftClient.getInstance();
        sync(client);
        if (marks.isEmpty() || client.player == null) {
            return;
        }
        Overlay overlay = Overlay.begin(event.context());
        if (overlay == null) {
            return;
        }
        for (BlockPos mark : marks) {
            if (mark.getSquaredDistance(client.player.getEntityPos()) > 128.0D * 128.0D) {
                continue;
            }
            overlay.blockOutline(mark.getX(), mark.getY(), mark.getZ(), COLOR, 3.0F);
            double cx = mark.getX() + 0.5D;
            double cz = mark.getZ() + 0.5D;
            double y = mark.getY() + 1.05D;
            // The ring on the floor and one at head height, so the area is visible from anywhere in the tunnel.
            for (double ringY : new double[]{y, y + 2.0D}) {
                for (int i = 0; i < RING_SEGMENTS; i++) {
                    double a = Math.PI * 2.0D * i / RING_SEGMENTS;
                    double b = Math.PI * 2.0D * (i + 1) / RING_SEGMENTS;
                    overlay.line(cx + Math.cos(a) * RADIUS, ringY, cz + Math.sin(a) * RADIUS,
                            cx + Math.cos(b) * RADIUS, ringY, cz + Math.sin(b) * RADIUS, COLOR, 2.5F);
                }
            }
            overlay.line(cx, y, cz, cx, y + 3.0D, cz, COLOR, 2.5F);
        }
    }

    // ── Storage (config/theprisons/borders/<server>_<dimension>.json) ────────

    /** Loads the marks of the current server / dimension when it changed. */
    private void sync(MinecraftClient client) {
        Path current = OreMacroModule.worldFile(client, "borders");
        if (Objects.equals(current, file)) {
            return;
        }
        file = current;
        marks.clear();
        if (current != null && Files.exists(current)) {
            try {
                for (var element : JsonParser.parseString(Files.readString(current)).getAsJsonArray()) {
                    JsonArray xyz = element.getAsJsonArray();
                    marks.add(new BlockPos(xyz.get(0).getAsInt(), xyz.get(1).getAsInt(), xyz.get(2).getAsInt()));
                }
            } catch (IOException | RuntimeException e) {
                ThePrisonsClient.LOGGER.warn("[borders] could not read {}", current, e);
            }
        }
        rebuild();
    }

    private void changed() {
        rebuild();
        Path target = file;
        if (target == null) {
            return;
        }
        JsonArray all = new JsonArray();
        for (BlockPos mark : marks) {
            JsonArray xyz = new JsonArray();
            xyz.add(mark.getX());
            xyz.add(mark.getY());
            xyz.add(mark.getZ());
            all.add(xyz);
        }
        try {
            Files.createDirectories(target.getParent());
            Files.writeString(target, all.toString());
        } catch (IOException e) {
            ThePrisonsClient.LOGGER.warn("[borders] could not save {}", target, e);
        }
    }

    private void rebuild() {
        BorderZones next = new BorderZones();
        for (BlockPos mark : marks) {
            next.add(mark.getX() + 0.5D, mark.getY() + 1.0D, mark.getZ() + 0.5D, RADIUS);
        }
        zones = next;
    }
}
