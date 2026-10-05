package io.theprisons.core.module;

import io.theprisons.core.profiling.Profiler;
import io.theprisons.core.setting.Settings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModuleManagerTest {
    record Tick() {
    }

    /** Test module that records its lifecycle and listens to {@link Tick}. */
    static final class Probe extends Module {
        final List<String> log = new ArrayList<>();
        final Settings.IntSetting speed;
        boolean crashOnTick;
        boolean crashOnEnable;
        String refuse;

        Probe(String id, Category category) {
            super(id, "Probe " + id, category, "Tests", "records calls", 65);
            speed = integer("speed", "Speed", 5, 1, 10, 1).group("Tuning");
        }

        @Override
        public String canEnable() {
            return refuse;
        }

        @Override
        protected void onEnable() {
            log.add("enable");
            if (crashOnEnable) {
                throw new IllegalStateException("broken start");
            }
            on(Tick.class, tick -> {
                log.add("tick");
                if (crashOnTick) {
                    throw new IllegalStateException("broken tick");
                }
            });
            every(1, "task", () -> log.add("task"));
        }

        @Override
        protected void onDisable() {
            log.add("disable");
        }
    }

    private final ModuleManager manager = new ModuleManager(new Profiler());
    private final List<ModuleHost.Notice> notices = new ArrayList<>();

    ModuleManagerTest() {
        manager.setNotifier(notices::add);
    }

    @AfterEach
    void stop() {
        manager.worker().shutdown();
    }

    @Test
    void enableRegistersAndDisableRemovesEverything() {
        Probe probe = manager.register(new Probe("probe", Category.MINING));
        assertTrue(manager.enable(probe));
        manager.bus().post(new Tick());
        manager.scheduler().tick();
        assertEquals(List.of("enable", "tick", "task"), probe.log);

        manager.disable(probe);
        manager.bus().post(new Tick());
        manager.scheduler().tick();
        assertEquals(List.of("enable", "tick", "task", "disable"), probe.log);
        assertEquals(0, manager.bus().listenerCount());
        assertEquals(0, manager.scheduler().size());
        assertFalse(probe.enabled());
    }

    @Test
    void crashingListenerDisablesOnlyThatModuleWithReason() {
        Probe bad = manager.register(new Probe("bad", Category.MINING));
        Probe good = manager.register(new Probe("good", Category.MINING));
        manager.enable(bad);
        manager.enable(good);
        bad.crashOnTick = true;
        manager.bus().post(new Tick());
        assertFalse(bad.enabled());
        assertTrue(good.enabled());
        assertNotNull(bad.lastStopReason());
        assertTrue(bad.lastStopReason().contains("broken tick"), bad.lastStopReason());
        assertTrue(bad.log.contains("disable"));
        assertEquals(1, notices.size());
        assertEquals(ModuleHost.Level.ERROR, notices.get(0).level());
    }

    @Test
    void failingStartLeavesModuleDisabledAndClean() {
        Probe probe = manager.register(new Probe("probe", Category.MINING));
        probe.crashOnEnable = true;
        assertFalse(manager.enable(probe));
        assertFalse(probe.enabled());
        assertEquals(0, manager.bus().listenerCount());
        assertTrue(probe.lastStopReason().startsWith("failed to start"));
    }

    @Test
    void refusalKeepsModuleOffAndNotifies() {
        Probe probe = manager.register(new Probe("probe", Category.MINING));
        probe.refuse = "select an ore first";
        assertFalse(manager.enable(probe));
        assertFalse(probe.enabled());
        assertEquals("select an ore first", notices.get(0).body());
        assertNull(probe.lastStopReason());
    }

    @Test
    void keybindTogglesOnlyOnKeyDownEdgeAndNotWhileTyping() {
        Probe probe = manager.register(new Probe("probe", Category.MINING));
        boolean[] down = {false};
        manager.pollKeybinds(key -> key == 65 && down[0], true);
        down[0] = true;
        manager.pollKeybinds(key -> key == 65 && down[0], true);
        assertTrue(probe.enabled());
        manager.pollKeybinds(key -> key == 65 && down[0], true);
        assertTrue(probe.enabled(), "holding the key does not toggle again");
        down[0] = false;
        manager.pollKeybinds(key -> key == 65 && down[0], true);
        down[0] = true;
        manager.pollKeybinds(key -> key == 65 && down[0], false);
        assertTrue(probe.enabled(), "no toggling while a screen (chat) is open");
    }

    @Test
    void searchFindsModulesBySettingNameAndCategory() {
        manager.register(new Probe("alpha", Category.MINING));
        manager.register(new Probe("beta", Category.HUD));
        assertEquals(2, manager.search("speed").size());
        assertEquals(1, manager.search("hud").size());
        assertEquals(0, manager.search("   ").size());
        assertEquals(2, manager.byCategory(Category.MINING).size() + manager.byCategory(Category.HUD).size());
    }

    @Test
    void settingChangesMarkConfigDirty() {
        int[] dirty = new int[1];
        manager.setDirtyHook(() -> dirty[0]++);
        Probe probe = manager.register(new Probe("probe", Category.MINING));
        probe.speed.set(8);
        probe.speed.set(8);
        assertEquals(1, dirty[0], "only effective changes count");
    }
}
