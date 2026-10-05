package io.theprisons.core.module;

import io.theprisons.core.concurrent.Worker;
import io.theprisons.core.hud.HudLine;
import io.theprisons.core.profiling.Profiler;
import io.theprisons.core.setting.Setting;
import io.theprisons.core.setting.Settings;
import io.theprisons.core.tick.TickScheduler;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Base class of every feature. A module declares its settings in the constructor and does its work only between
 * {@link #onEnable()} and {@link #onDisable()}: event listeners ({@link #on}), periodic tasks ({@link #every}) and
 * worker jobs ({@link #submit}) registered through the helpers are owned by the module and removed automatically
 * when it is disabled, crashes or is stopped by a failsafe.
 *
 * <p>Services beyond the kernel (world cache, navigation, input ...) are passed to the concrete module's constructor,
 * so every dependency of a module is visible in one place.
 */
public abstract class Module {
    /** GUI status indicator. */
    public enum StatusLevel { OFF, IDLE, ACTIVE, WARNING, ERROR }

    public record Status(StatusLevel level, String text) {
        public static final Status OFF = new Status(StatusLevel.OFF, "Off");
        public static final Status ON = new Status(StatusLevel.ACTIVE, "On");
    }

    private final String id;
    private final String name;
    private final Category category;
    private final String group;
    private final String description;
    private final List<Setting<?>> settings = new ArrayList<>();
    private final Settings.KeybindSetting keybind;
    private @Nullable ModuleHost host;
    private boolean enabled;
    private @Nullable String lastStopReason;
    private Profiler.@Nullable Section section;

    /**
     * @param group      sub-category inside the category (e.g. "Macros", "Widgets")
     * @param defaultKey GLFW key code or {@link Settings.KeybindSetting#NONE}
     */
    protected Module(String id, String name, Category category, String group, String description, int defaultKey) {
        this.id = id;
        this.name = name;
        this.category = category;
        this.group = group;
        this.description = description;
        this.keybind = new Settings.KeybindSetting("keybind", "Keybind", defaultKey)
                .description("Toggles the module. Click, then press a key; Esc / Backspace clears.")
                .group("General");
        settings.add(keybind);
    }

    // ── Lifecycle (overridable) ──────────────────────────────────────────────

    protected void onEnable() {
    }

    protected void onDisable() {
    }

    /** Whether the enabled state survives restarts. Macros return false: they never start on their own. */
    public boolean persistEnabled() {
        return true;
    }

    public boolean enabledByDefault() {
        return false;
    }

    /**
     * Enabled state right after loading. Legacy adapters override this to read their v1 config flag.
     *
     * @param persisted state from the module config, {@code null} when none was saved
     */
    public boolean initialEnabled(@Nullable Boolean persisted) {
        if (!persistEnabled()) {
            return false;
        }
        return persisted != null ? persisted : enabledByDefault();
    }

    /** False for settings-only modules (no on / off switch in the GUI). */
    public boolean toggleable() {
        return true;
    }

    /**
     * Called when the module's keybind is pressed. Return true when handled (e.g. "open a screen"); the default
     * toggles the module.
     */
    public boolean onKeybind() {
        return false;
    }

    /** Refuses to enable with a reason, or {@code null} when the module may start. */
    public @Nullable String canEnable() {
        return null;
    }

    public Status status() {
        return enabled ? Status.ON : Status.OFF;
    }

    /** HUD rows while enabled; called at most every few ticks, never per frame. */
    public void collectHud(List<HudLine> out) {
    }

    // ── Helpers for subclasses (valid while enabled) ─────────────────────────

    protected final ModuleHost host() {
        ModuleHost current = host;
        if (current == null) {
            throw new IllegalStateException("Module " + id + " is not registered");
        }
        return current;
    }

    /** Subscribes for as long as the module is enabled; the handler is profiled as {@code module:<id>}. */
    protected final <E> void on(Class<E> type, Consumer<? super E> handler) {
        on(type, 0, handler);
    }

    protected final <E> void on(Class<E> type, int priority, Consumer<? super E> handler) {
        Profiler.Section profiled = profilerSection();
        host().bus().subscribe(type, this, priority, (E event) -> {
            long start = profiled.begin();
            try {
                handler.accept(event);
            } finally {
                profiled.end(start);
            }
        });
    }

    protected final TickScheduler.Task every(int intervalTicks, String taskName, Runnable task) {
        return host().scheduler().every(this, id + "/" + taskName, intervalTicks, task);
    }

    protected final <T> Worker.Job submit(String key, Function<Worker.Cancel, T> job, Consumer<T> onResult) {
        return host().worker().submit(this, id + "/" + key, job, onResult);
    }

    /** Stops this module with a reason shown to the player. */
    protected final void disableSelf(String reason) {
        host().disable(this, reason);
    }

    protected final void notify(String body, ModuleHost.Level level) {
        host().notify(new ModuleHost.Notice(name, body, level));
    }

    /** Settings that must still be filled in before this module may start (see {@link Setting#required}). */
    public final List<Setting<?>> missing() {
        List<Setting<?>> list = new java.util.ArrayList<>();
        for (Setting<?> setting : settings) {
            if (setting.problem() != null) {
                list.add(setting);
            }
        }
        return list;
    }

    // ── Settings factories ───────────────────────────────────────────────────

    protected final <S extends Setting<?>> S add(S setting) {
        for (Setting<?> existing : settings) {
            if (existing.id().equals(setting.id())) {
                throw new IllegalArgumentException("Duplicate setting id " + setting.id() + " in " + id);
            }
        }
        settings.add(setting);
        return setting;
    }

    protected final Settings.BoolSetting bool(String settingId, String label, boolean defaultValue) {
        return add(new Settings.BoolSetting(settingId, label, defaultValue));
    }

    protected final Settings.IntSetting integer(String settingId, String label, int defaultValue, int min, int max, int step) {
        return add(new Settings.IntSetting(settingId, label, defaultValue, min, max, step));
    }

    protected final Settings.DoubleSetting decimal(String settingId, String label, double defaultValue, double min, double max, double step) {
        return add(new Settings.DoubleSetting(settingId, label, defaultValue, min, max, step));
    }

    protected final <E extends Enum<E>> Settings.EnumSetting<E> choice(String settingId, String label, E defaultValue, Function<E, String> labels) {
        return add(new Settings.EnumSetting<>(settingId, label, defaultValue, labels));
    }

    protected final Settings.MultiChoiceSetting multi(String settingId, String label, Collection<String> defaults, List<Settings.Option> options) {
        return add(new Settings.MultiChoiceSetting(settingId, label, defaults, options));
    }

    protected final Settings.TextSetting text(String settingId, String label, String defaultValue, int maxLength) {
        return add(new Settings.TextSetting(settingId, label, defaultValue, maxLength));
    }

    protected final Settings.ColorSetting color(String settingId, String label, int defaultValue) {
        return add(new Settings.ColorSetting(settingId, label, defaultValue));
    }

    protected final Settings.ActionSetting action(String settingId, String label, String buttonLabel, Runnable run) {
        return add(new Settings.ActionSetting(settingId, label, buttonLabel, run));
    }

    // ── Accessors ────────────────────────────────────────────────────────────

    public final String id() {
        return id;
    }

    public final String name() {
        return name;
    }

    public final Category category() {
        return category;
    }

    public final String group() {
        return group;
    }

    public final String description() {
        return description;
    }

    public final List<Setting<?>> settings() {
        return Collections.unmodifiableList(settings);
    }

    public final @Nullable Setting<?> setting(String settingId) {
        for (Setting<?> setting : settings) {
            if (setting.id().equals(settingId)) {
                return setting;
            }
        }
        return null;
    }

    public final Settings.KeybindSetting keybind() {
        return keybind;
    }

    public final boolean enabled() {
        return enabled;
    }

    /** Why the module last stopped on its own (failsafe, error), or {@code null}. */
    public final @Nullable String lastStopReason() {
        return lastStopReason;
    }

    // ── Manager access ───────────────────────────────────────────────────────

    final void attach(ModuleHost moduleHost) {
        this.host = moduleHost;
    }

    final void setEnabled(boolean value) {
        this.enabled = value;
    }

    final void setLastStopReason(@Nullable String reason) {
        this.lastStopReason = reason;
    }

    final Profiler.Section profilerSection() {
        Profiler.Section current = section;
        if (current == null) {
            current = host().profiler().section("module:" + id);
            section = current;
        }
        return current;
    }
}
