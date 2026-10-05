package com.freelocs.theprisons.gui.dashboard;

import com.freelocs.theprisons.core.ThePrisonsCore;
import com.freelocs.theprisons.core.module.Module;
import com.freelocs.theprisons.core.setting.Setting;
import com.freelocs.theprisons.core.setting.Settings;
import com.freelocs.theprisons.gui.kit.Ui;
import com.freelocs.theprisons.gui.theme.Theme;
import com.freelocs.theprisons.modules.FeatureProfile;
import com.freelocs.theprisons.modules.general.DesignModule;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.Click;
import net.minecraft.client.input.CharInput;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.input.KeyInput;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import net.minecraft.util.Util;
import net.minecraft.util.math.MathHelper;
import org.jspecify.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * The mod's settings screen ({@code /prisons}, keybind, Mod Menu) as a dashboard. The feature set is fixed by
 * {@link FeatureProfile}; users only see what is active and change the design, their keybinds and the HUD layout.
 *
 * <p>Animated throughout: drifting stars in the background, a shimmering title and flowing accent lines, a tab
 * underline that slides, pages that slide in, tiles that appear one after another and lift on hover, pulsing status
 * dots, switches and sliders that move smoothly and a live preview of the design.
 */
public final class DashboardScreen extends Screen implements com.freelocs.theprisons.gui.kit.HidesHud {
    private enum Page {
        OVERVIEW("Overview"), ORES("Mining"), BANDITS("Bandits"), TUNNEL("Tunnel"), DESIGN("Design"), CONTROLS("Controls"), HUD("HUD");

        final String label;

        Page(String label) {
            this.label = label;
        }
    }

    private static final int PANEL_MAX = 520;
    private static final int STARS = 46;

    private final @Nullable Screen parent;
    private final ThePrisonsCore core;
    private final Runnable openHudEditor;
    private final @Nullable Runnable openClassic;
    private final long openedMs = Util.getMeasuringTimeMs();
    private long lastFrameMs = openedMs;
    private Page page = Page.OVERVIEW;
    private long pageMs = openedMs;
    private int pageDirection = 1;
    private float tabX = -1;
    private float tabW;
    private final Map<String, Float> hover = new HashMap<>();
    private final float[][] stars = new float[STARS][4];
    private final List<Hit> hits = new ArrayList<>();
    private Settings.@Nullable KeybindSetting listening;
    private @Nullable Slider dragging;
    private float shownDarkness = -1;
    private float animationsKnob = -1;
    private float fontKnob = -1;
    private float comicKnob = -1;
    private float sprintKnob = -1;
    private final Map<String, Float> oreKnobs = new HashMap<>();
    private float scroll;
    private float scrollTarget;
    private int scrollMax;
    private int contentHeight = 200;

    /** A clickable area registered while drawing (rebuilt every frame). */
    private record Hit(int x, int y, int w, int h, String id, Runnable action) {
        boolean contains(double mx, double my) {
            return mx >= x && mx < x + w && my >= y && my < y + h;
        }
    }

    private record Slider(int x, int w, double min, double max, double step, java.util.function.DoubleConsumer apply) {
    }

    public DashboardScreen(@Nullable Screen parent, ThePrisonsCore core, Runnable openHudEditor, @Nullable Runnable openClassic) {
        super(Text.literal("ThePrisons"));
        this.parent = parent;
        this.core = core;
        this.openHudEditor = openHudEditor;
        this.openClassic = openClassic;
        Random random = new Random(7);
        for (float[] star : stars) {
            star[0] = random.nextFloat();
            star[1] = random.nextFloat();
            star[2] = 0.3F + random.nextFloat() * 0.9F;   // speed
            star[3] = random.nextFloat();                   // colour mix
        }
    }

    // ── frame ────────────────────────────────────────────────────────────────

    @Override
    public void renderBackground(DrawContext context, int mouseX, int mouseY, float deltaTicks) {
        float in = Ui.appear(openedMs, 0, 400.0F);
        context.fillGradient(0, 0, width, height, Ui.argb(Math.round(150 * in), 0x07060C), Ui.argb(Math.round(200 * in), 0x0C0A16));
        Theme theme = Ui.theme();
        double t = Util.getMeasuringTimeMs() / 1000.0D;
        for (float[] star : stars) {
            float y = (float) ((star[1] - t * 0.012D * star[2]) % 1.0D);
            if (y < 0) {
                y += 1.0F;
            }
            int sx = Math.round(star[0] * width);
            int sy = Math.round(y * height);
            float twinkle = DesignModule.animations() ? 0.5F + 0.5F * MathHelper.sin((float) (t * 2.0D * star[2] + star[0] * 20)) : 0.7F;
            int colour = Ui.mix(theme.accent(), theme.title(), star[3]);
            context.fill(sx, sy, sx + 1, sy + 1, Ui.argb(Math.round(200 * twinkle * in), colour));
            if (star[2] > 1.0F) {
                context.fill(sx - 1, sy, sx + 2, sy + 1, Ui.argb(Math.round(70 * twinkle * in), colour));
                context.fill(sx, sy - 1, sx + 1, sy + 2, Ui.argb(Math.round(70 * twinkle * in), colour));
            }
        }
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float deltaTicks) {
        super.render(context, mouseX, mouseY, deltaTicks);
        long now = Util.getMeasuringTimeMs();
        float dt = MathHelper.clamp((now - lastFrameMs) / 1000.0F, 0.0F, 0.1F);
        lastFrameMs = now;
        hits.clear();
        hoverTip = null;

        int panelW = Math.min(width - 24, PANEL_MAX);
        int left = (width - panelW) / 2;
        int top = Math.max(8, height / 2 - 128);

        drawHeader(context, left, top, panelW, mouseX, mouseY, dt);
        int tabsY = top + 42;
        drawTabs(context, left, tabsY, panelW, mouseX, mouseY, dt);

        // page slides in from the side it was chosen from
        float slide = Ui.appear(pageMs, 0, 300.0F);
        int offset = Math.round((1.0F - slide) * 24.0F * pageDirection);
        int contentY = tabsY + 22;
        int contentH = Math.min(height - contentY - 8, 210);
        contentHeight = contentH;
        context.enableScissor(left - 4, contentY - 2, left + panelW + 4, contentY + contentH);
        context.getMatrices().pushMatrix();
        context.getMatrices().translate(offset, 0);
        switch (page) {
            case OVERVIEW -> drawOverview(context, left, contentY, panelW, mouseX - offset, mouseY, dt, slide);
            case ORES -> drawOres(context, left, contentY, panelW, mouseX - offset, mouseY, dt);
            case BANDITS -> drawBandits(context, left, contentY, panelW, mouseX - offset, mouseY, dt);
            case TUNNEL -> drawTunnel(context, left, contentY, panelW, mouseX - offset, mouseY, dt);
            case DESIGN -> drawDesign(context, left, contentY, panelW, mouseX - offset, mouseY, dt, slide);
            case CONTROLS -> drawControls(context, left, contentY, panelW, mouseX - offset, mouseY, dt, slide);
            case HUD -> drawHud(context, left, contentY, panelW, mouseX - offset, mouseY, dt, slide);
        }
        context.getMatrices().popMatrix();
        context.disableScissor();
        String tip = hoverTip;
        if (tip != null) {
            List<String> lines = wrap(tip, 220, 4);
            int th = lines.size() * 10 + 6;
            int tx = Math.min(mouseX + 10, width - 232);
            int ty = Math.min(mouseY + 12, height - th - 2);
            context.fill(tx - 3, ty - 3, tx + 229, ty + th - 3, 0xEE12101C);
            Ui.outline(context, tx - 3, ty - 3, 232, th, Ui.argb(160, Ui.theme().accent()));
            for (int i = 0; i < lines.size(); i++) {
                Ui.draw(context, textRenderer, lines.get(i), tx, ty + i * 10, Ui.VALUE, 255);
            }
        }
        if (offset != 0) {
            // hit areas were registered in page coordinates; they are only used once the page has settled
            hits.removeIf(h -> h.id().startsWith("page:"));
        }
    }

    private void drawHeader(DrawContext c, int left, int top, int w, int mx, int my, float dt) {
        float in = Ui.appear(openedMs, 0, 450.0F);
        int y = top + Math.round((1.0F - in) * -12.0F);
        Ui.card(c, left, y, w, 36, in);
        Ui.flowLine(c, left, left + w, y, in);
        Ui.shimmer(c, textRenderer, "THEPRISONS", left + 10, y + 8, in);
        String version = FabricLoader.getInstance().getModContainer("theprisons")
                .map(m -> m.getMetadata().getVersion().getFriendlyString()).orElse("");
        Ui.draw(c, textRenderer, version, left + 18 + Ui.width(textRenderer, "THEPRISONS"), y + 8, Ui.MUTED, Math.round(255 * in));
        Ui.draw(c, textRenderer, "Cosmic Prisons companion  ·  ready to play", left + 10, y + 21, Ui.LABEL, Math.round(255 * in));

        long active = core.modules().all().stream()
                .filter(m -> FeatureProfile.feature(m) && (m.enabled() || !m.toggleable())).count();
        String pill = active + " features active";
        int pw = Ui.width(textRenderer, pill) + 18;
        int px = left + w - pw - 30;
        c.fill(px, y + 11, px + pw, y + 24, Ui.argb(Math.round(60 * in), Ui.GOOD));
        float pulse = Ui.pulse(1600L);
        c.fill(px + 5, y + 16, px + 9, y + 20, Ui.argb(Math.round((150 + 105 * pulse) * in), Ui.GOOD));
        Ui.draw(c, textRenderer, pill, px + 13, y + 14, Ui.VALUE, Math.round(255 * in));

        // close
        int cx = left + w - 20;
        boolean over = mx >= cx && mx < cx + 14 && my >= y + 10 && my < y + 24;
        float h = hoverAnim("close", over, dt);
        Ui.drawCentered(c, textRenderer, "✕", cx + 7, y + 13, Ui.mix(Ui.LABEL, Ui.theme().accent(), h), 255);
        hits.add(new Hit(cx, y + 10, 14, 14, "close", this::close));
    }

    private void drawTabs(DrawContext c, int left, int y, int w, int mx, int my, float dt) {
        float in = Ui.appear(openedMs, 80L, 400.0F);
        Page[] pages = Page.values();
        int tw = w / pages.length;
        for (int i = 0; i < pages.length; i++) {
            Page p = pages[i];
            int tx = left + i * tw;
            boolean over = mx >= tx && mx < tx + tw && my >= y && my < y + 16;
            float h = hoverAnim("tab:" + p, over, dt);
            int colour = p == page ? Ui.theme().title() : Ui.mix(Ui.LABEL, Ui.VALUE, h);
            String label = p.label.toUpperCase(java.util.Locale.ROOT);
            int lw = Ui.width(textRenderer, label) + 13;
            Ui.icon(c, "page_" + p.name().toLowerCase(java.util.Locale.ROOT), tx + (tw - lw) / 2, y + 2, 10,
                    in * (p == page ? 1.0F : 0.55F + 0.45F * h));
            Ui.draw(c, textRenderer, label, tx + (tw - lw) / 2 + 13, y + 4, colour, Math.round(255 * in));
            int index = i;
            hits.add(new Hit(tx, y, tw, 16, "tab", () -> switchPage(pages[index])));
        }
        float targetX = left + page.ordinal() * tw + tw * 0.2F;
        float targetW = tw * 0.6F;
        if (tabX < 0) {
            tabX = targetX;
            tabW = targetW;
        }
        tabX = Ui.approach(tabX, targetX, dt, 14.0F);
        tabW = Ui.approach(tabW, targetW, dt, 14.0F);
        c.fill(left, y + 15, left + w, y + 16, Ui.argb(Math.round(50 * in), 0xFFFFFF));
        c.fill(Math.round(tabX), y + 14, Math.round(tabX + tabW), y + 16, Ui.argb(Math.round(255 * in), Ui.theme().accent()));
    }

    private void switchPage(Page next) {
        if (next == page) {
            return;
        }
        commitEdit();
        scroll = 0;
        scrollTarget = 0;
        pageDirection = next.ordinal() > page.ordinal() ? 1 : -1;
        page = next;
        pageMs = Util.getMeasuringTimeMs();
        listening = null;
    }

    // ── pages ────────────────────────────────────────────────────────────────

    private void drawOverview(DrawContext c, int left, int y, int w, int mx, int my, float dt, float slide) {
        List<Module> features = new ArrayList<>();
        for (Module m : core.modules().all()) {
            if (FeatureProfile.feature(m)) {
                features.add(m);
            }
        }
        int cols = w >= 480 ? 3 : 2;
        int gap = 6;
        int tw = (w - gap * (cols - 1)) / cols;
        int th = 40;
        int rowsTotal = (features.size() + cols - 1) / cols;
        scrollMax = Math.max(0, rowsTotal * (th + gap) - gap - contentHeight);
        scrollTarget = MathHelper.clamp(scrollTarget, 0, scrollMax);
        scroll = Ui.approach(scroll, scrollTarget, dt, 14.0F);
        y -= Math.round(scroll);
        for (int i = 0; i < features.size(); i++) {
            Module m = features.get(i);
            int tx = left + (i % cols) * (tw + gap);
            int ty = y + (i / cols) * (th + gap);
            float a = Ui.appear(pageMs, 40L * i, 320.0F);
            if (a <= 0.01F) {
                continue;
            }
            boolean over = mx >= tx && mx < tx + tw && my >= ty && my < ty + th;
            float h = hoverAnim("feat:" + m.id(), over, dt);
            int lift = Math.round(h * 2.0F) + Math.round((1.0F - a) * 10.0F);
            int yy = ty - lift;
            Ui.card(c, tx, yy, tw, th, a);
            if (h > 0.01F) {
                Ui.outline(c, tx, yy, tw, th, Ui.argb(Math.round(160 * h * a), Ui.theme().accent()));
            }
            c.fill(tx, yy, tx + 2, yy + th, Ui.argb(Math.round(220 * a), Ui.theme().accent()));
            Ui.icon(c, Ui.iconOf(m), tx + 7, yy + 3, 12, a);
            Ui.draw(c, textRenderer, m.name(), tx + 22, yy + 6, Ui.theme().title(), Math.round(255 * a));
            float pulse = Ui.pulse(1800L + i * 90L);
            boolean on = m.enabled() || !m.toggleable(); // always-on modules have no switch
            int dot = on ? Ui.GOOD : Ui.BAD;
            c.fill(tx + tw - 10, yy + 7, tx + tw - 6, yy + 11, Ui.argb(Math.round((140 + 115 * pulse) * a), dot));
            List<String> lines = wrap(m.description(), tw - 16, 2);
            for (int l = 0; l < lines.size(); l++) {
                Ui.draw(c, textRenderer, lines.get(l), tx + 8, yy + 18 + l * 10, Ui.LABEL, Math.round(230 * a));
            }
        }
        if (features.isEmpty()) {
            Ui.drawCentered(c, textRenderer, "No features active", left + w / 2, y + 40, Ui.LABEL, 255);
        }
    }

    /** Ores: one row per ore with a switch for its ore and its deepslate pack, and the Ore Macro's auto sprint. */
    private void drawOresPage(DrawContext c, int left, int y, int w, int mx, int my, float dt) {
        Module macro = core.modules().get("ore_macro");
        if (macro == null || !(macro.setting("ore_packs") instanceof Settings.MultiChoiceSetting ores)) {
            Ui.drawCentered(c, textRenderer, "Ore Macro not available", left + w / 2, y + 40, Ui.LABEL, 255);
            return;
        }
        int colW = (w - 10) / 2;
        float a0 = Ui.appear(pageMs, 0, 300.0F);
        section(c, "ORES", left, y, a0);
        // All / None
        int bx = left + colW - 4;
        bx = textButton(c, "None", bx, y - 2, mx, my, dt, a0, () -> ores.options().forEach(o -> {
            if (ores.contains(o.id())) {
                ores.toggle(o.id());
            }
        })) - 6;
        textButton(c, "All", bx, y - 2, mx, my, dt, a0, () -> ores.options().forEach(o -> {
            if (!ores.contains(o.id())) {
                ores.toggle(o.id());
            }
        }));

        Map<String, List<Settings.Option>> rows = new LinkedHashMap<>();
        for (Settings.Option option : ores.options()) {
            rows.computeIfAbsent(option.section(), k -> new ArrayList<>()).add(option);
        }
        int i = 0;
        for (Map.Entry<String, List<Settings.Option>> row : rows.entrySet()) {
            float a = Ui.appear(pageMs, 40L * i + 60L, 300.0F);
            int ry = y + 16 + i * 17;
            boolean over = mx >= left && mx < left + colW && my >= ry && my < ry + 15;
            float h = hoverAnim("ore:" + row.getKey(), over, dt);
            c.fill(left, ry, left + colW, ry + 15, Ui.argb(Math.round((18 + 22 * h) * a), 0xFFFFFF));
            int colour = row.getValue().get(0).color() | 0xFF000000;
            c.fill(left, ry, left + 2, ry + 15, Ui.argb(Math.round(230 * a), colour & 0xFFFFFF));
            Ui.draw(c, textRenderer, row.getKey(), left + 8, ry + 4, Ui.VALUE, Math.round(255 * a));
            int sx = left + 74;
            for (Settings.Option option : row.getValue()) {
                String label = option.label().equals(option.section()) ? "Ore" : "Deepslate";
                sx = oreSwitch(c, sx, ry + 2, label, ores, option, mx, my, dt, a) + 8;
            }
            i++;
        }
        String summary = ores.get().size() + " of " + ores.options().size() + " packs selected";
        Ui.draw(c, textRenderer, ores.get().isEmpty() ? "Pick at least one ore to start the macro." : summary,
                left, y + 20 + rows.size() * 17, ores.get().isEmpty() ? Ui.BAD : Ui.MUTED, Math.round(255 * a0));

        // right: movement
        int px = left + colW + 10;
        float a1 = Ui.appear(pageMs, 120L, 300.0F);
        section(c, "MOVEMENT", px, y, a1);
        if (macro.setting("sprint") instanceof Settings.BoolSetting sprint) {
            sprintKnob = toggle(c, px, y + 18, "Auto sprint", sprint, sprintKnob, mx, my, dt, a1);
            Ui.draw(c, textRenderer, "Sprints on straight parts of the way.", px, y + 36, Ui.LABEL, Math.round(255 * a1));
            Ui.draw(c, textRenderer, "Also: /prisons sprint [on|off]", px, y + 47, Ui.MUTED, Math.round(255 * a1));
        }
        Ui.draw(c, textRenderer, "Ores of packs you switch off mark the border of the mine.", px, y + 70, Ui.MUTED, Math.round(255 * a1));
    }

    /** One ore switch (label + sliding switch); returns the right edge. */
    private int oreSwitch(DrawContext c, int x, int y, String label, Settings.MultiChoiceSetting ores, Settings.Option option,
                          int mx, int my, float dt, float a) {
        boolean on = ores.contains(option.id());
        float knob = Ui.approach(oreKnobs.getOrDefault(option.id(), on ? 1.0F : 0.0F), on ? 1.0F : 0.0F, dt, 14.0F);
        oreKnobs.put(option.id(), knob);
        Ui.draw(c, textRenderer, label, x, y + 2, on ? Ui.VALUE : Ui.LABEL, Math.round(255 * a));
        int sx = x + Ui.width(textRenderer, label) + 6;
        int track = Ui.mix(0x3A3F4E, option.color() & 0xFFFFFF, knob);
        c.fill(sx, y, sx + 22, y + 11, Ui.argb(Math.round(255 * a), track));
        int kx = sx + 1 + Math.round(knob * 11.0F);
        c.fill(kx, y + 1, kx + 9, y + 10, Ui.argb(Math.round(255 * a), 0xFFFFFF));
        String id = option.id();
        hits.add(new Hit(x - 2, y - 1, sx + 24 - x, 13, "page:ore", () -> ores.toggle(id)));
        return sx + 22;
    }

    /** A small text button whose right edge is {@code right}; returns its left edge. */
    private int textButton(DrawContext c, String label, int right, int y, int mx, int my, float dt, float a, Runnable action) {
        int bw = Ui.width(textRenderer, label) + 12;
        int bx = right - bw;
        boolean over = mx >= bx && mx < right && my >= y && my < y + 13;
        float h = hoverAnim("btn:" + label, over, dt);
        c.fill(bx, y, right, y + 13, Ui.argb(Math.round((40 + 70 * h) * a), Ui.theme().accent()));
        Ui.drawCentered(c, textRenderer, label, bx + bw / 2, y + 3, Ui.mix(Ui.VALUE, Ui.theme().title(), h), Math.round(255 * a));
        hits.add(new Hit(bx, y, bw, 13, "page:btn", action));
        return bx;
    }

    private void drawDesign(DrawContext c, int left, int y, int w, int mx, int my, float dt, float slide) {
        DesignModule design = DesignModule.get();
        if (design == null) {
            return;
        }
        int colW = (w - 10) / 2;
        // left: controls
        float a0 = Ui.appear(pageMs, 0, 300.0F);
        section(c, "THEME", left, y, a0);
        Theme[] themes = Theme.values();
        int sw = (colW - 5 * 4) / 6;
        for (int i = 0; i < themes.length; i++) {
            Theme t = themes[i];
            int sx = left + i * (sw + 4);
            int sy = y + 14;
            boolean over = mx >= sx && mx < sx + sw && my >= sy && my < sy + 26;
            float h = hoverAnim("theme:" + t, over, dt);
            float a = Ui.appear(pageMs, 40L * i, 300.0F);
            int grow = Math.round(h * 2.0F);
            c.fill(sx - grow, sy - grow, sx + sw + grow, sy + 13, Ui.argb(Math.round(255 * a), t.accent()));
            c.fill(sx - grow, sy + 13, sx + sw + grow, sy + 26 + grow, Ui.argb(Math.round(255 * a), t.title()));
            if (t == DesignModule.theme()) {
                float pulse = Ui.pulse(1400L);
                Ui.outline(c, sx - 3, sy - 3, sw + 6, 32, Ui.argb(Math.round(150 + 105 * pulse), 0xFFFFFF));
            }
            hits.add(new Hit(sx, sy, sw, 26, "page:theme", () -> design.themeSetting().set(t)));
        }
        Ui.draw(c, textRenderer, DesignModule.theme().label(), left, y + 46, Ui.theme().title(), Math.round(255 * a0));

        float a1 = Ui.appear(pageMs, 120L, 300.0F);
        section(c, "CARD DARKNESS", left, y + 62, a1);
        int value = design.darknessSetting().get();
        if (shownDarkness < 0) {
            shownDarkness = value;
        }
        shownDarkness = Ui.approach(shownDarkness, value, dt, 16.0F);
        slider(c, left, y + 78, colW - 30, design.darknessSetting(), shownDarkness, mx, my, a1);
        Ui.drawRight(c, textRenderer, value + "%", left + colW, y + 76, Ui.VALUE, Math.round(255 * a1));

        float a2 = Ui.appear(pageMs, 200L, 300.0F);
        section(c, "MOTION & FONT", left, y + 96, a2);
        animationsKnob = toggle(c, left, y + 112, "Animations", design.animationsSetting(), animationsKnob, mx, my, dt, a2);
        fontKnob = toggle(c, left, y + 130, "Boxy font", design.sleekFontSetting(), fontKnob, mx, my, dt, a2);
        comicKnob = toggle(c, left, y + 148, "Comic textures", design.comicTexturesSetting(), comicKnob, mx, my, dt, a2);

        // right: live preview
        int px = left + colW + 10;
        float a3 = Ui.appear(pageMs, 160L, 350.0F);
        section(c, "PREVIEW", px, y, a3);
        int cardY = y + 14 + Math.round((1.0F - a3) * 10.0F);
        Ui.card(c, px, cardY, colW, 110, a3);
        Ui.draw(c, textRenderer, "PV 1", px + 7, cardY + 5, Ui.theme().title(), Math.round(255 * a3));
        Ui.drawRight(c, textRenderer, "● LIVE", px + colW - 7, cardY + 5, Ui.theme().accent(), Math.round(255 * a3));
        Ui.line(c, px + 5, px + colW - 5, cardY + 17, a3);
        int cell = 14;
        for (int r = 0; r < 5; r++) {
            for (int col = 0; col < Math.min(9, (colW - 14) / cell); col++) {
                int cx = px + 7 + col * cell;
                int cy = cardY + 23 + r * cell;
                float wave = DesignModule.animations() ? 0.5F + 0.5F * MathHelper.sin(Util.getMeasuringTimeMs() / 400.0F - (r + col) * 0.6F) : 0.5F;
                c.fill(cx, cy, cx + cell - 2, cy + cell - 2, Ui.argb(Math.round((20 + 30 * wave) * a3), 0xFFFFFF));
            }
        }
    }

    private void drawControls(DrawContext c, int left, int y, int w, int mx, int my, float dt, float slide) {
        section(c, "KEYBINDS", left, y, Ui.appear(pageMs, 0, 300.0F));
        List<Module> bindable = new ArrayList<>();
        Module gui = core.modules().get("click_gui");
        if (gui != null) {
            bindable.add(gui);
        }
        Module storage = core.modules().get("storage_overlay");
        if (storage != null) {
            bindable.add(storage);
        }
        Module spear = core.modules().get("spear_helper");
        if (spear != null) {
            bindable.add(spear);
        }
        for (int i = 0; i < bindable.size(); i++) {
            Module m = bindable.get(i);
            Settings.KeybindSetting key = m.keybind();
            int ry = y + 16 + i * 30;
            float a = Ui.appear(pageMs, 60L * i, 300.0F);
            boolean over = mx >= left && mx < left + w && my >= ry && my < ry + 24;
            float h = hoverAnim("key:" + m.id(), over, dt);
            Ui.card(c, left, ry - Math.round(h * 1.5F), w, 24, a);
            String label = switch (m.id()) {
                case "click_gui" -> "Open dashboard";
                case "spear_helper" -> "Spear aim assist (start / stop)";
                default -> "Open storage overlay (/pv)";
            };
            Ui.draw(c, textRenderer, label, left + 10, ry + 8, Ui.VALUE, Math.round(255 * a));
            boolean listen = listening == key;
            String name = listen ? "Press a key…" : key.bound() ? keyName(key.key()) : "Not bound";
            int bw = Math.max(70, Ui.width(textRenderer, name) + 16);
            int bx = left + w - bw - 8;
            float pulse = listen ? Ui.pulse(700L) : 0.0F;
            c.fill(bx, ry + 4, bx + bw, ry + 20, Ui.argb(Math.round((40 + 60 * h + 80 * pulse) * a), Ui.theme().accent()));
            Ui.drawCentered(c, textRenderer, name, bx + bw / 2, ry + 8, listen ? Ui.theme().title() : Ui.VALUE, Math.round(255 * a));
            hits.add(new Hit(bx, ry + 4, bw, 16, "page:key", () -> listening = listen ? null : key));
        }
        Ui.draw(c, textRenderer, "Click a key, then press the new one  ·  Backspace clears  ·  Esc cancels",
                left, y + 22 + bindable.size() * 30, Ui.MUTED, Math.round(255 * Ui.appear(pageMs, 150L, 300.0F)));
    }

    private void drawHud(DrawContext c, int left, int y, int w, int mx, int my, float dt, float slide) {
        float a = Ui.appear(pageMs, 0, 320.0F);
        int ch = 70;
        boolean over = mx >= left && mx < left + w && my >= y && my < y + ch;
        float h = hoverAnim("hud:open", over, dt);
        int yy = y - Math.round(h * 2.0F) + Math.round((1.0F - a) * 10.0F);
        Ui.card(c, left, yy, w, ch, a);
        Ui.outline(c, left, yy, w, ch, Ui.argb(Math.round((60 + 160 * h) * a), Ui.theme().accent()));
        Ui.shimmer(c, textRenderer, "OPEN HUD EDITOR", left + 14, yy + 14, a);
        Ui.draw(c, textRenderer, "Move and scale the scoreboard, cooldowns, pets, armor and every other HUD element.",
                left + 14, yy + 30, Ui.LABEL, Math.round(255 * a));
        float arrow = DesignModule.animations() ? (float) Math.sin(Util.getMeasuringTimeMs() / 250.0D) * 3.0F * (0.4F + h) : 0.0F;
        Ui.draw(c, textRenderer, "→", left + w - 24 + Math.round(arrow), yy + 30, Ui.theme().accent(), Math.round(255 * a));
        hits.add(new Hit(left, y, w, ch, "page:hud", openHudEditor));

        float a2 = Ui.appear(pageMs, 120L, 320.0F);
        section(c, "ON YOUR SCREEN", left, y + ch + 12, a2);
        String[] names = {"Scoreboard", "Pets & Trinkets", "Cooldowns & Satchels", "Armor", "Notifications"};
        for (int i = 0; i < names.length; i++) {
            float ai = Ui.appear(pageMs, 160L + 50L * i, 300.0F);
            int chipW = Ui.width(textRenderer, names[i]) + 14;
            int cx = left + chipX(names, i);
            int cy = y + ch + 28 + Math.round((1.0F - ai) * 6.0F);
            c.fill(cx, cy, cx + chipW, cy + 14, Ui.argb(Math.round(70 * ai), Ui.theme().accent()));
            Ui.draw(c, textRenderer, names[i], cx + 7, cy + 3, Ui.VALUE, Math.round(255 * ai));
        }
        if (openClassic != null) {
            String dev = "Developer: classic module GUI";
            int dw = Ui.width(textRenderer, dev);
            int dx = left + w - dw;
            int dy = y + ch + 60;
            boolean o = mx >= dx && mx < dx + dw && my >= dy && my < dy + 10;
            Ui.draw(c, textRenderer, dev, dx, dy, o ? Ui.theme().accent() : Ui.MUTED, 255);
            hits.add(new Hit(dx, dy, dw, 10, "page:dev", openClassic));
        }
    }

    private int chipX(String[] names, int index) {
        int x = 0;
        for (int i = 0; i < index; i++) {
            x += Ui.width(textRenderer, names[i]) + 14 + 5;
        }
        return x;
    }

    // ── Mining + Bandits pages: sub-tabs with every setting of the module ───

    private record Sub(String label, List<String> groups) {
    }

    private static final List<Sub> MINING_SUBS = List.of(
            new Sub("Ores", List.of()),
            new Sub("Movement & View", List.of("Movement", "Route", "Route memory", "Display")),
            new Sub("Safety", List.of("Defence", "Breaks", "Recovery")),
            new Sub("Loot & Items", List.of("Item sorter", "Auto use")));
    private static final List<Sub> BANDIT_SUBS = List.of(
            new Sub("Aim & Recall", List.of("Aim assist (L)", "Recall signal (F)")),
            new Sub("Crosshair & Sight", List.of("Crosshair", "Sight point")),
            new Sub("Effects", List.of("Throw effects")),
            new Sub("Advanced", List.of("Ballistics")));

    private static final List<Sub> TUNNEL_SUBS = List.of(
            new Sub("Look", List.of("Background", "Road", "Player")),
            new Sub("Show", List.of("Show")));

    private void drawTunnel(DrawContext c, int left, int y, int w, int mx, int my, float dt) {
        Module m = core.modules().get("tunnel_vision");
        if (m == null) {
            Ui.drawCentered(c, textRenderer, "Tunnel Vision not available", left + w / 2, y + 40, Ui.LABEL, 255);
            return;
        }
        int sub = subIndex.getOrDefault(Page.TUNNEL, 0);
        int top = y;
        y = drawSubChips(c, Page.TUNNEL, TUNNEL_SUBS, m, left, y, w, mx, my, dt);
        float a = Ui.appear(pageMs, 0, 300.0F);
        Ui.draw(c, textRenderer, "F5 + V switches Tunnel Vision on and off  ·  pictures go to config/theprisons/tunnel", left, y + 1,
                Ui.MUTED, Math.round(255 * a));
        drawSettingsList(c, m, TUNNEL_SUBS.get(sub).groups(), left, y + 14, top, w, w, mx, my, dt);
    }

    private final Map<Page, Integer> subIndex = new java.util.EnumMap<>(Page.class);
    private final Map<String, Float> banditKnobs = new HashMap<>();
    private Settings.@Nullable TextSetting editing;
    private String editBuffer = "";

    private void drawOres(DrawContext c, int left, int y, int w, int mx, int my, float dt) {
        Module macro = core.modules().get("ore_macro");
        int sub = subIndex.getOrDefault(Page.ORES, 0);
        int top = y;
        y = drawSubChips(c, Page.ORES, MINING_SUBS, macro, left, y, w, mx, my, dt);
        if (sub == 0 || macro == null) {
            drawOresPage(c, left, y, w, mx, my, dt);
        } else {
            drawSettingsList(c, macro, MINING_SUBS.get(sub).groups(), left, y, top, w, w, mx, my, dt);
        }
    }

    private void drawBandits(DrawContext c, int left, int y, int w, int mx, int my, float dt) {
        Module m = core.modules().get("spear_helper");
        if (m == null) {
            Ui.drawCentered(c, textRenderer, "Spear Helper not available", left + w / 2, y + 40, Ui.LABEL, 255);
            return;
        }
        int sub = subIndex.getOrDefault(Page.BANDITS, 0);
        int top = y;
        y = drawSubChips(c, Page.BANDITS, BANDIT_SUBS, m, left, y, w, mx, my, dt);
        float a0 = Ui.appear(pageMs, 0, 300.0F);
        boolean crosshairTab = sub == 1;
        if (sub == 0 && m.toggleable()) {
            // Module switch on top of the first tab.
            boolean enabled = m.enabled();
            float t = Ui.approach(banditKnobs.getOrDefault("__on", enabled ? 1.0F : 0.0F), enabled ? 1.0F : 0.0F, dt, 14.0F);
            banditKnobs.put("__on", t);
            Ui.draw(c, textRenderer, "Spear Helper  ·  aim assist on key L", left, y + 2, Ui.theme().title(), Math.round(255 * a0));
            int sx = left + w - 132 - 24;
            c.fill(sx, y, sx + 22, y + 11, Ui.argb(Math.round(255 * a0), Ui.mix(0x3A3F4E, Ui.theme().accent(), t)));
            int kx = sx + 1 + Math.round(t * 11.0F);
            c.fill(kx, y + 1, kx + 9, y + 10, Ui.argb(Math.round(255 * a0), 0xFFFFFF));
            hits.add(new Hit(sx - 2, y - 1, 26, 13, "page:bandit_on", () -> {
                if (m.enabled()) {
                    core.modules().disable(m);
                } else {
                    core.modules().enable(m);
                }
            }));
            y += 17;
        }
        if (crosshairTab) {
            // One-click looks
            String[] looks = {"Shooter", "Minimal", "Sniper", "Spear tip"};
            int bx = left;
            Ui.draw(c, textRenderer, "Looks:", bx, y + 3, Ui.LABEL, Math.round(255 * a0));
            bx += Ui.width(textRenderer, "Looks:") + 8;
            for (String look : looks) {
                int bw = Ui.width(textRenderer, look) + 12;
                boolean over = mx >= bx && mx < bx + bw && my >= y && my < y + 13;
                float h = hoverAnim("look:" + look, over, dt);
                c.fill(bx, y, bx + bw, y + 13, Ui.argb(Math.round((40 + 70 * h) * a0), Ui.theme().accent()));
                Ui.drawCentered(c, textRenderer, look, bx + bw / 2, y + 3, Ui.VALUE, Math.round(255 * a0));
                hits.add(new Hit(bx, y, bw, 13, "page:look", () -> applyLook(m, look)));
                bx += bw + 5;
            }
            y += 17;
        }
        drawSettingsList(c, m, BANDIT_SUBS.get(sub).groups(), left, y, top, w, w - 132, mx, my, dt);
        if (crosshairTab) {
            int px = left + w - 124;
            float a1 = Ui.appear(pageMs, 120L, 300.0F);
            section(c, "PREVIEW", px, top, a1);
            Ui.card(c, px, top + 14, 124, 74, a1);
            com.freelocs.theprisons.modules.qol.bandit.SpearHelperModule.preview(c, px + 31, top + 51);
            com.freelocs.theprisons.modules.qol.bandit.SpearHelperModule.previewSight(c, px + 93, top + 51);
            Ui.draw(c, textRenderer, "Crosshair", px + 12, top + 78, Ui.MUTED, Math.round(255 * a1));
            Ui.draw(c, textRenderer, "Sight point", px + 66, top + 78, Ui.MUTED, Math.round(255 * a1));
        } else if (sub == 0) {
            int px = left + w - 124;
            float a1 = Ui.appear(pageMs, 120L, 300.0F);
            section(c, "HOW IT WORKS", px, top + 20, a1);
            String[] lines = {"L starts / stops", "the aim assist:", "the mod looks at", "the best line of", "bandits (mouse is", "locked meanwhile).", "", "F is yours: the", "timer says when."};
            for (int i = 0; i < lines.length; i++) {
                Ui.draw(c, textRenderer, lines[i], px, top + 36 + i * 10, Ui.MUTED, Math.round(255 * a1));
            }
        }
    }

    /** The sub-tab chips and a "reset section" button; returns the y below them. */
    private int drawSubChips(DrawContext c, Page page, List<Sub> subs, @Nullable Module module, int left, int y, int w, int mx, int my, float dt) {
        float a = Ui.appear(pageMs, 0, 300.0F);
        int sub = subIndex.getOrDefault(page, 0);
        int x = left;
        for (int i = 0; i < subs.size(); i++) {
            String label = subs.get(i).label();
            int bw = Ui.width(textRenderer, label) + 14;
            boolean over = mx >= x && mx < x + bw && my >= y && my < y + 14;
            float h = hoverAnim("sub:" + page + i, over, dt);
            boolean on = i == sub;
            c.fill(x, y, x + bw, y + 14, Ui.argb(Math.round((on ? 120 : 26 + 40 * h) * a), Ui.theme().accent()));
            if (on) {
                c.fill(x, y + 13, x + bw, y + 14, Ui.argb(Math.round(255 * a), Ui.theme().title()));
            }
            Ui.drawCentered(c, textRenderer, label, x + bw / 2, y + 3, on ? Ui.theme().title() : Ui.mix(Ui.LABEL, Ui.VALUE, h), Math.round(255 * a));
            int index = i;
            hits.add(new Hit(x, y, bw, 14, "page:sub", () -> selectSub(page, index)));
            x += bw + 4;
        }
        if (module != null && !subs.get(sub).groups().isEmpty()) {
            List<String> groups = subs.get(sub).groups();
            textButton(c, "Reset section", left + w, y + 1, mx, my, dt, a, () -> {
                for (Setting<?> setting : module.settings()) {
                    if (groups.contains(setting.group()) && setting != module.keybind() && setting.persistent()
                            && !(setting instanceof Settings.ActionSetting)) {
                        setting.reset();
                    }
                }
            });
        }
        return y + 20;
    }

    private void selectSub(Page page, int index) {
        commitEdit();
        subIndex.put(page, index);
        scroll = 0;
        scrollTarget = 0;
        pageMs = Util.getMeasuringTimeMs();
    }

    /** Every setting of the module in these groups as a scrollable list of rows. */
    private void drawSettingsList(DrawContext c, Module m, List<String> groups, int left, int y, int top, int w, int listW,
                                  int mx, int my, float dt) {
        int rowH = 17;
        int total = 0;
        String group = "";
        int view = contentHeight - (y - top);
        int yy = y - Math.round(scroll);
        int i = 0;
        for (Setting<?> setting : m.settings()) {
            if (setting == m.keybind() || !setting.visible() || !groups.contains(setting.group()) || unsupported(setting)) {
                continue;
            }
            float a = Ui.appear(pageMs, 20L * Math.min(i, 12), 300.0F);
            if (!setting.group().equals(group)) {
                group = setting.group();
                section(c, group.toUpperCase(java.util.Locale.ROOT), left, yy + 2, a);
                yy += 15;
                total += 15;
            }
            boolean shown = yy >= y - 2 && yy < y + view - 10;
            drawSettingRow(c, setting, left, yy, listW, mx, my, dt, a, shown);
            yy += rowH;
            total += rowH;
            i++;
        }
        scrollMax = Math.max(0, total - view + 4);
        scrollTarget = MathHelper.clamp(scrollTarget, 0, scrollMax);
        scroll = Ui.approach(scroll, scrollTarget, dt, 14.0F);
    }

    private static boolean unsupported(Setting<?> s) {
        return s instanceof Settings.MultiChoiceSetting || s instanceof Settings.KeybindSetting;
    }

    private void drawSettingRow(DrawContext c, Setting<?> setting, int left, int y, int w, int mx, int my, float dt, float a, boolean shown) {
        int alpha = Math.round(255 * a);
        Ui.draw(c, textRenderer, setting.name(), left, y + 2, Ui.VALUE, alpha);
        int right = left + w;
        if (!setting.description().isEmpty() && shown && mx >= left && mx < left + Math.min(w / 2, Ui.width(textRenderer, setting.name())) && my >= y && my < y + 13) {
            hoverTip = setting.description();
        }
        if (setting instanceof Settings.BoolSetting b) {
            float t = banditKnobs.getOrDefault(b.id(), b.on() ? 1.0F : 0.0F);
            t = Ui.approach(t, b.on() ? 1.0F : 0.0F, dt, 14.0F);
            banditKnobs.put(b.id(), t);
            int sx = right - 24;
            c.fill(sx, y, sx + 22, y + 11, Ui.argb(alpha, Ui.mix(0x3A3F4E, Ui.theme().accent(), t)));
            int kx = sx + 1 + Math.round(t * 11.0F);
            c.fill(kx, y + 1, kx + 9, y + 10, Ui.argb(alpha, 0xFFFFFF));
            if (shown) {
                hits.add(new Hit(left, y - 1, w, 13, "page:b_" + b.id(), b::toggle));
            }
        } else if (setting instanceof Settings.IntSetting n) {
            int sw = Math.min(110, w / 3);
            int sx = right - sw - 52;
            sliderRaw(c, sx, y, sw, n.min(), n.max(), n.value(), alpha, shown, Math.max(1, n.step()), v -> n.set((int) Math.round(v)));
            Ui.drawRight(c, textRenderer, n.display(), right, y + 2, Ui.theme().title(), alpha);
        } else if (setting instanceof Settings.DoubleSetting d) {
            int sw = Math.min(110, w / 3);
            int sx = right - sw - 52;
            sliderRaw(c, sx, y, sw, d.min(), d.max(), d.value(), alpha, shown, d.step(), d::set);
            Ui.drawRight(c, textRenderer, d.display(), right, y + 2, Ui.theme().title(), alpha);
        } else if (setting instanceof Settings.EnumSetting<?> e) {
            enumButton(c, e, right, y, mx, my, dt, alpha, shown);
        } else if (setting instanceof Settings.ChoiceSetting choice) {
            String label = choice.display();
            int bw = Math.min(170, Ui.width(textRenderer, label) + 16);
            int bx = right - bw;
            boolean over = mx >= bx && mx < right && my >= y && my < y + 13;
            float hh = hoverAnim("choice:" + choice.id(), over, dt);
            c.fill(bx, y, right, y + 13, Ui.argb(Math.round((40 + 70 * hh) * a), Ui.theme().accent()));
            String text = label;
            while (text.length() > 3 && Ui.width(textRenderer, text) > bw - 10) {
                text = text.substring(0, text.length() - 1);
            }
            Ui.drawCentered(c, textRenderer, text, bx + bw / 2, y + 3, Ui.VALUE, alpha);
            if (shown) {
                hits.add(new Hit(bx, y, bw, 13, "page:ch_" + choice.id(), () -> {
                    List<String> ids = new ArrayList<>();
                    ids.add("");
                    choice.options().forEach(o -> ids.add(o.id()));
                    choice.set(ids.get((Math.max(0, ids.indexOf(choice.get())) + 1) % ids.size()));
                }));
            }
        } else if (setting instanceof Settings.ActionSetting action) {
            int bw = Ui.width(textRenderer, action.label()) + 16;
            int bx = right - bw;
            boolean over = mx >= bx && mx < right && my >= y && my < y + 13;
            float hh = hoverAnim("action:" + action.id(), over, dt);
            c.fill(bx, y, right, y + 13, Ui.argb(Math.round((70 + 90 * hh) * a), Ui.theme().accent()));
            Ui.drawCentered(c, textRenderer, action.label(), bx + bw / 2, y + 3, Ui.VALUE, alpha);
            if (shown) {
                hits.add(new Hit(bx, y, bw, 13, "page:act_" + action.id(), action::run));
            }
        } else if (setting instanceof Settings.TextSetting t) {
            int bw = Math.min(150, w / 2);
            int bx = right - bw;
            boolean edit = editing == t;
            c.fill(bx, y - 1, right, y + 12, Ui.argb(Math.round((edit ? 90 : 40) * a), edit ? Ui.theme().accent() : 0xFFFFFF));
            String text = edit ? editBuffer + ((Util.getMeasuringTimeMs() / 450) % 2 == 0 ? "_" : "") : t.get();
            while (text.length() > 1 && Ui.width(textRenderer, text) > bw - 8) {
                text = text.substring(1);
            }
            Ui.draw(c, textRenderer, text, bx + 4, y + 2, edit ? Ui.theme().title() : Ui.VALUE, alpha);
            if (shown) {
                hits.add(new Hit(bx, y - 1, bw, 13, "page:t_" + t.id(), () -> {
                    commitEdit();
                    editing = t;
                    editBuffer = t.get();
                }));
            }
        } else if (setting instanceof Settings.ColorSetting col) {
            int sz = 9;
            int x = right - Settings.ColorSetting.PALETTE.length * (sz + 2) - 14;
            for (int k = 0; k < Settings.ColorSetting.PALETTE.length; k++) {
                int pc = Settings.ColorSetting.PALETTE[k];
                int cx = x + k * (sz + 2);
                c.fill(cx, y + 1, cx + sz, y + 1 + sz, Ui.argb(alpha, pc & 0xFFFFFF));
                if ((col.get() & 0xFFFFFF) == (pc & 0xFFFFFF)) {
                    Ui.outline(c, cx - 1, y, sz + 2, sz + 2, Ui.argb(alpha, 0xFFFFFF));
                }
                if (shown) {
                    hits.add(new Hit(cx, y, sz + 1, sz + 2, "page:c_" + col.id() + k, () -> col.set(pc)));
                }
            }
            int cur = right - 11;
            c.fill(cur, y + 1, cur + 11, y + 1 + sz, Ui.argb(alpha, col.get() & 0xFFFFFF));
            Ui.outline(c, cur - 1, y, 13, sz + 2, Ui.argb(alpha, 0xFFFFFF));
        }
    }

    private @Nullable String hoverTip;

    private <E extends Enum<E>> void enumButton(DrawContext c, Settings.EnumSetting<E> e, int right, int y, int mx, int my, float dt, int alpha, boolean shown) {
        String label = e.display();
        int bw = Ui.width(textRenderer, label) + 16;
        int bx = right - bw;
        boolean over = mx >= bx && mx < right && my >= y && my < y + 13;
        float h = hoverAnim("enum:" + e.id(), over, dt);
        c.fill(bx, y, right, y + 13, Ui.argb(Math.round((40 + 70 * h) * alpha / 255.0F), Ui.theme().accent()));
        Ui.drawCentered(c, textRenderer, label, bx + bw / 2, y + 3, Ui.VALUE, alpha);
        if (shown) {
            hits.add(new Hit(bx, y, bw, 13, "page:e_" + e.id(), () -> {
                java.util.List<E> options = e.options();
                e.set(options.get((options.indexOf(e.get()) + 1) % options.size()));
            }));
        }
    }

    private void sliderRaw(DrawContext c, int x, int y, int w, double min, double max, double value, int alpha,
                           boolean shown, double step, java.util.function.DoubleConsumer apply) {
        float f = (float) ((value - min) / (max - min));
        c.fill(x, y + 4, x + w, y + 6, Ui.argb(Math.round(60 * alpha / 255.0F), 0xFFFFFF));
        c.fillGradient(x, y + 4, x + Math.round(w * f), y + 6, Ui.argb(alpha, Ui.theme().accent()), Ui.argb(alpha, Ui.theme().title()));
        int kx = x + Math.round(w * f);
        c.fill(kx - 3, y + 2, kx + 3, y + 8, Ui.argb(alpha, 0xFFFFFF));
        if (shown) {
            Slider s = new Slider(x, w, min, max, step, apply);
            hits.add(new Hit(x - 4, y - 3, w + 8, 16, "page:slider", () -> dragging = s));
        }
    }

    private void commitEdit() {
        Settings.TextSetting t = editing;
        if (t != null) {
            t.set(editBuffer);
            editing = null;
        }
    }

    /** One-click crosshair looks: several settings at once. */
    private void applyLook(Module m, String look) {
        record Look(String preset, int size, int gap, int thick, boolean dot, int color) {
        }
        Look l = switch (look) {
            case "Minimal" -> new Look("DOT", 2, 0, 2, true, 0xFFFFFF);
            case "Sniper" -> new Look("SNIPER", 4, 4, 1, false, 0xFF4D4D);
            case "Spear tip" -> new Look("SPEAR", 6, 2, 1, false, 0xFFD34D);
            default -> new Look("SHOOTER", 5, 3, 1, true, 0x00FFD0);
        };
        setEnum(m, "preset", l.preset());
        if (m.setting("crosshair_size") instanceof Settings.IntSetting n) {
            n.set(l.size());
        }
        if (m.setting("crosshair_gap") instanceof Settings.IntSetting n) {
            n.set(l.gap());
        }
        if (m.setting("crosshair_thick") instanceof Settings.IntSetting n) {
            n.set(l.thick());
        }
        if (m.setting("crosshair_dot") instanceof Settings.BoolSetting b) {
            b.set(l.dot());
        }
        if (m.setting("crosshair_color") instanceof Settings.ColorSetting col) {
            col.set(0xFF000000 | l.color());
        }
    }

    private static <E extends Enum<E>> void setEnum(Module m, String id, String name) {
        if (m.setting(id) instanceof Settings.EnumSetting<?> e) {
            for (Object o : e.options()) {
                if (((Enum<?>) o).name().equals(name)) {
                    @SuppressWarnings("unchecked")
                    Settings.EnumSetting<E> typed = (Settings.EnumSetting<E>) e;
                    typed.set((E) o);
                }
            }
        }
    }

    // ── widgets ──────────────────────────────────────────────────────────────

    private void section(DrawContext c, String title, int x, int y, float a) {
        Ui.draw(c, textRenderer, title, x, y, Ui.theme().title(), Math.round(255 * a));
        Ui.line(c, x + Ui.width(textRenderer, title) + 6, x + Math.max(60, Ui.width(textRenderer, title) + 60), y + 4, 0.6F * a);
    }

    private void slider(DrawContext c, int x, int y, int w, Settings.IntSetting setting, float shown, int mx, int my, float a) {
        float min = setting.min();
        float max = setting.max();
        float f = (shown - min) / (max - min);
        c.fill(x, y + 3, x + w, y + 5, Ui.argb(Math.round(60 * a), 0xFFFFFF));
        c.fillGradient(x, y + 3, x + Math.round(w * f), y + 5, Ui.argb(Math.round(255 * a), Ui.theme().accent()),
                Ui.argb(Math.round(255 * a), Ui.theme().title()));
        int kx = x + Math.round(w * f);
        boolean over = mx >= kx - 5 && mx < kx + 5 && my >= y - 2 && my < y + 10;
        int r = over || dragging != null ? 4 : 3;
        c.fill(kx - r, y + 4 - r, kx + r, y + 4 + r, Ui.argb(Math.round(255 * a), 0xFFFFFF));
        Slider s = new Slider(x, w, setting.min(), setting.max(), Math.max(1, setting.step()), v -> setting.set((int) Math.round(v)));
        hits.add(new Hit(x - 4, y - 4, w + 8, 16, "page:slider", () -> {
            dragging = s;
        }));
    }

    private float toggle(DrawContext c, int x, int y, String label, Settings.BoolSetting setting, float knob, int mx, int my,
                         float dt, float a) {
        float target = setting.on() ? 1.0F : 0.0F;
        knob = knob < 0 ? target : Ui.approach(knob, target, dt, 14.0F);
        Ui.draw(c, textRenderer, label, x, y + 2, Ui.VALUE, Math.round(255 * a));
        int sx = x + 120;
        int track = Ui.mix(0x3A3F4E, Ui.theme().accent(), knob);
        c.fill(sx, y, sx + 22, y + 11, Ui.argb(Math.round(255 * a), track));
        int kx = sx + 1 + Math.round(knob * 11.0F);
        c.fill(kx, y + 1, kx + 9, y + 10, Ui.argb(Math.round(255 * a), 0xFFFFFF));
        hits.add(new Hit(x, y - 2, 145, 15, "page:toggle", () -> setting.set(!setting.on())));
        return knob;
    }

    private float hoverAnim(String id, boolean over, float dt) {
        float h = Ui.approach(hover.getOrDefault(id, 0.0F), over ? 1.0F : 0.0F, dt, 12.0F);
        hover.put(id, h);
        return h;
    }

    private List<String> wrap(String text, int maxWidth, int maxLines) {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            String next = line.isEmpty() ? word : line + " " + word;
            if (Ui.width(textRenderer, next) > maxWidth && !line.isEmpty()) {
                lines.add(line.toString());
                line = new StringBuilder(word);
                if (lines.size() == maxLines) {
                    break;
                }
            } else {
                line = new StringBuilder(next);
            }
        }
        if (lines.size() < maxLines && !line.isEmpty()) {
            lines.add(line.toString());
        } else if (lines.size() == maxLines) {
            String last = lines.get(maxLines - 1);
            while (last.length() > 1 && Ui.width(textRenderer, last + "…") > maxWidth) {
                last = last.substring(0, last.length() - 1);
            }
            lines.set(maxLines - 1, last + "…");
        }
        return lines;
    }

    private static String keyName(int key) {
        return InputUtil.Type.KEYSYM.createFromCode(key).getLocalizedText().getString();
    }

    // ── input ────────────────────────────────────────────────────────────────

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        if (click.button() == 0) {
            for (Hit hit : List.copyOf(hits)) {
                if (hit.contains(click.x(), click.y())) {
                    hit.action().run();
                    if (dragging != null) {
                        dragSlider(click.x());
                    }
                    return true;
                }
            }
        }
        return super.mouseClicked(click, doubled);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (page == Page.OVERVIEW || page == Page.BANDITS || page == Page.TUNNEL || (page == Page.ORES && subIndex.getOrDefault(Page.ORES, 0) != 0)) {
            scrollTarget = MathHelper.clamp(scrollTarget - (float) verticalAmount * 24.0F, 0, scrollMax);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    @Override
    public boolean mouseDragged(Click click, double offsetX, double offsetY) {
        if (dragging != null) {
            dragSlider(click.x());
            return true;
        }
        return super.mouseDragged(click, offsetX, offsetY);
    }

    @Override
    public boolean mouseReleased(Click click) {
        dragging = null;
        return super.mouseReleased(click);
    }

    private void dragSlider(double mouseX) {
        Slider s = dragging;
        if (s == null) {
            return;
        }
        double f = MathHelper.clamp((mouseX - s.x()) / s.w(), 0.0D, 1.0D);
        double raw = s.min() + f * (s.max() - s.min());
        s.apply().accept(Math.round(raw / s.step()) * s.step());
    }

    @Override
    public boolean keyPressed(KeyInput input) {
        if (editing != null) {
            if (input.key() == GLFW.GLFW_KEY_ENTER || input.key() == GLFW.GLFW_KEY_KP_ENTER) {
                commitEdit();
            } else if (input.key() == GLFW.GLFW_KEY_ESCAPE) {
                editing = null;
            } else if (input.key() == GLFW.GLFW_KEY_BACKSPACE && !editBuffer.isEmpty()) {
                editBuffer = editBuffer.substring(0, editBuffer.length() - 1);
            }
            return true;
        }
        Settings.KeybindSetting key = listening;
        if (key != null) {
            if (input.key() == GLFW.GLFW_KEY_BACKSPACE) {
                key.set(Settings.KeybindSetting.NONE);
            } else if (input.key() != GLFW.GLFW_KEY_ESCAPE) {
                key.set(input.key());
            }
            listening = null;
            return true;
        }
        if (input.key() == GLFW.GLFW_KEY_TAB) {
            Page[] pages = Page.values();
            switchPage(pages[(page.ordinal() + 1) % pages.length]);
            return true;
        }
        return super.keyPressed(input);
    }

    @Override
    public boolean charTyped(CharInput input) {
        Settings.TextSetting text = editing;
        if (text != null && input.isValidChar()) {
            if (editBuffer.length() < text.maxLength()) {
                editBuffer += input.asString();
            }
            return true;
        }
        return super.charTyped(input);
    }

    @Override
    public void close() {
        commitEdit();
        core.config().markDirty();
        client.setScreen(parent);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
