package com.freelocs.theprisons.gui.kit;

import com.freelocs.theprisons.gui.theme.Theme;
import com.freelocs.theprisons.modules.general.DesignModule;
import com.freelocs.theprisons.core.module.Module;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Style;
import net.minecraft.text.StyleSpriteSource;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.Util;
import net.minecraft.util.math.MathHelper;

/**
 * Shared drawing helpers of the new design (dashboard, HUD editor, scoreboard, HUD widgets): cards, accent lines,
 * the mod font (Boxy), colour maths and easing.
 */
public final class Ui {
    public static final Identifier FONT = Identifier.of("theprisons", "boxy");
    public static final int LABEL = 0x9AA3B5;
    public static final int MUTED = 0x6B7385;
    public static final int VALUE = 0xF2F4F8;
    public static final int GOOD = 0x7CF0A0;
    public static final int WARN = 0xFFE066;
    public static final int BAD = 0xFF5E6C;

    /** Modules with an own menu icon (tools/textures/icons.py); the others show their category's icon. */
    private static final java.util.Set<String> MODULE_ICONS = java.util.Set.of("storage_overlay", "item_look", "scoreboard",
            "better_tab", "session_hud", "pet_hud", "command_cooldowns", "satchel_hud", "armor_hud", "item_insights",
            "sneak_trade", "player_cards", "message_notifications", "peaceful_mining", "vitals_warnings", "ready_announcements",
            "cooldown_cache", "update_checker", "ore_macro", "waypoint_editor");

    private Ui() {
    }

    /** A menu icon (GUI sprite theprisons:icon/{@code name}), {@code size} px square. */
    public static void icon(DrawContext c, String name, int x, int y, int size, float alpha) {
        int a = MathHelper.clamp(Math.round(255 * alpha), 0, 255);
        if (a < 8) {
            return;
        }
        c.drawGuiTexture(RenderPipelines.GUI_TEXTURED, Identifier.of("theprisons", "icon/" + name), x, y, size, size,
                (a << 24) | 0xFFFFFF);
    }

    /**
     * A GUI sprite of the mod ({@code theprisons:<path>}, e.g. "market/panel_body"), stretched to {@code w} x {@code h}
     * (nine-slice where the sprite has a .mcmeta) and tinted with {@code argb}.
     */
    public static void sprite(DrawContext c, String path, int x, int y, int w, int h, int argb) {
        if ((argb >>> 24) < 4) {
            return;
        }
        c.drawGuiTexture(RenderPipelines.GUI_TEXTURED, Identifier.of("theprisons", path), x, y, w, h, argb);
    }

    /** The icon name of a module: its own one, else its category's. */
    public static String iconOf(Module module) {
        return MODULE_ICONS.contains(module.id()) ? module.id()
                : "category_" + module.category().name().toLowerCase(java.util.Locale.ROOT);
    }

    public static Theme theme() {
        return DesignModule.theme();
    }

    public static Text text(String s) {
        Text t = Text.literal(s);
        return DesignModule.sleekFont() ? Text.literal(s).setStyle(Style.EMPTY.withFont(new StyleSpriteSource.Font(FONT))) : t;
    }

    public static int width(TextRenderer tr, String s) {
        return tr.getWidth(text(s));
    }

    public static void draw(DrawContext c, TextRenderer tr, String s, int x, int y, int rgb, int alpha) {
        c.drawText(tr, text(s), x, y, argb(Math.max(8, alpha), rgb), false);
    }

    public static void drawShadow(DrawContext c, TextRenderer tr, String s, int x, int y, int rgb, int alpha) {
        c.drawText(tr, text(s), x, y, argb(Math.max(8, alpha), rgb), true);
    }

    public static void drawRight(DrawContext c, TextRenderer tr, String s, int right, int y, int rgb, int alpha) {
        draw(c, tr, s, right - width(tr, s), y, rgb, alpha);
    }

    public static void drawCentered(DrawContext c, TextRenderer tr, String s, int cx, int y, int rgb, int alpha) {
        draw(c, tr, s, cx - width(tr, s) / 2, y, rgb, alpha);
    }

    /** A card: black at the design's darkness, optional accent outline. */
    public static void card(DrawContext c, int x, int y, int w, int h, float alpha) {
        c.fill(x, y, x + w, y + h, Math.round(DesignModule.cardAlpha() * alpha) << 24);
    }

    /** A filled rectangle with rounded corners (radius 2-3 px, pixel style). */
    public static void round(DrawContext c, int x, int y, int w, int h, int argb) {
        c.fill(x + 3, y, x + w - 3, y + h, argb);
        c.fill(x + 1, y + 1, x + 3, y + h - 1, argb);
        c.fill(x + w - 3, y + 1, x + w - 1, y + h - 1, argb);
        c.fill(x, y + 3, x + 1, y + h - 3, argb);
        c.fill(x + w - 1, y + 3, x + w, y + h - 3, argb);
    }

    /** A rounded card with a soft drop shadow below / right. */
    public static void shadowCard(DrawContext c, int x, int y, int w, int h, float alpha) {
        round(c, x + 3, y + 4, w, h, argb(Math.round(28 * alpha), 0x000000));
        round(c, x + 1, y + 2, w, h, argb(Math.round(55 * alpha), 0x000000));
        round(c, x, y, w, h, argb(Math.round(DesignModule.cardAlpha() * alpha), 0x0A0A12));
    }

    public static void outline(DrawContext c, int x, int y, int w, int h, int argb) {
        c.fill(x, y, x + w, y + 1, argb);
        c.fill(x, y + h - 1, x + w, y + h, argb);
        c.fill(x, y + 1, x + 1, y + h - 1, argb);
        c.fill(x + w - 1, y + 1, x + w, y + h - 1, argb);
    }

    /** The accent separator line. */
    public static void line(DrawContext c, int x0, int x1, int y, float alpha) {
        c.fill(x0, y, x1, y + 1, argb(Math.round(200 * alpha), theme().accent()));
    }

    /** A line whose colour runs from the accent to the title colour and moves slowly (animated). */
    public static void flowLine(DrawContext c, int x0, int x1, int y, float alpha) {
        if (!DesignModule.animations()) {
            line(c, x0, x1, y, alpha);
            return;
        }
        int a = theme().accent();
        int b = theme().title();
        double t = Util.getMeasuringTimeMs() / 1400.0D;
        for (int x = x0; x < x1; x += 2) {
            double f = 0.5D + 0.5D * Math.sin(t + (x - x0) * 0.045D);
            c.fill(x, y, Math.min(x1, x + 2), y + 1, argb(Math.round(220 * alpha), mix(a, b, (float) f)));
        }
    }

    /** Title text with a light sweeping across (animated). */
    public static void shimmer(DrawContext c, TextRenderer tr, String s, int x, int y, float alpha) {
        int base = theme().title();
        if (!DesignModule.animations()) {
            draw(c, tr, s, x, y, base, Math.round(255 * alpha));
            return;
        }
        double sweep = (Util.getMeasuringTimeMs() % 2600L) / 2600.0D * (s.length() + 8) - 4;
        int cx = x;
        for (int i = 0; i < s.length(); i++) {
            String ch = s.substring(i, i + 1);
            double d = Math.abs(i - sweep);
            float glow = (float) MathHelper.clamp(1.0D - d / 3.0D, 0.0D, 1.0D);
            draw(c, tr, ch, cx, y, mix(base, 0xFFFFFF, glow * 0.85F), Math.round(255 * alpha));
            cx += width(tr, ch);
        }
    }

    public static boolean animationsOn() {
        return DesignModule.animations();
    }

    public static float easeOut(float t) {
        t = MathHelper.clamp(t, 0.0F, 1.0F);
        return 1.0F - (1.0F - t) * (1.0F - t) * (1.0F - t);
    }

    /** Appear progress of an element shown {@code delayMs} after {@code startMs}, eased (1 when animations are off). */
    public static float appear(long startMs, long delayMs, float durationMs) {
        if (!DesignModule.animations()) {
            return 1.0F;
        }
        return easeOut((Util.getMeasuringTimeMs() - startMs - delayMs) / durationMs);
    }

    /** Moves {@code value} towards {@code target}; frame-rate independent. */
    public static float approach(float value, float target, float dt, float speed) {
        if (!DesignModule.animations()) {
            return target;
        }
        return value + (target - value) * Math.min(1.0F, dt * speed);
    }

    public static float pulse(long periodMs) {
        if (!DesignModule.animations()) {
            return 1.0F;
        }
        return 0.5F + 0.5F * MathHelper.sin((float) (Util.getMeasuringTimeMs() % periodMs) / periodMs * MathHelper.TAU);
    }

    public static int argb(int alpha, int rgb) {
        return (MathHelper.clamp(alpha, 0, 255) << 24) | (rgb & 0xFFFFFF);
    }

    public static int mix(int a, int b, float t) {
        t = MathHelper.clamp(t, 0.0F, 1.0F);
        int r = Math.round(((a >> 16) & 0xFF) * (1 - t) + ((b >> 16) & 0xFF) * t);
        int g = Math.round(((a >> 8) & 0xFF) * (1 - t) + ((b >> 8) & 0xFF) * t);
        int bl = Math.round((a & 0xFF) * (1 - t) + (b & 0xFF) * t);
        return (r << 16) | (g << 8) | bl;
    }
}
