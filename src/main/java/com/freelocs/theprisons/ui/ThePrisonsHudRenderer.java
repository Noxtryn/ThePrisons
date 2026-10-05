package com.freelocs.theprisons.ui;

import com.freelocs.theprisons.ThePrisonsClient;
import com.freelocs.theprisons.bandit.ThePrisonsBanditManager;
import com.freelocs.theprisons.cache.ThePrisonsCache.ThePrisonsEntry;
import com.freelocs.theprisons.config.ThePrisonsConfig;
import com.freelocs.theprisons.feature.ThePrisonsFeatureManager;
import com.freelocs.theprisons.feature.ThePrisonsFeatureManager.FeatureHudEntry;
import com.freelocs.theprisons.core.ThePrisonsCore;
import com.freelocs.theprisons.core.hud.HudLine;
import com.freelocs.theprisons.core.hud.HudService;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.text.Text;
import com.freelocs.theprisons.gui.kit.Ui;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public final class ThePrisonsHudRenderer {
    private static final long ANNOUNCEMENT_DURATION_MS = 4500L;
    private static final List<Notification> ANNOUNCEMENTS = new ArrayList<>();
    private static final int ALERT_ROW_HEIGHT = 32;
    private static final int ALERT_TITLE_OFFSET = 4;
    private static final int ALERT_BODY_OFFSET = 17;
    private static final float ALERT_TEXT_SCALE = 1.45F;
    private static final int ALERT_SIDE_PADDING = 8;
    private static final int PADDING = 6;
    private static final int HEADER_HEIGHT = 12;
    private static final int ENTRY_HEIGHT = 11;
    private static final int SECTION_GAP = 6;
    private static final int ICON = 13;
    private static final int BAR_HEIGHT = 5;
    private static final int STATUS_READY = 0x7CF0A0;
    private static final int STATUS_ACTIVE = 0x8AD8FF;
    private static final int STATUS_COOLDOWN = 0xFFC14D;

    private ThePrisonsHudRenderer() {
    }

    public static void pushAnnouncement(String petName) {
        pushNotification(petName, "Ready", ThePrisonsColors.ACCENT_BLUE);
    }

    public static void pushNotification(String title, String body, int accentColor) {
        Notification notification = new Notification(title, body, accentColor, System.currentTimeMillis());
        ANNOUNCEMENTS.removeIf(existing -> existing.title.equalsIgnoreCase(title) && existing.body.equalsIgnoreCase(body));
        ANNOUNCEMENTS.add(notification);
    }

    public static HudDimensions drawHudOverlay(DrawContext context, MinecraftClient client, ThePrisonsConfig config, int x, int y, float scale, boolean preview) {
        if (client == null || client.textRenderer == null) {
            return HudDimensions.EMPTY;
        }

        List<TrackedSection> sections = collectSections(client, config);
        if (sections.isEmpty()) {
            return HudDimensions.EMPTY;
        }

        int baseWidth = measureWidth(client, sections);
        int baseHeight = measureHeight(sections);

        context.getMatrices().pushMatrix();
        context.getMatrices().translate(x / scale, y / scale);
        context.getMatrices().scale(scale, scale);

        drawFrame(context, config, baseWidth, baseHeight);

        int cursorY = PADDING;
        for (int i = 0; i < sections.size(); i++) {
            TrackedSection section = sections.get(i);
            drawSection(context, client, config, section, baseWidth, cursorY);
            cursorY += sectionHeight(section);
            if (i < sections.size() - 1) {
                cursorY += SECTION_GAP;
            }
        }

        if (preview) {
            context.drawTextWithShadow(client.textRenderer, Text.literal("Scroll to resize"), PADDING, baseHeight + 4, ThePrisonsColors.FG_MUTED);
        }

        context.getMatrices().popMatrix();
        return new HudDimensions(Math.round(baseWidth * scale), Math.round(baseHeight * scale));
    }

    public static void render(DrawContext context, RenderTickCounter tickCounter) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.textRenderer == null || client.options.hudHidden) {
            return;
        }

        ThePrisonsConfig config = ThePrisonsClient.CONFIG.get();
        if (config.hud.petHudEnabled) {
            drawWidget(context, client, sectionsFor(client, config, "PET_TRINKET", false), config.gui.petHudX, config.gui.petHudY,
                    config.gui.petHudScale, false);
        }
        if (moduleOn("session_stats")) {
            drawWidget(context, client, sectionsFor(client, config, "SESSION_XP", false), config.gui.sessionXpHudX,
                    config.gui.sessionXpHudY, config.gui.sessionXpHudScale, false);
            drawWidget(context, client, sectionsFor(client, config, "ENERGY", false), config.gui.energyHudX,
                    config.gui.energyHudY, config.gui.energyHudScale, false);
        }
        drawWidget(context, client, sectionsFor(client, config, "MINING", false), config.gui.miningHudX, config.gui.miningHudY,
                config.gui.miningHudScale, false);

        ThePrisonsBanditManager.renderHud(context, client, config);
        renderAnnouncements(context, client, config);
    }

    private static boolean moduleOn(String id) {
        ThePrisonsCore core = ThePrisonsCore.getOrNull();
        com.freelocs.theprisons.core.module.Module module = core != null ? core.modules().get(id) : null;
        return module != null && module.enabled();
    }

    /** The sections a widget shows (preview: sample rows when there is nothing to show yet). */
    private static List<TrackedSection> sectionsFor(MinecraftClient client, ThePrisonsConfig config, String widgetId, boolean preview) {
        List<TrackedSection> sections = new ArrayList<>();
        switch (widgetId) {
            case "PET_TRINKET" -> {
                List<HudEntry> pets = collectEntries("PET", ThePrisonsClient.CACHE.entries().values(), client, config);
                List<HudEntry> trinkets = collectEntries("TRINKET", ThePrisonsClient.CACHE.entries().values(), client, config);
                if (preview && pets.isEmpty() && trinkets.isEmpty()) {
                    pets.add(new HudEntry("Anti XP Tax", "ACTIVE 29:12", false, STATUS_ACTIVE, "minecraft:player_head"));
                    pets.add(new HudEntry("Lucky", "READY", true, STATUS_READY, "minecraft:player_head"));
                    trinkets.add(new HudEntry("Blink Trinket", "4:12", false, STATUS_COOLDOWN, "minecraft:ender_eye"));
                }
                if (!pets.isEmpty()) {
                    sections.add(new TrackedSection("PETS", 0, pets));
                }
                if (!trinkets.isEmpty()) {
                    sections.add(new TrackedSection("TRINKETS", 0, trinkets));
                }
            }
            case "SESSION_XP" -> addSection(sections, "SESSION XP", filterStatsEntries(true));
            case "ENERGY" -> addSection(sections, "ENERGY", filterStatsEntries(false));
            case "MINING" -> {
                if (moduleOn("command_cooldowns")) {
                    addSection(sections, "COOLDOWNS", toHudEntries(ThePrisonsFeatureManager.commandCooldownEntries()));
                }
                if (moduleOn("satchel_hud")) {
                    addSection(sections, "SATCHELS", satchelRows(preview));
                }
                if (com.freelocs.theprisons.modules.FeatureProfile.DEV) {
                    addSection(sections, "MACRO", toHudEntries(moduleEntries()));
                }
                if (preview && sections.isEmpty()) {
                    List<HudEntry> sample = new ArrayList<>();
                    sample.add(new HudEntry("/fix", "READY", true, STATUS_READY, null));
                    sample.add(new HudEntry("/jet", "2:41", false, STATUS_COOLDOWN, null));
                    sections.add(new TrackedSection("COOLDOWNS", 0, sample));
                }
            }
            default -> {
            }
        }
        return sections;
    }

    /** Satchels: icon, name, amount and a fill bar (green -> amber -> red, pulsing from the warning level). */
    private static List<HudEntry> satchelRows(boolean preview) {
        List<HudEntry> rows = new ArrayList<>();
        List<ThePrisonsFeatureManager.SatchelView> views = ThePrisonsFeatureManager.satchelViews();
        if (preview && views.isEmpty()) {
            views = List.of(new ThePrisonsFeatureManager.SatchelView("Gold Satchel", 41_200, 64_000, 64.4F,
                            new net.minecraft.item.ItemStack(net.minecraft.item.Items.GOLD_INGOT), false),
                    new ThePrisonsFeatureManager.SatchelView("Diamond Satchel", 61_900, 64_000, 96.7F,
                            new net.minecraft.item.ItemStack(net.minecraft.item.Items.DIAMOND), true));
        }
        for (ThePrisonsFeatureManager.SatchelView v : views) {
            String amount = v.fillPercent() >= 100.0F ? "FULL"
                    : v.capacity() > 0 ? Math.round(v.fillPercent()) + "%  " + ThePrisonsFeatureManager.compactAmount(v.amount())
                    : ThePrisonsFeatureManager.compactAmount(v.amount());
            HudEntry row = new HudEntry(v.name(), amount, false, fillColour(v.fillPercent()), "minecraft:chest");
            row.stack = v.icon();
            row.fill = v.capacity() > 0 ? v.fillPercent() / 100.0F : -1.0F;
            row.warn = v.warn();
            rows.add(row);
        }
        return rows;
    }

    private static int fillColour(float percent) {
        float t = Math.max(0.0F, Math.min(1.0F, percent / 100.0F));
        return t < 0.6F ? Ui.mix(0x7CF0A0, 0xFFE066, t / 0.6F) : Ui.mix(0xFFE066, 0xFF5E6C, (t - 0.6F) / 0.4F);
    }

    private static void addSection(List<TrackedSection> sections, String title, List<HudEntry> entries) {
        if (!entries.isEmpty()) {
            sections.add(new TrackedSection(title, 0, entries));
        }
    }



    public static HudBounds widgetBounds(MinecraftClient client, ThePrisonsConfig config, String widgetId) {
        if (client == null || client.textRenderer == null) {
            return HudBounds.EMPTY;
        }
        // The editor shows previews, so the bounds are those of the preview content.
        List<TrackedSection> sections = sectionsFor(client, config, widgetId, true);
        if (sections.isEmpty()) {
            return HudBounds.EMPTY;
        }
        float scale = widgetScale(config, widgetId);
        int width = Math.round(measureWidth(client, sections) * scale);
        int height = Math.round(measureHeight(sections) * scale);
        int x = switch (widgetId) {
            case "PET_TRINKET" -> config.gui.petHudX;
            case "SESSION_XP" -> config.gui.sessionXpHudX;
            case "ENERGY" -> config.gui.energyHudX;
            case "MINING" -> config.gui.miningHudX;
            default -> -1;
        };
        int y = switch (widgetId) {
            case "PET_TRINKET" -> config.gui.petHudY;
            case "SESSION_XP" -> config.gui.sessionXpHudY;
            case "ENERGY" -> config.gui.energyHudY;
            case "MINING" -> config.gui.miningHudY;
            default -> -1;
        };
        if (x < 0 || y < 0) {
            return HudBounds.EMPTY;
        }
        return new HudBounds(x, y, width, height);
    }

    public static void drawWidgetPreviews(DrawContext context, MinecraftClient client, ThePrisonsConfig config) {
        if (config.hud.petHudEnabled) {
            drawWidget(context, client, sectionsFor(client, config, "PET_TRINKET", true), config.gui.petHudX, config.gui.petHudY,
                    config.gui.petHudScale, true);
        }
        if (moduleOn("session_stats")) {
            drawWidget(context, client, sectionsFor(client, config, "SESSION_XP", true), config.gui.sessionXpHudX,
                    config.gui.sessionXpHudY, config.gui.sessionXpHudScale, true);
            drawWidget(context, client, sectionsFor(client, config, "ENERGY", true), config.gui.energyHudX,
                    config.gui.energyHudY, config.gui.energyHudScale, true);
        }
        drawWidget(context, client, sectionsFor(client, config, "MINING", true), config.gui.miningHudX, config.gui.miningHudY,
                config.gui.miningHudScale, true);
    }


    public static HudBounds notificationBounds(MinecraftClient client, ThePrisonsConfig config, boolean preview) {
        if (client == null || client.textRenderer == null) {
            return HudBounds.EMPTY;
        }
        return notificationBounds(client, config, activeNotifications(preview));
    }


    public static void drawNotificationPreview(DrawContext context, MinecraftClient client, ThePrisonsConfig config) {
        drawNotificationStack(context, client, config, true);
    }







    private static float widgetScale(ThePrisonsConfig config, String widgetId) {
        return switch (widgetId) {
            case "PET_TRINKET" -> config.gui.petHudScale;
            case "SESSION_XP" -> config.gui.sessionXpHudScale;
            case "ENERGY" -> config.gui.energyHudScale;
            case "MINING" -> config.gui.miningHudScale;
            default -> config.gui.hudScale;
        };
    }





    private static List<HudEntry> toHudEntries(List<FeatureHudEntry> featureEntries) {
        List<HudEntry> entries = new ArrayList<>();
        if (config.hud.showTrackedPets) {
            entries.addAll(collectEntries("PET", ThePrisonsClient.CACHE.entries().values(), client, config));
        }
        if (config.hud.showTrinkets) {
            entries.addAll(collectEntries("TRINKET", ThePrisonsClient.CACHE.entries().values(), client, config));
        }
        return entries;
    }

    private static List<HudEntry> filterStatsEntries(boolean xp) {
        List<HudEntry> entries = new ArrayList<>();
        for (FeatureHudEntry entry : ThePrisonsFeatureManager.statsEntries()) {
            boolean isXpEntry = entry.name().toLowerCase(Locale.ROOT).startsWith("xp");
            if (xp == isXpEntry) {
                entries.add(new HudEntry(entry.name(), entry.value(), false, entry.color(), null));
            }
        }
        return entries;
    }


    /** A widget card in the new design: dark card, flowing accent line, section titles, icon rows. */
    private static void drawWidget(DrawContext context, MinecraftClient client, List<TrackedSection> sections,
                                   int x, int y, float scale, boolean preview) {
        if (sections.isEmpty() || x < 0 || y < 0) {
            return;
        }
        int baseWidth = measureWidth(client, sections);
        int baseHeight = measureHeight(sections);

        context.getMatrices().pushMatrix();
        context.getMatrices().translate(x, y);
        context.getMatrices().scale(scale, scale);

        drawFrame(context, config, baseWidth, baseHeight);

        int cursorY = PADDING;
        for (int i = 0; i < sections.size(); i++) {
            TrackedSection section = sections.get(i);
            drawSection(context, client, section, baseWidth, cursorY);
            cursorY += sectionHeight(section);
            if (i < sections.size() - 1) {
                cursorY += SECTION_GAP;
            }
        }
        context.getMatrices().popMatrix();
    }

    private static List<HudEntry> collectEntries(String source, Iterable<ThePrisonsEntry> values, MinecraftClient client, ThePrisonsConfig config) {
        long nowMs = System.currentTimeMillis();
        List<HudEntry> entries = new ArrayList<>();
        for (ThePrisonsEntry entry : values) {
            if (entry == null || entry.displayName == null) {
                continue;
            }
            String category = entry.source == null || entry.source.isBlank() ? "PET" : entry.source;
            if (!source.equalsIgnoreCase(category)) {
                continue;
            }
            String status;
            int colour;
            boolean ready = entry.cooldownEndsAtMs <= nowMs;
            if (entry.activeUntilMs > nowMs) {
                status = "ACTIVE " + formatCooldownRemaining(entry.activeUntilMs - nowMs);
                colour = STATUS_ACTIVE;
            } else if (ready) {
                status = config.hud.showReadyStatus ? "READY" : "";
                colour = STATUS_READY;
            } else {
                status = config.hud.showCountdown ? formatCooldownRemaining(entry.cooldownEndsAtMs - nowMs) : "";
                colour = STATUS_COOLDOWN;
            }
            HudEntry row = new HudEntry(stripPetSuffix(entry.displayName), status, ready, colour, entry.itemId);
            row.stack = com.freelocs.theprisons.state.ThePrisonsTracker.stack(entry.key);
            entries.add(row);
        }
        entries.sort(Comparator.comparing(value -> value.displayName.toLowerCase(Locale.ROOT)));
        return entries;
    }

    private static String formatCooldownRemaining(long remainingMs) {
        long remainingSeconds = Math.max(1L, (long) Math.ceil(Math.max(0L, remainingMs) / 1000.0D));
        long hours = remainingSeconds / 3600L;
        long minutes = remainingSeconds % 3600L / 60L;
        long seconds = remainingSeconds % 60L;
        if (hours > 0L) {
            return String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds);
        }
        if (minutes > 0L) {
            return String.format(Locale.ROOT, "%d:%02d", minutes, seconds);
        }
        return remainingSeconds + "s";
    }

    private static void drawFrame(DrawContext context, ThePrisonsConfig config, int width, int height) {
        context.fill(0, 0, width, height, config.hud.backgroundColor);
        context.fill(1, 1, width - 1, 2, 0x22000000);
        context.fill(2, 2, width - 2, height - 2, 0x22000000);
        context.fill(0, 0, width, 2, ThePrisonsColors.ACCENT_CYAN);
        context.fill(0, 2, width, 3, ThePrisonsColors.ACCENT_AMBER);
        context.fill(0, 0, width, 1, config.hud.borderColor);
        context.fill(0, height - 1, width, height, config.hud.borderColor);
        context.fill(0, 0, 1, height, config.hud.borderColor);
        context.fill(width - 1, 0, width, height, config.hud.borderColor);
    }

    private static void drawSection(DrawContext context, MinecraftClient client, ThePrisonsConfig config, TrackedSection section, int width, int startY) {
        int textColor = section.accentColor;
        context.fill(PADDING, startY + 2, PADDING + 3, startY + 9, textColor);
        context.drawTextWithShadow(client.textRenderer, Text.literal(section.title), PADDING + 7, startY, textColor);
        int cursorY = startY + HEADER_HEIGHT;
        for (HudEntry entry : section.entries) {
            int textX = PADDING;
            if (entry.itemId != null) {
                net.minecraft.item.ItemStack icon = entry.stack;
                if (icon.isEmpty()) {
                    net.minecraft.util.Identifier id = net.minecraft.util.Identifier.tryParse(entry.itemId);
                    icon = id != null ? new net.minecraft.item.ItemStack(net.minecraft.registry.Registries.ITEM.get(id)) : icon;
                }
                if (!icon.isEmpty()) {
                    context.getMatrices().pushMatrix();
                    context.getMatrices().translate(PADDING, cursorY - 1);
                    context.getMatrices().scale(0.625F, 0.625F);
                    context.drawItem(icon, 0, 0);
                    context.getMatrices().popMatrix();
                }
                textX += ICON;
            }
            Ui.draw(context, client.textRenderer, entry.displayName, textX, cursorY, Ui.VALUE, 255);
            if (!entry.statusText.isEmpty()) {
                int alpha = entry.ready ? Math.round(170 + 85 * Ui.pulse(1400L)) : 255;
                Ui.drawRight(context, client.textRenderer, entry.statusText, width - PADDING, cursorY, entry.statusColor, alpha);
            }
            cursorY += ENTRY_HEIGHT;
            if (entry.fill >= 0.0F) {
                int bx = PADDING + (entry.itemId != null ? ICON : 0);
                int bw = width - PADDING - bx;
                int alpha = entry.warn ? Math.round(150 + 105 * Ui.pulse(700L)) : 255;
                Ui.round(context, bx, cursorY - 1, bw, 4, Ui.argb(55, 0xFFFFFF));
                int fill = Math.round(bw * Math.min(1.0F, entry.fill));
                if (fill > 2) {
                    context.fillGradient(bx + 1, cursorY, bx + fill - 1, cursorY + 2, Ui.argb(alpha, 0x7CF0A0),
                            Ui.argb(alpha, entry.statusColor));
                }
                cursorY += BAR_HEIGHT;
            }
        }
    }

    private static int measureWidth(MinecraftClient client, List<TrackedSection> sections) {
        int width = 0;
        for (TrackedSection section : sections) {
            int sectionWidth = Ui.width(client.textRenderer, section.title) + 30;
            for (HudEntry entry : section.entries) {
                int entryWidth = Ui.width(client.textRenderer, entry.displayName) + (entry.itemId != null ? ICON : 0);
                if (!entry.statusText.isEmpty()) {
                    entryWidth += 12 + Ui.width(client.textRenderer, entry.statusText);
                }
                sectionWidth = Math.max(sectionWidth, entryWidth);
            }
            width = Math.max(width, sectionWidth + PADDING * 2);
        }
        return Math.max(width, 120);
    }

    private static int measureHeight(List<TrackedSection> sections) {
        int height = PADDING;
        for (int i = 0; i < sections.size(); i++) {
            TrackedSection section = sections.get(i);
            height += sectionHeight(section);
            if (i < sections.size() - 1) {
                height += SECTION_GAP;
            }
        }
        return height + PADDING;
    }

    private static int sectionHeight(TrackedSection section) {
        int bars = 0;
        for (HudEntry entry : section.entries) {
            if (entry.fill >= 0.0F) {
                bars++;
            }
        }
        return HEADER_HEIGHT + section.entries.size() * ENTRY_HEIGHT + bars * BAR_HEIGHT;
    }

    private static void renderAnnouncements(DrawContext context, MinecraftClient client, ThePrisonsConfig config) {
        drawNotificationStack(context, client, config, false);
    }

    /** Toasts in the cards' look: slide down and fade in, a bar shows the time left, then they fade out. */
    private static void drawNotificationStack(DrawContext context, MinecraftClient client, ThePrisonsConfig config, boolean preview) {
        List<Notification> active = activeNotifications(preview);
        if (active.isEmpty()) {
            return;
        }
        HudBounds bounds = notificationBounds(client, config, active);
        if (bounds == HudBounds.EMPTY) {
            return;
        }
        int screenWidth = client.getWindow().getScaledWidth();
        int y = bounds.y;
        int rendered = 0;
        long nowMs = System.currentTimeMillis();
        for (Notification notification : active) {
            long age = preview ? 1_000L : nowMs - notification.startedAtMs;
            float in = Ui.easeOut(age / 260.0F);
            float out = preview ? 1.0F : Math.min(1.0F, (ANNOUNCEMENT_DURATION_MS - age) / 400.0F);
            float a = Math.max(0.0F, Math.min(in, out));
            if (!com.freelocs.theprisons.modules.general.DesignModule.animations()) {
                a = 1.0F;
            }
            int width = notificationWidth(client, notification);
            int x = (screenWidth - width) / 2;
            x = Math.max(0, x);

            int rowBottom = y + ALERT_ROW_HEIGHT;
            context.fill(x, y, x + width, rowBottom, 0xD8141826);
            context.fill(x, y, x + width, y + 2, notification.accentColor);

            context.getMatrices().pushMatrix();
            context.getMatrices().translate(x, y);
            context.getMatrices().scale(ALERT_TEXT_SCALE, ALERT_TEXT_SCALE);

            Text titleText = Text.literal(notification.title).copy().styled(style -> style.withBold(true));
            int titleWidth = client.textRenderer.getWidth(titleText);
            int bodyWidth = client.textRenderer.getWidth(notification.body);
            int scaledWidth = width / ALERT_TEXT_SCALE;
            int titleX = Math.max(1, (scaledWidth - titleWidth) / 2);
            int bodyX = Math.max(1, (scaledWidth - bodyWidth) / 2);

            context.drawTextWithShadow(client.textRenderer, titleText, titleX, ALERT_TITLE_OFFSET / ALERT_TEXT_SCALE, config.hud.alertTitleColor);
            context.drawTextWithShadow(client.textRenderer, Text.literal(notification.body), bodyX, ALERT_BODY_OFFSET / ALERT_TEXT_SCALE, config.hud.alertBodyColor);
            context.getMatrices().popMatrix();

            y += ALERT_ROW_HEIGHT;
            rendered++;
            if (rendered >= 3) {
                break;
            }
        }
    }

    private static List<Notification> activeNotifications(boolean preview) {
        long nowMs = System.currentTimeMillis();
        List<Notification> active = new ArrayList<>();
        for (Notification announcement : ANNOUNCEMENTS) {
            if (nowMs - announcement.startedAtMs <= ANNOUNCEMENT_DURATION_MS) {
                active.add(announcement);
            }
        }
        active.sort(Comparator.comparingLong(announcement -> announcement.startedAtMs));
        if (active.isEmpty()) {
            if (preview) {
                active.add(new Notification("Alert", "Position preview", ThePrisonsColors.ACCENT_CYAN, nowMs));
            } else {
                ANNOUNCEMENTS.removeIf(announcement -> nowMs - announcement.startedAtMs > ANNOUNCEMENT_DURATION_MS);
            }
        }
        return active;
    }

    private static HudBounds notificationBounds(MinecraftClient client, ThePrisonsConfig config, List<Notification> notifications) {
        if (notifications.isEmpty()) {
            return HudBounds.EMPTY;
        }
        int width = 0;
        int rendered = 0;
        for (Notification notification : notifications) {
            width = Math.max(width, notificationWidth(client, notification));
            rendered++;
            if (rendered >= 3) {
                break;
            }
        }
        if (rendered <= 0) {
            return HudBounds.EMPTY;
        }
        int x = (client.getWindow().getScaledWidth() - width) / 2;
        int y = Math.max(2, Math.round(client.getWindow().getScaledHeight() * 0.25F));
        return new HudBounds(Math.max(0, x), y, width, rendered * ALERT_ROW_HEIGHT);
    }

    private static int notificationWidth(MinecraftClient client, Notification notification) {
        int titleWidth = client.textRenderer.getWidth(notification.title) * ALERT_TEXT_SCALE;
        int bodyWidth = client.textRenderer.getWidth(notification.body) * ALERT_TEXT_SCALE;
        int width = Math.max(280, titleWidth + ALERT_SIDE_PADDING * 2);
        return Math.max(width, bodyWidth + ALERT_SIDE_PADDING * 2);
    }

    private static String stripPetSuffix(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        if (trimmed.toLowerCase(Locale.ROOT).endsWith(" pet")) {
            return trimmed.substring(0, trimmed.length() - 4).trim();
        }
        return trimmed;
    }

    public static final class HudDimensions {
        public static final HudDimensions EMPTY = new HudDimensions(0, 0);
        public final int width;
        public final int height;

        public HudDimensions(int width, int height) {
            this.width = width;
            this.height = height;
        }
    }

    public static final class HudBounds {
        public static final HudBounds EMPTY = new HudBounds(0, 0, 0, 0);
        public final int x;
        public final int y;
        public final int width;
        public final int height;

        public HudBounds(int x, int y, int width, int height) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
        }

        public boolean contains(int mouseX, int mouseY) {
            return width > 0 && height > 0 && mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + height;
        }
    }

    public static final class Notification {
        public final String title;
        public final String body;
        public final int accentColor;
        public final long startedAtMs;

        public Notification(String title, String body, int accentColor, long startedAtMs) {
            this.title = title;
            this.body = body;
            this.accentColor = accentColor;
            this.startedAtMs = startedAtMs;
        }
    }

    private static final class TrackedSection {
        private final String title;
        private final int accentColor;
        private final List<HudEntry> entries;

        private TrackedSection(String title, int accentColor, List<HudEntry> entries) {
            this.title = title;
            this.accentColor = accentColor;
            this.entries = entries;
        }
    }

    private static final class HudEntry {
        private final String displayName;
        private final String statusText;
        private final boolean ready;
        private final int statusColor;
        private final @org.jspecify.annotations.Nullable String itemId;
        private net.minecraft.item.ItemStack stack = net.minecraft.item.ItemStack.EMPTY;
        /** Fill bar 0..1 under the row (satchels), -1 = none. */
        private float fill = -1.0F;
        private boolean warn;

        private HudEntry(String displayName, String statusText, boolean ready, int statusColor,
                         @org.jspecify.annotations.Nullable String itemId) {
            this.displayName = displayName;
            this.statusText = statusText == null ? "" : statusText;
            this.ready = ready;
            this.statusColor = statusColor & 0xFFFFFF;
            this.itemId = itemId;
        }
    }

    /** Rows of enabled modules, collected by the core every few ticks; converting them is a small list copy. */
    private static List<FeatureHudEntry> moduleEntries() {
        ThePrisonsCore core = ThePrisonsCore.getOrNull();
        if (core == null) {
            return List.of();
        }
        List<HudService.Block> blocks = core.hud().blocks();
        if (blocks.isEmpty()) {
            return List.of();
        }
        List<FeatureHudEntry> entries = new ArrayList<>();
        for (HudService.Block block : blocks) {
            if (blocks.size() > 1) {
                entries.add(new FeatureHudEntry(block.title(), "", block.color()));
            }
            for (HudLine line : block.lines()) {
                entries.add(new FeatureHudEntry(line.label(), line.value(), line.color()));
            }
        }
        return entries;
    }
}
