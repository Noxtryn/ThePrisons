package io.theprisons.core.module;

import io.theprisons.core.concurrent.Worker;
import io.theprisons.core.event.EventBus;
import io.theprisons.core.profiling.Profiler;
import io.theprisons.core.setting.Setting;
import io.theprisons.core.tick.TickScheduler;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.IntPredicate;

/**
 * Registry and lifecycle of all modules. Owns the kernel (bus, scheduler, worker, profiler) and hands it to modules
 * as their {@link ModuleHost}.
 *
 * <p>Guarantees: {@code onDisable} runs exactly once per {@code onEnable}; after it, the module has no listeners, no
 * tasks and no pending worker callbacks left; any exception thrown by a module (lifecycle, listener, task, job
 * callback) disables that module with the error as reason instead of propagating into the game loop.
 */
public final class ModuleManager implements ModuleHost {
    private static final Logger LOGGER = LoggerFactory.getLogger("ThePrisons/Modules");

    /** Hooks around enable / disable, e.g. releasing the control lease of a macro. */
    public interface LifecycleListener {
        default void enabled(Module module) {
        }

        default void disabled(Module module, @Nullable String reason) {
        }
    }

    private final Profiler profiler;
    private final EventBus bus;
    private final TickScheduler scheduler;
    private final Worker worker;
    private final Map<String, Module> modules = new LinkedHashMap<>();
    private final List<LifecycleListener> lifecycleListeners = new ArrayList<>();
    private final Map<Integer, Boolean> keyWasDown = new LinkedHashMap<>();
    private Consumer<Notice> notifier = notice -> {
    };
    private Runnable dirtyHook = () -> {
    };
    private long stateVersion;
    /** Fixed states (the shipped feature profile): {@code null} = the module may be switched freely. */
    private java.util.function.Function<Module, @Nullable Boolean> policy = module -> null;

    public ModuleManager(Profiler profiler) {
        this.profiler = profiler;
        this.bus = new EventBus(this::onListenerError);
        this.scheduler = new TickScheduler(profiler, this::onError);
        this.worker = new Worker(profiler, this::onError);
    }

    // ── ModuleHost ───────────────────────────────────────────────────────────

    @Override
    public EventBus bus() {
        return bus;
    }

    @Override
    public TickScheduler scheduler() {
        return scheduler;
    }

    @Override
    public Worker worker() {
        return worker;
    }

    @Override
    public Profiler profiler() {
        return profiler;
    }

    @Override
    public void notify(Notice notice) {
        notifier.accept(notice);
    }

    public void setNotifier(Consumer<Notice> notifier) {
        this.notifier = notifier;
    }

    /** Called whenever something persistent changed (setting value, enabled state, keybind). */
    public void setDirtyHook(Runnable hook) {
        this.dirtyHook = hook;
    }

    /** Sets the shipped feature profile; locked modules ignore toggles by keybind, command or GUI. */
    public void setPolicy(java.util.function.Function<Module, @Nullable Boolean> policy) {
        this.policy = policy;
    }

    /** The state the profile fixes for this module, or {@code null} when it is free. */
    public @Nullable Boolean forced(Module module) {
        return policy.apply(module);
    }

    public void addLifecycleListener(LifecycleListener listener) {
        lifecycleListeners.add(listener);
    }

    // ── Registry ─────────────────────────────────────────────────────────────

    public <M extends Module> M register(M module) {
        if (modules.containsKey(module.id())) {
            throw new IllegalArgumentException("Duplicate module id " + module.id());
        }
        module.attach(this);
        for (Setting<?> setting : module.settings()) {
            setting.setChangeHook(this::markDirty);
        }
        modules.put(module.id(), module);
        return module;
    }

    public Collection<Module> all() {
        return Collections.unmodifiableCollection(modules.values());
    }

    public @Nullable Module get(String id) {
        return modules.get(id.toLowerCase(Locale.ROOT));
    }

    public List<Module> byCategory(Category category) {
        List<Module> result = new ArrayList<>();
        for (Module module : modules.values()) {
            if (module.category() == category) {
                result.add(module);
            }
        }
        return result;
    }

    /** Case-insensitive search in names, descriptions, groups and setting names. */
    public List<Module> search(String query) {
        String q = query.trim().toLowerCase(Locale.ROOT);
        List<Module> result = new ArrayList<>();
        if (q.isEmpty()) {
            return result;
        }
        for (Module module : modules.values()) {
            if (matches(module, q)) {
                result.add(module);
            }
        }
        return result;
    }

    private static boolean matches(Module module, String q) {
        if (module.name().toLowerCase(Locale.ROOT).contains(q) || module.description().toLowerCase(Locale.ROOT).contains(q)
                || module.group().toLowerCase(Locale.ROOT).contains(q) || module.category().label().toLowerCase(Locale.ROOT).contains(q)) {
            return true;
        }
        for (Setting<?> setting : module.settings()) {
            if (setting.name().toLowerCase(Locale.ROOT).contains(q)) {
                return true;
            }
        }
        return false;
    }

    /** Increments on every enable / disable; lets the GUI and HUD refresh cheaply. */
    public long stateVersion() {
        return stateVersion;
    }

    // ── Lifecycle ────────────────────────────────────────────────────────────

    /** @return true when the module is enabled afterwards */
    public boolean enable(Module module) {
        if (module.enabled()) {
            return true;
        }
        String refusal;
        try {
            refusal = module.canEnable();
        } catch (Throwable error) {
            refusal = "check failed: " + error;
        }
        if (refusal != null) {
            notify(new Notice(module.name(), refusal, Level.WARNING));
            return false;
        }
        module.setEnabled(true);
        module.setLastStopReason(null);
        stateVersion++;
        try {
            module.onEnable();
        } catch (Throwable error) {
            LOGGER.error("Module {} failed to start", module.id(), error);
            disable(module, "failed to start: " + describe(error));
            return false;
        }
        if (!module.enabled()) {
            // onEnable() stopped the module itself.
            return false;
        }
        for (LifecycleListener listener : lifecycleListeners) {
            listener.enabled(module);
        }
        if (module.persistEnabled()) {
            markDirty();
        }
        return true;
    }

    public void disable(Module module) {
        disableInternal(module, null);
    }

    /** Disables with a reason (failsafe, error); the reason is shown to the player and kept for the GUI. */
    @Override
    public void disable(Module module, String reason) {
        disableInternal(module, reason);
    }

    private void disableInternal(Module module, @Nullable String reason) {
        if (!module.enabled()) {
            return;
        }
        module.setEnabled(false);
        module.setLastStopReason(reason);
        stateVersion++;
        bus.unsubscribeAll(module);
        scheduler.cancelAll(module);
        worker.cancelAll(module);
        try {
            module.onDisable();
        } catch (Throwable error) {
            LOGGER.error("Module {} failed to stop cleanly", module.id(), error);
        }
        // Owner-scoped resources registered while disabling are removed as well.
        bus.unsubscribeAll(module);
        scheduler.cancelAll(module);
        worker.cancelAll(module);
        for (LifecycleListener listener : lifecycleListeners) {
            try {
                listener.disabled(module, reason);
            } catch (Throwable error) {
                LOGGER.error("Lifecycle listener failed for {}", module.id(), error);
            }
        }
        if (reason != null) {
            notify(new Notice(module.name(), reason, Level.ERROR));
        }
        if (module.persistEnabled()) {
            markDirty();
        }
    }

    public boolean toggle(Module module) {
        if (forced(module) != null) {
            return module.enabled(); // locked by the feature profile
        }
        if (module.enabled()) {
            disable(module);
            return false;
        }
        return enable(module);
    }

    /** Disables every module (client shutdown). */
    public void disableAll() {
        List<Module> enabled = new ArrayList<>();
        for (Module module : modules.values()) {
            if (module.enabled()) {
                enabled.add(module);
            }
        }
        Collections.reverse(enabled);
        for (Module module : enabled) {
            disable(module);
        }
    }

    // ── Per tick ─────────────────────────────────────────────────────────────

    /**
     * Toggles modules whose keybind went down since the last poll.
     *
     * @param keyDown   current key state by GLFW code
     * @param listening false while a screen is open (typing in chat must not toggle modules)
     */
    public void pollKeybinds(IntPredicate keyDown, boolean listening) {
        for (Module module : new ArrayList<>(modules.values())) {
            int key = module.keybind().key();
            if (key < 0) {
                continue;
            }
            boolean down = keyDown.test(key);
            Boolean before = keyWasDown.put(key, down);
            if (listening && down && !Boolean.TRUE.equals(before)) {
                boolean handled;
                try {
                    handled = module.onKeybind();
                } catch (Throwable error) {
                    onError(module, error);
                    continue;
                }
                if (!handled && module.toggleable()) {
                    toggle(module);
                }
            }
        }
    }

    // ── Errors ───────────────────────────────────────────────────────────────

    private void onListenerError(Object owner, Class<?> eventType, Throwable error) {
        onError(owner, new RuntimeException("while handling " + eventType.getSimpleName(), error));
    }

    private void onError(@Nullable Object owner, Throwable error) {
        if (owner instanceof Module module) {
            LOGGER.error("Module {} crashed", module.id(), error);
            disable(module, "crashed: " + describe(error));
        } else {
            LOGGER.error("Core task failed ({})", owner, error);
        }
    }

    private static String describe(Throwable error) {
        Throwable root = error;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String message = root.getMessage();
        return root.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }

    private void markDirty() {
        dirtyHook.run();
    }
}
