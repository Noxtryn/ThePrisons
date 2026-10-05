package io.theprisons.modules.hud.tab;

import net.minecraft.text.Text;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BetterTabModuleTest {
    @Test
    void infoWithoutAdsAndBlankLines() {
        Text header = Text.literal("§d§lCOSMIC PRISONS\n\n§7Planet: Cosmic\n§7-----------");
        Text footer = Text.literal("§eStore: store.cosmicprisons.com\n§7Players: 812\n§9discord.gg/cosmic\n§aVote for rewards!");
        assertEquals(List.of("COSMIC PRISONS", "Planet: Cosmic", "Players: 812"), BetterTabModule.infoLines(header, footer));
    }
}
