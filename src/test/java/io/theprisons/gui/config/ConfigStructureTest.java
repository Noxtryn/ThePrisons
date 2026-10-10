package io.theprisons.gui.config;

import io.theprisons.core.module.Category;
import io.theprisons.core.module.Module;
import io.theprisons.core.setting.Settings;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Structure of the one config GUI that needs no client: the documented 204 settings, the category of every module and
 * the rule that no second configuration screen exists. The live registry is checked by ConfigGuiClientGameTest.
 */
class ConfigStructureTest {
    private static final Path INVENTORY = Path.of("src/gametest/resources/settings-inventory.txt");

    private static final class Stub extends Module {
        Stub(String id, Category category, String group) {
            super(id, id, category, group, "", Settings.KeybindSetting.NONE);
            bool("flag", "Flag", false).group("Misc");
        }
    }

    private static List<String> inventory() throws IOException {
        return Files.readAllLines(INVENTORY).stream().filter(l -> !l.isBlank()).toList();
    }

    @Test
    void theInventoryHasExactly204UniqueSettings() throws IOException {
        List<String> keys = inventory().stream().map(l -> l.split(" ")[0]).toList();
        assertEquals(204, keys.size());
        assertEquals(204, new HashSet<>(keys).size());
    }

    @Test
    void everyModuleOfTheInventoryHasAnExplicitCategory() throws IOException {
        Set<String> ids = new HashSet<>();
        inventory().forEach(l -> ids.add(l.substring(0, l.indexOf('.'))));
        for (String id : ids) {
            // Engine category GENERAL would silently land in Settings: the explicit mapping must decide.
            ConfigCategory home = ConfigCategory.home(new Stub(id, Category.GENERAL, "x"));
            assertNotEquals(ConfigCategory.OVERVIEW, home, id);
            if (home == ConfigCategory.SETTINGS) {
                assertTrue(Set.of("click_gui", "design").contains(id), id + " falls back to Settings");
            }
        }
    }

    @Test
    void theOreMacroSplitsItsGroupsOverTheCategories() {
        Module ore = new Stub("ore_macro", Category.MINING, "Macros");
        assertEquals(ConfigCategory.MINING, ConfigCategory.home(ore));
        assertEquals(ConfigCategory.UTILITIES, ConfigCategory.of(ore, "Item sorter"));
        assertEquals(ConfigCategory.PVP_COMBAT, ConfigCategory.of(ore, "Defence"));
        assertEquals(ConfigCategory.MINING, ConfigCategory.of(ore, "Targets"));
    }

    @Test
    void overviewListsEveryModule() {
        List<Module> all = List.of(new Stub("ore_macro", Category.MINING, "a"), new Stub("scoreboard", Category.HUD, "b"));
        assertEquals(2, ConfigCategory.modulesIn(all, ConfigCategory.OVERVIEW).size());
        assertEquals(1, ConfigCategory.modulesIn(all, ConfigCategory.HUD_OVERLAYS).size());
    }

    /** Exactly one configuration screen: ConfigScreen. The HUD editor is the only other screen under gui/. */
    @Test
    void thereIsExactlyOneConfigScreen() throws IOException {
        List<String> screens = new ArrayList<>();
        try (Stream<Path> files = Files.walk(Path.of("src/main/java/io/theprisons/gui"))) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                if (Files.readString(file).contains(" extends Screen")) {
                    screens.add(file.getFileName().toString());
                }
            }
        }
        screens.sort(String::compareTo);
        assertEquals(List.of("ConfigScreen.java", "HudEditorScreen.java"), screens);
    }
}
