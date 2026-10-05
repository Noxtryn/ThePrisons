package com.freelocs.theprisons.core.config;

import com.freelocs.theprisons.core.module.Module;
import com.freelocs.theprisons.core.module.ModuleManager;
import com.freelocs.theprisons.core.setting.Setting;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Persists module state in {@code config/theprisons/modules.json}:
 * <pre>
 * { "schema": 1, "modules": { "ore_macro": { "enabled": true, "settings": { "turn_speed": 120 } } } }
 * </pre>
 * Only values that differ from their defaults are written (like BetterCosmic), so improved defaults reach every
 * untouched setting after an update. Unknown modules / settings are ignored, invalid values keep the default and
 * are logged. A file that cannot be parsed is renamed to {@code modules.json.corrupt-<time>} instead of crashing.
 *
 * <p>Saving: {@link #markDirty()} schedules a save after {@value #DEBOUNCE_TICKS} quiet ticks; the JSON is built on
 * the client thread (microseconds) and written atomically on the IO executor. {@link #saveNow(boolean)} writes
 * immediately (GUI close, shutdown).
 */
public final class ConfigStore {
    public static final int SCHEMA = 1;
    private static final int DEBOUNCE_TICKS = 40;
    private static final Logger LOGGER = LoggerFactory.getLogger("ThePrisons/Config");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private final Path file;
    private final ModuleManager modules;
    private final Executor io;
    private final AtomicLong writes = new AtomicLong();
    private int dirtyTicks = -1;
    private final List<Runnable> saveListeners = new java.util.ArrayList<>();
    private volatile @Nullable String lastError;

    public ConfigStore(Path file, ModuleManager modules, Executor io) {
        this.file = file;
        this.modules = modules;
        this.io = io;
    }

    /** Extra writers that must run on every save (the legacy v1 config). */
    public void addSaveListener(Runnable listener) {
        saveListeners.add(listener);
    }

    public Path file() {
        return file;
    }

    public boolean dirty() {
        return dirtyTicks >= 0;
    }

    public long writes() {
        return writes.get();
    }

    public @Nullable String lastError() {
        return lastError;
    }

    // ── Loading ──────────────────────────────────────────────────────────────

    /**
     * Reads the file and applies it. With {@code applyEnabled}, module enabled states are applied as well (module
     * start-up, or the GUI's "Load" button); settings are reset to defaults first so removed overrides take effect.
     *
     * @return per-module persisted enabled flags (absent = not stored)
     */
    public Map<String, Boolean> load(boolean applyEnabled) {
        Map<String, Boolean> enabledStates = new java.util.HashMap<>();
        JsonObject root = read();
        JsonObject stored = root != null && root.has("modules") && root.get("modules").isJsonObject()
                ? root.getAsJsonObject("modules") : new JsonObject();
        for (Module module : modules.all()) {
            for (Setting<?> setting : module.settings()) {
                if (setting.persistent()) {
                    setting.reset();
                }
            }
            JsonObject entry = stored.has(module.id()) && stored.get(module.id()).isJsonObject()
                    ? stored.getAsJsonObject(module.id()) : null;
            if (entry == null) {
                continue;
            }
            if (entry.has("enabled") && entry.get("enabled").isJsonPrimitive()) {
                enabledStates.put(module.id(), entry.get("enabled").getAsBoolean());
            }
            if (entry.has("settings") && entry.get("settings").isJsonObject()) {
                for (Map.Entry<String, JsonElement> value : entry.getAsJsonObject("settings").entrySet()) {
                    Setting<?> setting = module.setting(value.getKey());
                    if (setting == null || !setting.persistent()) {
                        continue;
                    }
                    try {
                        setting.fromJson(value.getValue());
                    } catch (RuntimeException invalid) {
                        LOGGER.warn("Ignoring invalid value for {}.{}: {}", module.id(), setting.id(), value.getValue());
                        setting.reset();
                    }
                }
            }
        }
        if (applyEnabled) {
            for (Module module : modules.all()) {
                Boolean forced = modules.forced(module);
                boolean wanted = forced != null ? forced : module.initialEnabled(enabledStates.get(module.id()));
                if (wanted && !module.enabled()) {
                    modules.enable(module);
                } else if (!wanted && module.enabled() && module.persistEnabled()) {
                    modules.disable(module);
                }
            }
        }
        dirtyTicks = -1;
        return enabledStates;
    }

    private @Nullable JsonObject read() {
        if (!Files.exists(file)) {
            return null;
        }
        try {
            JsonElement parsed = JsonParser.parseString(Files.readString(file));
            if (!parsed.isJsonObject()) {
                throw new IllegalStateException("root is not an object");
            }
            return parsed.getAsJsonObject();
        } catch (IOException | RuntimeException error) {
            Path backup = file.resolveSibling(file.getFileName() + ".corrupt-" + System.currentTimeMillis());
            LOGGER.error("Could not read {}; moved it to {} and using defaults", file, backup, error);
            lastError = "config was corrupt, backed up to " + backup.getFileName();
            try {
                Files.move(file, backup, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException moveError) {
                LOGGER.error("Could not back up corrupt config", moveError);
            }
            return null;
        }
    }

    // ── Saving ───────────────────────────────────────────────────────────────

    public void markDirty() {
        dirtyTicks = 0;
    }

    /** Client thread, once per tick: saves after a quiet period. */
    public void tick() {
        if (dirtyTicks >= 0 && ++dirtyTicks >= DEBOUNCE_TICKS) {
            saveNow(false);
        }
    }

    /** Builds the JSON of all overrides (client thread). */
    public String serialize() {
        JsonObject root = new JsonObject();
        root.addProperty("schema", SCHEMA);
        JsonObject all = new JsonObject();
        for (Module module : modules.all()) {
            JsonObject entry = new JsonObject();
            if (module.persistEnabled() && module.enabled() != module.enabledByDefault()) {
                entry.addProperty("enabled", module.enabled());
            }
            JsonObject settings = new JsonObject();
            for (Setting<?> setting : module.settings()) {
                if (setting.persistent() && !setting.isDefault()) {
                    settings.add(setting.id(), setting.toJson());
                }
            }
            if (!settings.isEmpty()) {
                entry.add("settings", settings);
            }
            if (!entry.isEmpty()) {
                all.add(module.id(), entry);
            }
        }
        root.add("modules", all);
        return GSON.toJson(root);
    }

    /** @param blocking write on the calling thread (shutdown) instead of the IO executor */
    public void saveNow(boolean blocking) {
        dirtyTicks = -1;
        String json = serialize();
        for (Runnable listener : saveListeners) {
            try {
                listener.run();
            } catch (RuntimeException error) {
                LOGGER.error("Config save listener failed", error);
            }
        }
        Runnable write = () -> write(json);
        if (blocking) {
            write.run();
        } else {
            io.execute(write);
        }
    }

    private synchronized void write(String json) {
        try {
            Files.createDirectories(file.getParent());
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(temp, json);
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            writes.incrementAndGet();
            lastError = null;
        } catch (IOException error) {
            lastError = "save failed: " + error.getMessage();
            LOGGER.error("Could not save {}", file, error);
        }
    }
}
