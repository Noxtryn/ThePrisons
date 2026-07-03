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
            return;
        }
        updateArmorWarnings(player, ThePrisonsClient.CONFIG.get());
    }

    public static void renderWorld(WorldRenderContext context) {
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
    }

    public static void onChatMessage(Text message, @Nullable Object signedMessage, @Nullable Object sender, Object params, java.time.Instant receptionTimestamp) {
    }

    private static void updateArmorWarnings(ClientPlayerEntity player, ThePrisonsConfig config) {
        for (EquipmentSlot slot : ARMOR_SLOTS) {
            ItemStack stack = player.getEquippedStack(slot);
            if (stack.isDamageable() && durabilityPercent(stack) < 20.0F && config.hud.showArmorWarnings) {
                ThePrisonsHudRenderer.pushNotification("Armor Critical", slotName(slot) + " needs repair soon.", ThePrisonsColors.ACCENT_AMBER);
            }
        }
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
        int percentXOffset = iconSize + 4;
        ThePrisonsHudRenderer.HudBounds bounds = measureArmorHud(client, config);
        int startX = bounds.x + 2;
        int startY = bounds.y + 2;

        for (int i = 0; i < slots.length; i++) {
            EquipmentSlot slot = slots[i];
            ItemStack stack = player == null ? ItemStack.EMPTY : player.getEquippedStack(slot);
            int iconX = startX;
            int iconY = startY + i * (rowHeight + gap);

            if (stack.isEmpty()) {
                int textY = iconY + Math.max(0, (iconSize - 8) / 2);
                context.drawTextWithShadow(client.textRenderer, Text.literal("--%"), iconX + percentXOffset, textY, ThePrisonsColors.FG_MUTED);
            } else {
                context.drawItem(stack, iconX, iconY, 0);
                if (stack.isDamageable()) {
                    float durabilityPercent = durabilityPercent(stack);
                    float destroyRatePercent = Math.max(0.0F, 100.0F - durabilityPercent);
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
