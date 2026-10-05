package com.freelocs.theprisons.bandit;

import com.freelocs.theprisons.ThePrisonsClient;
import com.freelocs.theprisons.config.ThePrisonsConfig;
import com.freelocs.theprisons.ui.ThePrisonsColors;
import com.freelocs.theprisons.ui.ThePrisonsHudRenderer;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import org.jspecify.annotations.Nullable;

public final class ThePrisonsBanditManager {
    private static final EquipmentSlot[] ARMOR_SLOTS = {
            EquipmentSlot.HEAD,
            EquipmentSlot.CHEST,
            EquipmentSlot.LEGS,
            EquipmentSlot.FEET
    };

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
        if (client == null || client.textRenderer == null) {
            return ThePrisonsHudRenderer.HudBounds.EMPTY;
        }

        int iconSize = armorIconSize(config);
        int gap = armorGap(config);
        int rowHeight = iconSize + 2;
        int totalHeight = ARMOR_SLOTS.length * rowHeight + (ARMOR_SLOTS.length - 1) * gap;
        int percentColumnWidth = Math.max(34, client.textRenderer.getWidth("100%") + 6);
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
    }

    public static void onChatMessage(Text message, @Nullable Object signedMessage, @Nullable Object sender, Object params, java.time.Instant receptionTimestamp) {
    }

    private static void updateArmorWarnings(ClientPlayerEntity player, ThePrisonsConfig config) {
        for (EquipmentSlot slot : ARMOR_SLOTS) {
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
        com.freelocs.theprisons.gui.kit.Ui.card(context, bounds.x, bounds.y, bounds.width, bounds.height, 1.0F);
        com.freelocs.theprisons.gui.kit.Ui.flowLine(context, bounds.x, bounds.x + bounds.width, bounds.y, 1.0F);
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
            ItemStack stack = player == null ? ItemStack.EMPTY : player.getEquippedStack(slots[i]);
            if (stack.isEmpty() && preview && i < samples.length) {
                stack = new ItemStack(samples[i]);
            }
            int iconY = startY + i * (rowHeight + gap);
            int textY = iconY + Math.max(0, (iconSize - 8) / 2) - 1;
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
            float scale = iconSize / 16.0F;
            context.getMatrices().pushMatrix();
            context.getMatrices().translate(startX, iconY);
            context.getMatrices().scale(scale, scale);
            context.drawItem(stack, 0, 0);
            context.getMatrices().popMatrix();
            float percent = durabilityPercent(stack);
            int colour = armorColor(percent) & 0xFFFFFF;
            boolean critical = stack.isDamageable() && percent < 20.0F;
            int alpha = critical ? Math.round(150 + 105 * com.freelocs.theprisons.gui.kit.Ui.pulse(700L)) : 255;
            com.freelocs.theprisons.gui.kit.Ui.draw(context, client.textRenderer,
                    stack.isDamageable() ? Math.round(percent) + "%" : "∞", columnX, textY, colour, alpha);
            int barY = textY + 9;
            context.fill(columnX, barY, columnX + columnW, barY + 2, com.freelocs.theprisons.gui.kit.Ui.argb(60, 0xFFFFFF));
            context.fill(columnX, barY, columnX + Math.round(columnW * percent / 100.0F), barY + 2,
                    com.freelocs.theprisons.gui.kit.Ui.argb(alpha, colour));
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
}
