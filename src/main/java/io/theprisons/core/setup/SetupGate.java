package io.theprisons.core.setup;

import io.theprisons.core.i18n.I18n;
import io.theprisons.core.module.Module;
import io.theprisons.core.setting.Setting;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * A macro starts only when every required setting of the module is filled in (there is no welcome setup any more). Otherwise the player gets (only in their own chat) what is missing, each with a link straight to the
 * place where it is set.
 */
public final class SetupGate {
    public static final String OPEN_COMMAND = "/prisons open";

    private SetupGate() {
    }

    /** @return null when the module may start, else the (translated) reason; the details went to chat. */
    public static @Nullable String check(Module module) {
        List<Setting<?>> missing = module.missing();
        if (missing.isEmpty()) {
            return null;
        }
        ModChat.show(ModChat.header("Setup needed before the start"));
        for (Setting<?> setting : missing) {
            String problem = setting.problem();
            ModChat.show(ModChat.line(I18n.t(setting.name()) + ": " + I18n.t(problem == null ? "" : problem))
                    .append(ModChat.link("Set now", OPEN_COMMAND + " " + module.id() + " " + setting.id(),
                            "Opens the settings right at this point")));
        }
        return I18n.t("Setup incomplete - see chat.");
    }
}
