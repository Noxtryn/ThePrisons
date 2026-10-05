package com.freelocs.theprisons.gui.dashboard;

import com.freelocs.theprisons.core.ThePrisonsCore;
import com.freelocs.theprisons.core.module.Module;
import com.freelocs.theprisons.core.setting.Settings;
import com.freelocs.theprisons.gui.kit.Ui;
import com.freelocs.theprisons.gui.theme.Theme;
import com.freelocs.theprisons.modules.FeatureProfile;
import com.freelocs.theprisons.modules.general.DesignModule;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.Click;
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
        OVERVIEW("Overview"), DESIGN("Design"), CONTROLS("Controls"), HUD("HUD");

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

    private record Slider(int x, int w, Settings.IntSetting setting) {
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
            case DESIGN -> drawDesign(context, left, contentY, panelW, mouseX - offset, mouseY, dt, slide);
            case CONTROLS -> drawControls(context, left, contentY, panelW, mouseX - offset, mouseY, dt, slide);
            case HUD -> drawHud(context, left, contentY, panelW, mouseX - offset, mouseY, dt, slide);
        }
        context.getMatrices().popMatrix();
        context.disableScissor();
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
        for (int i = 0; i < bindable.size(); i++) {
            Module m = bindable.get(i);
            Settings.KeybindSetting key = m.keybind();
            int ry = y + 16 + i * 30;
            float a = Ui.appear(pageMs, 60L * i, 300.0F);
            boolean over = mx >= left && mx < left + w && my >= ry && my < ry + 24;
            float h = hoverAnim("key:" + m.id(), over, dt);
            Ui.card(c, left, ry - Math.round(h * 1.5F), w, 24, a);
            String label = m.id().equals("click_gui") ? "Open dashboard" : "Open storage overlay (/pv)";
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
        Slider s = new Slider(x, w, setting);
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
        if (page == Page.OVERVIEW) {
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
        Settings.IntSetting setting = s.setting();
        double f = MathHelper.clamp((mouseX - s.x()) / s.w(), 0.0D, 1.0D);
        int raw = (int) Math.round(setting.min() + f * (setting.max() - setting.min()));
        int step = Math.max(1, setting.step());
        setting.set(Math.round(raw / (float) step) * step);
    }

    @Override
    public boolean keyPressed(KeyInput input) {
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
    public void close() {
        core.config().markDirty();
        client.setScreen(parent);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
