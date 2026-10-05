package com.freelocs.theprisons.core.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TextStripTest {
    @Test
    void removesEveryFormattingCode() {
        assertEquals("[Tax] 12.5%", TextStrip.strip("§r§6[Tax] §f12.5%"));
        assertEquals("Booster", TextStrip.strip("§c§lBooster"));
        assertEquals("Booster", TextStrip.strip("§C§LBooster§R"));
        assertEquals("Rainbow", TextStrip.strip("§x§f§f§0§0§a§aRainbow"));
        assertEquals("Guard XP Tax: 5%", TextStrip.strip(" Guard XP​  Tax:§ 5% "));
        assertEquals("", TextStrip.strip(null));
        // Cosmic keeps its sidebar lines apart with a control character at the end.
        assertEquals("82 (17,670,120 XP)", TextStrip.strip("82 (17,670,120 XP)\u0086"));
        assertEquals("Cosmic Energy", TextStrip.strip("Cosmic Energy\u0087"));
    }
}
