package com.freelocs.theprisons.core.config;

import com.freelocs.theprisons.core.module.Category;
import com.freelocs.theprisons.core.module.Module;
import com.freelocs.theprisons.core.module.ModuleManager;
import com.freelocs.theprisons.core.profiling.Profiler;
import com.freelocs.theprisons.core.setting.Settings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigStoreTest {
    enum Mode { FAST, SMOOTH }

    static final class Sample extends Module {
        final Settings.BoolSetting flag = bool("flag", "Flag", true);
        final Settings.IntSetting number = integer("number", "Number", 10, 0, 100, 5);
        final Settings.DoubleSetting ratio = decimal("ratio", "Ratio", 0.5D, 0.0D, 1.0D, 0.05D);
        final Settings.EnumSetting<Mode> mode = choice("mode", "Mode", Mode.FAST, Enum::name);
        final Settings.MultiChoiceSetting ores = multi("ores", "Ores", List.of("a"),
                List.of(new Settings.Option("a", "A", "", 0), new Settings.Option("b", "B", "", 0)));
        final Settings.TextSetting text = text("text", "Text", "", 16);
        final Settings.ColorSetting color = color("color", "Color", 0xFFFFFFFF);
        int boundValue = 3;
        final Settings.IntSetting bound = integer("bound", "Bound", 3, 0, 9, 1).bind(() -> boundValue, v -> boundValue = v);

        Sample() {
            super("sample", "Sample", Category.GENERAL, "Tests", "", 75);
        }
    }

    static final class Macro extends Module {
        Macro() {
            super("macro", "Macro", Category.MINING, "Tests", "", -1);
        }

        @Override
        public boolean persistEnabled() {
            return false;
        }
    }

    @TempDir
    Path dir;
    private final ModuleManager manager = new ModuleManager(new Profiler());

    @AfterEach
    void stop() {
        manager.worker().shutdown();
    }

    private ConfigStore store() {
        return new ConfigStore(dir.resolve("theprisons").resolve("modules.json"), manager, Runnable::run);
    }

    @Test
    void onlyOverridesArePersistedAndRoundTrip() throws IOException {
        Sample sample = manager.register(new Sample());
        Macro macro = manager.register(new Macro());
        ConfigStore store = store();
        assertEquals("{\n  \"schema\": 1,\n  \"modules\": {}\n}", store.serialize());

        sample.number.set(40);
        sample.ratio.set(0.3D);
        sample.mode.set(Mode.SMOOTH);
        sample.ores.set(Set.of("b"));
        sample.text.set("hello");
        sample.color.set(0xFF00E5FF);
        sample.keybind().set(80);
        sample.bound.set(7);
        manager.enable(sample);
        manager.enable(macro);
        store.saveNow(true);
        String json = Files.readString(store.file());
        assertFalse(json.contains("\"flag\""), "default values are not written");
        assertFalse(json.contains("\"bound\""), "bound (legacy) settings are not written");
        assertFalse(json.contains("\"macro\""), "macros never persist their enabled state");

        ModuleManager fresh = new ModuleManager(new Profiler());
        Sample loaded = fresh.register(new Sample());
        Macro loadedMacro = fresh.register(new Macro());
        new ConfigStore(store.file(), fresh, Runnable::run).load(true);
        assertEquals(40, loaded.number.value());
        assertEquals(0.3D, loaded.ratio.value(), 1.0E-9D);
        assertEquals(Mode.SMOOTH, loaded.mode.get());
        assertEquals(Set.of("b"), loaded.ores.get());
        assertEquals("hello", loaded.text.get());
        assertEquals(0xFF00E5FF, loaded.color.get());
        assertEquals(80, loaded.keybind().key());
        assertTrue(loaded.enabled());
        assertFalse(loadedMacro.enabled());
        fresh.worker().shutdown();
    }

    @Test
    void invalidValuesFallBackToDefaultsAndClampingApplies() throws IOException {
        Sample sample = manager.register(new Sample());
        Path file = store().file();
        Files.createDirectories(file.getParent());
        Files.writeString(file, """
                {"schema":1,"modules":{"sample":{"settings":{
                  "number": 999, "ratio": "not a number", "mode": "warp", "ores": ["a","zzz","b"], "unknown": 1
                }}, "gone_module": {"enabled": true}}}
                """);
        store().load(true);
        assertEquals(100, sample.number.value(), "clamped to max");
        assertEquals(0.5D, sample.ratio.value(), "invalid -> default");
        assertEquals(Mode.FAST, sample.mode.get());
        assertEquals(Set.of("a", "b"), sample.ores.get(), "unknown option dropped");
    }

    @Test
    void corruptFileIsBackedUpAndDefaultsUsed() throws IOException {
        Sample sample = manager.register(new Sample());
        sample.number.set(55);
        Path file = store().file();
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{ this is not json");
        ConfigStore store = store();
        store.load(true);
        assertEquals(10, sample.number.value(), "reset to default");
        assertNotNull(store.lastError());
        try (var files = Files.list(file.getParent())) {
            assertTrue(files.anyMatch(p -> p.getFileName().toString().startsWith("modules.json.corrupt-")));
        }
    }

    @Test
    void debounceSavesAfterQuietTicks() {
        Sample sample = manager.register(new Sample());
        ConfigStore store = store();
        manager.setDirtyHook(store::markDirty);
        sample.number.set(20);
        assertTrue(store.dirty());
        for (int i = 0; i < 39; i++) {
            store.tick();
        }
        assertEquals(0, store.writes());
        store.tick();
        assertEquals(1, store.writes());
        assertFalse(store.dirty());
    }
}
