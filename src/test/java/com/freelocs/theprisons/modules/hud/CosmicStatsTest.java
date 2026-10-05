package com.freelocs.theprisons.modules.hud;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CosmicStatsTest {
    private final List<String> unparsed = new ArrayList<>();
    private final CosmicStats stats = new CosmicStats(0L, unparsed::add);

    @Test
    void opsCountsTheLastMinuteIncludingProcBlocks() {
        for (int s = 0; s < 120; s++) {
            // 2 hit ores + 1 proc block per second.
            stats.addOres(2, s * 1000L);
            stats.addOres(1, s * 1000L + 500L);
        }
        CosmicStats.Snapshot snap = stats.snapshot(119_999L, 0, "");
        assertEquals(3.0D, snap.opsRecent(), 1e-9);
        assertEquals(360L, snap.ores());
        assertEquals(3.0D, snap.opsAverage(), 0.05D);
    }

    @Test
    void energyFromThePickaxeLoreWithoutAbsorbedOrbs() {
        assertEquals(1234L, CosmicStats.loreEnergy("Energy: 1,234 / 50,000"));
        assertEquals(-1L, CosmicStats.loreEnergy("Efficiency VI"));
        assertEquals(-1L, CosmicStats.loreEnergy("+34% Energy Gain from 6 Charge Orbs"));
        // The lore as Cosmic writes it (from the game log, 2026-10-03).
        assertEquals(242_159L, CosmicStats.loreEnergy(java.util.List.of("Energy Collector III", "Feed", "",
                "Cosmic Energy", "|||||||||||||||||||| 82.0%", "(242,159 / 293,135)", "", "Battery",
                "|||||||||| 0.0%", "(0 / 58,627)", "", "+34% Energy Gain from 6 Charge Orbs")));
        stats.pickaxeEnergy("Pick", 1_000L, 0L);
        stats.pickaxeEnergy("Pick", 1_600L, 10_000L);
        stats.message("(!) Absorbed 5,000 Cosmic Energy.", false, 20_000L);
        stats.pickaxeEnergy("Pick", 6_900L, 21_000L);
        // Emptied, then another pickaxe: baselines only.
        stats.pickaxeEnergy("Pick", 0L, 30_000L);
        stats.pickaxeEnergy("Other", 9_000L, 40_000L);
        CosmicStats.Snapshot snap = stats.snapshot(300_000L, 0, "");
        // 600 + 300 gained in 300 s → 900 * 12 per hour.
        assertEquals(900.0D * 12.0D, snap.energyPerHour(), 1e-6);
    }

    @Test
    void xpFromTheActionBarBeatsTheVanillaPoints() {
        stats.message("+12 XP  +3.5k Energy", true, 1_000L);
        stats.vanillaXp(100L, 2_000L);
        stats.vanillaXp(500L, 3_000L);
        CosmicStats.Snapshot snap = stats.snapshot(300_000L, 0, "");
        assertEquals(12.0D * 12.0D, snap.xpPerHour(), 1e-6);
        assertEquals(3_500.0D * 12.0D, snap.energyPerHour(), 1e-6, "no lore energy known: the action bar counts");
    }

    @Test
    void taxFromTheSidebarAndRations() {
        stats.sidebar(List.of("Cosmic Prisons", "Guard XP Tax: 12.5%", "Balance: $5"), 1_000L);
        assertEquals(12.5D, stats.snapshot(1_000L, 0, "").taxPercent());
        stats.sidebar(List.of("Cosmic Prisons", "Guard XP Tax", "0%"), 2_000L);
        assertEquals(0.0D, stats.snapshot(2_000L, 0, "").taxPercent());
        stats.sidebar(List.of("Cosmic Prisons"), 3_000L);
        assertNull(stats.snapshot(3_000L, 0, "").taxPercent());
        stats.message("(!) No tax applied due to Inmate Rations.", false, 4_000L);
        CosmicStats.Snapshot snap = stats.snapshot(5_000L, 0, "");
        assertEquals(0.0D, snap.taxPercent());
        assertEquals("Rations", snap.taxNote());
    }

    @Test
    void boostersOnlyWhileActive() {
        assertTrue(stats.snapshot(0L, 0, "").personalBoosters().isEmpty());
        stats.message("(!) You received 1.5x Shard Booster / 10m for participating in the competition!", false, 0L);
        assertTrue(stats.snapshot(0L, 0, "").personalBoosters().isEmpty(), "received = an item, not active");
        stats.message("(!) You activated a 2x Energy Booster for 30m!", false, 0L);
        List<CosmicStats.Booster> personal = stats.snapshot(1_000L, 0, "").personalBoosters();
        assertEquals(1, personal.size());
        assertEquals("2x Energy Booster", personal.get(0).name());
        assertEquals(30L * 60_000L, personal.get(0).endsAtMs());
        assertTrue(stats.snapshot(30L * 60_000L + 1L, 0, "").personalBoosters().isEmpty(), "expired");

        stats.message("(!) Inmate Rations: 2x Mining Energy.", false, 0L);
        assertEquals("Rations: 2x Mining Energy", stats.snapshot(1_000L, 0, "").personalBoosters().get(0).name());
        assertTrue(stats.snapshot(CosmicStats.RATION_MS + 1L, 0, "").personalBoosters().isEmpty());

        stats.bossBars(List.of("Server Booster: 1.5x XP (12:30)"), 10_000L);
        CosmicStats.Snapshot snap = stats.snapshot(10_000L, 0, "");
        assertEquals(1, snap.serverBoosters().size());
        assertEquals(1.5D, snap.serverBoosters().get(0).multiplier());
        assertEquals("1.5x XP", snap.serverBoosters().get(0).name());
        assertEquals(10_000L + 750_000L, snap.serverBoosters().get(0).endsAtMs());
        assertTrue(stats.snapshot(10_000L + CosmicStats.SEEN_MS + 1L, 0, "").serverBoosters().isEmpty(), "bar gone");
    }

    @Test
    void unknownWordingIsLoggedOncePerWording() {
        stats.message("(!) Your energy multiplier is 3x", false, 0L);
        stats.message("(!) Your energy multiplier is 4x", false, 1L);
        stats.message("(!) Welcome to spawn!", false, 2L);
        assertEquals(1, unparsed.size());
    }

    @Test
    void timeLeftFormats() {
        assertEquals(750_000L, CosmicStats.remaining("12:30"));
        assertEquals(3_723_000L, CosmicStats.remaining("1:02:03"));
        assertEquals(3_900_000L, CosmicStats.remaining("1h 5m"));
        assertEquals(30_000L, CosmicStats.remaining("30s left"));
        assertEquals("00:01:05", NebulaHudRenderer.clock(65_000L));
        assertEquals("1.25M", NebulaHudRenderer.compact(1_250_000L));
    }

    @Test
    void levelUpEtaFromTheSidebarXpLineElseVanilla() {
        stats.message("+100 XP", true, 1_000L);
        // 100 XP in 300 s → 1,200 XP per hour; 600 left → 30 minutes.
        stats.vanillaXpLeft(600L);
        assertEquals(30L * 60_000L, stats.snapshot(300_000L, 0, "").levelUpEtaMs());
        stats.sidebar(List.of("Level 50", "XP: 1,000 / 2.2k"), 300_000L);
        assertEquals(60L * 60_000L, stats.snapshot(300_000L, 0, "").levelUpEtaMs(), "sidebar first: 1,200 left");
        assertEquals("01:00", NebulaHudRenderer.eta(60L * 60_000L));
    }

    @Test
    void procShareAndPickaxeWarning() {
        stats.addOres(10, 0L);
        stats.addProcOres(4, 2, 0L);
        List<String> lore = List.of("Efficiency VI", "Fractured III", "Shatter 12%");
        stats.pickaxe(CosmicStats.procLines(lore), 0.05D, CosmicStats.procChance(lore));
        CosmicStats.Snapshot snap = stats.snapshot(1_000L, 0, "");
        assertEquals(0.4D, snap.procShare(), 1e-9);
        assertEquals("Fractured III · Shatter 12%", snap.loreProcs());
        assertEquals("REPAIR REQUIRED", NebulaHudRenderer.rows(snap, 0L).get(0).value());
        // 10 hits in the last 60 s window (1 s old session → 10 per second), 12 % proc, 2 blocks per proc measured.
        assertEquals(10.0D * (1.0D + 0.12D * 2.0D), snap.forecastOps(), 1e-9);
        stats.pickaxe("", 0.5D, -1.0D);
        assertEquals("UpTime", NebulaHudRenderer.rows(stats.snapshot(1_000L, 0, ""), 0L).get(0).label());
    }

    @Test
    void botStateAndInventoryRowsOnlyWhenKnown() {
        List<NebulaHudRenderer.Row> rows = NebulaHudRenderer.rows(stats.snapshot(1_000L, 0, "Tunnel", "SORTING LOOT", 75), 0L);
        assertEquals("Bot State", rows.get(0).label());
        assertEquals("SORTING LOOT", rows.get(0).value());
        assertEquals("75 %", rows.get(1).value());
        assertEquals("UpTime", NebulaHudRenderer.rows(stats.snapshot(1_000L, 0, ""), 0L).get(0).label(), "macro off: no state row");
    }

    @org.junit.jupiter.api.Test
    void effectsFromChatBecomeBoosters() {
        CosmicStats stats = new CosmicStats(0L, line -> {
        });
        long now = 1_000_000L;
        stats.message("(!) You have 1 hrs 20 min of Rested XP at 2x XP!", false, now);
        stats.message("Current bonus: 12% Energy Gain from 3 Charge Orbs.", false, now);
        stats.message("(!) Anti XP Tax Pet [LVL 3]: no Guard XP Tax for 30m.", false, now);
        stats.message("(!) Lucky Pet: You have been imbued with an extreme sense of good fortune for 1 minute", false, now);
        java.util.List<String> names = new java.util.ArrayList<>();
        CosmicStats.Snapshot s = stats.snapshot(now + 1_000L, 0, "");
        s.personalBoosters().forEach(b -> names.add(b.name()));
        s.serverBoosters().forEach(b -> names.add(b.name()));
        org.junit.jupiter.api.Assertions.assertTrue(names.contains("2x Rested XP"), names.toString());
        org.junit.jupiter.api.Assertions.assertTrue(names.contains("Charge Orbs +12% Energy"), names.toString());
        org.junit.jupiter.api.Assertions.assertTrue(names.contains("No XP Tax (pet)"), names.toString());
        org.junit.jupiter.api.Assertions.assertTrue(names.contains("Lucky (pet)"), names.toString());
        stats.message("(!) Your Rested XP has run out.", false, now + 2_000L);
        stats.message("(!) Your Anti XP Tax [LVL 3] has run out.", false, now + 2_000L);
        java.util.List<String> after = new java.util.ArrayList<>();
        stats.snapshot(now + 3_000L, 0, "").personalBoosters().forEach(b -> after.add(b.name()));
        org.junit.jupiter.api.Assertions.assertFalse(after.contains("2x Rested XP"), after.toString());
        org.junit.jupiter.api.Assertions.assertFalse(after.contains("No XP Tax (pet)"), after.toString());
    }

    /** XP/h and energy/h from the real Cosmic sidebar (no "+N XP" on the action bar, vanilla XP points stay 0). */
    @Test
    void ratesFromTheSidebar() {
        CosmicStats s = new CosmicStats(0L, line -> { });
        s.sidebar(List.of("Account M4cL4ren", "", "Level", "81 (16,321,009 XP)", "Cosmic Energy", "34% (level 71)",
                "(429,210 / 1,259,523)", "Momentum", "+0.00% (0s)"), 1_000L);
        s.sidebar(List.of("Account M4cL4ren", "", "Level", "81 (16,325,101 XP)", "Cosmic Energy", "35% (level 71)",
                "(439,210 / 1,259,523)"), 11_000L);
        assertEquals(4_092L, s.xpTotal());
        assertEquals(10_000L, s.energyTotal());
        // another pickaxe (other capacity) and "(0 / 0)" without a pickaxe: no gain
        s.sidebar(List.of("Level", "81 (16,325,101 XP)", "Cosmic Energy", "(900,000 / 2,000,000)"), 12_000L);
        s.sidebar(List.of("Cosmic Energy", "0% (level 1)", "(0 / 0)"), 13_000L);
        assertEquals(10_000L, s.energyTotal());
        assertTrue(s.snapshot(11_000L, 0, "").xpPerHour() > 0.0D);
        // the activity gets what the global stats measured
        CosmicStats activity = new CosmicStats(0L, line -> { });
        activity.addGains(4_092L, 10_000L, 5_000L);
        assertTrue(activity.snapshot(5_000L, 0, "").energyPerHour() > 0.0D);
    }

    /** Per-minute rates on the action bar, scaled to the hour and kept (not reset) afterwards. */
    @Test
    void perMinuteFromTheActionBar() {
        String[][] cases = {
                {"+1,234 XP/m", "74040", "-1"}, {"Energy: 12.5k/min | XP: 3,000/min", "180000", "750000"},
                {"XP/min: 2,000  Energy/min: 4,000", "120000", "240000"}, {"+3.4k Energy per minute", "-1", "204000"},
        };
        for (String[] c : cases) {
            CosmicStats s = new CosmicStats(0L, line -> { });
            s.message(c[0], true, 1_000L);
            assertEquals(Double.parseDouble(c[1]), s.barXpPerHour(), 0.5D, c[0]);
            assertEquals(Double.parseDouble(c[2]), s.barEnergyPerHour(), 0.5D, c[0]);
            s.message("$5M in /cf | 2x Comp XP (5m)", true, 90_000L);
            assertEquals(Double.parseDouble(c[1]), s.snapshot(90_000L, 0, "").xpPerHour() < 0 ? -1 : s.barXpPerHour(), 0.5D);
        }
    }
}
