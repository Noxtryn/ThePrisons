package io.theprisons.hud;

import io.theprisons.gui.kit.TextFit;
import io.theprisons.modules.hud.CosmicStats;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DashboardLayoutTest {
    private static final long NOW = 5_000_000L;

    private static CosmicStats.Snapshot snapshot(List<CosmicStats.Booster> server, List<CosmicStats.Booster> personal, Double tax, long eta, int inventory) {
        return new CosmicStats.Snapshot(5_058_000L, 4.86D, 4.31D, 18_240L, 1_250_000D, 860_000D, tax, "", server, personal, 3, "Cave", eta, 0.082D, "Gears III, Overload II", 0.82D,
                5.1D, 0.12D, "MINING", inventory, 1_100_000D, 800_000D);
    }

    private static SessionView ore(String activity, List<CosmicStats.Booster> server, List<CosmicStats.Booster> personal, List<SessionView.Row> debug) {
        return SessionViewFactory.ore(snapshot(server, personal, 4.5D, 2_520_000L, 78), activity, true, true, NOW, true, debug);
    }

    private static final List<CosmicStats.Booster> TWO = List.of(new CosmicStats.Booster("2x XP Booster", 2.0D, NOW + 5_050_000L),
            new CosmicStats.Booster("Charge Orbs +12% Energy and a very long description of the orb", 1.12D, 0L));

    private static SessionView bandit(BanditHudInfo info) {
        return SessionViewFactory.bandit(snapshot(List.of(), List.of(), 4.5D, 2_520_000L, 78), info, 42L, 1L, true, NOW, List.of());
    }

    private static BanditHudInfo info() {
        return new BanditHudInfo(true, "Hunting", "bandit_0f_00ab12", 18.4D, 2, "Ready", 0.6D, "Not needed", "NE ring 17", "Patrol A", false, 42L, 1L,
                List.of("nav WALK_FORWARD", "nodes 17 / replan: better lane"));
    }

    private static void assertClean(DashboardLayout.Result r, String what) {
        for (int i = 0; i < r.items().size(); i++) {
            DashboardLayout.Item a = r.items().get(i);
            assertTrue(a.x() >= 0 && a.x() + a.w() <= r.width(), what + ": " + a.kind() + " '" + a.text() + "' leaves the widget (x " + a.x() + " w " + a.w() + " of " + r.width() + ")");
            assertTrue(a.y() >= 0 && a.y() + a.h() <= r.height(), what + ": " + a.kind() + " '" + a.text() + "' leaves the widget vertically");
            if (!a.isText()) {
                continue;
            }
            for (int j = i + 1; j < r.items().size(); j++) {
                DashboardLayout.Item b = r.items().get(j);
                if (b.isText()) {
                    assertFalse(a.overlaps(b), what + ": '" + a.text() + "' (" + a.kind() + ") overlaps '" + b.text() + "' (" + b.kind() + ")");
                }
            }
        }
    }

    @Test
    void noTextOverlapsAtAnyFontWidthWidgetWidthOrStyle() {
        List<SessionView> views = List.of(ore("Mining Gold", TWO, TWO, List.of()), ore("Walking to the far waypoint of the diamond route lane seven", TWO, List.of(), List.of()),
                ore("Idle", List.of(), List.of(), List.of()), bandit(info()), bandit(null), bandit(new BanditHudInfo(true, "Repositioning to a better firing line",
                        "bandit_0f_00ab12_with_a_very_long_name", 123.0D, 12, "Cooldown", -1.0D, "Recalling the spear", "", "", true, 0L, 0L, List.of())));
        for (int charW : new int[]{4, 5, 6, 7, 8}) {
            TextFit.Measure m = s -> s.length() * charW;
            for (int width : new int[]{140, 180, 220, 260, 300, 340, 400}) {
                for (DashboardStyle style : DashboardStyle.values()) {
                    for (int i = 0; i < views.size(); i++) {
                        assertClean(DashboardLayout.layout(views.get(i), style, width, m), "char " + charW + " width " + width + " " + style + " view " + i);
                    }
                }
            }
        }
    }

    @Test
    void fourKpiCardsWhenTheyFitAndATwoByTwoGridWhenTheyDoNot() {
        TextFit.Measure m = s -> s.length() * 6;
        assertEquals(4, DashboardLayout.layout(ore("Mining Gold", List.of(), List.of(), List.of()), DashboardStyle.STANDARD, 320, m).kpiColumns());
        assertEquals(2, DashboardLayout.layout(ore("Mining Gold", List.of(), List.of(), List.of()), DashboardStyle.STANDARD, 200, m).kpiColumns());
        DashboardLayout.Result grid = DashboardLayout.layout(ore("Mining Gold", List.of(), List.of(), List.of()), DashboardStyle.STANDARD, 200, m);
        assertEquals(4, grid.items().stream().filter(i -> i.kind() == DashboardLayout.Kind.CARD).count());
        long rowsOfCards = grid.items().stream().filter(i -> i.kind() == DashboardLayout.Kind.CARD).map(DashboardLayout.Item::y).distinct().count();
        assertEquals(2, rowsOfCards, "two rows of two");
    }

    @Test
    void theStylesGrowAndMinimalHasNoCards() {
        TextFit.Measure m = s -> s.length() * 6;
        SessionView v = ore("Mining Gold", TWO, TWO, List.of());
        DashboardLayout.Result min = DashboardLayout.layout(v, DashboardStyle.MINIMAL, 320, m);
        DashboardLayout.Result std = DashboardLayout.layout(v, DashboardStyle.STANDARD, 320, m);
        DashboardLayout.Result det = DashboardLayout.layout(v, DashboardStyle.DETAILED, 320, m);
        assertTrue(min.height() < std.height() && std.height() < det.height(), min.height() + " < " + std.height() + " < " + det.height());
        assertTrue(min.width() <= DashboardLayout.MINIMAL_WIDTH);
        assertTrue(min.items().stream().noneMatch(i -> i.kind() == DashboardLayout.Kind.CARD));
        assertTrue(min.items().stream().anyMatch(i -> i.kind() == DashboardLayout.Kind.TITLE && i.text().equals("THEPRISONS")));
        assertEquals(320, std.width());
        assertTrue(std.items().stream().noneMatch(i -> i.kind() == DashboardLayout.Kind.BOOSTER_NAME), "boosters belong to the detailed view");
        assertEquals(4, det.items().stream().filter(i -> i.kind() == DashboardLayout.Kind.BOOSTER_NAME).count(), "two server and two personal boosters");
    }

    @Test
    void theBoundsAreTheRealSizeAndShrinkWithTheSpaceTheWidgetIsGiven() {
        TextFit.Measure m = s -> s.length() * 6;
        SessionView v = ore("Mining Gold", TWO, TWO, List.of());
        DashboardLayout.Result a = DashboardLayout.layout(v, DashboardStyle.DETAILED, 330, m);
        DashboardLayout.Result b = DashboardLayout.layout(v, DashboardStyle.DETAILED, 200, m);
        assertEquals(330, a.width());
        assertEquals(200, b.width());
        int maxBottom = a.items().stream().mapToInt(i -> i.y() + i.h()).max().orElse(0);
        assertTrue(a.height() >= maxBottom && a.height() <= maxBottom + DashboardLayout.PAD + 2, "height " + a.height() + " content " + maxBottom);
    }

    @Test
    void aLongActivityIsCutWithAnEllipsisNotWrappedIntoTheNextField() {
        TextFit.Measure m = s -> s.length() * 6;
        DashboardLayout.Result r = DashboardLayout.layout(ore("Walking to the far waypoint of the diamond route lane seven", List.of(), List.of(), List.of()), DashboardStyle.STANDARD, 220, m);
        DashboardLayout.Item activity = r.items().stream().filter(i -> i.kind() == DashboardLayout.Kind.ACTIVITY).findFirst().orElseThrow();
        assertTrue(activity.text().endsWith(TextFit.ELLIPSIS), activity.text());
        assertTrue(activity.w() <= 220 - 2 * DashboardLayout.PAD);
    }

    @Test
    void theInventoryBarShowsOnlyWhenKnownAndFits() {
        TextFit.Measure m = s -> s.length() * 6;
        DashboardLayout.Result known = DashboardLayout.layout(ore("Mining Gold", List.of(), List.of(), List.of()), DashboardStyle.STANDARD, 300, m);
        DashboardLayout.Item bar = known.items().stream().filter(i -> i.kind() == DashboardLayout.Kind.BAR).findFirst().orElseThrow();
        assertEquals(0.78D, bar.fraction(), 1e-9);
        SessionView unknown = SessionViewFactory.ore(snapshot(List.of(), List.of(), 4.5D, 2_520_000L, -1), "Mining Gold", true, true, NOW, true, List.of());
        assertTrue(DashboardLayout.layout(unknown, DashboardStyle.STANDARD, 300, m).items().stream().noneMatch(i -> i.kind() == DashboardLayout.Kind.BAR));
    }

    @Test
    void debugRowsNeverShowInTheNormalViews() {
        TextFit.Measure m = s -> s.length() * 6;
        SessionView withDebug = ore("Mining Gold", List.of(), List.of(), List.of(new SessionView.Row("Learned routes", "7", SessionView.Tone.MUTED)));
        assertTrue(DashboardLayout.layout(withDebug, DashboardStyle.STANDARD, 320, m).items().stream().noneMatch(i -> i.text().equals("DEBUG")));
        assertTrue(DashboardLayout.layout(withDebug, DashboardStyle.MINIMAL, 320, m).items().stream().noneMatch(i -> i.text().equals("DEBUG")));
        assertTrue(DashboardLayout.layout(withDebug, DashboardStyle.DETAILED, 320, m).items().stream().anyMatch(i -> i.text().equals("DEBUG")));
        SessionView none = ore("Mining Gold", List.of(), List.of(), List.of());
        assertTrue(DashboardLayout.layout(none, DashboardStyle.DETAILED, 320, m).items().stream().noneMatch(i -> i.text().equals("DEBUG")));
    }

    // ── what the views contain: only known values ────────────────────────────

    @Test
    void theOreViewHasTheFourKpisAndNothingInvented() {
        SessionView v = SessionViewFactory.ore(snapshot(List.of(), List.of(), null, -1L, -1), "Mining Gold", true, true, NOW, true, List.of());
        assertEquals(List.of("OP/s", "ORES", "ENERGY", "XP"), v.kpis().stream().map(SessionView.Kpi::label).toList());
        assertEquals("4.86", v.kpis().get(0).value());
        assertEquals("18.2K", v.kpis().get(1).value());
        assertEquals("1.25M/h", v.kpis().get(2).value());
        assertEquals("860.0K/h", v.kpis().get(3).value());
        assertTrue(v.stats().stream().noneMatch(r -> r.label().equals("Guard Tax")), "no tax known: no tax row");
        assertTrue(v.stats().stream().noneMatch(r -> r.label().equals("Level ETA")), "no eta known: no eta row");
        for (String invented : List.of("Efficiency", "Loop Risk", "Travel", "Route Yield")) {
            assertTrue(v.stats().stream().noneMatch(r -> r.label().equals(invented)) && v.detail().stream().noneMatch(r -> r.label().equals(invented)),
                    invented + " is not exposed by the macro, so it is not shown");
        }
        assertEquals("THEPRISONS • ORE MINING", v.title());
        assertEquals("01:24:18", v.clock());
    }

    @Test
    void theOreViewShowsTaxAndEtaWhenKnown() {
        SessionView v = ore("Mining Gold", List.of(), List.of(), List.of());
        assertTrue(v.stats().stream().anyMatch(r -> r.label().equals("Guard Tax") && r.value().equals("4.5%")));
        assertTrue(v.stats().stream().anyMatch(r -> r.label().equals("Level ETA") && r.value().equals("00:42")), v.stats().toString());
        assertEquals("Running • Mining Gold", v.stateLine());
    }

    @Test
    void theBanditViewHasKillsTargetThreatsSpearAndHidesInternals() {
        SessionView v = bandit(info());
        assertEquals(List.of("KILLS", "KILLS/h", "ENERGY", "XP"), v.kpis().stream().map(SessionView.Kpi::label).toList());
        assertEquals("42", v.kpis().get(0).value());
        for (String field : List.of("Target", "Threats", "State", "Spear", "Charge", "Recall")) {
            assertTrue(v.stats().stream().anyMatch(r -> r.label().equals(field)), field + " in " + v.stats());
        }
        assertTrue(v.stats().stream().anyMatch(r -> r.label().equals("Target") && r.value().contains("18m")), v.stats().toString());
        assertTrue(v.stats().stream().anyMatch(r -> r.label().equals("Charge") && r.value().equals("60%")));
        // the normal rows are words, not planner terms
        assertTrue(v.stats().stream().noneMatch(r -> r.value().contains("WALK_FORWARD") || r.value().contains("replan")));
        assertEquals(2, v.debug().size(), "the internal lines are only in the debug rows");
        assertEquals("THEPRISONS • BANDITS", v.title());
        assertEquals(-1, bandit(null).activity().inventoryPercent(), "no inventory bar for bandits");
        assertNotNull(bandit(null).activity());
        assertTrue(bandit(null).stats().isEmpty(), "no Bandit Macro information: no invented rows");
    }

    @Test
    void aDryRunIsSaidClearly() {
        BanditHudInfo dry = new BanditHudInfo(true, "Hunting", "", -1.0D, 0, "Ready", -1.0D, "", "", "", true, 0L, 0L, List.of());
        assertTrue(bandit(dry).detail().stream().anyMatch(r -> r.value().contains("DRY RUN")));
        assertTrue(bandit(dry).stats().stream().noneMatch(r -> r.label().equals("Charge")), "an unreadable charge is not shown");
    }

    @Test
    void compactNumbersAndClocks() {
        assertEquals("18.2K", SessionViewFactory.compact(18_240));
        assertEquals("1.25M", SessionViewFactory.compact(1_250_000));
        assertEquals("950", SessionViewFactory.compact(950));
        assertEquals("01:24:18", SessionViewFactory.clock(5_058_000L));
        assertEquals("00:42", SessionViewFactory.eta(2_520_000L));
        assertEquals(">99:00", SessionViewFactory.eta(99L * 3_600_000L + 2 * 3_600_000L));
    }
}
