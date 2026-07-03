package com.freelocs.theprisons.ui;

import com.freelocs.theprisons.ThePrisonsClient;
import com.freelocs.theprisons.bandit.ThePrisonsBanditManager;
import com.freelocs.theprisons.cache.ThePrisonsCache.ThePrisonsEntry;
import com.freelocs.theprisons.config.ThePrisonsConfig;
import com.freelocs.theprisons.feature.ThePrisonsFeatureManager;
import com.freelocs.theprisons.feature.ThePrisonsFeatureManager.FeatureHudEntry;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public final class ThePrisonsHudRenderer {
    private static final long ANNOUNCEMENT_DURATION_MS = 4500L;
    private static final List<Notification> ANNOUNCEMENTS = new ArrayList<>();
    private static final int ALERT_ROW_HEIGHT = 44;
    private static final int ALERT_TITLE_OFFSET = 6;
    private static final int ALERT_BODY_OFFSET = 23;
    private static final int ALERT_TEXT_SCALE = 2;
    private static final int ALERT_SIDE_PADDING = 12;
    private static final int PADDING = 6;
    private static final int HEADER_HEIGHT = 12;
    private static final int ENTRY_HEIGHT = 11;
    private static final int SECTION_GAP = 6;

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
        if (client == null || client.textRenderer == null) {
            return;
        }

        ThePrisonsConfig config = ThePrisonsClient.CONFIG.get();
        if (config.hud.petHudEnabled) {
            drawTrackedWidget(context, client, config, "Pets & Trinkets", ThePrisonsColors.ACCENT_BLUE,
                    collectPetTrinketEntries(client, config),
                    config.gui.petHudX, config.gui.petHudY, config.gui.petHudScale, false);
            drawTrackedWidget(context, client, config, "Session XP", ThePrisonsColors.ACCENT_CYAN,
                    filterStatsEntries(true),
                    config.gui.sessionXpHudX, config.gui.sessionXpHudY, config.gui.sessionXpHudScale, false);
            drawTrackedWidget(context, client, config, "Energy", ThePrisonsColors.ACCENT_LIME,
                    filterStatsEntries(false),
                    config.gui.energyHudX, config.gui.energyHudY, config.gui.energyHudScale, false);
        }

        ThePrisonsBanditManager.renderHud(context, client, config);
        renderAnnouncements(context, client, config);
    }

    public static HudDimensions measureHud(MinecraftClient client, ThePrisonsConfig config) {
        List<TrackedSection> sections = collectSections(client, config);
        if (sections.isEmpty()) {
            return HudDimensions.EMPTY;
        }
        int baseWidth = measureWidth(client, sections);
        int baseHeight = measureHeight(sections);
        return new HudDimensions(Math.round(baseWidth * config.gui.hudScale), Math.round(baseHeight * config.gui.hudScale));
    }

    public static HudBounds widgetBounds(MinecraftClient client, ThePrisonsConfig config, String widgetId) {
        if (client == null || client.textRenderer == null) {
            return HudBounds.EMPTY;
        }
        TrackedSection section = sectionForWidget(client, config, widgetId);
        if (section == null || section.entries.isEmpty()) {
            return HudBounds.EMPTY;
        }
        float scale = widgetScale(config, widgetId);
        int width = Math.round((measureWidth(client, List.of(section))) * scale);
        int height = Math.round((measureHeight(List.of(section))) * scale);
        int x = switch (widgetId) {
            case "PET_TRINKET" -> config.gui.petHudX;
            case "SESSION_XP" -> config.gui.sessionXpHudX;
            case "ENERGY" -> config.gui.energyHudX;
            default -> -1;
        };
        int y = switch (widgetId) {
            case "PET_TRINKET" -> config.gui.petHudY;
            case "SESSION_XP" -> config.gui.sessionXpHudY;
            case "ENERGY" -> config.gui.energyHudY;
            default -> -1;
        };
        if (x < 0 || y < 0) {
            return HudBounds.EMPTY;
        }
        return new HudBounds(x, y, width, height);
    }

    public static void drawWidgetPreviews(DrawContext context, MinecraftClient client, ThePrisonsConfig config) {
        drawTrackedWidget(context, client, config, "Pets & Trinkets", ThePrisonsColors.ACCENT_BLUE,
                collectPetTrinketEntries(client, config),
                config.gui.petHudX, config.gui.petHudY, config.gui.petHudScale, true);
        drawTrackedWidget(context, client, config, "Session XP", ThePrisonsColors.ACCENT_CYAN,
                filterStatsEntries(true),
                config.gui.sessionXpHudX, config.gui.sessionXpHudY, config.gui.sessionXpHudScale, true);
        drawTrackedWidget(context, client, config, "Energy", ThePrisonsColors.ACCENT_LIME,
                filterStatsEntries(false),
                config.gui.energyHudX, config.gui.energyHudY, config.gui.energyHudScale, true);
    }

    public static boolean isInsideHud(MinecraftClient client, ThePrisonsConfig config, int mouseX, int mouseY) {
        HudDimensions size = measureHud(client, config);
        int scaledWidth = size.width;
        int scaledHeight = size.height;
        int x = config.gui.petHudX;
        int y = config.gui.petHudY;
        return mouseX >= x && mouseX <= x + scaledWidth && mouseY >= y && mouseY <= y + scaledHeight;
    }

    public static HudBounds notificationBounds(MinecraftClient client, ThePrisonsConfig config, boolean preview) {
        if (client == null || client.textRenderer == null) {
            return HudBounds.EMPTY;
        }
        return notificationBounds(client, config, activeNotifications(preview));
    }

    public static boolean isInsideNotifications(MinecraftClient client, ThePrisonsConfig config, int mouseX, int mouseY) {
        return notificationBounds(client, config, true).contains(mouseX, mouseY);
    }

    public static void drawNotificationPreview(DrawContext context, MinecraftClient client, ThePrisonsConfig config) {
        drawNotificationStack(context, client, config, true);
    }

    private static List<TrackedSection> collectSections(MinecraftClient client, ThePrisonsConfig config) {
        List<TrackedSection> sections = new ArrayList<>();
        List<HudEntry> petTrinketEntries = collectPetTrinketEntries(client, config);
        if (!petTrinketEntries.isEmpty()) {
            sections.add(new TrackedSection("Pets & Trinkets", ThePrisonsColors.ACCENT_BLUE, petTrinketEntries));
        }
        addFeatureSection(sections, "Cooldowns", ThePrisonsColors.ACCENT_AMBER, ThePrisonsFeatureManager.commandCooldownEntries());
        addFeatureSection(sections, "Satchels", ThePrisonsColors.ACCENT_LIME, ThePrisonsFeatureManager.satchelEntries());
        addFeatureSection(sections, "Session", ThePrisonsColors.ACCENT_CYAN, ThePrisonsFeatureManager.statsEntries());
        return sections;
    }

    private static void addFeatureSection(List<TrackedSection> sections, String title, int accent, List<FeatureHudEntry> featureEntries) {
        if (featureEntries.isEmpty()) {
            return;
        }
        List<HudEntry> entries = new ArrayList<>();
        for (FeatureHudEntry entry : featureEntries) {
            entries.add(new HudEntry(entry.name(), entry.value(), false, entry.color()));
        }
        sections.add(new TrackedSection(title, accent, entries));
    }

    private static @org.jspecify.annotations.Nullable TrackedSection sectionForWidget(MinecraftClient client, ThePrisonsConfig config, String widgetId) {
        return switch (widgetId) {
            case "PET_TRINKET" -> new TrackedSection("Pets & Trinkets", ThePrisonsColors.ACCENT_BLUE,
                    collectPetTrinketEntries(client, config));
            case "SESSION_XP" -> new TrackedSection("Session XP", ThePrisonsColors.ACCENT_CYAN, filterStatsEntries(true));
            case "ENERGY" -> new TrackedSection("Energy", ThePrisonsColors.ACCENT_LIME, filterStatsEntries(false));
            default -> null;
        };
    }

    private static float widgetScale(ThePrisonsConfig config, String widgetId) {
        return switch (widgetId) {
            case "PET_TRINKET" -> config.gui.petHudScale;
            case "SESSION_XP" -> config.gui.sessionXpHudScale;
            case "ENERGY" -> config.gui.energyHudScale;
            default -> config.gui.hudScale;
        };
    }

    private static List<HudEntry> collectPetTrinketEntries(MinecraftClient client, ThePrisonsConfig config) {
        List<HudEntry> entries = new ArrayList<>();
        if (config.hud.showTrackedPets) {
            entries.addAll(collectEntries("PET", ThePrisonsClient.CACHE.entries().values(), client, config));
        }
        if (config.hud.showTrinkets) {
            entries.addAll(collectEntries("TRINKET", ThePrisonsClient.CACHE.entries().values(), client, config));
        }
        return entries;
    }

    private static void drawTrackedWidget(DrawContext context, MinecraftClient client, ThePrisonsConfig config,
                                          String title, int accent, List<HudEntry> entries,
                                          int x, int y, float scale, boolean preview) {
        if (entries.isEmpty()) {
            return;
        }
        List<TrackedSection> sections = List.of(new TrackedSection(title, accent, entries));
        drawSectionsOverlay(context, client, config, x, y, scale, preview, sections);
    }

    private static List<HudEntry> filterStatsEntries(boolean xp) {
        List<HudEntry> entries = new ArrayList<>();
        for (FeatureHudEntry entry : ThePrisonsFeatureManager.statsEntries()) {
            boolean isXpEntry = entry.name().toLowerCase(Locale.ROOT).startsWith("xp");
            if (xp == isXpEntry) {
                entries.add(new HudEntry(entry.name(), entry.value(), false, entry.color()));
            }
        }
        return entries;
    }


    private static HudDimensions drawSectionsOverlay(DrawContext context, MinecraftClient client, ThePrisonsConfig config,
                                                     int x, int y, float scale, boolean preview, List<TrackedSection> sections) {
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

            boolean ready = entry.cooldownEndsAtMs <= nowMs;
            String status = "";
            int statusColor = ready ? 0xFF45F28A : 0xFFFF4B5C;
            if (ready && config.hud.showReadyStatus) {
                status = "Ready";
            } else if (!ready && config.hud.showCountdown) {
                status = formatCooldownRemaining(entry.cooldownEndsAtMs - nowMs);
            }

            entries.add(new HudEntry(stripPetSuffix(entry.displayName), status, ready, statusColor));
        }

        entries.sort(Comparator.comparing(value -> value.displayName.toLowerCase(Locale.ROOT)));
        return entries;
    }

    private static String formatCooldownRemaining(long remainingMs) {
        long remainingSeconds = Math.max(1L, (long) Math.ceil(Math.max(0L, remainingMs) / 1000.0D));
        long minutes = remainingSeconds / 60L;
        long seconds = remainingSeconds % 60L;
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
            context.drawTextWithShadow(client.textRenderer, Text.literal(entry.displayName), PADDING, cursorY, ThePrisonsColors.FG_PRIMARY);
            if (!entry.statusText.isEmpty()) {
                int statusX = width - PADDING - client.textRenderer.getWidth(entry.statusText);
                context.drawTextWithShadow(client.textRenderer, Text.literal(entry.statusText), statusX, cursorY, entry.statusColor);
            }
            cursorY += ENTRY_HEIGHT;
        }
    }

    private static int measureWidth(MinecraftClient client, List<TrackedSection> sections) {
        int width = 0;
        for (TrackedSection section : sections) {
            int sectionWidth = client.textRenderer.getWidth(section.title);
            for (HudEntry entry : section.entries) {
                int entryWidth = client.textRenderer.getWidth(entry.displayName);
                if (!entry.statusText.isEmpty()) {
                    entryWidth += 10 + client.textRenderer.getWidth(entry.statusText);
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
        return HEADER_HEIGHT + (section.entries.size() * ENTRY_HEIGHT);
    }

    private static void renderAnnouncements(DrawContext context, MinecraftClient client, ThePrisonsConfig config) {
        drawNotificationStack(context, client, config, false);
    }

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

        context.getMatrices().pushMatrix();
        for (Notification notification : active) {
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
        context.getMatrices().popMatrix();
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
        int y = Math.max(2, config.gui.announcementY);
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

        private HudEntry(String displayName, String statusText, boolean ready, int statusColor) {
            this.displayName = displayName;
            this.statusText = statusText;
            this.ready = ready;
            this.statusColor = statusColor;
        }
    }
}
