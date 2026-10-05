package io.theprisons.modules.general;

import io.theprisons.core.i18n.I18n;
import io.theprisons.core.module.Category;
import io.theprisons.core.module.Module;
import io.theprisons.core.setting.Settings;
import org.lwjgl.glfw.GLFW;

/** The keybind that opens the module GUI, plus its display options, the language and the welcome setup state. */
public final class ClickGuiModule extends Module {
    private final Runnable open;
    private final Settings.BoolSetting blur;
    private final Settings.BoolSetting descriptions;
    private final Settings.EnumSetting<I18n.Lang> language;

    public ClickGuiModule(Runnable open) {
        super("click_gui", "Click GUI", Category.GENERAL, "Interface",
                "The module menu. Also opens with /prisons.", GLFW.GLFW_KEY_RIGHT_SHIFT);
        this.open = open;
        language = choice("language", "Language", I18n.Lang.EN, I18n.Lang::label)
                .description("Language of the menu, the welcome setup and the mod's chat messages. Switches live.")
                .group("Display");
        language.onChange(I18n::setLang);
        blur = bool("dim_background", "Dim background", true).group("Display");
        descriptions = bool("descriptions", "Show descriptions", true).description("Setting descriptions under each control.").group("Display");
    }

    @Override
    public boolean toggleable() {
        return false;
    }

    @Override
    public boolean onKeybind() {
        open.run();
        return true;
    }

    public boolean dimBackground() {
        return blur.on();
    }

    public boolean showDescriptions() {
        return descriptions.on();
    }

    public I18n.Lang language() {
        return language.get();
    }

    /** Switches the language (live) and saves it. */
    public void setLanguage(I18n.Lang lang) {
        language.set(lang);
        I18n.setLang(lang);
    }

}
