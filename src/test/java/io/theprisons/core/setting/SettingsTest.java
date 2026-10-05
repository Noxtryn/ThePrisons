package io.theprisons.core.setting;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SettingsTest {
    @Test
    void numbersAreClampedAndStepped() {
        Settings.IntSetting speed = new Settings.IntSetting("speed", "Speed", 32, 10, 90, 2);
        speed.set(100);
        assertEquals(90, speed.value());
        speed.set(33);
        assertEquals(34, speed.value());
        Settings.DoubleSetting reach = new Settings.DoubleSetting("reach", "Reach", 4.4D, 2.5D, 6.0D, 0.1D);
        reach.set(4.0000001D);
        assertEquals(4.0D, reach.value(), 0.0D);
        reach.set(Double.NaN);
        assertEquals(4.4D, reach.value(), 0.0D);
        assertEquals("4.4", reach.display());
    }

    @Test
    void listenersRunOnlyOnEffectiveChange() {
        List<Boolean> seen = new ArrayList<>();
        Settings.BoolSetting flag = new Settings.BoolSetting("flag", "Flag", false).onChange(seen::add);
        flag.set(false);
        flag.toggle();
        flag.toggle();
        assertEquals(List.of(true, false), seen);
        assertTrue(flag.isDefault());
    }

    @Test
    void multiChoiceKeepsOptionOrderAndDropsUnknown() {
        Settings.MultiChoiceSetting ores = new Settings.MultiChoiceSetting("ores", "Ores", List.of(),
                List.of(new Settings.Option("x", "X", "", 0), new Settings.Option("y", "Y", "", 0), new Settings.Option("z", "Z", "", 0)));
        ores.set(Set.of("z", "nope", "x"));
        assertEquals(List.of("x", "z"), new ArrayList<>(ores.get()));
        ores.toggle("x");
        assertFalse(ores.contains("x"));
        assertThrows(UnsupportedOperationException.class, () -> ores.get().add("y"), "values are immutable");
    }

    @Test
    void boundSettingsReadAndWriteExternalStorage() {
        int[] external = {4};
        Settings.IntSetting bound = new Settings.IntSetting("gap", "Gap", 2, 0, 8, 1).bind(() -> external[0], v -> external[0] = v);
        assertEquals(4, bound.value());
        bound.set(12);
        assertEquals(8, external[0]);
        assertFalse(bound.persistent());
        bound.reset();
        assertEquals(2, external[0]);
    }

    @Test
    void idsAreValidated() {
        assertThrows(IllegalArgumentException.class, () -> new Settings.BoolSetting("Bad Id", "x", true));
    }

    @Test
    void textIsSingleLineAndBounded() {
        Settings.TextSetting text = new Settings.TextSetting("ids", "Ids", "", 5);
        text.set("ab\ncdefgh");
        assertEquals("ab cd", text.get());
        assertEquals(List.of("minecraft:stone", "diamond_ore"), Settings.splitIds(" Minecraft:Stone, diamond_ore;;"));
    }
}
