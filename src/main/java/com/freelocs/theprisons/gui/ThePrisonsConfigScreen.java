package com.freelocs.theprisons.gui;

import com.freelocs.theprisons.ThePrisonsClient;
import com.freelocs.theprisons.config.ThePrisonsConfig;
import com.freelocs.theprisons.ui.ThePrisonsColors;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class ThePrisonsConfigScreen extends Screen {
    private static final Identifier LOGO = Identifier.of(ThePrisonsClient.MOD_ID, "icon.png");
    private static final int HEADER_H = 68;
    private static final int SIDEBAR_W = 176;
    private static final int CONTENT_TOP = 122;
    private static final int CONTENT_BOTTOM = 56;
    private static final int ROW_H = 20;
    private static final int ROW_GAP = 8;

    private final Screen parent;
    private final int selectedCategory;
    private final int selectedSubCategory;
    private final List<ContentItem> contentItems = new ArrayList<>();
    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;
    private int contentScroll;
    private int maxContentScroll;

    public ThePrisonsConfigScreen(Screen parent) {
        this(parent, 0, 0);
    }

    public ThePrisonsConfigScreen(Screen parent, int selectedCategory) {
        this(parent, selectedCategory, 0);
    }

    public ThePrisonsConfigScreen(Screen parent, int selectedCategory, int selectedSubCategory) {
        super(Text.literal("ThePrisons"));
        this.parent = parent;
        this.selectedCategory = selectedCategory;
        this.selectedSubCategory = selectedSubCategory;
    }

    @Override
    protected void init() {
        panelW = Math.max(700, Math.min((int) (width * 0.74), 960));
        panelH = Math.max(420, Math.min((int) (height * 0.82), 640));
        panelX = (width - panelW) / 2;
        panelY = (height - panelH) / 2;
        contentItems.clear();
        contentScroll = 0;

        layoutStaticButtons();
        layoutContent();
        maxContentScroll = Math.max(0, contentHeight() - contentViewportHeight());
        applyScroll();
    }

    private void layoutStaticButtons() {
        ThePrisonsConfig config = ThePrisonsClient.CONFIG.get();
        int sidebarX = panelX + 16;
        int sidebarY = panelY + HEADER_H + 18;
        int contentX = panelX + SIDEBAR_W + 28;

        addDrawableChild(ButtonWidget.builder(Text.literal("General"), button -> client.setScreen(new ThePrisonsConfigScreen(parent, 0, 0)))
                .dimensions(sidebarX, sidebarY, 156, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Cosmic Prisons"), button -> client.setScreen(new ThePrisonsConfigScreen(parent, 1, 0)))
                .dimensions(sidebarX, sidebarY + 28, 156, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("GUI"), button -> client.setScreen(new ThePrisonsConfigScreen(parent, 2, 0)))
                .dimensions(sidebarX, sidebarY + 56, 156, 20).build());

        addDrawableChild(ButtonWidget.builder(Text.literal("HUD Layout"), button ->
                client.setScreen(new ThePrisonsHudLayoutScreen(this, config))).dimensions(contentX, panelY + panelH - 40, 128, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Done"), button -> close()).dimensions(panelX + panelW - 84, panelY + panelH - 40, 72, 20).build());
    }

    private void layoutContent() {
        ThePrisonsConfig config = ThePrisonsClient.CONFIG.get();
        int contentX = panelX + SIDEBAR_W + 28;
        int tabsY = panelY + 74;
        int contentY = panelY + CONTENT_TOP;
        int rowW = panelW - SIDEBAR_W - 56;

        switch (selectedCategory) {
            case 0 -> {
                addSubTabs(contentX, tabsY, rowW, new String[]{"Core", "Safety", "Updates"}, selectedSubCategory);
                if (selectedSubCategory == 0) {
                    addContentRow(contentX, contentY, rowW, toggleText("Ready announcements", config.general.showReadyAnnouncements), () -> {
                        config.general.showReadyAnnouncements = !config.general.showReadyAnnouncements;
                        saveAndReload(0, 0);
                    });
                    addContentRow(contentX, contentY, rowW, toggleText("Persist cooldown cache", config.general.persistCooldownCache), () -> {
                        config.general.persistCooldownCache = !config.general.persistCooldownCache;
                        saveAndReload(0, 0);
                    });
                    addContentRow(contentX, contentY, rowW, toggleText("Message sound alerts", config.qol.messageNotifications), () -> {
                        config.qol.messageNotifications = !config.qol.messageNotifications;
                        saveAndReload(0, 0);
                    });
                    addContentRow(contentX, contentY, rowW, toggleText("Message sound", config.qol.messageNotificationSound), () -> {
                        config.qol.messageNotificationSound = !config.qol.messageNotificationSound;
                        saveAndReload(0, 0);
                    });
                    addContentRow(contentX, contentY, rowW, "Message volume: " + formatFloat(config.qol.messageNotificationVolume), () -> {
                        config.qol.messageNotificationVolume = cycle(config.qol.messageNotificationVolume, 0.25F, 0.50F, 0.85F, 1.00F, 1.50F, 2.00F);
                        saveAndReload(0, 0);
                    });
                } else if (selectedSubCategory == 1) {
                    addContentRow(contentX, contentY, rowW, toggleText("Peaceful mining safety", config.qol.peacefulMiningSafety), () -> {
                        config.qol.peacefulMiningSafety = !config.qol.peacefulMiningSafety;
                        saveAndReload(0, 1);
                    });
                } else {
                    addContentRow(contentX, contentY, rowW, toggleText("Auto update check", config.general.autoUpdaterEnabled), () -> {
                        config.general.autoUpdaterEnabled = !config.general.autoUpdaterEnabled;
                        saveAndReload(0, 2);
                    });
                    addContentRow(contentX, contentY, rowW, "Check interval: " + config.general.updateCheckIntervalHours + "h", () -> {
                        config.general.updateCheckIntervalHours = cycle(config.general.updateCheckIntervalHours, 6, 12, 24, 48, 72);
                        saveAndReload(0, 2);
                    });
                }
            }
            case 1 -> {
                addSubTabs(contentX, tabsY, rowW, new String[]{"HUDs", "Items", "Events", "Style"}, selectedSubCategory);
                if (selectedSubCategory == 0) {
                    addContentRow(contentX, contentY, rowW, toggleText("Pets & Trinkets HUD", config.hud.petHudEnabled), () -> {
                        config.hud.petHudEnabled = !config.hud.petHudEnabled;
                        saveAndReload(1, 0);
                    });
                    addContentRow(contentX, contentY, rowW, toggleText("Ready status", config.hud.showReadyStatus), () -> {
                        config.hud.showReadyStatus = !config.hud.showReadyStatus;
                        saveAndReload(1, 0);
                    });
                    addContentRow(contentX, contentY, rowW, toggleText("Countdown", config.hud.showCountdown), () -> {
                        config.hud.showCountdown = !config.hud.showCountdown;
                        saveAndReload(1, 0);
                    });
                    addContentRow(contentX, contentY, rowW, toggleText("Command cooldown HUD", config.hud.showCommandCooldowns), () -> {
                        config.hud.showCommandCooldowns = !config.hud.showCommandCooldowns;
                        saveAndReload(1, 0);
                    });
                    addContentRow(contentX, contentY, rowW, toggleText("Satchel HUD", config.hud.showSatchelHud), () -> {
                        config.hud.showSatchelHud = !config.hud.showSatchelHud;
                        saveAndReload(1, 0);
                    });
                    addContentRow(contentX, contentY, rowW, toggleText("Stats HUD", config.hud.showStatsHud), () -> {
                        config.hud.showStatsHud = !config.hud.showStatsHud;
                        saveAndReload(1, 0);
                    });
                    addContentRow(contentX, contentY, rowW, toggleText("Pause stats tracking", config.hud.pauseStatsHud), () -> {
                        config.hud.pauseStatsHud = !config.hud.pauseStatsHud;
                        saveAndReload(1, 0);
                    });
                    addContentRow(contentX, contentY, rowW, toggleText("Armor HUD", config.hud.showArmorHud), () -> {
                        config.hud.showArmorHud = !config.hud.showArmorHud;
                        saveAndReload(1, 0);
                    });
                } else if (selectedSubCategory == 1) {
                    addContentRow(contentX, contentY, rowW, toggleText("Clue scroll step overlay", config.hud.showClueScrollSteps), () -> {
                        config.hud.showClueScrollSteps = !config.hud.showClueScrollSteps;
                        saveAndReload(1, 1);
                    });
                    addContentRow(contentX, contentY, rowW, toggleText("Item insight tooltips", config.hud.showItemInsightTooltips), () -> {
                        config.hud.showItemInsightTooltips = !config.hud.showItemInsightTooltips;
                        saveAndReload(1, 1);
                    });
                    addContentRow(contentX, contentY, rowW, toggleText("Satchel fill alerts", config.hud.showSatchelWarnings), () -> {
                        config.hud.showSatchelWarnings = !config.hud.showSatchelWarnings;
                        saveAndReload(1, 1);
                    });
                    addContentRow(contentX, contentY, rowW, "Satchel alert at: " + config.hud.satchelWarningPercent + "%", () -> {
                        config.hud.satchelWarningPercent = cycle(config.hud.satchelWarningPercent, 70, 80, 85, 90, 95, 100);
                        saveAndReload(1, 1);
                    });
                } else if (selectedSubCategory == 2) {
                    addContentRow(contentX, contentY, rowW, toggleText("Armor destroy alert", config.hud.showArmorWarnings), () -> {
                        config.hud.showArmorWarnings = !config.hud.showArmorWarnings;
                        saveAndReload(1, 2);
                    });
                } else {
                    addContentRow(contentX, contentY, rowW, "Pet accent: #" + hex(config.hud.petHudColor), () -> {
                        config.hud.petHudColor = cycle(config.hud.petHudColor, 0xFFA8E8FF, 0xFF7DD3FC, 0xFFFFC14D, 0xFF65F59B);
                        saveAndReload(1, 3);
                    });
                    addContentRow(contentX, contentY, rowW, "Trinket accent: #" + hex(config.hud.readyColor), () -> {
                        config.hud.readyColor = cycle(config.hud.readyColor, 0xFFF84EA8, 0xFFFFC14D, 0xFF22C55E, 0xFF38BDF8);
                        saveAndReload(1, 3);
                    });
                    addContentRow(contentX, contentY, rowW, "Alert title color: #" + hex(config.hud.alertTitleColor), () -> {
                        config.hud.alertTitleColor = cycle(config.hud.alertTitleColor, 0xFFFF3DBA, 0xFFF84EA8, 0xFFFF6AD5, 0xFFFF9EC6);
                        saveAndReload(1, 3);
                    });
                    addContentRow(contentX, contentY, rowW, "Alert body color: #" + hex(config.hud.alertBodyColor), () -> {
                        config.hud.alertBodyColor = cycle(config.hud.alertBodyColor, 0xFF4D8DFF, 0xFF35C9FF, 0xFF6BA8FF, 0xFF9CC6FF);
                        saveAndReload(1, 3);
                    });
                    addContentRow(contentX, contentY, rowW, "Armor HUD scale: " + formatFloat(config.hud.armorHudScale), () -> {
                        config.hud.armorHudScale = cycle(config.hud.armorHudScale, 0.85F, 1.0F, 1.10F, 1.20F, 1.35F);
                        saveAndReload(1, 3);
                    });
                    addContentRow(contentX, contentY, rowW, "Armor icon size: " + config.hud.armorHudIconSize, () -> {
                        config.hud.armorHudIconSize = cycle(config.hud.armorHudIconSize, 10, 12, 14, 16, 18, 20);
                        saveAndReload(1, 3);
                    });
                    addContentRow(contentX, contentY, rowW, "Armor spacing: " + config.hud.armorHudGap, () -> {
                        config.hud.armorHudGap = cycle(config.hud.armorHudGap, 0, 1, 2, 3, 4, 6, 8);
                        saveAndReload(1, 3);
                    });
                }
            }
            case 2 -> {
                addSubTabs(contentX, tabsY, rowW, new String[]{"Placement", "Sizing"}, selectedSubCategory);
                if (selectedSubCategory == 0) {
                    addContentRow(contentX, contentY, rowW, toggleText("Snap to grid", config.gui.snapToGrid), () -> {
                        config.gui.snapToGrid = !config.gui.snapToGrid;
                        saveAndReload(2, 0);
                    });
                    addContentRow(contentX, contentY, rowW, "Grid size: " + config.gui.gridSize, () -> {
                        config.gui.gridSize = cycle(config.gui.gridSize, 6, 12, 16, 24, 32);
                        saveAndReload(2, 0);
                    });
                    addContentRow(contentX, contentY, rowW, "Open HUD layout editor", () ->
                            client.setScreen(new ThePrisonsHudLayoutScreen(this, config)));
                } else {
                    addContentRow(contentX, contentY, rowW, "HUD scale: " + formatFloat(config.gui.hudScale), () -> {
                        config.gui.hudScale = cycle(config.gui.hudScale, 0.75F, 0.90F, 1.0F, 1.15F, 1.35F, 1.60F);
                        saveAndReload(2, 1);
                    });
                    addContentRow(contentX, contentY, rowW, "Reset HUD position", () -> {
                        config.gui.petHudX = 16;
                        config.gui.petHudY = 16;
                        config.gui.sessionXpHudX = 16;
                        config.gui.sessionXpHudY = 94;
                        config.gui.energyHudX = 16;
                        config.gui.energyHudY = 148;
                        config.gui.hudScale = 1.0F;
                        saveAndReload(2, 1);
                    });
                }
            }
            default -> {
            }
        }
    }

    private void addSubTabs(int x, int y, int width, String[] labels, int activeIndex) {
        int tabWidth = Math.max(76, Math.min(132, (width - 8 * (labels.length - 1)) / Math.max(1, labels.length)));
        for (int i = 0; i < labels.length; i++) {
            int tabX = x + i * (tabWidth + 8);
            int tabIndex = i;
            addDrawableChild(ButtonWidget.builder(Text.literal(labels[i]), button ->
                    client.setScreen(new ThePrisonsConfigScreen(parent, selectedCategory, tabIndex))).dimensions(tabX, y, tabWidth, 20).build());
        }
    }

    private void addContentRow(int x, int baseY, int width, String label, Runnable action) {
        int rowY = baseY + contentItems.size() * (ROW_H + ROW_GAP);
        ButtonWidget button = ButtonWidget.builder(Text.literal(label), b -> action.run()).dimensions(x, rowY, width, ROW_H).build();
        contentItems.add(new ContentItem(button, rowY));
        addDrawableChild(button);
    }

    private void saveAndReload(int category, int subCategory) {
        ThePrisonsClient.CONFIG.save();
        if (client != null) {
            client.setScreen(new ThePrisonsConfigScreen(parent, category, subCategory));
        }
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        ThePrisonsConfig config = ThePrisonsClient.CONFIG.get();
        context.fill(0, 0, width, height, ThePrisonsColors.BG_OVERLAY);
        context.fill(panelX, panelY, panelX + panelW, panelY + panelH, ThePrisonsColors.BG_PANEL);
        context.fill(panelX, panelY, panelX + panelW, panelY + 2, ThePrisonsColors.ACCENT_CYAN);
        context.fill(panelX, panelY + 2, panelX + panelW, panelY + 4, ThePrisonsColors.ACCENT_AMBER);
        context.fill(panelX, panelY + 4, panelX + panelW, panelY + 6, ThePrisonsColors.ACCENT_LIME);

        context.fill(panelX, panelY + HEADER_H, panelX + SIDEBAR_W, panelY + panelH, ThePrisonsColors.BG_SIDE);
        context.fill(panelX + SIDEBAR_W, panelY + HEADER_H, panelX + SIDEBAR_W + 1, panelY + panelH, ThePrisonsColors.BORDER);
        context.fill(panelX + SIDEBAR_W + 14, panelY + HEADER_H + 18, panelX + panelW - 14, panelY + panelH - 16, ThePrisonsColors.BG_SECTION);

        context.drawTexture(RenderPipelines.GUI_TEXTURED, LOGO, panelX + 16, panelY + 14, 0f, 0f, 40, 40, 500, 500);
        context.drawTextWithShadow(textRenderer, Text.literal("ThePrisons"), panelX + 68, panelY + 18, ThePrisonsColors.FG_PRIMARY);
        context.drawTextWithShadow(textRenderer, Text.literal("Client-side CosmicPrisons HUD and QoL"), panelX + 68, panelY + 32, ThePrisonsColors.FG_MUTED);
        context.drawTextWithShadow(textRenderer, Text.literal("TrustTheCat"), panelX + 68, panelY + 46, ThePrisonsColors.ACCENT_CYAN);

        drawSidebarLabel(context, "General", 0, panelX + 20, panelY + HEADER_H + 28);
        drawSidebarLabel(context, "Cosmic Prisons", 1, panelX + 20, panelY + HEADER_H + 56);
        drawSidebarLabel(context, "GUI", 2, panelX + 20, panelY + HEADER_H + 84);

        int contentX = panelX + SIDEBAR_W + 28;
        int contentY = panelY + CONTENT_TOP;
        String title = selectedCategory == 0 ? "General" : selectedCategory == 1 ? "Cosmic Prisons" : "GUI";
        String subtitle = selectedCategory == 0
                ? selectedSubCategory == 0 ? "Core notifications and persistence" :
                  selectedSubCategory == 1 ? "Local safety guards for mining and valuable items" :
                  "Modrinth update checks"
                : selectedCategory == 1
                ? selectedSubCategory == 0 ? "HUD modules and tracked timers" :
                  selectedSubCategory == 1 ? "Inventory overlays and item tooltip insights" :
                  selectedSubCategory == 2 ? "Alerts and armor warning display settings" :
                  "HUD colors and density"
                : selectedSubCategory == 0 ? "Screen positions and layout editor" : "HUD scale and reset actions";

        context.drawTextWithShadow(textRenderer, Text.literal(title), contentX, contentY - 14, ThePrisonsColors.FG_PRIMARY);
        context.drawTextWithShadow(textRenderer, Text.literal(subtitle), contentX, contentY, ThePrisonsColors.FG_MUTED);
        context.fill(contentX, contentY + 10, panelX + panelW - 24, contentY + 11, ThePrisonsColors.BORDER_HI);

        applyScroll();
        super.render(context, mouseX, mouseY, delta);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (maxContentScroll > 0 && mouseX >= panelX + SIDEBAR_W + 16 && mouseX <= panelX + panelW - 24
                && mouseY >= panelY + CONTENT_TOP - 8 && mouseY <= panelY + panelH - CONTENT_BOTTOM) {
            contentScroll += verticalAmount > 0 ? -18 : 18;
            clampScroll();
            applyScroll();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    private void applyScroll() {
        int viewportTop = panelY + CONTENT_TOP;
        int viewportBottom = panelY + panelH - CONTENT_BOTTOM;
        for (ContentItem item : contentItems) {
            int y = item.baseY - contentScroll;
            item.button.setY(y);
            boolean visible = y + ROW_H >= viewportTop && y <= viewportBottom;
            item.button.visible = visible;
            item.button.active = visible;
        }
    }

    private void clampScroll() {
        contentScroll = Math.max(0, Math.min(contentScroll, maxContentScroll));
    }

    private int contentViewportHeight() {
        return panelH - CONTENT_TOP - CONTENT_BOTTOM;
    }

    private int contentHeight() {
        if (contentItems.isEmpty()) {
            return 0;
        }
        int first = contentItems.get(0).baseY;
        int last = contentItems.get(contentItems.size() - 1).baseY;
        return (last - first) + ROW_H + ROW_GAP;
    }

    private void drawSidebarLabel(DrawContext context, String label, int category, int x, int y) {
        int color = selectedCategory == category ? ThePrisonsColors.FG_PRIMARY : ThePrisonsColors.FG_MUTED;
        int accent = selectedCategory == category
                ? (category == 0 ? ThePrisonsColors.ACCENT_CYAN : category == 1 ? ThePrisonsColors.ACCENT_AMBER : ThePrisonsColors.ACCENT_LIME)
                : ThePrisonsColors.BORDER;
        context.fill(x - 6, y - 2, x - 2, y + 12, accent);
        context.drawTextWithShadow(textRenderer, Text.literal(label), x, y, color);
    }

    private String toggleText(String label, boolean enabled) {
        return label + ": " + (enabled ? "On" : "Off");
    }

    private int cycle(int current, int... values) {
        for (int i = 0; i < values.length; i++) {
            if (values[i] == current) {
                return values[(i + 1) % values.length];
            }
        }
        return values[0];
    }

    private float cycle(float current, float... values) {
        for (int i = 0; i < values.length; i++) {
            if (Float.compare(values[i], current) == 0) {
                return values[(i + 1) % values.length];
            }
        }
        return values[0];
    }

    private double cycle(double current, double... values) {
        for (int i = 0; i < values.length; i++) {
            if (Double.compare(values[i], current) == 0) {
                return values[(i + 1) % values.length];
            }
        }
        return values[0];
    }

    private String hex(int color) {
        return String.format(Locale.ROOT, "%08X", color);
    }

    private String formatFloat(float value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    @Override
    public void close() {
        ThePrisonsClient.CONFIG.save();
        if (client != null) {
            client.setScreen(parent);
        }
    }

    private record ContentItem(ButtonWidget button, int baseY) {
    }
}
