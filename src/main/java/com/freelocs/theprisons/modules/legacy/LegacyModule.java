package com.freelocs.theprisons.modules.legacy;

import com.freelocs.theprisons.core.module.Category;
import com.freelocs.theprisons.core.module.Module;
import com.freelocs.theprisons.core.setting.Settings;
import org.jspecify.annotations.Nullable;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Adapter that shows a v1 feature in the module manager / GUI until it is migrated. The feature's code is unchanged
 * and keeps checking its own config flag; enabling / disabling the module flips that flag, and the module's settings
 * are bound to the v1 config fields (persisted in {@code theprisons.json}, not in the module config).
 */
public class LegacyModule extends Module {
    private final @Nullable BooleanSupplier flag;
    private final @Nullable Consumer<Boolean> setFlag;
    private final Runnable changed;

    /**
     * @param flag    v1 on / off flag, {@code null} for settings-only entries
     * @param changed marks the configuration dirty (the v1 file is written with the next save)
     */
    public LegacyModule(String id, String name, Category category, String group, String description,
                        @Nullable BooleanSupplier flag, @Nullable Consumer<Boolean> setFlag, Runnable changed) {
        super(id, name, category, group, description, Settings.KeybindSetting.NONE);
        this.flag = flag;
        this.setFlag = setFlag;
        this.changed = changed;
    }

    @Override
    public boolean persistEnabled() {
        return false;
    }

    @Override
    public boolean toggleable() {
        return flag != null;
    }

    @Override
    public boolean initialEnabled(@Nullable Boolean persisted) {
        return flag != null && flag.getAsBoolean();
    }

    @Override
    protected void onEnable() {
        if (setFlag != null && flag != null && !flag.getAsBoolean()) {
            setFlag.accept(true);
            changed.run();
        }
    }

    @Override
    protected void onDisable() {
        if (setFlag != null && flag != null && flag.getAsBoolean()) {
            setFlag.accept(false);
            changed.run();
        }
    }

    // Bound setting helpers: the value lives in the v1 config object.

    protected final Settings.BoolSetting boundBool(String settingId, String label, boolean defaultValue, BooleanSupplier getter, Consumer<Boolean> setter) {
        Settings.BoolSetting setting = bool(settingId, label, defaultValue);
        return setting.bind(getter::getAsBoolean, setter);
    }

    protected final Settings.IntSetting boundInt(String settingId, String label, int defaultValue, int min, int max, int step,
                                                 java.util.function.IntSupplier getter, Consumer<Integer> setter) {
        Settings.IntSetting setting = integer(settingId, label, defaultValue, min, max, step);
        return setting.bind(getter::getAsInt, setter);
    }

    protected final Settings.DoubleSetting boundDecimal(String settingId, String label, double defaultValue, double min, double max, double step,
                                                        java.util.function.DoubleSupplier getter, Consumer<Double> setter) {
        Settings.DoubleSetting setting = decimal(settingId, label, defaultValue, min, max, step);
        return setting.bind(getter::getAsDouble, setter);
    }

    protected final Settings.ColorSetting boundColor(String settingId, String label, int defaultValue, java.util.function.IntSupplier getter, Consumer<Integer> setter) {
        Settings.ColorSetting setting = color(settingId, label, defaultValue);
        return setting.bind(getter::getAsInt, setter);
    }

    protected final Settings.ActionSetting button(String settingId, String label, String buttonLabel, Runnable run) {
        return action(settingId, label, buttonLabel, run);
    }
}
