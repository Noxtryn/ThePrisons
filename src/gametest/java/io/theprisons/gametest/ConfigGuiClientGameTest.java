package io.theprisons.gametest;

import io.theprisons.ThePrisonsClient;
import io.theprisons.core.ThePrisonsCore;
import io.theprisons.core.module.Module;
import io.theprisons.core.setting.Setting;
import io.theprisons.gui.config.ConfigCategory;
import io.theprisons.core.module.AutomationModule;
import io.theprisons.gui.config.ConfigScreen;
import io.theprisons.modules.FeatureProfile;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.gui.screen.Screen;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The config GUI on a real client ({@code ./gradlew runClientGameTest -Pconfig}, user build): every one of the 204
 * documented settings exists in the live registry and has a place in the GUI, the entry points open the one
 * {@link ConfigScreen}, and every category is captured at GUI scale 1, 2 and 3.
 */
public final class ConfigGuiClientGameTest implements FabricClientGameTest {
    private static final Logger LOGGER = LoggerFactory.getLogger("ThePrisons/ConfigGui");

    @Override
    public void runTest(ClientGameTestContext context) {
        if (!ShowcaseClientGameTest.CONFIG) {
            return;
        }
        ThePrisonsCore core = ThePrisonsCore.get();
        List<String> problems = new ArrayList<>();
        checkInventory(core, problems);
        checkEntryPoints(context, core, problems);
        context.runOnClient(client -> checkActivation(core, problems));
        if (!problems.isEmpty()) {
            throw new AssertionError("Config GUI check failed:\n - " + String.join("\n - ", problems));
        }
        screenshots(context, 1280, 720);
        screenshots(context, 1920, 1080);
        context.setScreen(() -> null);
    }

    /** Every documented setting exists and appears in the category of its group; nothing undocumented exists. */
    private static void checkInventory(ThePrisonsCore core, List<String> problems) {
        Set<String> documented = new HashSet<>();
        for (String line : inventory()) {
            String key = line.split(" ")[0];
            documented.add(key);
            int dot = key.indexOf('.');
            Module module = core.modules().get(key.substring(0, dot));
            if (module == null) {
                problems.add("module missing for " + key);
                continue;
            }
            Setting<?> setting = module.setting(key.substring(dot + 1));
            if (setting == null) {
                problems.add("setting missing: " + key);
                continue;
            }
            if (!ConfigCategory.listed(module)) {
                continue; // removed from this build (shown with the developer profile): checked by the dev run
            }
            ConfigCategory category = ConfigCategory.of(module, setting.group());
            if (!ConfigCategory.modulesIn(core.modules().all(), category).contains(module)) {
                problems.add(key + " is not listed in " + category);
            }
        }
        for (Module module : core.modules().all()) {
            for (Setting<?> setting : module.settings()) {
                String key = module.id() + "." + setting.id();
                if (!setting.id().equals("keybind") && !documented.contains(key) && !module.id().equals("bandit_dodge_test")) {
                    problems.add("setting not in the inventory (add it to settings-inventory.txt): " + key);
                }
            }
            if (module.setting("keybind") == null) {
                problems.add("no keybind in " + module.id());
            }
        }
        LOGGER.info("[config-gui] {} documented settings checked, {} modules", documented.size(), core.modules().all().size());
    }


    /**
     * The switches are real: every switchable module can be turned off and on again without losing or duplicating
     * its event listeners, core services refuse to be switched, modules outside the build stay off, and the state
     * survives saving and loading. Macros are left alone (enabling one starts it).
     */
    private static void checkActivation(ThePrisonsCore core, List<String> problems) {
        for (Module module : new ArrayList<>(core.modules().all())) {
            FeatureProfile.Kind kind = FeatureProfile.kind(module);
            String id = module.id();
            try {
                switch (kind) {
                    case SWITCHABLE -> {
                        if (module instanceof AutomationModule || module.canEnable() != null) {
                            continue; // macros start when enabled; some modules need a world or a route first
                        }
                        boolean before = module.enabled();
                        int listeners = core.bus().listenerCount();
                        if (!before) {
                            core.modules().enable(module);
                            listeners = core.bus().listenerCount();
                        }
                        for (int round = 0; round < 2; round++) {
                            core.modules().toggle(module);
                            if (module.enabled()) {
                                problems.add(id + " stays enabled after toggle off");
                            }
                            core.modules().toggle(module);
                            if (!module.enabled()) {
                                problems.add(id + " does not come back on (" + module.lastStopReason() + ")");
                            }
                            if (core.bus().listenerCount() != listeners) {
                                problems.add(id + " changes the listener count " + listeners + " -> " + core.bus().listenerCount());
                            }
                        }
                        if (!before) {
                            core.modules().disable(module);
                        }
                    }
                    case CORE -> {
                        if (core.modules().toggle(module) != module.enabled() || !module.enabled()) {
                            problems.add(id + " (core) must stay on");
                        }
                    }
                    case NOT_IN_BUILD -> {
                        if (module.enabled()) {
                            problems.add(id + " is not in this build but is enabled");
                        }
                    }
                    case SETTINGS_ONLY -> {
                    }
                }
            } catch (RuntimeException error) {
                problems.add(id + " threw while switching: " + error);
            }
        }
        // Persistence: a core module (modules.json) and a v1 module (theprisons.json) keep their state.
        for (String id : new String[]{"scoreboard", "armor_hud"}) {
            Module module = core.modules().get(id);
            if (module == null || FeatureProfile.kind(module) != FeatureProfile.Kind.SWITCHABLE) {
                continue;
            }
            core.modules().disable(module);
            core.config().saveNow(false);
            ThePrisonsClient.CONFIG.save();
            ThePrisonsClient.CONFIG.load();
            core.config().load(true);
            if (module.enabled()) {
                problems.add(id + " is on again after save and load");
            }
            core.modules().enable(module);
            core.config().saveNow(false);
            ThePrisonsClient.CONFIG.save();
            ThePrisonsClient.CONFIG.load();
            core.config().load(true);
            if (!module.enabled()) {
                problems.add(id + " is off again after save and load");
            }
        }
    }

    private static void checkEntryPoints(ClientGameTestContext context, ThePrisonsCore core, List<String> problems) {
        Screen[] opened = new Screen[2];
        context.runOnClient(client -> {
            opened[0] = ThePrisonsClient.configScreen(null, core);
            core.modules().get("click_gui").onKeybind();
        });
        context.waitTicks(2);
        context.runOnClient(client -> opened[1] = client.currentScreen);
        if (!(opened[0] instanceof ConfigScreen)) {
            problems.add("configScreen() is " + opened[0]);
        }
        if (!(opened[1] instanceof ConfigScreen)) {
            problems.add("the click_gui keybind opened " + opened[1]);
        }
    }

    private static void screenshots(ClientGameTestContext context, int width, int height) {
        context.getInput().resizeWindow(width, height);
        for (int scale = 1; scale <= 3; scale++) {
            final int guiScale = scale;
            context.runOnClient(client -> {
                client.options.getGuiScale().setValue(guiScale);
                client.onResolutionChanged();
            });
            for (ConfigCategory category : ConfigCategory.values()) {
                context.runOnClient(client -> {
                    ThePrisonsCore core = ThePrisonsCore.get();
                    ConfigScreen.focusCategory(category);
                    client.setScreen(ThePrisonsClient.configScreen(null, core));
                });
                context.waitTicks(2);
                LOGGER.info("[config-gui] {}x{} scale={} {} {}", width, height, guiScale, category,
                        context.takeScreenshot("config_" + width + "x" + height + "_s" + guiScale + "_" + category.name().toLowerCase()));
            }
        }
    }

    private static List<String> inventory() {
        try (InputStream in = ConfigGuiClientGameTest.class.getResourceAsStream("/settings-inventory.txt")) {
            if (in == null) {
                throw new AssertionError("settings-inventory.txt is not on the gametest classpath");
            }
            List<String> lines = new ArrayList<>();
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\\R")) {
                if (!line.isBlank()) {
                    lines.add(line.trim());
                }
            }
            return lines;
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
