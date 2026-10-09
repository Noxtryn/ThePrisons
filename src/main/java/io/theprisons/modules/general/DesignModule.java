package io.theprisons.modules.general;

import io.theprisons.core.module.Category;
import io.theprisons.core.module.Module;
import io.theprisons.core.setting.Settings;
import io.theprisons.gui.theme.Theme;
import org.jspecify.annotations.Nullable;

/**
 * The look users may change: colour theme, how dark the cards are, animations and the Boxy font. Read by the
 * dashboard, the storage overlay, the HUD editor and the scoreboard.
 */
public final class DesignModule extends Module {
    private static @Nullable DesignModule instance;

    private final Settings.EnumSetting<Theme> theme;
    private final Settings.IntSetting darkness;
    private final Settings.BoolSetting animations;
    private final Settings.BoolSetting sleekFont;

    public DesignModule() {
        super("design", "Design", Category.GENERAL, "Interface", "Theme, card darkness, animations and font.",
                Settings.KeybindSetting.NONE);
        theme = choice("theme", "Theme", Theme.COSMIC, Theme::label).group("Design");
        darkness = integer("darkness", "Card darkness", 60, 10, 100, 5).suffix("%").group("Design");
        animations = bool("animations", "Animations", true).group("Design");
        sleekFont = bool("sleek_font", "Boxy font", true).group("Design");
        instance = this;
    }

    public static @Nullable DesignModule get() {
        return instance;
    }

    @Override
    public boolean toggleable() {
        return false;
    }

    public static Theme theme() {
        DesignModule d = instance;
        return d != null ? d.theme.get() : Theme.COSMIC;
    }

    /** Card background alpha, 0-255. */
    public static int cardAlpha() {
        DesignModule d = instance;
        return Math.round((d != null ? d.darkness.get() : 60) * 2.55F);
    }

    public static boolean animations() {
        DesignModule d = instance;
        return d == null || d.animations.on();
    }

    public static boolean sleekFont() {
        DesignModule d = instance;
        return d == null || d.sleekFont.on();
    }

    public Settings.EnumSetting<Theme> themeSetting() {
        return theme;
    }

    public Settings.IntSetting darknessSetting() {
        return darkness;
    }

    public Settings.BoolSetting animationsSetting() {
        return animations;
    }

    public Settings.BoolSetting sleekFontSetting() {
        return sleekFont;
    }
}
