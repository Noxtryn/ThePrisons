package com.freelocs.theprisons.core.setup;

import com.freelocs.theprisons.core.i18n.I18n;
import com.freelocs.theprisons.core.module.Module;
import com.freelocs.theprisons.core.setting.Setting;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * A macro starts only when it is set up: the welcome setup was finished once and every required setting of the module
 * is filled in. Otherwise the player gets (only in their own chat) what is missing, each with a link straight to the
 * place where it is set.
 */
public final class SetupGate {
    public static final String OPEN_COMMAND = "/prisons open";
    public static final String SETUP_COMMAND = "/prisons setup";

    private static BooleanSupplier welcomeDone = () -> true;

    private SetupGate() {
    }

    public static void setWelcomeDone(BooleanSupplier done) {
        welcomeDone = done;
    }

    public static boolean welcomeDone() {
        return welcomeDone.getAsBoolean();
    }

    /** @return null when the module may start, else the (translated) reason; the details went to chat. */
    public static @Nullable String check(Module module) {
        boolean welcome = welcomeDone();
        List<Setting<?>> missing = module.missing();
        if (welcome && missing.isEmpty()) {
            return null;
        }
        ModChat.show(ModChat.header("Setup needed before the start"));
        if (!welcome) {
            ModChat.show(ModChat.line(I18n.t("Go through the welcome setup once (2 minutes)."))
                    .append(ModChat.link("Start setup", SETUP_COMMAND, "Opens the welcome setup")));
        }
        for (Setting<?> setting : missing) {
            String problem = setting.problem();
            ModChat.show(ModChat.line(I18n.t(setting.name()) + ": " + I18n.t(problem == null ? "" : problem))
                    .append(ModChat.link("Set now", OPEN_COMMAND + " " + module.id() + " " + setting.id(),
                            "Opens the settings right at this point")));
        }
        return I18n.t("Setup incomplete - see chat.");
    }
}
