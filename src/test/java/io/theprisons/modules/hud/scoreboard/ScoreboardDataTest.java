package io.theprisons.modules.hud.scoreboard;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ScoreboardDataTest {
    /** The real sidebar, as logged on the server (2026-10-04). */
    private static final List<String> PLAYER_BOARD = List.of("M4cL4ren Day 9", "Criminal Record", "Neutral", "Guarded W",
            "Cosmic Coins", "Lifetime: 1,100", "Balance", "$2,223,016.2", "Current Zone", "Safezone", "Level",
            "81 (16,321,009 XP)", "Progress", "308,993 (73.1%) to 82");
    private static final List<String> PLANET_BOARD = List.of("", "Celestial", "Planet", "85 / 500", "", "Aether", "Planet",
            "131 / 500", "", "Spaceship", "36 / 1500");

    @Test
    void readsTheCosmicSidebar() {
        ScoreboardData.Values v = ScoreboardData.parse(PLAYER_BOARD);
        assertEquals("9", v.day());
        assertEquals("Neutral", v.record());
        assertEquals("$2.22M", ScoreboardData.money(v.balance()));
        assertEquals("1,100", v.coinsLifetime());
        assertEquals("Safezone", v.zone());
        assertEquals("81", v.level());
        assertEquals(16_321_009L, v.xpTotal());
        assertEquals(0.731D, v.progress(), 1e-9);
        assertEquals("308,993", v.toGo());
        assertEquals("82", v.nextLevel());
        assertEquals(List.of("Guarded W"), v.extra());
    }

    @Test
    void planetsBoardKeepsThePlayerValues() {
        ScoreboardData.Values player = ScoreboardData.parse(PLAYER_BOARD);
        ScoreboardData.Values merged = ScoreboardData.parse(PLANET_BOARD).over(player);
        assertEquals(3, merged.planets().size());
        assertEquals(new ScoreboardData.Planet("Spaceship", 36, 1500), merged.planets().get(2));
        assertEquals("Safezone", merged.zone());
        ScoreboardData.Values back = ScoreboardData.parse(PLAYER_BOARD).over(merged);
        assertEquals(3, back.planets().size(), "planets stay known");
    }

    @Test
    void serverIconsAreRemoved() {
        assertEquals("Balance", ScoreboardData.clean("\uE001 Balance\uF8FF"));
        assertEquals(ScoreboardData.Values.EMPTY, ScoreboardData.parse(null));
    }
}
