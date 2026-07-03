package com.freelocs.theprisons.bandit;

import com.freelocs.theprisons.ThePrisonsClient;
import com.freelocs.theprisons.config.ThePrisonsConfig;
import com.freelocs.theprisons.ui.ThePrisonsColors;
import com.freelocs.theprisons.ui.ThePrisonsHudRenderer;
import com.mojang.authlib.GameProfile;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexRendering;
import net.minecraft.client.render.RenderLayers;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.scoreboard.AbstractTeam;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ThePrisonsBanditManager {
    private static final Pattern BANDIT_LEVEL_PATTERN = Pattern.compile("(?i)bandit level\\s*[:#-]?\\s*(\\d+)");
    private static final Pattern BANDIT_PERCENT_PATTERN = Pattern.compile("(?i)(\\d{1,3})\\s*%\\s*(?:to|until|toward|towards|for)?\\s*(?:your )?(?:next )?bandit level");
    private static final Pattern BANDIT_RATIO_PATTERN = Pattern.compile("(?i)(\\d+)\\s*/\\s*(\\d+)");
    private static final Pattern BANDIT_REMAINING_PATTERN = Pattern.compile("(?i)only\\s+(\\d+)\\s+kills?\\s+(?:until|to)\\s+(?:your )?(?:next )?bandit level");
    private static final Pattern BANDIT_PROGRESS_PATTERN = Pattern.compile("(?i)(bandit|level|kill|progress)");
    private static final Pattern COORDS = Pattern.compile("(?i)(?:x\\s*[:=]\\s*)?(-?\\d{2,6})\\D{1,12}(?:y\\s*[:=]\\s*)?(-?\\d{1,3})?\\D{1,12}(?:z\\s*[:=]\\s*)?(-?\\d{2,6})");

    private static final long ARMOR_WARNING_COOLDOWN_MS = 12_000L;
    private static final long BANDIT_ALERT_COOLDOWN_MS = 8_000L;
    private static final long PATH_RECALC_COOLDOWN_MS = 180L;
    private static final double BANDIT_TRACE_DISTANCE = 25.0D;
    private static final double RUSH_SAME_AREA_RADIUS = 240.0D;
    private static final double TRACE_POINT_RADIUS = 0.055D;
    private static final double TRACE_POINT_STEP = 0.07D;
    private static final double TRACE_SURFACE_OFFSET = 0.016D;

    private static final Map<UUID, BanditSnapshot> BANDIT_SNAPSHOTS = new HashMap<>();
    private static final Map<EquipmentSlot, ArmorSnapshot> ARMOR_SNAPSHOTS = new EnumMap<>(EquipmentSlot.class);
    private static final List<BanditTarget> BANDIT_TARGETS = new ArrayList<>();
    private static final EquipmentSlot[] ARMOR_SLOTS = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};

    private static ProgressSnapshot lastProgress = ProgressSnapshot.empty();
    private static RushSnapshot activeRush = RushSnapshot.empty();
    private static @Nullable BanditOreTier lastKilledBanditTier;
    private static long lastBanditScanAtMs;
    private static final BanditPathingBehavior BANDIT_PATHING = new BanditPathingBehavior();

    private ThePrisonsBanditManager() {
    }

    public static void tick(MinecraftClient client) {
        ClientPlayerEntity player = client.player;
        if (player == null || client.world == null) {
            BANDIT_TARGETS.clear();
            BANDIT_SNAPSHOTS.clear();
            BANDIT_PATHING.cancelEverything();
            activeRush = RushSnapshot.empty();
            lastKilledBanditTier = null;
            return;
        }

        activeRush = activeRush.isExpired() ? RushSnapshot.empty() : activeRush;
        ThePrisonsConfig config = ThePrisonsClient.CONFIG.get();
        boolean banditFeaturesEnabled = config.bandit.showBanditProgressAlerts
                || config.bandit.showBanditTrace;
        if (banditFeaturesEnabled) {
            scanBandits(client, player, config);
        } else {
            BANDIT_TARGETS.clear();
            BANDIT_PATHING.cancelEverything();
            lastProgress = lastProgress.withTraceTarget(null);
        }
        updateArmorWarnings(player, config);
    }

    public static void renderWorld(WorldRenderContext context) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.player == null) {
            return;
        }
        if (!isRushInSameArea(client.player)) {
            return;
        }
        ThePrisonsConfig config = ThePrisonsClient.CONFIG.get();
        drawBanditOverlays(context, client, config);
    }

    public static void renderHud(DrawContext context, MinecraftClient client, ThePrisonsConfig config) {
        if (!config.hud.showArmorHud) {
            return;
        }

        drawArmorHud(context, client, config, false);
    }

    public static ThePrisonsHudRenderer.HudBounds measureArmorHud(MinecraftClient client, ThePrisonsConfig config) {
        if (client == null) {
            return ThePrisonsHudRenderer.HudBounds.EMPTY;
        }

        int iconSize = armorIconSize(config);
        int gap = armorGap(config);
        int rowHeight = iconSize + 2;
        int totalHeight = ARMOR_SLOTS.length * rowHeight + (ARMOR_SLOTS.length - 1) * gap;
        int percentColumnWidth = Math.max(30, client.textRenderer.getWidth("100%"));
        int panelWidth = iconSize + percentColumnWidth + 10;
        int startX = config.gui.armorHudX >= 0
                ? config.gui.armorHudX + 2
                : defaultArmorStartX(client, config, panelWidth);
        int startY = config.gui.armorHudY >= 0
                ? config.gui.armorHudY + 2
                : defaultArmorStartY(client, config, totalHeight);
        return new ThePrisonsHudRenderer.HudBounds(startX - 2, startY - 2, panelWidth + 4, totalHeight + 4);
    }

    public static boolean isInsideArmorHud(MinecraftClient client, ThePrisonsConfig config, int mouseX, int mouseY) {
        return measureArmorHud(client, config).contains(mouseX, mouseY);
    }

    public static void drawArmorHudPreview(DrawContext context, MinecraftClient client, ThePrisonsConfig config) {
        drawArmorHud(context, client, config, true);
    }

    public static void onGameMessage(Text message, boolean overlay) {
        parseRushMessage(message);
        parseProgressMessage(message);
    }

    public static void onChatMessage(Text message, @Nullable Object signedMessage, @Nullable GameProfile sender, Object params, Instant receptionTimestamp) {
        parseRushMessage(message);
        parseProgressMessage(message);
    }

    private static void scanBandits(MinecraftClient client, ClientPlayerEntity player, ThePrisonsConfig config) {
        boolean rushInSameArea = isRushInSameArea(player);
        if (!rushInSameArea) {
            BANDIT_TARGETS.clear();
            BANDIT_PATHING.cancelEverything();
            lastProgress = lastProgress.withTraceTarget(null);
            return;
        }

        long nowMs = System.currentTimeMillis();
        if (nowMs - lastBanditScanAtMs < Math.max(50, config.bandit.banditScanIntervalMs)) {
            return;
        }
        lastBanditScanAtMs = nowMs;
        BANDIT_TARGETS.clear();

        double scanRange = Math.max(24.0D, config.bandit.banditScanRange);
        Box scanBox = player.getBoundingBox().expand(scanRange);
        List<Entity> entities = client.world.getOtherEntities(player, scanBox, entity -> entity instanceof LivingEntity);
        Set<UUID> seen = new HashSet<>();
        double nearestDistance = Double.MAX_VALUE;
        BanditTarget nearestBandit = null;

        for (Entity entity : entities) {
            if (!(entity instanceof LivingEntity living) || !isBandit(client, living)) {
                continue;
            }

            UUID uuid = entity.getUuid();
            seen.add(uuid);

            BanditSnapshot snapshot = BANDIT_SNAPSHOTS.computeIfAbsent(uuid, ignored -> new BanditSnapshot());
            snapshot.lastSeenAtMs = nowMs;
            snapshot.name = strip(entity.getName().getString());
            snapshot.health = living.getHealth();
            snapshot.maxHealth = Math.max(1.0F, living.getMaxHealth());
            snapshot.position = new Vec3d(entity.getX(), entity.getY(), entity.getZ());
            snapshot.box = entity.getBoundingBox().expand(0.05D);
            snapshot.dead = !living.isAlive() || living.getHealth() <= 0.0F;
            snapshot.attacker = living.getAttacker();
            snapshot.counted = snapshot.counted || snapshot.dead && snapshot.attacker == player;

            if (snapshot.dead && !snapshot.counted && snapshot.attacker == player) {
                snapshot.counted = true;
                maybeAnnounceBanditProgress();
            }

            double distance = entity.squaredDistanceTo(player);
            BanditTarget target = new BanditTarget(uuid, snapshot.name, snapshot.box, snapshot.position, Math.sqrt(distance));
            BANDIT_TARGETS.add(target);

            if (target.distance < nearestDistance) {
                nearestDistance = target.distance;
                nearestBandit = target;
            }
        }

        pruneSnapshots(seen);
        BANDIT_TARGETS.sort(Comparator.comparingDouble(target -> target.distance));

        boolean hasTraceTarget = nearestBandit != null;
        if (hasTraceTarget && client.world != null) {
            BANDIT_PATHING.update(client.world, player, new BanditPathTarget(nearestBandit.uuid, nearestBandit.box, nearestBandit.position, nearestBandit.distance), scanRange, nowMs);
            lastProgress = lastProgress.withTraceTarget(nearestBandit);
        } else {
            lastProgress = lastProgress.withTraceTarget(null);
            BANDIT_PATHING.cancelEverything();
        }
    }

    private static void updateArmorWarnings(ClientPlayerEntity player, ThePrisonsConfig config) {
        for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
            ItemStack stack = player.getEquippedStack(slot);
            float percent = durabilityPercent(stack);
            ArmorSnapshot snapshot = ARMOR_SNAPSHOTS.computeIfAbsent(slot, ignored -> new ArmorSnapshot());
            snapshot.percent = percent;

            if (!config.hud.showArmorWarnings) {
                snapshot.alertedBelowTwenty = false;
                continue;
            }

            if (percent < 20.0F) {
                long now = System.currentTimeMillis();
                if (!snapshot.alertedBelowTwenty || now - snapshot.lastAlertAtMs >= ARMOR_WARNING_COOLDOWN_MS) {
                    snapshot.alertedBelowTwenty = true;
                    snapshot.lastAlertAtMs = now;
                    ThePrisonsHudRenderer.pushNotification("Armor Critical", slotName(slot) + " needs repair soon.", ThePrisonsColors.ACCENT_AMBER);
                }
            } else {
                snapshot.alertedBelowTwenty = false;
            }
        }
    }

    private static void drawBanditOverlays(WorldRenderContext context, MinecraftClient client, ThePrisonsConfig config) {
        if (context.matrices() == null || client.player == null) {
            return;
        }
        if (!isRushInSameArea(client.player)) {
            return;
        }

        var matrices = context.matrices();
        VertexConsumer lines = context.consumers().getBuffer(RenderLayers.LINES);
        VertexConsumer fills = context.consumers().getBuffer(RenderLayers.debugFilledBox());
        Camera camera = client.gameRenderer.getCamera();
        Entity cameraEntity = camera.getFocusedEntity();
        Vec3d cameraPos = cameraEntity == null
                ? Vec3d.ZERO
                : new Vec3d(cameraEntity.getX(), cameraEntity.getY(), cameraEntity.getZ());

        matrices.push();
        matrices.translate(-cameraPos.x, -cameraPos.y, -cameraPos.z);

        boolean hasBanditInArea = !BANDIT_TARGETS.isEmpty();
        if (config.bandit.showBanditTrace && hasBanditInArea && BANDIT_PATHING.hasPath()) {
            drawGroundTrace(matrices, lines, fills, config, BANDIT_PATHING.visiblePoints(client.player));
        }

        matrices.pop();
    }

    private static void renderEntityBox(net.minecraft.client.util.math.MatrixStack matrices, VertexConsumer lines, VertexConsumer fills,
                                        Box box, int outlineColor, int fillColor, float lineWidth) {
        Box inset = box.expand(-0.01D);
        if (inset.maxX > inset.minX && inset.maxY > inset.minY && inset.maxZ > inset.minZ) {
            drawFilledBox(matrices, fills, inset, fillColor);
        }

        VoxelShape shape = VoxelShapes.cuboid(box);
        VertexRendering.drawOutline(matrices, lines, shape, 0.0D, 0.0D, 0.0D, outlineColor, lineWidth);
    }

    private static void drawFilledBox(net.minecraft.client.util.math.MatrixStack matrices, VertexConsumer consumer, Box box, int argb) {
        float alpha = ((argb >>> 24) & 0xFF) / 255.0F;
        float red = ((argb >>> 16) & 0xFF) / 255.0F;
        float green = ((argb >>> 8) & 0xFF) / 255.0F;
        float blue = (argb & 0xFF) / 255.0F;
        int light = 0xF000F0;
        int overlay = 0;
        var entry = matrices.peek();

        emitFace(consumer, entry, box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.minZ, box.maxX, box.maxY, box.maxZ, box.minX, box.minY, box.maxZ, red, green, blue, alpha, 0.0F, 0.0F, 1.0F);
        emitFace(consumer, entry, box.minX, box.minY, box.maxZ, box.maxX, box.maxY, box.maxZ, box.maxX, box.maxY, box.minZ, box.minX, box.minY, box.minZ, red, green, blue, alpha, 0.0F, 0.0F, -1.0F);
        emitFace(consumer, entry, box.minX, box.minY, box.maxZ, box.minX, box.maxY, box.maxZ, box.maxX, box.maxY, box.maxZ, box.maxX, box.minY, box.maxZ, red, green, blue, alpha, 1.0F, 0.0F, 0.0F);
        emitFace(consumer, entry, box.maxX, box.minY, box.minZ, box.maxX, box.maxY, box.minZ, box.minX, box.maxY, box.minZ, box.minX, box.minY, box.minZ, red, green, blue, alpha, -1.0F, 0.0F, 0.0F);
        emitFace(consumer, entry, box.minX, box.maxY, box.minZ, box.maxX, box.maxY, box.minZ, box.maxX, box.maxY, box.maxZ, box.minX, box.maxY, box.maxZ, red, green, blue, alpha, 0.0F, 1.0F, 0.0F);
        emitFace(consumer, entry, box.minX, box.minY, box.maxZ, box.maxX, box.minY, box.maxZ, box.maxX, box.minY, box.minZ, box.minX, box.minY, box.minZ, red, green, blue, alpha, 0.0F, -1.0F, 0.0F);
    }

    private static void emitFace(VertexConsumer consumer, net.minecraft.client.util.math.MatrixStack.Entry entry,
                                 double x1, double y1, double z1, double x2, double y2, double z2,
                                 double x3, double y3, double z3, double x4, double y4, double z4,
                                 float red, float green, float blue, float alpha,
                                 float normalX, float normalY, float normalZ) {
        int light = 0xF000F0;
        int overlay = 0;
        consumer.vertex(entry, (float) x1, (float) y1, (float) z1).color(red, green, blue, alpha).overlay(overlay).light(light).normal(normalX, normalY, normalZ);
        consumer.vertex(entry, (float) x2, (float) y2, (float) z2).color(red, green, blue, alpha).overlay(overlay).light(light).normal(normalX, normalY, normalZ);
        consumer.vertex(entry, (float) x3, (float) y3, (float) z3).color(red, green, blue, alpha).overlay(overlay).light(light).normal(normalX, normalY, normalZ);
        consumer.vertex(entry, (float) x4, (float) y4, (float) z4).color(red, green, blue, alpha).overlay(overlay).light(light).normal(normalX, normalY, normalZ);
    }

    private static void drawGroundTrace(net.minecraft.client.util.math.MatrixStack matrices, VertexConsumer lines, VertexConsumer fills, ThePrisonsConfig config, List<Vec3d> points) {
        List<Vec3d> trace = smoothTrace(points);
        if (trace.size() < 2) {
            return;
        }

        float traceWidth = Math.max(0.85F, config.bandit.banditTraceWidth * 3.0F);
        int traceFillColor = config.bandit.banditTraceFillColor;
        int traceOutlineColor = config.bandit.banditTraceOutlineColor;
        for (int i = 0; i < trace.size() - 1; i++) {
            Vec3d a = trace.get(i);
            Vec3d b = trace.get(i + 1);
            drawRoundSegment(matrices, lines, fills, a, b, traceWidth, traceFillColor, traceOutlineColor);
        }
    }

    private static void drawRoundSegment(net.minecraft.client.util.math.MatrixStack matrices, VertexConsumer lines, VertexConsumer fills,
                                         Vec3d start, Vec3d end, float traceWidth, int traceFillColor, int traceOutlineColor) {
        double distance = start.distanceTo(end);
        int steps = Math.max(1, (int) Math.ceil(distance / TRACE_POINT_STEP));
        for (int step = 0; step <= steps; step++) {
            double t = step / (double) steps;
            double baseY = Math.min(start.y, end.y) + TRACE_SURFACE_OFFSET;
            Vec3d point = new Vec3d(
                    start.x + (end.x - start.x) * t,
                    baseY,
                    start.z + (end.z - start.z) * t
            );
            Box dot = new Box(
                    point.x - TRACE_POINT_RADIUS,
                    point.y - TRACE_POINT_RADIUS,
                    point.z - TRACE_POINT_RADIUS,
                    point.x + TRACE_POINT_RADIUS,
                    point.y + TRACE_POINT_RADIUS,
                    point.z + TRACE_POINT_RADIUS
            );
            drawFilledBox(matrices, fills, dot, traceFillColor);
            VoxelShape shape = VoxelShapes.cuboid(dot);
            VertexRendering.drawOutline(matrices, lines, shape, 0.0D, 0.0D, 0.0D, traceOutlineColor, traceWidth);
        }
    }


    private static List<Vec3d> smoothTrace(List<Vec3d> points) {
        if (points.size() < 2) {
            return points;
        }

        List<Vec3d> smoothed = new ArrayList<>();
        smoothed.add(points.get(0));

        for (int i = 0; i < points.size() - 1; i++) {
            Vec3d p0 = i > 0 ? points.get(i - 1) : points.get(i);
            Vec3d p1 = points.get(i);
            Vec3d p2 = points.get(i + 1);
            Vec3d p3 = i + 2 < points.size() ? points.get(i + 2) : p2;

            double distance = p1.distanceTo(p2);
            int segments = Math.max(2, (int) Math.ceil(distance / 0.12D));
            for (int segment = 1; segment <= segments; segment++) {
                double t = segment / (double) segments;
                smoothed.add(catmullRom(p0, p1, p2, p3, t));
            }
        }

        return smoothed;
    }

    private static Vec3d catmullRom(Vec3d p0, Vec3d p1, Vec3d p2, Vec3d p3, double t) {
        double t2 = t * t;
        double t3 = t2 * t;
        double x = 0.5D * ((2.0D * p1.x) + (-p0.x + p2.x) * t + (2.0D * p0.x - 5.0D * p1.x + 4.0D * p2.x - p3.x) * t2 + (-p0.x + 3.0D * p1.x - 3.0D * p2.x + p3.x) * t3);
        double y = 0.5D * ((2.0D * p1.y) + (-p0.y + p2.y) * t + (2.0D * p0.y - 5.0D * p1.y + 4.0D * p2.y - p3.y) * t2 + (-p0.y + 3.0D * p1.y - 3.0D * p2.y + p3.y) * t3);
        double z = 0.5D * ((2.0D * p1.z) + (-p0.z + p2.z) * t + (2.0D * p0.z - 5.0D * p1.z + 4.0D * p2.z - p3.z) * t2 + (-p0.z + 3.0D * p1.z - 3.0D * p2.z + p3.z) * t3);
        return new Vec3d(x, y, z);
    }

    private static void drawArmorHud(DrawContext context, MinecraftClient client, ThePrisonsConfig config, boolean preview) {
        ClientPlayerEntity player = client.player;
        if (player == null && !preview) {
            return;
        }

        EquipmentSlot[] slots = ARMOR_SLOTS;
        int iconSize = armorIconSize(config);
        int gap = armorGap(config);
        int rowHeight = iconSize + 2;
        int totalHeight = slots.length * rowHeight + (slots.length - 1) * gap;
        int percentXOffset = iconSize + 4;
        ThePrisonsHudRenderer.HudBounds bounds = measureArmorHud(client, config);
        int startX = bounds.x + 2;
        int startY = bounds.y + 2;

        int panelLeft = bounds.x;
        int panelTop = bounds.y;
        int panelRight = bounds.x + bounds.width;
        int panelBottom = bounds.y + bounds.height;

        context.fill(panelLeft, panelTop, panelRight, panelBottom, config.hud.armorHudBackground);

        if (!config.hud.armorHudShowBackground) {
            context.fill(panelLeft, panelTop, panelRight, panelTop + 1, 0x2200E5FF);
        }

        for (int i = 0; i < slots.length; i++) {
            EquipmentSlot slot = slots[i];
            ItemStack stack = player == null ? ItemStack.EMPTY : player.getEquippedStack(slot);
            int iconX = startX;
            int iconY = startY + i * (rowHeight + gap);

            if (stack.isEmpty()) {
                context.fill(iconX, iconY, iconX + iconSize, iconY + iconSize, 0x24000000);
                context.fill(iconX, iconY, iconX + iconSize, iconY + 1, 0x22FFFFFF);
                context.fill(iconX, iconY + iconSize - 1, iconX + iconSize, iconY + iconSize, 0x33000000);
                int textY = iconY + Math.max(0, (iconSize - 8) / 2);
                context.drawTextWithShadow(client.textRenderer, Text.literal("--%"), iconX + percentXOffset, textY, ThePrisonsColors.FG_MUTED);
            } else {
                context.drawItem(stack, iconX, iconY, 0);
                if (stack.isDamageable()) {
                    float durabilityPercent = durabilityPercent(stack);
                    float destroyRatePercent = Math.max(0.0F, 100.0F - durabilityPercent);
                    context.fill(iconX, iconY + iconSize, iconX + iconSize, iconY + iconSize + 1, armorColor(durabilityPercent));

                    String destroyRateText = Math.round(destroyRatePercent) + "%";
                    int textY = iconY + Math.max(0, (iconSize - 8) / 2);
                    context.drawTextWithShadow(client.textRenderer, Text.literal(destroyRateText), iconX + percentXOffset, textY, armorColor(durabilityPercent));
                } else {
                    int textY = iconY + Math.max(0, (iconSize - 8) / 2);
                    context.drawTextWithShadow(client.textRenderer, Text.literal("0%"), iconX + percentXOffset, textY, ThePrisonsColors.FG_MUTED);
                }
            }
        }
    }

    private static int armorIconSize(ThePrisonsConfig config) {
        return Math.max(12, Math.round(config.hud.armorHudIconSize * config.hud.armorHudScale));
    }

    private static int armorGap(ThePrisonsConfig config) {
        return Math.max(1, Math.round(config.hud.armorHudGap * config.hud.armorHudScale));
    }

    private static int defaultArmorStartX(MinecraftClient client, ThePrisonsConfig config, int panelWidth) {
        int hotbarLeft = client.getWindow().getScaledWidth() / 2 - 91;
        return hotbarLeft - panelWidth - 18 + config.hud.armorHudOffsetX;
    }

    private static int defaultArmorStartY(MinecraftClient client, ThePrisonsConfig config, int totalHeight) {
        return client.getWindow().getScaledHeight() - totalHeight - 4 + config.hud.armorHudOffsetY;
    }

    private static void parseProgressMessage(Text message) {
        if (message == null) {
            return;
        }

        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.player == null || !isRushInSameArea(client.player)) {
            return;
        }

        String raw = strip(message.getString());
        String normalized = raw.toLowerCase(Locale.ROOT);
        if (!BANDIT_PROGRESS_PATTERN.matcher(normalized).find()) {
            return;
        }

        Integer level = extractInt(BANDIT_LEVEL_PATTERN, raw);
        Integer percent = extractInt(BANDIT_PERCENT_PATTERN, raw);
        Integer remaining = extractInt(BANDIT_REMAINING_PATTERN, raw);
        Integer kills = null;
        Integer total = null;
        Matcher ratioMatcher = BANDIT_RATIO_PATTERN.matcher(raw);
        if (ratioMatcher.find()) {
            kills = parseInt(ratioMatcher.group(1));
            total = parseInt(ratioMatcher.group(2));
        }

        ProgressSnapshot next = lastProgress;
        if (level != null) {
            next = next.withLevel(level);
        }
        if (percent != null) {
            next = next.withPercent(Math.max(0, Math.min(100, percent)));
        }
        if (remaining != null) {
            next = next.withRemainingKills(remaining);
        }
        if (kills != null && total != null && total > 0) {
            next = next.withFraction(kills, total);
        }

        if (!next.equals(lastProgress)) {
            lastProgress = next;
            maybeAnnounceProgress();
        }
    }

    private static void parseRushMessage(Text message) {
        if (message == null) {
            return;
        }

        String raw = strip(message.getString());
        String lower = raw.toLowerCase(Locale.ROOT);

        BanditOreTier killedTier = BanditOreTier.detectKilledTier(lower);
        if (killedTier != null) {
            lastKilledBanditTier = killedTier;
        }

        if (!lower.contains("bandit rush")) {
            return;
        }

        if (lastKilledBanditTier != null && !lastKilledBanditTier.matchesRush(lower)) {
            return;
        }

        Matcher matcher = COORDS.matcher(raw);
        if (!matcher.find()) {
            return;
        }

        Integer x = parseInt(matcher.group(1));
        Integer y = parseInt(matcher.group(2));
        Integer z = parseInt(matcher.group(3));
        if (x == null || z == null) {
            return;
        }

        activeRush = new RushSnapshot(new BlockPos(x, y == null ? 64 : y, z), System.currentTimeMillis() + 5 * 60_000L);
    }

    private static boolean isRushInSameArea(PlayerEntity player) {
        if (player == null || activeRush.isExpired()) {
            return false;
        }
        double distance = new Vec3d(player.getX(), player.getY(), player.getZ()).distanceTo(Vec3d.ofCenter(activeRush.pos));
        return distance <= RUSH_SAME_AREA_RADIUS;
    }

    private static void maybeAnnounceProgress() {
        if (!ThePrisonsClient.CONFIG.get().bandit.showBanditProgressAlerts) {
            return;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.player == null || !isRushInSameArea(client.player)) {
            return;
        }

        ProgressSnapshot snapshot = lastProgress;
        int percent = snapshot.percent();
        if (percent <= 0) {
            return;
        }

        int quarter = percent >= 100 ? 4 : Math.max(0, (percent - 1) / 25);
        if (quarter == snapshot.lastAnnouncedQuarter || System.currentTimeMillis() - snapshot.lastAlertAtMs < BANDIT_ALERT_COOLDOWN_MS) {
            return;
        }

        String title;
        String body;
        if (percent >= 100) {
            title = "Congratulations";
            body = snapshot.remainingKills() != null
                    ? "Only " + snapshot.remainingKills() + " kills until your next Bandit Level."
                    : "Bandit progress is complete. Check the next tier.";
        } else if (quarter >= 3) {
            title = "Nice";
            body = buildRemainingMessage(snapshot, "You are nearly there.");
        } else if (quarter == 2) {
            title = "Keep Going";
            body = buildRemainingMessage(snapshot, "You are halfway to the next Bandit Level.");
        } else {
            title = "Momentum";
            body = buildRemainingMessage(snapshot, "The next Bandit Level is within reach.");
        }

        ThePrisonsHudRenderer.pushNotification(title, body, ThePrisonsColors.ACCENT_BLUE);
        lastProgress = snapshot.withAlertState(quarter, System.currentTimeMillis());
    }

    private static String buildRemainingMessage(ProgressSnapshot snapshot, String fallback) {
        Integer remaining = snapshot.remainingKills();
        if (remaining != null) {
            return "Only " + remaining + " kills until your next Bandit Level.";
        }
        Integer percent = snapshot.percent();
        if (percent != null && percent > 0) {
            return "You are " + percent + "% into the current Bandit Level.";
        }
        return fallback;
    }

    private static void maybeAnnounceBanditProgress() {
        if (!ThePrisonsClient.CONFIG.get().bandit.showBanditProgressAlerts) {
            return;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.player == null || !isRushInSameArea(client.player)) {
            return;
        }
        if (System.currentTimeMillis() - lastProgress.lastAlertAtMs < BANDIT_ALERT_COOLDOWN_MS) {
            return;
        }
        ThePrisonsHudRenderer.pushNotification("Nice", "Bandit progress updated.", ThePrisonsColors.ACCENT_LIME);
        lastProgress = lastProgress.withAlertTime(System.currentTimeMillis());
    }

    private static void pruneSnapshots(Set<UUID> seen) {
        long now = System.currentTimeMillis();
        BANDIT_SNAPSHOTS.entrySet().removeIf(entry -> {
            BanditSnapshot snapshot = entry.getValue();
            return snapshot == null || snapshot.counted || (snapshot.lastSeenAtMs > 0L && !seen.contains(entry.getKey()) && now - snapshot.lastSeenAtMs > 2_500L);
        });
    }

    private static boolean isBandit(MinecraftClient client, LivingEntity entity) {
        if (!(entity instanceof PlayerEntity playerEntity) || playerEntity == client.player) {
            return false;
        }

        if (looksLikeBandit(playerEntity)) {
            return true;
        }

        return false;
    }

    private static boolean hasPlayerListEntry(MinecraftClient client, UUID uuid) {
        if (client.getNetworkHandler() == null) {
            return false;
        }
        PlayerListEntry entry = client.getNetworkHandler().getPlayerListEntry(uuid);
        return entry != null;
    }

    private static boolean looksLikeBandit(PlayerEntity entity) {
        String name = strip(entity.getName().getString()).toLowerCase(Locale.ROOT);
        if (name.contains("bandit")) {
            return true;
        }

        String display = strip(entity.getDisplayName().getString()).toLowerCase(Locale.ROOT);
        if (display.contains("bandit")) {
            return true;
        }

        String custom = entity.hasCustomName() && entity.getCustomName() != null ? strip(entity.getCustomName().getString()).toLowerCase(Locale.ROOT) : "";
        if (custom.contains("bandit")) {
            return true;
        }

        AbstractTeam team = entity.getScoreboardTeam();
        if (team != null) {
            String teamName = strip(team.getName()).toLowerCase(Locale.ROOT);
            String decoratedName = strip(team.decorateName(Text.literal(entity.getName().getString())).getString()).toLowerCase(Locale.ROOT);
            if (teamName.contains("bandit") || decoratedName.contains("bandit")) {
                return true;
            }
        }

        return false;
    }

    private static float durabilityPercent(ItemStack stack) {
        if (stack == null || stack.isEmpty() || !stack.isDamageable()) {
            return 100.0F;
        }
        int maxDamage = Math.max(1, stack.getMaxDamage());
        int damage = Math.min(stack.getDamage(), maxDamage);
        return Math.max(0.0F, 100.0F * (1.0F - (damage / (float) maxDamage)));
    }

    private static int armorColor(float percent) {
        float value = Math.max(0.0F, Math.min(100.0F, percent)) / 100.0F;
        if (value >= 0.55F) {
            return lerpColor(0xFF65F59B, 0xFFFFC14D, (1.0F - value) / 0.45F);
        }
        return lerpColor(0xFFFFC14D, 0xFFFF5E6C, (0.55F - value) / 0.55F);
    }

    private static int lerpColor(int from, int to, float t) {
        float clamped = Math.max(0.0F, Math.min(1.0F, t));
        int fa = (from >>> 24) & 0xFF;
        int fr = (from >>> 16) & 0xFF;
        int fg = (from >>> 8) & 0xFF;
        int fb = from & 0xFF;
        int ta = (to >>> 24) & 0xFF;
        int tr = (to >>> 16) & 0xFF;
        int tg = (to >>> 8) & 0xFF;
        int tb = to & 0xFF;
        int a = Math.round(fa + (ta - fa) * clamped);
        int r = Math.round(fr + (tr - fr) * clamped);
        int g = Math.round(fg + (tg - fg) * clamped);
        int b = Math.round(fb + (tb - fb) * clamped);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private static String slotName(EquipmentSlot slot) {
        return switch (slot) {
            case HEAD -> "Helmet";
            case CHEST -> "Chestplate";
            case LEGS -> "Leggings";
            case FEET -> "Boots";
            default -> slot.name();
        };
    }

    private static String strip(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ");
    }

    @Nullable
    private static Integer extractInt(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        if (!matcher.find()) {
            return null;
        }
        return parseInt(matcher.group(1));
    }

    @Nullable
    private static Integer parseInt(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static final class BanditSnapshot {
        private long lastSeenAtMs;
        private String name = "";
        private float health;
        private float maxHealth;
        private Vec3d position = Vec3d.ZERO;
        private Box box = new Box(0.0D, 0.0D, 0.0D, 0.0D, 0.0D, 0.0D);
        private boolean dead;
        private boolean counted;
        private @Nullable Entity attacker;
    }

    private static final class RushSnapshot {
        private final BlockPos pos;
        private final long expiresAtMs;

        private RushSnapshot(BlockPos pos, long expiresAtMs) {
            this.pos = pos;
            this.expiresAtMs = expiresAtMs;
        }

        private static RushSnapshot empty() {
            return new RushSnapshot(BlockPos.ORIGIN, 0L);
        }

        private boolean isExpired() {
            return expiresAtMs <= 0L || System.currentTimeMillis() >= expiresAtMs;
        }
    }

    private static final class ArmorSnapshot {
        private float percent;
        private boolean alertedBelowTwenty;
        private long lastAlertAtMs;
    }

    private enum BanditOreTier {
        IRON("iron"),
        GOLD("gold"),
        DIAMOND("diamond");

        private final String keyword;

        BanditOreTier(String keyword) {
            this.keyword = keyword;
        }

        private boolean matchesRush(String lowerMessage) {
            return lowerMessage.contains(keyword + " bandit") || lowerMessage.contains(keyword + " rush");
        }

        private static @Nullable BanditOreTier detectKilledTier(String lowerMessage) {
            if (!lowerMessage.contains("bandit") || !(lowerMessage.contains("killed") || lowerMessage.contains("slain"))) {
                return null;
            }
            for (BanditOreTier tier : values()) {
                if (lowerMessage.contains(tier.keyword + " bandit")) {
                    return tier;
                }
            }
            return null;
        }
    }

    private static final class BanditTarget {
        private final UUID uuid;
        private final String name;
        private final Box box;
        private final Vec3d position;
        private final double distance;

        private BanditTarget(UUID uuid, String name, Box box, Vec3d position, double distance) {
            this.uuid = uuid;
            this.name = name;
            this.box = box;
            this.position = position;
            this.distance = distance;
        }
    }

    private static final class ProgressSnapshot {
        private final int level;
        private final Integer percent;
        private final Integer killsIntoLevel;
        private final Integer totalKillsForLevel;
        private final Integer remainingKills;
        private final int lastAnnouncedQuarter;
        private final long lastAlertAtMs;
        private final @Nullable BanditTarget traceTarget;

        private ProgressSnapshot(int level, @Nullable Integer percent, @Nullable Integer killsIntoLevel, @Nullable Integer totalKillsForLevel, @Nullable Integer remainingKills, int lastAnnouncedQuarter, long lastAlertAtMs, @Nullable BanditTarget traceTarget) {
            this.level = level;
            this.percent = percent;
            this.killsIntoLevel = killsIntoLevel;
            this.totalKillsForLevel = totalKillsForLevel;
            this.remainingKills = remainingKills;
            this.lastAnnouncedQuarter = lastAnnouncedQuarter;
            this.lastAlertAtMs = lastAlertAtMs;
            this.traceTarget = traceTarget;
        }

        private static ProgressSnapshot empty() {
            return new ProgressSnapshot(0, null, null, null, null, -1, 0L, null);
        }

        private ProgressSnapshot withLevel(int newLevel) {
            if (newLevel == level) {
                return this;
            }
            return new ProgressSnapshot(newLevel, percent, killsIntoLevel, totalKillsForLevel, remainingKills, lastAnnouncedQuarter, lastAlertAtMs, traceTarget);
        }

        private ProgressSnapshot withPercent(int newPercent) {
            if (percent != null && percent == newPercent) {
                return this;
            }
            return new ProgressSnapshot(level, newPercent, killsIntoLevel, totalKillsForLevel, remainingKills, lastAnnouncedQuarter, lastAlertAtMs, traceTarget);
        }

        private ProgressSnapshot withFraction(int kills, int total) {
            if (killsIntoLevel != null && totalKillsForLevel != null && killsIntoLevel == kills && totalKillsForLevel == total) {
                return this;
            }
            int pct = total <= 0 ? 0 : Math.min(100, Math.round((kills / (float) total) * 100.0F));
            Integer remaining = Math.max(0, total - kills);
            return new ProgressSnapshot(level, pct, kills, total, remaining, lastAnnouncedQuarter, lastAlertAtMs, traceTarget);
        }

        private ProgressSnapshot withRemainingKills(int kills) {
            if (remainingKills != null && remainingKills == kills) {
                return this;
            }
            return new ProgressSnapshot(level, percent, killsIntoLevel, totalKillsForLevel, kills, lastAnnouncedQuarter, lastAlertAtMs, traceTarget);
        }

        private ProgressSnapshot withAlertState(int quarter, long alertAtMs) {
            return new ProgressSnapshot(level, percent, killsIntoLevel, totalKillsForLevel, remainingKills, quarter, alertAtMs, traceTarget);
        }

        private ProgressSnapshot withAlertTime(long alertAtMs) {
            return new ProgressSnapshot(level, percent, killsIntoLevel, totalKillsForLevel, remainingKills, lastAnnouncedQuarter, alertAtMs, traceTarget);
        }

        private ProgressSnapshot withTraceTarget(@Nullable BanditTarget target) {
            return new ProgressSnapshot(level, percent, killsIntoLevel, totalKillsForLevel, remainingKills, lastAnnouncedQuarter, lastAlertAtMs, target);
        }

        private int percent() {
            if (percent != null) {
                return percent;
            }
            if (killsIntoLevel != null && totalKillsForLevel != null && totalKillsForLevel > 0) {
                return Math.min(100, Math.round((killsIntoLevel / (float) totalKillsForLevel) * 100.0F));
            }
            return 0;
        }

        private @Nullable Integer remainingKills() {
            return remainingKills;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof ProgressSnapshot other)) {
                return false;
            }
            return level == other.level
                    && Objects.equals(percent, other.percent)
                    && Objects.equals(killsIntoLevel, other.killsIntoLevel)
                    && Objects.equals(totalKillsForLevel, other.totalKillsForLevel)
                    && Objects.equals(remainingKills, other.remainingKills);
        }

        @Override
        public int hashCode() {
            return Objects.hash(level, percent, killsIntoLevel, totalKillsForLevel, remainingKills);
        }
    }

    private static final class GroundPathSnapshot {
        private final List<Vec3d> points;
        private final Vec3d playerStart;
        private final BlockPos playerPos;
        private final Vec3d targetPos;
        private final long updatedAtMs;

        private GroundPathSnapshot(List<Vec3d> points, Vec3d playerStart, BlockPos playerPos, Vec3d targetPos, long updatedAtMs) {
            this.points = points;
            this.playerStart = playerStart;
            this.playerPos = playerPos;
            this.targetPos = targetPos;
            this.updatedAtMs = updatedAtMs;
        }

        private static GroundPathSnapshot empty() {
            return new GroundPathSnapshot(List.of(), Vec3d.ZERO, new BlockPos(0, 0, 0), Vec3d.ZERO, 0L);
        }

        private boolean hasPath() {
            return !points.isEmpty();
        }

        private boolean matches(Vec3d playerPos, BlockPos playerBlockPos, Vec3d targetPos) {
            return this.playerPos.equals(playerBlockPos)
                    && this.playerStart.squaredDistanceTo(playerPos) < 0.09D
                    && this.targetPos.squaredDistanceTo(targetPos) < 1.0D;
        }

        private List<Vec3d> visiblePoints(PlayerEntity player) {
            if (points.size() < 2) {
                return points;
            }

            Vec3d current = new Vec3d(player.getX(), player.getY(), player.getZ());
            int closestIndex = 0;
            double best = Double.MAX_VALUE;
            for (int i = 0; i < points.size(); i++) {
                double distance = points.get(i).squaredDistanceTo(current);
                if (distance < best) {
                    best = distance;
                    closestIndex = i;
                }
            }

            int start = Math.min(Math.max(closestIndex, 0), points.size() - 1);
            List<Vec3d> visible = new ArrayList<>(points.subList(start, points.size()));
            return visible.size() >= 2 ? visible : points;
        }
    }

    private static final class GroundPathfinder {
        private static final int MAX_NODES = 6_000;
        private static final int MAX_RADIUS = 56;
        private static final int GOAL_DISTANCE_SQUARED = 4;

        private GroundPathfinder() {
        }

        private static GroundPathSnapshot update(ClientWorld world, ClientPlayerEntity player, Vec3d targetPosition, Box targetBox, double scanRange, long nowMs, GroundPathSnapshot previous) {
            Vec3d playerPos = new Vec3d(player.getX(), player.getY(), player.getZ());
            if (nowMs - previous.updatedAtMs < PATH_RECALC_COOLDOWN_MS
                    && previous.matches(playerPos, player.getBlockPos(), targetPosition)) {
                return previous;
            }

            BlockPos start = findStandable(world, BlockPos.ofFloored(playerPos), player.getBlockY());
            if (start == null) {
                return GroundPathSnapshot.empty();
            }

            List<BlockPos> goals = findGoals(world, targetPosition, targetBox, start.getY());
            if (goals.isEmpty()) {
                return GroundPathSnapshot.empty();
            }

            List<BlockPos> path = search(world, start, goals, scanRange);
            if (path.isEmpty()) {
                path = buildFallbackPath(world, start, targetPosition, targetBox);
                if (path.isEmpty()) {
                    return GroundPathSnapshot.empty();
                }
            }

            List<Vec3d> points = new ArrayList<>(path.size());
            for (BlockPos pos : path) {
                points.add(new Vec3d(pos.getX() + 0.5D, pos.getY() + 0.05D, pos.getZ() + 0.5D));
            }
            return new GroundPathSnapshot(points, playerPos, start, targetPosition, nowMs);
        }

        private static List<BlockPos> buildFallbackPath(ClientWorld world, BlockPos start, Vec3d targetPosition, Box targetBox) {
            List<BlockPos> path = new ArrayList<>();
            path.add(start);

            Vec3d startCenter = new Vec3d(start.getX() + 0.5D, start.getY(), start.getZ() + 0.5D);
            Vec3d targetCenter = targetBox.getCenter().subtract(0.0D, 0.5D, 0.0D);
            Vec3d delta = targetCenter.subtract(startCenter);
            int steps = Math.max(6, (int) Math.ceil(delta.length() / 0.75D));

            BlockPos last = start;
            for (int step = 1; step <= steps; step++) {
                double t = step / (double) steps;
                Vec3d sample = new Vec3d(
                        startCenter.x + delta.x * t,
                        startCenter.y + delta.y * t,
                        startCenter.z + delta.z * t
                );
                BlockPos around = BlockPos.ofFloored(sample);
                BlockPos standable = findStandable(world, around, around.getY());
                if (standable != null && !standable.equals(last)) {
                    path.add(standable);
                    last = standable;
                }
            }

            BlockPos targetBlock = findStandable(world, BlockPos.ofFloored(targetPosition), BlockPos.ofFloored(targetPosition).getY());
            if (targetBlock != null && !targetBlock.equals(last)) {
                path.add(targetBlock);
            }

            return path;
        }

        private static List<BlockPos> search(ClientWorld world, BlockPos start, List<BlockPos> goals, double scanRange) {
            PriorityQueue<Node> open = new PriorityQueue<>(Comparator.comparingDouble(node -> node.fScore));
            Map<Long, Node> best = new HashMap<>();
            Set<Long> closed = new HashSet<>();

            Node startNode = new Node(start, null, 0.0D, heuristic(start, goals));
            open.add(startNode);
            best.put(key(start), startNode);

            int explored = 0;
            while (!open.isEmpty() && explored < MAX_NODES) {
                Node current = open.poll();
                long currentKey = key(current.pos);
                if (!closed.add(currentKey)) {
                    continue;
                }
                explored++;

                if (isGoal(current.pos, goals)) {
                    return reconstruct(current);
                }

                for (BlockPos next : neighbors(world, start, current.pos, scanRange)) {
                    long nextKey = key(next);
                    if (closed.contains(nextKey) || !canStandAt(world, next)) {
                        continue;
                    }

                    double tentative = current.gScore + movementCost(current.pos, next);
                    Node known = best.get(nextKey);
                    if (known != null && tentative >= known.gScore) {
                        continue;
                    }

                    Node node = new Node(next, current, tentative, tentative + heuristic(next, goals));
                    best.put(nextKey, node);
                    open.add(node);
                }
            }

            return List.of();
        }

        private static List<BlockPos> reconstruct(Node node) {
            List<BlockPos> path = new ArrayList<>();
            Node cursor = node;
            while (cursor != null) {
                path.add(cursor.pos);
                cursor = cursor.parent;
            }
            List<BlockPos> reversed = new ArrayList<>(path.size());
            for (int i = path.size() - 1; i >= 0; i--) {
                reversed.add(path.get(i));
            }
            return reversed;
        }

        private static List<BlockPos> findGoals(ClientWorld world, Vec3d targetPosition, Box targetBox, int referenceY) {
            List<BlockPos> goals = new ArrayList<>();
            int minX = (int) Math.floor(targetBox.minX) - 1;
            int maxX = (int) Math.floor(targetBox.maxX) + 1;
            int minZ = (int) Math.floor(targetBox.minZ) - 1;
            int maxZ = (int) Math.floor(targetBox.maxZ) + 1;
            int minY = Math.max(world.getBottomY(), referenceY - 2);
            int maxY = Math.min(319, referenceY + 2);

            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    for (int y = minY; y <= maxY; y++) {
                        BlockPos pos = new BlockPos(x, y, z);
                        if (canStandAt(world, pos)) {
                            goals.add(pos);
                        }
                    }
                }
            }

            if (goals.isEmpty()) {
                BlockPos center = BlockPos.ofFloored(targetPosition);
                if (canStandAt(world, center)) {
                    goals.add(center);
                }
            }
            return goals;
        }

        private static List<BlockPos> neighbors(ClientWorld world, BlockPos origin, BlockPos pos, double scanRange) {
            List<BlockPos> neighbors = new ArrayList<>(6);
            double maxDistanceSquared = Math.pow(Math.max(8.0D, Math.min(MAX_RADIUS, scanRange)), 2.0D);
            int x = pos.getX();
            int y = pos.getY();
            int z = pos.getZ();
            BlockPos[] candidates = {
                    new BlockPos(x + 1, y, z),
                    new BlockPos(x - 1, y, z),
                    new BlockPos(x, y, z + 1),
                    new BlockPos(x, y, z - 1),
                    new BlockPos(x, y + 1, z),
                    new BlockPos(x, y - 1, z)
            };

            for (BlockPos candidate : candidates) {
                if (candidate.getSquaredDistance(origin.getX(), origin.getY(), origin.getZ()) > maxDistanceSquared) {
                    continue;
                }
                BlockPos standable = findStandable(world, candidate, y);
                if (standable != null && Math.abs(standable.getY() - y) <= 1) {
                    neighbors.add(standable);
                }
            }

            return neighbors;
        }

        private static BlockPos findStandable(ClientWorld world, BlockPos around, int referenceY) {
            for (int delta = 0; delta <= 3; delta++) {
                int[] candidates = delta == 0
                        ? new int[]{referenceY}
                        : new int[]{referenceY + delta, referenceY - delta};
                for (int y : candidates) {
                    if (y <= world.getBottomY() || y >= 318) {
                        continue;
                    }
                    BlockPos pos = new BlockPos(around.getX(), y, around.getZ());
                    if (canStandAt(world, pos)) {
                        return pos;
                    }
                }
            }

            return canStandAt(world, around) ? around : null;
        }

        private static boolean canStandAt(ClientWorld world, BlockPos pos) {
            if (pos.getY() <= world.getBottomY() || pos.getY() >= 318) {
                return false;
            }

            BlockPos feet = pos;
            BlockPos head = pos.up();
            BlockPos floor = pos.down();

            return isEmpty(world, feet) && isEmpty(world, head) && !isEmpty(world, floor);
        }

        private static boolean isEmpty(ClientWorld world, BlockPos pos) {
            return world.getBlockState(pos).getCollisionShape(world, pos).isEmpty();
        }

        private static boolean isGoal(BlockPos pos, List<BlockPos> goals) {
            for (BlockPos goal : goals) {
                if (pos.getSquaredDistance(goal.getX(), goal.getY(), goal.getZ()) <= GOAL_DISTANCE_SQUARED) {
                    return true;
                }
            }
            return false;
        }

        private static double movementCost(BlockPos from, BlockPos to) {
            return 1.0D + Math.abs(from.getY() - to.getY()) * 0.5D;
        }

        private static double heuristic(BlockPos pos, List<BlockPos> goals) {
            double best = Double.MAX_VALUE;
            for (BlockPos goal : goals) {
                double distance = pos.getSquaredDistance(goal.getX(), goal.getY(), goal.getZ());
                best = Math.min(best, distance);
            }
            return Math.sqrt(best);
        }

        private static long key(BlockPos pos) {
            return (((long) pos.getX()) & 0x3FFFFFFL) << 38 | (((long) pos.getZ()) & 0x3FFFFFFL) << 12 | ((long) pos.getY() & 0xFFFL);
        }

        private static final class Node {
            private final BlockPos pos;
            private final Node parent;
            private final double gScore;
            private final double fScore;

            private Node(BlockPos pos, Node parent, double gScore, double fScore) {
                this.pos = pos;
                this.parent = parent;
                this.gScore = gScore;
                this.fScore = fScore;
            }
        }
    }
}
