package com.freelocs.theprisons.core.i18n;

import com.freelocs.theprisons.core.setting.Settings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class I18nTest {
    @AfterEach
    void english() {
        I18n.setLang(I18n.Lang.EN);
    }

    @Test
    void switchesLiveAndFallsBackToEnglish() {
        assertEquals("Next", I18n.t("Next"));
        I18n.setLang(I18n.Lang.DE);
        assertEquals("Weiter", I18n.t("Next"));
        assertEquals("Schritt 2 von 8", I18n.f("Step %d of %d", 2, 8));
        assertEquals("no german text here", I18n.t("no german text here"));
        assertEquals(I18n.Lang.EN, I18n.lang().next());
    }

    @Test
    void itemSorterAndSetupTextsAreTranslated() {
        for (String key : new String[]{"Private Vault - Shards", "Private Vault - Other", "Item sorter",
                "Setup needed before the start", "Set now", "Language: English",
                "Teleported away from the mine (no ore around) - macro stopped."}) {
            assertTrue(I18n.hasGerman(key), key);
        }
    }

    @Test
    void requiredSettingReportsItsProblemOnlyWhileVisibleAndInvalid() {
        boolean[] shown = {true};
        Settings.TextSetting vault = new Settings.TextSetting("vault", "Vault", "", 4)
                .visibleWhen(() -> shown[0])
                .required(value -> !value.isBlank(), "Enter a number.");
        assertNotNull(vault.problem());
        vault.set("7");
        assertNull(vault.problem());
        vault.set("");
        shown[0] = false;
        assertNull(vault.problem(), "hidden settings are not required");
    }
}
