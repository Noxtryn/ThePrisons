package io.theprisons.gui.config;

import io.theprisons.ThePrisonsClient;
import io.theprisons.core.ThePrisonsCore;
import io.theprisons.core.config.ConfigStore;
import io.theprisons.core.i18n.I18n;
import io.theprisons.core.module.Module;
import io.theprisons.core.module.ModuleManager;
import io.theprisons.core.setting.Setting;
import io.theprisons.core.setting.Settings;
import io.theprisons.modules.ModuleRegistry;
import io.theprisons.modules.general.ClickGuiModule;
import io.theprisons.ui.ThePrisonsColors;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.input.CharInput;
import net.minecraft.client.input.KeyInput;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.OrderedText;
import net.minecraft.text.StringVisitable;
import net.minecraft.text.Text;
import net.minecraft.util.math.MathHelper;
import org.jspecify.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Module GUI in the style of Nebula-like clients, bound directly to the core {@link ModuleManager}.
 *
 * <pre>
 * ┌────────────┬──────────────────────────────────────────────────────────┐
 * │ ThePrisons │ Mining                                                   │
 * │ [search ]  │ Ore macros and mining utilities    [All][Macros]         │
 * │ ⛏ Mining 1 │ ┌ module list ─────────┐ ┌ settings of selected module ┐ │
 * │ ☄ Meteor 0 │ │ Ore Macro     (●)━━○ │ │ Ore Macro          [on] [K] │ │
 * │ …          │ │ ● Mining · 312 ores  │ │ Targets …                   │ │
 * │            │ └──────────────────────┘ └─────────────────────────────┘ │
 * │ ● status   │ Saved                               [Save][Load][Reset]  │
 * └────────────┴──────────────────────────────────────────────────────────┘
 * </pre>
 *
 * Immediate-mode: every frame lays out the visible widgets and records their hit boxes; input is matched against
 * the boxes of the last frame. Nothing here touches files except through {@link ConfigStore}.
 */
public final class ConfigScreen extends Screen {
    /** The real configuration screen deliberately has a desktop-style layout, even on a scaled Minecraft GUI. */
    private static final int CARD_H = 46;
    private static final int CONTROL_W = 148;
    private static final int ROW_GAP = 8;

    private static ConfigCategory lastTab = ConfigCategory.OVERVIEW;
    private static @Nullable String lastModule;
    /** Setting to scroll to and flash once the GUI opens ({@link #focus}). */
    private static @Nullable String focusSetting;
    private static long focusAtMs;
    /** The next GUI opens on this module, scrolled to this setting, which flashes for a moment. */
    public static void focus(Module module, @Nullable String settingId) {
        lastTab = ConfigCategory.home(module);
        lastModule = module.id();
        focusSetting = settingId;
        focusAtMs = System.currentTimeMillis();
    }

    /** The next GUI opens on this category (its first module selected). */
    public static void focusCategory(ConfigCategory category) {
        lastTab = category;
        lastModule = null;
        focusSetting = null;
    }

    private interface ClickAction {
        void run(double mouseX, double mouseY, int button);
    }

    private record Hit(int x1, int y1, int x2, int y2, ClickAction action) {
        boolean contains(double x, double y) {
            return x >= x1 && x < x2 && y >= y1 && y < y2;
        }
    }

    private record Slider(Setting<?> setting, int x, int width) {
    }

    private final @Nullable Screen parent;
    private final ThePrisonsCore core;
    private final ModuleManager modules;
    private final List<Hit> hits = new ArrayList<>();
    private final List<Hit> popupHits = new ArrayList<>();

    /** Small windows (GUI scale 3 on 720p is 427x240): narrow sidebar, and the list and the settings take turns. */
    private boolean compact;
    private boolean showDetail;
    private int sidebarW = 164;
    private int headerH = 70;
    private int footerH = 34;
    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;

    private ConfigCategory tab;
    private String groupFilter = "";
    private @Nullable Module selected;
    private String search = "";
    private boolean searchFocused;
    private double listScroll;
    private double settingsScroll;
    private int listContentH;
    private int settingsContentH;

    private @Nullable Slider dragging;
    private Settings.@Nullable TextSetting editing;
    private String editBuffer = "";
    private Settings.@Nullable KeybindSetting listening;
    private @Nullable Setting<?> dropdown;
    private int dropdownX;
    private int dropdownY;
    private String toast = "";
    private long toastAtMs;
    private int focusY = Integer.MIN_VALUE;
    private boolean focusScrolled;
    private int clipX1;
    private int clipY1;
    private int clipX2;
    private int clipY2;

    public ConfigScreen(@Nullable Screen parent, ThePrisonsCore core) {
        super(Text.literal("ThePrisons"));
        this.parent = parent;
        this.core = core;
        this.modules = core.modules();
        this.tab = lastTab;
        this.selected = lastModule == null ? null : modules.get(lastModule);
        this.groupFilter = "";
        if (selected == null || !modulesIn(tab).contains(selected)) {
            List<Module> inTab = modulesIn(tab);
            selected = inTab.isEmpty() ? null : inTab.get(0);
        }
    }

    // ── Lifecycle ────────────────────────────────────────────────────────────

    @Override
    protected void init() {
        panelW = Math.min(width - 8, MathHelper.clamp((int) (width * 0.90), 620, 1120));
        panelH = Math.min(height - 8, MathHelper.clamp((int) (height * 0.90), 390, 680));
        panelX = (width - panelW) / 2;
        panelY = (height - panelH) / 2;
        compact = panelW < 520 || panelH < 300;
        sidebarW = compact ? 100 : 164;
        headerH = compact ? 56 : 70;
        footerH = compact ? 26 : 34;
        if (focusSetting != null) {
            showDetail = true;
        }
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    @Override
    public void close() {
        commitEdit();
        lastTab = tab;
        lastModule = selected == null ? null : selected.id();
        focusSetting = null;
        if (core.config().dirty()) {
            core.config().saveNow(false);
        }
        if (client != null) {
            client.setScreen(parent);
        }
    }

    private void switchLanguage() {
        ClickGuiModule gui = guiModule();
        I18n.Lang next = I18n.lang().next();
        if (gui != null) {
            gui.setLanguage(next);
        } else {
            I18n.setLang(next);
        }
        showToast(I18n.t("Language: English"));
    }

    private @Nullable ClickGuiModule guiModule() {
        return ModuleRegistry.clickGui(core);
    }

    // ── Rendering ────────────────────────────────────────────────────────────

    @Override
    public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta) {
        ClickGuiModule gui = guiModule();
        if (gui == null || gui.dimBackground()) {
            context.fill(0, 0, width, height, ThePrisonsColors.BG_OVERLAY);
        }
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        hits.clear();
        popupHits.clear();
        clipX1 = Integer.MIN_VALUE;
        clipY1 = Integer.MIN_VALUE;
        clipX2 = Integer.MAX_VALUE;
        clipY2 = Integer.MAX_VALUE;
        if (dragging != null) {
            applySlider(dragging, mouseX);
        }
        renderBackground(context, mouseX, mouseY, delta);
        // Layered gunmetal shell: this is the actual, interactive configuration surface -- not a preview renderer.
        context.fill(panelX - 3, panelY - 3, panelX + panelW + 3, panelY + panelH + 3, 0x52000000);
        context.fillGradient(panelX, panelY, panelX + panelW, panelY + panelH, 0xF7161B22, 0xF7090C10);
        gradientBar(context, panelX, panelY, panelW);
        context.drawStrokedRectangle(panelX, panelY, panelW, panelH, 0x9A94A4B8);
        context.drawStrokedRectangle(panelX + 2, panelY + 2, panelW - 4, panelH - 4, 0x245C6B7D);
        context.fillGradient(panelX + 3, panelY + 3, panelX + sidebarW, panelY + panelH - 3, 0xF0121820, 0xF00A0E14);
        context.fill(panelX + sidebarW, panelY + 3, panelX + sidebarW + 2, panelY + panelH - 3, 0xFF4C5665);

        renderSidebar(context, mouseX, mouseY);
        renderContent(context, mouseX, mouseY);
        renderFooter(context, mouseX, mouseY);
        renderDropdown(context, mouseX, mouseY);
    }

    private void gradientBar(DrawContext context, int x, int y, int w) {
        int[] colors = {ThePrisonsColors.HEADING_PINK, ThePrisonsColors.MOD_BLUE};
        int segments = colors.length - 1;
        for (int i = 0; i < w; i++) {
            float t = i / (float) Math.max(1, w - 1) * segments;
            int index = Math.min(segments - 1, (int) t);
            context.fill(x + i, y, x + i + 1, y + 2, ThePrisonsColors.lerp(colors[index], colors[index + 1], t - index));
        }
    }

    private void renderSidebarCompact(DrawContext context, int mouseX, int mouseY) {
        TextRenderer font = textRenderer;
        int x = panelX;
        int y = panelY + 8;
        context.drawText(font, "ThePrisons", x + 8, y, ThePrisonsColors.FG_PRIMARY, true);
        String lang = I18n.lang().code();
        int lw = font.getWidth(lang) + 8;
        int lx = x + sidebarW - 8 - lw;
        boolean langHover = inside(mouseX, mouseY, lx, y - 2, lx + lw, y + 11);
        context.fill(lx, y - 2, lx + lw, y + 11, langHover ? ThePrisonsColors.SIDEBAR_ACTIVE : ThePrisonsColors.BG_INPUT);
        context.drawStrokedRectangle(lx, y - 2, lw, 13, ThePrisonsColors.ACCENT_CYAN);
        context.drawText(font, lang, lx + 4, y, ThePrisonsColors.FG_PRIMARY, false);
        hit(lx, y - 2, lx + lw, y + 11, (mx, my, b) -> switchLanguage());

        int sx1 = x + 6;
        int sy1 = y + 14;
        int sx2 = x + sidebarW - 6;
        int sy2 = sy1 + 15;
        context.fill(sx1, sy1, sx2, sy2, ThePrisonsColors.BG_INPUT);
        context.drawStrokedRectangle(sx1, sy1, sx2 - sx1, sy2 - sy1, searchFocused ? ThePrisonsColors.ACCENT_CYAN : ThePrisonsColors.BORDER);
        String shown = search.isEmpty() && !searchFocused ? I18n.t("Search…") : search + (searchFocused && blink() ? "_" : "");
        context.drawText(font, trim(shown, sx2 - sx1 - 10), sx1 + 4, sy1 + 4, search.isEmpty() && !searchFocused ? ThePrisonsColors.FG_DISABLED : ThePrisonsColors.FG_PRIMARY, false);
        hit(sx1, sy1, sx2, sy2, (mx, my, b) -> {
            stopEditing();
            searchFocused = true;
        });

        int cy = sy2 + 6;
        int tabH = Math.max(14, Math.min(18, (panelY + panelH - 6 - cy) / ConfigCategory.values().length));
        for (ConfigCategory entry : ConfigCategory.values()) {
            boolean active = search.isEmpty() && entry == tab;
            boolean hover = inside(mouseX, mouseY, x + 4, cy, x + sidebarW - 4, cy + tabH);
            if (active) {
                context.fill(x + 4, cy, x + sidebarW - 4, cy + tabH, 0x38C084FC);
                context.fill(x + 4, cy, x + 6, cy + tabH, ThePrisonsColors.ACCENT_CYAN);
            } else if (hover) {
                context.fill(x + 4, cy, x + sidebarW - 4, cy + tabH, ThePrisonsColors.SIDEBAR_HOVER);
            }
            int textY = cy + (tabH - 8) / 2;
            context.drawText(font, entry.icon(), x + 9, textY, active ? ThePrisonsColors.ACCENT_CYAN : ThePrisonsColors.FG_MUTED, false);
            context.drawText(font, trim(I18n.t(entry.label()), sidebarW - 30), x + 22, textY,
                    active ? ThePrisonsColors.FG_PRIMARY : ThePrisonsColors.FG_SECONDARY, false);
            ConfigCategory target = entry;
            hit(x + 4, cy, x + sidebarW - 4, cy + tabH, (mx, my, b) -> selectTab(target));
            cy += tabH;
        }
    }

    private void renderSidebar(DrawContext context, int mouseX, int mouseY) {
        if (compact) {
            renderSidebarCompact(context, mouseX, mouseY);
            return;
        }
        TextRenderer font = textRenderer;
        int x = panelX;
        int y = panelY + 14;
        context.fill(x + 12, y - 2, x + 38, y + 24, 0xFF202B38);
        context.drawStrokedRectangle(x + 12, y - 2, 26, 26, ThePrisonsColors.ACCENT_CYAN);
        context.drawCenteredTextWithShadow(font, "T", x + 25, y + 6, ThePrisonsColors.ACCENT_CYAN);
        context.drawText(font, Text.literal("THEPRISONS").styled(style -> style.withBold(true)), x + 46, y, ThePrisonsColors.FG_PRIMARY, true);
        context.drawText(font, "CONFIG", x + 46, y + 12, ThePrisonsColors.ACCENT_CYAN, false);
        long enabled = modules.all().stream().filter(Module::enabled).count();
        context.drawText(font, I18n.f("%d / %d SYSTEMS ONLINE", enabled, modules.all().size()), x + 12, y + 35, ThePrisonsColors.FG_MUTED, false);
        // Language button (live): EN ⇄ DE
        String lang = I18n.lang().code();
        int lw = font.getWidth(lang) + 10;
        int lx = x + sidebarW - 12 - lw;
        boolean langHover = inside(mouseX, mouseY, lx, y + 27, lx + lw, y + 41);
        context.fill(lx, y + 27, lx + lw, y + 41, langHover ? ThePrisonsColors.SIDEBAR_ACTIVE : ThePrisonsColors.BG_INPUT);
        context.drawStrokedRectangle(lx, y + 27, lw, 14, ThePrisonsColors.ACCENT_CYAN);
        context.drawText(font, lang, lx + 5, y + 30, ThePrisonsColors.FG_PRIMARY, false);
        hit(lx, y + 27, lx + lw, y + 41, (mx, my, b) -> switchLanguage());

        // Search box
        int sx1 = x + 8;
        int sy1 = y + 52;
        int sx2 = x + sidebarW - 12;
        int sy2 = sy1 + 22;
        context.fill(sx1, sy1, sx2, sy2, ThePrisonsColors.BG_INPUT);
        context.drawStrokedRectangle(sx1, sy1, sx2 - sx1, sy2 - sy1, searchFocused ? ThePrisonsColors.ACCENT_CYAN : ThePrisonsColors.BORDER);
        String shown = search.isEmpty() && !searchFocused ? I18n.t("Search… (Ctrl+F)") : search + (searchFocused && blink() ? "_" : "");
        context.drawText(font, "⌕", sx1 + 6, sy1 + 6, ThePrisonsColors.ACCENT_CYAN, false);
        context.drawText(font, trim(shown, sx2 - sx1 - 20), sx1 + 18, sy1 + 7, search.isEmpty() && !searchFocused ? ThePrisonsColors.FG_DISABLED : ThePrisonsColors.FG_PRIMARY, false);
        hit(sx1, sy1, sx2, sy2, (mx, my, b) -> {
            stopEditing();
            searchFocused = true;
        });

        // Tabs (by topic)
        int cy = sy2 + 12;
        // GUI scale 3 on a 16:9 client can leave only ~280 logical pixels of height.  Keep every real category
        // clickable in that case instead of allowing the status area to cover the final entries.
        boolean compactSidebar = panelH < 420;
        int tabH = compactSidebar ? 17 : 22;
        int tabGap = compactSidebar ? 1 : 3;
        for (ConfigCategory entry : ConfigCategory.values()) {
            boolean active = search.isEmpty() && entry == tab;
            boolean hover = inside(mouseX, mouseY, x + 8, cy, x + sidebarW - 8, cy + tabH);
            if (active) {
                context.fill(x + 8, cy, x + sidebarW - 8, cy + tabH, 0x38C084FC);
                context.fill(x + 8, cy, x + 11, cy + tabH, ThePrisonsColors.ACCENT_CYAN);
                context.drawStrokedRectangle(x + 8, cy, sidebarW - 16, tabH, 0x40C084FC);
            } else if (hover) {
                context.fill(x + 8, cy, x + sidebarW - 8, cy + tabH, ThePrisonsColors.SIDEBAR_HOVER);
            }
            List<Module> inCategory = modulesIn(entry);
            int on = 0;
            for (Module module : inCategory) {
                on += module.enabled() ? 1 : 0;
            }
            int textY = cy + (compactSidebar ? 4 : 7);
            context.drawText(font, entry.icon(), x + 18, textY, active ? ThePrisonsColors.ACCENT_CYAN : ThePrisonsColors.FG_MUTED, false);
            String count = inCategory.isEmpty() ? "–" : on + "/" + inCategory.size();
            context.drawText(font, trim(I18n.t(entry.label()), sidebarW - 58 - font.getWidth(count)), x + 36, textY,
                    active ? ThePrisonsColors.FG_PRIMARY : ThePrisonsColors.FG_SECONDARY, false);
            context.drawText(font, count, x + sidebarW - 16 - font.getWidth(count), textY,
                    on > 0 ? ThePrisonsColors.ACCENT_LIME : ThePrisonsColors.FG_DISABLED, false);
            ConfigCategory target = entry;
            hit(x + 8, cy, x + sidebarW - 8, cy + tabH, (mx, my, b) -> selectTab(target));
            cy += tabH + tabGap;
        }

        // Automation status
        if (!compactSidebar) {
            int by = panelY + panelH - 43;
            String owner = core.control().ownerName();
            int dot = owner != null ? ThePrisonsColors.ACCENT_LIME : ThePrisonsColors.FG_DISABLED;
            context.fill(x + 10, by + 3, x + 14, by + 7, dot);
            context.drawText(font, trim(owner != null ? I18n.f("%s running", I18n.t(owner)) : I18n.t("No macro running"), sidebarW - 28), x + 18, by, ThePrisonsColors.FG_SECONDARY, false);
            var tick = core.profiler().section("core:tick");
            context.drawText(font, String.format(Locale.ROOT, "tick %.2f ms", tick.avgMs()), x + 18, by + 10, ThePrisonsColors.FG_MUTED, false);
        }
    }

    private void renderContent(DrawContext context, int mouseX, int mouseY) {
        TextRenderer font = textRenderer;
        int cx = panelX + sidebarW + 2;
        int cw = panelW - sidebarW - 2;
        boolean searching = !search.isBlank();
        String title = searching ? I18n.t("Search: ") + search : I18n.t(tab.label()).toUpperCase(Locale.ROOT);
        String subtitle = searching ? I18n.t("Matches in all tabs") : I18n.t(tab.description());
        context.fillGradient(cx + 1, panelY + 3, panelX + panelW - 3, panelY + headerH, 0xE017202A, 0xA810151C);
        int titleY = compact ? panelY + 9 : panelY + 18;
        context.fill(cx + (compact ? 6 : 13), titleY, cx + (compact ? 9 : 17), titleY + (compact ? 10 : 30), ThePrisonsColors.ACCENT_CYAN);
        context.drawText(font, Text.literal(title).styled(style -> style.withBold(true)), cx + (compact ? 14 : 27), titleY, ThePrisonsColors.FG_PRIMARY, true);
        if (!compact) {
            context.drawText(font, subtitle, cx + 27, panelY + 32, ThePrisonsColors.FG_MUTED, false);
        }

        List<Module> list = visibleModules();
        // Sub-category chips
        int chipX = cx + 12;
        int chipY = compact ? panelY + headerH - 20 : panelY + 51;
        if (compact && showDetail) {
            chip(context, mouseX, mouseY, cx + 8, chipY, "‹ " + I18n.t("Back"), false, () -> showDetail = false);
        } else if (!searching && !(compact && tab == ConfigCategory.OVERVIEW)) {
            List<String> groups = groupsOf(tab);
            if (groups.size() > 1) {
                chipX = chip(context, mouseX, mouseY, chipX, chipY, I18n.t("All"), groupFilter.isEmpty(), () -> groupFilter = "");
                for (String group : groups) {
                    if (chipX + textRenderer.getWidth(I18n.t(group)) + 14 > panelX + panelW - 8) {
                        break; // no room: the remaining groups are still listed under their headings
                    }
                    chipX = chip(context, mouseX, mouseY, chipX, chipY, I18n.t(group), group.equals(groupFilter), () -> {
                        groupFilter = group;
                        listScroll = 0;
                    });
                }
            }
        }

        int top = panelY + headerH + 8;
        int bottom = panelY + panelH - footerH;
        int listX = cx + 8;
        int listW = Math.max(132, Math.min(220, (int) (cw * 0.42)));
        int setX = listX + listW + 12;
        if (compact) {
            listW = cw - 16;
            setX = listX;
        }
        int setW = panelX + panelW - 8 - setX;
        boolean drawList = !compact || !showDetail;
        boolean drawDetail = !compact || showDetail;

        if (drawList) {
        // Module list
        clip(context, listX, top, listX + listW, bottom);
        int y = top - (int) listScroll;
        String lastGroup = null;
        if (list.isEmpty()) {
            List<OrderedText> lines = font.wrapLines(StringVisitable.plain(I18n.t(searching
                    ? "No module matches the search."
                    : "No modules in this tab yet.")), listW - 8);
            for (OrderedText line : lines) {
                context.drawText(font, line, listX + 4, y + 4, ThePrisonsColors.FG_MUTED, false);
                y += 10;
            }
        }
        for (Module module : list) {
            String header = searching ? ConfigCategory.home(module).label() : module.group();
            if (!header.equals(lastGroup) && (searching || groupFilter.isEmpty())) {
                context.drawText(font, I18n.t(header).toUpperCase(Locale.ROOT), listX + 2, y + 3, ThePrisonsColors.HEADING_PINK, false);
                y += 13;
                lastGroup = header;
            }
            renderCard(context, mouseX, mouseY, module, listX, y, listW);
            y += CARD_H + 3;
        }
        listContentH = y + (int) listScroll - top;
        unclip(context);
        scrollbar(context, listX + listW - 2, top, bottom, listScroll, listContentH);
        }

        if (drawDetail) {
        // Settings panel
        context.fillGradient(setX, top, setX + setW, bottom, 0xBB111821, 0xA8070B10);
        context.drawStrokedRectangle(setX, top, setW, bottom - top, 0x56616D7C);
        clip(context, setX + 1, top + 1, setX + setW - 1, bottom - 1);
        Module module = selected;
        if (module == null) {
            context.drawText(font, I18n.t("Select a module."), setX + 10, top + 10, ThePrisonsColors.FG_MUTED, false);
            settingsContentH = 0;
        } else {
            focusY = Integer.MIN_VALUE;
            settingsContentH = renderSettings(context, mouseX, mouseY, module, setX + 15, top + 13 - (int) settingsScroll, setW - 30) - (top + 13 - (int) settingsScroll);
            if (focusSetting != null && focusY != Integer.MIN_VALUE && !focusScrolled) {
                // Opened from a chat link: scroll so the setting sits near the top of the panel.
                focusScrolled = true;
                int view = bottom - top;
                settingsScroll = MathHelper.clamp(settingsScroll + focusY - (top + 30), 0.0D, Math.max(0, settingsContentH + 16 - view));
            }
        }
        unclip(context);
        scrollbar(context, setX + setW - 3, top, bottom, settingsScroll, settingsContentH + 16);
        }
    }

    private void renderCard(DrawContext context, int mouseX, int mouseY, Module module, int x, int y, int w) {
        TextRenderer font = textRenderer;
        boolean isSelected = module == selected;
        boolean hover = inside(mouseX, mouseY, x, y, x + w, y + CARD_H) && insideClip(mouseX, mouseY);
        context.fillGradient(x, y, x + w, y + CARD_H,
                hover || isSelected ? 0xE0222B37 : 0xD0131920, hover || isSelected ? 0xE011161D : 0xD00C1016);
        context.drawStrokedRectangle(x, y, w, CARD_H, isSelected ? ThePrisonsColors.ACCENT_CYAN : 0x52616D7C);
        context.fill(x, y, x + 3, y + CARD_H, module.enabled() ? ThePrisonsColors.ACCENT_LIME : ThePrisonsColors.FG_DISABLED);
        Module.Status status = module.status();
        context.drawText(font, trim(I18n.t(module.name()), w - 48), x + 12, y + 9, ThePrisonsColors.FG_PRIMARY, false);
        int missing = module.missing().size();
        if (missing > 0 && !module.enabled()) {
            context.fill(x + 12, y + 29, x + 17, y + 34, ThePrisonsColors.ACCENT_RED);
            context.drawText(font, trim(I18n.f("SETUP NEEDED: %d", missing), w - 30), x + 22, y + 27, ThePrisonsColors.ACCENT_RED, false);
        } else {
            int dot = statusColor(status.level());
            context.fill(x + 12, y + 29, x + 17, y + 34, dot);
            context.drawText(font, trim(status.text(), w - 30), x + 22, y + 27, ThePrisonsColors.FG_MUTED, false);
        }
        hit(x, y, x + w, y + CARD_H, (mx, my, b) -> {
            select(module);
            showDetail = compact;
        });
        if (module.toggleable()) {
            int sx = x + w - 30;
            int sy = y + 12;
            toggle(context, sx, sy, module.enabled());
            hit(sx - 4, sy - 3, sx + 26, sy + 12, (mx, my, b) -> {
                modules.toggle(module);
                select(module);
            });
        }
    }

    private int renderSettings(DrawContext context, int mouseX, int mouseY, Module module, int x, int y, int w) {
        TextRenderer font = textRenderer;
        context.drawText(font, Text.literal(I18n.t(module.name())).styled(style -> style.withBold(true)), x, y, ThePrisonsColors.HEADING_PINK, true);
        // Overview is a real working view: it intentionally exposes the selected module's complete settings,
        // rather than sending the player to a second, parallel settings renderer.
        boolean home = !search.isBlank() || tab == ConfigCategory.OVERVIEW || ConfigCategory.home(module) == tab;
        context.drawText(font, I18n.t(tab.label()) + " › " + I18n.t(module.group()), x, y + 11, ThePrisonsColors.FG_MUTED, false);
        if (module.toggleable()) {
            toggle(context, x + w - 22, y + 1, module.enabled());
            hit(x + w - 26, y - 2, x + w + 2, y + 13, (mx, my, b) -> modules.toggle(module));
        }
        y += 24;
        if (!home) {
            // Another tab's part of this module: only the settings of this tab (name, status and keybind are in its home tab).
            context.drawText(font, trim(I18n.f("Main settings: %s", I18n.t(ConfigCategory.home(module).label())), w), x, y, ThePrisonsColors.FG_MUTED, false);
            y += 12;
            return renderGroups(context, mouseX, mouseY, module, x, y, w);
        }
        for (OrderedText line : font.wrapLines(StringVisitable.plain(I18n.t(module.description())), w)) {
            context.drawText(font, line, x, y, ThePrisonsColors.FG_SECONDARY, false);
            y += 10;
        }
        Module.Status status = module.status();
        context.fill(x, y + 5, x + 4, y + 9, statusColor(status.level()));
        for (OrderedText line : font.wrapLines(StringVisitable.plain(status.text()), w - 8)) {
            context.drawText(font, line, x + 8, y + 3, ThePrisonsColors.FG_MUTED, false);
            y += 10;
        }
        y += 8;
        List<Setting<?>> missingSettings = module.missing();
        if (!missingSettings.isEmpty()) {
            // What still has to be set before the start, in red.
            context.fill(x, y, x + w, y + 12 + 10 * missingSettings.size(), 0x40FF3040);
            context.drawText(font, Text.literal(I18n.t("Before you start:")).styled(style -> style.withBold(true)), x + 4, y + 2, ThePrisonsColors.ACCENT_RED, false);
            int my2 = y + 12;
            for (Setting<?> setting : missingSettings) {
                context.drawText(font, trim("• " + I18n.t(setting.name()), w - 8), x + 4, my2, ThePrisonsColors.FG_PRIMARY, false);
                my2 += 10;
            }
            y = my2 + 6;
        }

        // Keybind row (every module has one)
        y = renderRow(context, mouseX, mouseY, module.keybind(), x, y, w);
        return renderGroups(context, mouseX, mouseY, module, x, y, w);
    }

    /** The module's settings of the current tab (all while searching), grouped, in declaration order. */
    private int renderGroups(DrawContext context, int mouseX, int mouseY, Module module, int x, int y, int w) {
        TextRenderer font = textRenderer;
        Map<String, List<Setting<?>>> grouped = new LinkedHashMap<>();
        for (Setting<?> setting : module.settings()) {
            if (setting == module.keybind() || !setting.visible()
                    || search.isBlank() && !ConfigCategory.shows(module, setting, tab)) {
                continue;
            }
            grouped.computeIfAbsent(setting.group(), key -> new ArrayList<>()).add(setting);
        }
        int step = 0;
        for (Map.Entry<String, List<Setting<?>>> group : grouped.entrySet()) {
            y += 6;
            if (!group.getKey().isEmpty()) {
                // Step by step: numbered sections in the order they are set up.
                step++;
                String number = String.valueOf(step);
                int nw = font.getWidth(number) + 6;
                context.fill(x, y - 1, x + nw, y + 9, ThePrisonsColors.HEADING_PINK);
                context.drawText(font, number, x + 3, y, 0xFF000000, false);
                context.drawText(font, Text.literal(I18n.t(group.getKey()).toUpperCase(Locale.ROOT)).styled(style -> style.withBold(true)),
                        x + nw + 5, y, ThePrisonsColors.HEADING_PINK, false);
                context.fill(x, y + 11, x + w, y + 12, ThePrisonsColors.BORDER);
                y += 16;
            }
            for (Setting<?> setting : group.getValue()) {
                y = renderRow(context, mouseX, mouseY, setting, x, y, w);
            }
        }
        return y;
    }

    /** One setting: label (+ description) on the left, control on the right or below. Returns the next y. */
    private int renderRow(DrawContext context, int mouseX, int mouseY, Setting<?> setting, int x, int y, int w) {
        TextRenderer font = textRenderer;
        ClickGuiModule gui = guiModule();
        boolean descriptions = gui == null || gui.showDescriptions();
        int labelW = w - CONTROL_W - 8;
        int labelColor = setting.isDefault() ? ThePrisonsColors.FG_SECONDARY : ThePrisonsColors.FG_PRIMARY;
        boolean wide = setting instanceof Settings.MultiChoiceSetting || setting instanceof Settings.TextSetting;
        String problem = setting.problem();
        if (problem != null) {
            labelColor = ThePrisonsColors.ACCENT_RED;
        }
        if (setting.id().equals(focusSetting) && selected != null && selected.settings().contains(setting)) {
            focusY = y;
            long age = System.currentTimeMillis() - focusAtMs;
            if (age < 3000L && (age / 300L) % 2L == 0L) {
                context.fill(x - 6, y - 2, x + w + 4, y + 16, 0x40FF6EC7);
                context.drawStrokedRectangle(x - 6, y - 2, w + 10, 18, ThePrisonsColors.HEADING_PINK);
            }
        }
        context.drawText(font, trim(I18n.t(setting.name()), wide ? w : labelW), x, y + 3, labelColor, false);
        if (problem != null) {
            context.fill(x - 5, y + 2, x - 3, y + 12, ThePrisonsColors.ACCENT_RED);
        } else if (!setting.isDefault() && setting.persistent() && !(setting instanceof Settings.ActionSetting)) {
            // Small "changed" marker; right-click on the control resets it.
            context.fill(x - 5, y + 4, x - 3, y + 10, ThePrisonsColors.ACCENT_VIOLET);
        }
        int cx = x + w - CONTROL_W;
        int rowTop = y;
        int controlBottom;
        if (setting instanceof Settings.BoolSetting bool) {
            toggle(context, x + w - 22, y + 2, bool.on());
            hit(x + w - 26, y, x + w + 2, y + 14, (mx, my, b) -> {
                if (b == 1) {
                    bool.reset();
                } else {
                    bool.toggle();
                }
            });
            controlBottom = y + 14;
        } else if (setting instanceof Settings.IntSetting || setting instanceof Settings.DoubleSetting) {
            double min = setting instanceof Settings.IntSetting i ? i.min() : ((Settings.DoubleSetting) setting).min();
            double max = setting instanceof Settings.IntSetting i ? i.max() : ((Settings.DoubleSetting) setting).max();
            double value = ((Number) setting.get()).doubleValue();
            float t = (float) ((value - min) / Math.max(1.0E-9D, max - min));
            String text = setting.display();
            context.drawText(font, text, x + w - font.getWidth(text), y + 1, ThePrisonsColors.ACCENT_CYAN, false);
            int ty = y + 12;
            context.fill(cx, ty, cx + CONTROL_W, ty + 3, ThePrisonsColors.TOGGLE_OFF);
            int fill = cx + Math.round(CONTROL_W * MathHelper.clamp(t, 0.0F, 1.0F));
            context.fillGradient(cx, ty, Math.max(cx + 1, fill), ty + 3, ThePrisonsColors.ACCENT_VIOLET, ThePrisonsColors.ACCENT_CYAN);
            context.fill(fill - 2, ty - 2, fill + 2, ty + 5, ThePrisonsColors.TOGGLE_KNOB);
            hit(cx - 3, ty - 4, cx + CONTROL_W + 3, ty + 7, (mx, my, b) -> {
                if (b == 1) {
                    setting.reset();
                    return;
                }
                dragging = new Slider(setting, cx, CONTROL_W);
                applySlider(dragging, mx);
            });
            controlBottom = ty + 6;
        } else if (setting instanceof Settings.EnumSetting<?> choice) {
            box(context, cx, y, CONTROL_W, 14, dropdown == choice);
            context.drawText(font, trim(choice.display(), CONTROL_W - 16), cx + 4, y + 3, ThePrisonsColors.FG_PRIMARY, false);
            context.drawText(font, "▾", cx + CONTROL_W - 9, y + 3, ThePrisonsColors.FG_MUTED, false);
            hit(cx, y, cx + CONTROL_W, y + 14, (mx, my, b) -> {
                if (b == 1) {
                    choice.reset();
                    return;
                }
                dropdown = dropdown == choice ? null : choice;
                dropdownX = cx;
                dropdownY = y + 15;
            });
            controlBottom = y + 14;
        } else if (setting instanceof Settings.ChoiceSetting choice) {
            box(context, cx, y, CONTROL_W, 14, dropdown == choice);
            context.drawText(font, trim(choice.display(), CONTROL_W - 16), cx + 4, y + 3, ThePrisonsColors.FG_PRIMARY, false);
            context.drawText(font, "▾", cx + CONTROL_W - 9, y + 3, ThePrisonsColors.FG_MUTED, false);
            hit(cx, y, cx + CONTROL_W, y + 14, (mx, my, b) -> {
                if (b == 1) {
                    choice.reset();
                    return;
                }
                dropdown = dropdown == choice ? null : choice;
                dropdownX = cx;
                dropdownY = y + 15;
            });
            controlBottom = y + 14;
        } else if (setting instanceof Settings.KeybindSetting bind) {
            boolean active = listening == bind;
            box(context, cx, y, CONTROL_W, 14, active);
            String text = active ? I18n.t("Press a key…") : keyName(bind.key());
            context.drawText(font, trim(text, CONTROL_W - 8), cx + 4, y + 3, active ? ThePrisonsColors.ACCENT_AMBER : ThePrisonsColors.FG_PRIMARY, false);
            hit(cx, y, cx + CONTROL_W, y + 14, (mx, my, b) -> {
                stopEditing();
                if (b == 1) {
                    bind.set(Settings.KeybindSetting.NONE);
                    listening = null;
                } else {
                    listening = bind;
                }
            });
            controlBottom = y + 14;
        } else if (setting instanceof Settings.ColorSetting color) {
            int size = 8;
            int per = Math.max(1, CONTROL_W / (size + 2));
            int px = cx;
            int py = y;
            int index = 0;
            for (int swatch : Settings.ColorSetting.PALETTE) {
                int sx = px + (index % per) * (size + 2);
                int sy = py + (index / per) * (size + 2);
                context.fill(sx, sy, sx + size, sy + size, swatch);
                if ((swatch & 0xFFFFFF) == (color.get() & 0xFFFFFF)) {
                    context.drawStrokedRectangle(sx - 1, sy - 1, size + 2, size + 2, 0xFFFFFFFF);
                }
                hit(sx, sy, sx + size, sy + size, (mx, my, b) -> color.set(swatch));
                index++;
            }
            int rows = (Settings.ColorSetting.PALETTE.length + per - 1) / per;
            controlBottom = py + rows * (size + 2);
            context.drawText(font, color.display(), x, y + 13, color.get(), false);
            controlBottom = Math.max(controlBottom, y + 22);
        } else if (setting instanceof Settings.ActionSetting button) {
            boolean hover = inside(mouseX, mouseY, cx, y, cx + CONTROL_W, y + 14);
            context.fill(cx, y, cx + CONTROL_W, y + 14, hover ? ThePrisonsColors.ACCENT_VIOLET : ThePrisonsColors.BG_CARD_HOVER);
            context.drawStrokedRectangle(cx, y, CONTROL_W, 14, ThePrisonsColors.BORDER_HI);
            context.drawCenteredTextWithShadow(font, button.label(), cx + CONTROL_W / 2, y + 3, ThePrisonsColors.FG_PRIMARY);
            hit(cx, y, cx + CONTROL_W, y + 14, (mx, my, b) -> {
                button.run();
                showToast(I18n.t(button.name()) + ": " + I18n.t("done"));
            });
            controlBottom = y + 14;
        } else if (setting instanceof Settings.TextSetting text) {
            int ty = y + 13;
            boolean active = editing == text;
            box(context, x, ty, w, 14, active);
            String value = active ? editBuffer + (blink() ? "_" : "") : text.get();
            if (!active && value.isEmpty()) {
                context.drawText(font, I18n.t("click to edit"), x + 4, ty + 3, ThePrisonsColors.FG_DISABLED, false);
            } else {
                context.drawText(font, trimLeft(value, w - 8), x + 4, ty + 3, ThePrisonsColors.FG_PRIMARY, false);
            }
            hit(x, ty, x + w, ty + 14, (mx, my, b) -> {
                if (b == 1) {
                    text.reset();
                    return;
                }
                commitEdit();
                searchFocused = false;
                editing = text;
                editBuffer = text.get();
            });
            controlBottom = ty + 14;
        } else if (setting instanceof Settings.MultiChoiceSetting multi) {
            int ty = y + 13;
            int chipX = x;
            int chipY = ty;
            for (Settings.Option option : multi.options()) {
                boolean on = multi.contains(option.id());
                int chipW = font.getWidth(option.label()) + 14;
                if (chipX + chipW > x + w) {
                    chipX = x;
                    chipY += 14;
                }
                boolean hover = inside(mouseX, mouseY, chipX, chipY, chipX + chipW, chipY + 12);
                context.fill(chipX, chipY, chipX + chipW, chipY + 12, on ? ThePrisonsColors.SIDEBAR_ACTIVE : hover ? ThePrisonsColors.SIDEBAR_HOVER : ThePrisonsColors.BG_INPUT);
                context.drawStrokedRectangle(chipX, chipY, chipW, 12, on ? option.color() : ThePrisonsColors.BORDER);
                context.fill(chipX + 3, chipY + 4, chipX + 7, chipY + 8, on ? option.color() : ThePrisonsColors.FG_DISABLED);
                context.drawText(font, option.label(), chipX + 10, chipY + 2, on ? ThePrisonsColors.FG_PRIMARY : ThePrisonsColors.FG_MUTED, false);
                String id = option.id();
                hit(chipX, chipY, chipX + chipW, chipY + 12, (mx, my, b) -> multi.toggle(id));
                chipX += chipW + 3;
            }
            String summary = multi.display();
            context.drawText(font, summary, x + w - font.getWidth(summary), y + 3, ThePrisonsColors.ACCENT_CYAN, false);
            controlBottom = chipY + 12;
        } else {
            context.drawText(font, setting.display(), cx, y + 3, ThePrisonsColors.FG_PRIMARY, false);
            controlBottom = y + 12;
        }
        int bottom = Math.max(controlBottom, rowTop + 14);
        if (problem != null) {
            // What is missing replaces the description, in red.
            List<OrderedText> lines = font.wrapLines(StringVisitable.plain("! " + I18n.t(problem)), wide ? w : labelW);
            int dy = wide ? bottom + 2 : rowTop + 14;
            for (OrderedText line : lines) {
                context.drawText(font, line, x, dy, ThePrisonsColors.ACCENT_RED, false);
                dy += 9;
            }
            bottom = Math.max(bottom, dy);
        } else if (descriptions && !setting.description().isEmpty()) {
            List<OrderedText> lines = font.wrapLines(StringVisitable.plain(I18n.t(setting.description())), wide ? w : labelW);
            int dy = wide ? bottom + 2 : rowTop + 14;
            for (OrderedText line : lines) {
                context.drawText(font, line, x, dy, ThePrisonsColors.FG_MUTED, false);
                dy += 9;
            }
            bottom = Math.max(bottom, dy);
        }
        return bottom + ROW_GAP + 2;
    }

    private void renderFooter(DrawContext context, int mouseX, int mouseY) {
        TextRenderer font = textRenderer;
        int x1 = panelX + sidebarW + 9;
        int y = panelY + panelH - footerH + 4;
        ConfigStore store = core.config();
        String state;
        int color;
        if (!toast.isEmpty() && System.currentTimeMillis() - toastAtMs < 2500L) {
            state = toast;
            color = ThePrisonsColors.ACCENT_LIME;
        } else if (store.lastError() != null) {
            state = store.lastError();
            color = ThePrisonsColors.ACCENT_RED;
        } else if (store.dirty()) {
            state = I18n.t("Unsaved changes (auto-saves in 2 s)");
            color = ThePrisonsColors.ACCENT_AMBER;
        } else {
            state = I18n.t("All changes saved");
            color = ThePrisonsColors.FG_MUTED;
        }
        context.drawText(font, trim(state, panelW - sidebarW - 200), x1, y + 4, color, false);
        int bx = panelX + panelW - 8;
        bx = button(context, mouseX, mouseY, bx, y, I18n.t("Reset module"), selected != null, () -> {
            Module module = selected;
            if (module != null) {
                for (Setting<?> setting : module.settings()) {
                    if (!(setting instanceof Settings.ActionSetting)) {
                        setting.reset();
                    }
                }
                showToast(I18n.f("%s reset to defaults", I18n.t(module.name())));
            }
        });
        bx = button(context, mouseX, mouseY, bx - 4, y, I18n.t("Load"), true, () -> {
            stopEditing();
            ThePrisonsClient.CONFIG.load();
            store.load(true);
            showToast(I18n.t("Loaded from disk"));
        });
        bx = button(context, mouseX, mouseY, bx - 4, y, I18n.t("Save"), true, () -> {
            commitEdit();
            store.saveNow(false);
            showToast(I18n.t("Saved to ") + store.file().getFileName());
        });
    }

    private void renderDropdown(DrawContext context, int mouseX, int mouseY) {
        Setting<?> open = dropdown;
        if (open instanceof Settings.EnumSetting<?> choice) {
            renderEnumPopup(context, mouseX, mouseY, choice);
        } else if (open instanceof Settings.ChoiceSetting choice) {
            renderChoicePopup(context, mouseX, mouseY, choice);
        }
    }

    /** "Off" first, then the options grouped under their section headers (e.g. routes by ore). */
    private void renderChoicePopup(DrawContext context, int mouseX, int mouseY, Settings.ChoiceSetting setting) {
        List<Settings.Option> options = setting.options();
        int rows = 1;
        String section = null;
        for (Settings.Option option : options) {
            if (!option.section().equals(section)) {
                section = option.section();
                rows++;
            }
            rows++;
        }
        int h = rows * 13 + 2;
        int y = Math.max(panelY + 2, Math.min(dropdownY, panelY + panelH - h - 2));
        context.fill(dropdownX, y, dropdownX + CONTROL_W, y + h, ThePrisonsColors.BG_CARD_INNER);
        context.drawStrokedRectangle(dropdownX, y, CONTROL_W, h, ThePrisonsColors.ACCENT_VIOLET);
        int oy = y + 1;
        oy = choiceRow(context, mouseX, mouseY, setting, "", setting.offLabel(), ThePrisonsColors.FG_PRIMARY, oy);
        section = null;
        for (Settings.Option option : options) {
            if (!option.section().equals(section)) {
                section = option.section();
                context.drawText(textRenderer, trim(section, CONTROL_W - 8), dropdownX + 4, oy + 3, option.color(), false);
                oy += 13;
            }
            oy = choiceRow(context, mouseX, mouseY, setting, option.id(), "  " + option.label(), ThePrisonsColors.FG_PRIMARY, oy);
        }
    }

    private int choiceRow(DrawContext context, int mouseX, int mouseY, Settings.ChoiceSetting setting, String id, String label,
                          int color, int oy) {
        boolean hover = inside(mouseX, mouseY, dropdownX, oy, dropdownX + CONTROL_W, oy + 13);
        boolean current = id.equals(setting.get());
        if (hover || current) {
            context.fill(dropdownX + 1, oy, dropdownX + CONTROL_W - 1, oy + 13, hover ? ThePrisonsColors.SIDEBAR_HOVER : ThePrisonsColors.SIDEBAR_ACTIVE);
        }
        context.drawText(textRenderer, trim(label, CONTROL_W - 8), dropdownX + 4, oy + 3, current ? ThePrisonsColors.ACCENT_CYAN : color, false);
        popupHits.add(new Hit(dropdownX, oy, dropdownX + CONTROL_W, oy + 13, (mx, my, b) -> {
            setting.set(id);
            dropdown = null;
        }));
        return oy + 13;
    }

    private <E extends Enum<E>> void renderEnumPopup(DrawContext context, int mouseX, int mouseY, Settings.EnumSetting<E> setting) {
        List<E> options = setting.options();
        int h = options.size() * 13 + 2;
        int y = Math.min(dropdownY, panelY + panelH - h - 2);
        context.fill(dropdownX, y, dropdownX + CONTROL_W, y + h, ThePrisonsColors.BG_CARD_INNER);
        context.drawStrokedRectangle(dropdownX, y, CONTROL_W, h, ThePrisonsColors.ACCENT_VIOLET);
        int oy = y + 1;
        for (E option : options) {
            boolean hover = inside(mouseX, mouseY, dropdownX, oy, dropdownX + CONTROL_W, oy + 13);
            boolean current = option == setting.get();
            if (hover || current) {
                context.fill(dropdownX + 1, oy, dropdownX + CONTROL_W - 1, oy + 13, hover ? ThePrisonsColors.SIDEBAR_HOVER : ThePrisonsColors.SIDEBAR_ACTIVE);
            }
            context.drawText(textRenderer, trim(setting.label(option), CONTROL_W - 8), dropdownX + 4, oy + 3,
                    current ? ThePrisonsColors.ACCENT_CYAN : ThePrisonsColors.FG_PRIMARY, false);
            int top = oy;
            popupHits.add(new Hit(dropdownX, top, dropdownX + CONTROL_W, top + 13, (mx, my, b) -> {
                setting.set(option);
                dropdown = null;
            }));
            oy += 13;
        }
    }

    // ── Widgets ──────────────────────────────────────────────────────────────

    private void toggle(DrawContext context, int x, int y, boolean on) {
        int w = 20;
        int h = 10;
        if (on) {
            context.fillGradient(x, y, x + w, y + h, ThePrisonsColors.TOGGLE_ON_L, ThePrisonsColors.TOGGLE_ON_R);
        } else {
            context.fill(x, y, x + w, y + h, ThePrisonsColors.TOGGLE_OFF);
        }
        int knob = on ? x + w - 9 : x + 1;
        context.fill(knob, y + 1, knob + 8, y + h - 1, ThePrisonsColors.TOGGLE_KNOB);
    }

    private void box(DrawContext context, int x, int y, int w, int h, boolean focused) {
        context.fill(x, y, x + w, y + h, ThePrisonsColors.BG_INPUT);
        context.drawStrokedRectangle(x, y, w, h, focused ? ThePrisonsColors.ACCENT_CYAN : ThePrisonsColors.BORDER_HI);
    }

    private int chip(DrawContext context, int mouseX, int mouseY, int x, int y, String label, boolean active, Runnable action) {
        int w = textRenderer.getWidth(label) + 10;
        boolean hover = inside(mouseX, mouseY, x, y, x + w, y + 12);
        context.fill(x, y, x + w, y + 12, active ? ThePrisonsColors.SIDEBAR_ACTIVE : hover ? ThePrisonsColors.SIDEBAR_HOVER : ThePrisonsColors.BG_INPUT);
        context.drawStrokedRectangle(x, y, w, 12, active ? ThePrisonsColors.ACCENT_VIOLET : ThePrisonsColors.BORDER);
        context.drawText(textRenderer, label, x + 5, y + 2, active ? ThePrisonsColors.ACCENT_CYAN : ThePrisonsColors.FG_SECONDARY, false);
        hit(x, y, x + w, y + 12, (mx, my, b) -> action.run());
        return x + w + 4;
    }

    /** Right-aligned button ending at {@code right}; returns its left edge. */
    private int button(DrawContext context, int mouseX, int mouseY, int right, int y, String label, boolean enabled, Runnable action) {
        int w = textRenderer.getWidth(label) + 12;
        int x = right - w;
        boolean hover = enabled && inside(mouseX, mouseY, x, y, right, y + 14);
        context.fill(x, y, right, y + 14, hover ? ThePrisonsColors.ACCENT_VIOLET : ThePrisonsColors.BG_CARD_HOVER);
        context.drawStrokedRectangle(x, y, w, 14, ThePrisonsColors.BORDER_HI);
        context.drawText(textRenderer, label, x + 6, y + 3, enabled ? ThePrisonsColors.FG_PRIMARY : ThePrisonsColors.FG_DISABLED, false);
        if (enabled) {
            hit(x, y, right, y + 14, (mx, my, b) -> action.run());
        }
        return x;
    }

    private void scrollbar(DrawContext context, int x, int top, int bottom, double scroll, int content) {
        int view = bottom - top;
        if (content <= view) {
            return;
        }
        int barH = Math.max(12, view * view / content);
        int barY = top + (int) ((view - barH) * (scroll / (content - view)));
        context.fill(x, barY, x + 2, barY + barH, ThePrisonsColors.BORDER_HI);
    }

    private static int statusColor(Module.StatusLevel level) {
        return switch (level) {
            case OFF -> ThePrisonsColors.FG_DISABLED;
            case IDLE -> ThePrisonsColors.FG_MUTED;
            case ACTIVE -> ThePrisonsColors.ACCENT_LIME;
            case WARNING -> ThePrisonsColors.ACCENT_AMBER;
            case ERROR -> ThePrisonsColors.ACCENT_RED;
        };
    }

    // ── Layout helpers ───────────────────────────────────────────────────────

    private void clip(DrawContext context, int x1, int y1, int x2, int y2) {
        clipX1 = x1;
        clipY1 = y1;
        clipX2 = x2;
        clipY2 = y2;
        context.enableScissor(x1, y1, x2, y2);
    }

    private void unclip(DrawContext context) {
        context.disableScissor();
        clipX1 = Integer.MIN_VALUE;
        clipY1 = Integer.MIN_VALUE;
        clipX2 = Integer.MAX_VALUE;
        clipY2 = Integer.MAX_VALUE;
    }

    private boolean insideClip(double x, double y) {
        return x >= clipX1 && x < clipX2 && y >= clipY1 && y < clipY2;
    }

    /** Registers a click box, cut to the current clip region. */
    private void hit(int x1, int y1, int x2, int y2, ClickAction action) {
        int cx1 = Math.max(x1, clipX1);
        int cy1 = Math.max(y1, clipY1);
        int cx2 = Math.min(x2, clipX2);
        int cy2 = Math.min(y2, clipY2);
        if (cx1 < cx2 && cy1 < cy2) {
            hits.add(new Hit(cx1, cy1, cx2, cy2, action));
        }
    }

    private static boolean inside(double mx, double my, int x1, int y1, int x2, int y2) {
        return mx >= x1 && mx < x2 && my >= y1 && my < y2;
    }

    private String trim(String text, int maxWidth) {
        if (textRenderer.getWidth(text) <= maxWidth) {
            return text;
        }
        return textRenderer.trimToWidth(text, Math.max(0, maxWidth - textRenderer.getWidth("…"))) + "…";
    }

    private String trimLeft(String text, int maxWidth) {
        String result = text;
        while (result.length() > 1 && textRenderer.getWidth(result) > maxWidth) {
            result = result.substring(1);
        }
        return result;
    }

    private static boolean blink() {
        return (System.currentTimeMillis() / 500L) % 2L == 0L;
    }

    private static String keyName(int key) {
        if (key == Settings.KeybindSetting.NONE) {
            return I18n.t("None");
        }
        return InputUtil.Type.KEYSYM.createFromCode(key).getLocalizedText().getString();
    }

    // ── State ────────────────────────────────────────────────────────────────

    private List<Module> visibleModules() {
        if (!search.isBlank()) {
            return modules.search(search);
        }
        List<Module> list = new ArrayList<>();
        for (Module module : modulesIn(tab)) {
            if (groupFilter.isEmpty() || module.group().equals(groupFilter)) {
                list.add(module);
            }
        }
        return list;
    }

    /** Modules shown in a category: its home modules and every module with settings in it. */
    private List<Module> modulesIn(ConfigCategory entry) {
        return ConfigCategory.modulesIn(modules.all(), entry);
    }

    private List<String> groupsOf(ConfigCategory entry) {
        List<String> groups = new ArrayList<>();
        for (Module module : modulesIn(entry)) {
            if (!groups.contains(module.group())) {
                groups.add(module.group());
            }
        }
        return groups;
    }

    private void selectTab(ConfigCategory entry) {
        stopEditing();
        search = "";
        searchFocused = false;
        tab = entry;
        showDetail = false;
        groupFilter = "";
        listScroll = 0;
        List<Module> list = modulesIn(entry);
        select(list.isEmpty() ? null : list.get(0));
    }

    private void select(@Nullable Module module) {
        if (module != selected) {
            settingsScroll = 0;
            dropdown = null;
            listening = null;
            commitEdit();
        }
        selected = module;
    }

    private void showToast(String text) {
        toast = text;
        toastAtMs = System.currentTimeMillis();
    }

    private void applySlider(Slider slider, double mouseX) {
        double t = MathHelper.clamp((mouseX - slider.x()) / slider.width(), 0.0D, 1.0D);
        if (slider.setting() instanceof Settings.IntSetting setting) {
            setting.set((int) Math.round(setting.min() + t * (setting.max() - setting.min())));
        } else if (slider.setting() instanceof Settings.DoubleSetting setting) {
            setting.set(setting.min() + t * (setting.max() - setting.min()));
        }
    }

    private void commitEdit() {
        Settings.TextSetting current = editing;
        if (current != null) {
            current.set(editBuffer);
        }
        editing = null;
    }

    private void stopEditing() {
        commitEdit();
        listening = null;
        dropdown = null;
    }

    // ── Input ────────────────────────────────────────────────────────────────

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        double mx = click.x();
        double my = click.y();
        int button = click.button();
        if (listening != null) {
            // Mouse buttons are not bindable here; a click elsewhere just cancels.
            listening = null;
        }
        if (dropdown != null) {
            for (Hit hit : popupHits) {
                if (hit.contains(mx, my)) {
                    hit.action().run(mx, my, button);
                    return true;
                }
            }
            dropdown = null;
            return true;
        }
        // Any click commits a text edit; clicking the same field re-opens it with the committed value.
        commitEdit();
        searchFocused = false;
        for (int i = hits.size() - 1; i >= 0; i--) {
            Hit hit = hits.get(i);
            if (hit.contains(mx, my)) {
                hit.action().run(mx, my, button);
                return true;
            }
        }
        return super.mouseClicked(click, doubled);
    }

    @Override
    public boolean mouseReleased(Click click) {
        dragging = null;
        return super.mouseReleased(click);
    }

    @Override
    public boolean mouseDragged(Click click, double offsetX, double offsetY) {
        if (dragging != null) {
            applySlider(dragging, click.x());
            return true;
        }
        return super.mouseDragged(click, offsetX, offsetY);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        int cx = panelX + sidebarW + 2;
        int cw = panelW - sidebarW - 2;
        int listX = cx + 8;
        int listW = Math.max(132, Math.min(220, (int) (cw * 0.42)));
        int top = panelY + headerH + 8;
        int bottom = panelY + panelH - footerH;
        int view = bottom - top;
        double step = verticalAmount * 18.0D;
        if (mouseX >= listX && mouseX < listX + listW) {
            listScroll = MathHelper.clamp(listScroll - step, 0.0D, Math.max(0, listContentH - view));
        } else if (mouseX >= listX + listW) {
            settingsScroll = MathHelper.clamp(settingsScroll - step, 0.0D, Math.max(0, settingsContentH + 16 - view));
        }
        return true;
    }

    @Override
    public boolean keyPressed(KeyInput input) {
        int key = input.key();
        Settings.KeybindSetting bind = listening;
        if (bind != null) {
            bind.set(key == GLFW.GLFW_KEY_ESCAPE || key == GLFW.GLFW_KEY_BACKSPACE || key == GLFW.GLFW_KEY_DELETE
                    ? Settings.KeybindSetting.NONE : key);
            listening = null;
            return true;
        }
        if (editing != null) {
            switch (key) {
                case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> commitEdit();
                case GLFW.GLFW_KEY_ESCAPE -> editing = null;
                case GLFW.GLFW_KEY_BACKSPACE -> {
                    if (!editBuffer.isEmpty()) {
                        editBuffer = editBuffer.substring(0, editBuffer.length() - 1);
                    }
                }
                default -> {
                    if (input.isPaste() && client != null) {
                        editBuffer += client.keyboard.getClipboard().replace("\n", " ");
                    }
                }
            }
            return true;
        }
        if (input.hasCtrl() && key == GLFW.GLFW_KEY_F) {
            searchFocused = true;
            return true;
        }
        if (searchFocused) {
            switch (key) {
                case GLFW.GLFW_KEY_ESCAPE -> {
                    search = "";
                    searchFocused = false;
                }
                case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> {
                    searchFocused = false;
                    List<Module> found = modules.search(search);
                    if (!found.isEmpty()) {
                        select(found.get(0));
                    }
                }
                case GLFW.GLFW_KEY_BACKSPACE -> {
                    if (!search.isEmpty()) {
                        search = search.substring(0, search.length() - 1);
                        listScroll = 0;
                    }
                }
                default -> {
                }
            }
            return true;
        }
        if (key == GLFW.GLFW_KEY_ESCAPE && dropdown != null) {
            dropdown = null;
            return true;
        }
        if (key == GLFW.GLFW_KEY_ESCAPE && compact && showDetail) {
            showDetail = false;
            return true;
        }
        return super.keyPressed(input);
    }

    @Override
    public boolean charTyped(CharInput input) {
        if (!input.isValidChar()) {
            return false;
        }
        String typed = input.asString();
        Settings.TextSetting text = editing;
        if (text != null) {
            if (editBuffer.length() < text.maxLength()) {
                editBuffer += typed;
            }
            return true;
        }
        if (listening != null) {
            return true;
        }
        if (!searchFocused && !typed.isBlank()) {
            // Typing anywhere starts a search.
            searchFocused = true;
            search = "";
        }
        if (searchFocused && search.length() < 40) {
            search += typed;
            listScroll = 0;
            return true;
        }
        return false;
    }
}
