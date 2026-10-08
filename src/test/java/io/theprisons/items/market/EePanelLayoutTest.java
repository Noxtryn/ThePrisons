package io.theprisons.items.market;

import io.theprisons.gui.kit.TextFit;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EePanelLayoutTest {
    private static EeAnalysis.Panel panel() {
        EeAnalysis.Section rates = new EeAnalysis.Section("RATES", List.of(new EeAnalysis.Row("CHEAPEST", "$2.30K / 1k", EeAnalysis.Tone.GOOD),
                new EeAnalysis.Row("TYPICAL", "$2.55K / 1k", EeAnalysis.Tone.NEUTRAL)), true);
        EeAnalysis.Section market = new EeAnalysis.Section("MARKET", List.of(new EeAnalysis.Row("vs 7-day average", "-4.2%", EeAnalysis.Tone.GOOD),
                new EeAnalysis.Row("Price rises in", "23h 40m", EeAnalysis.Tone.MUTED)), false);
        EeAnalysis.Section buy = new EeAnalysis.Section("BUY COST", List.of(new EeAnalysis.Row("10K", "$23.0K", EeAnalysis.Tone.NEUTRAL),
                new EeAnalysis.Row("100K", "$231K", EeAnalysis.Tone.NEUTRAL), new EeAnalysis.Row("1M", "$2.34M", EeAnalysis.Tone.NEUTRAL)), false);
        EeAnalysis.Section you = new EeAnalysis.Section("YOU", List.of(new EeAnalysis.Row("Balance", "$1,788,270,626.00", EeAnalysis.Tone.NEUTRAL),
                new EeAnalysis.Row("Can buy", "4.30M CE", EeAnalysis.Tone.NEUTRAL), new EeAnalysis.Row("Hold", "840K CE", EeAnalysis.Tone.NEUTRAL),
                new EeAnalysis.Row("Value", "~$1.93M", EeAnalysis.Tone.GOOD)), false);
        return new EeAnalysis.Panel("ENERGY MARKET", List.of(rates, market, buy, you), "2 extreme listings ignored");
    }

    private static void assertClean(EePanelLayout.Result r, int width) {
        for (int i = 0; i < r.items().size(); i++) {
            EePanelLayout.Item a = r.items().get(i);
            assertTrue(a.x() >= 0 && a.x() + a.w() <= width, a.kind() + " '" + a.text() + "' leaves the panel: x " + a.x() + " w " + a.w() + " of " + width);
            assertTrue(a.y() + a.h() <= r.height(), a.kind() + " '" + a.text() + "' below the panel");
            for (int j = i + 1; j < r.items().size(); j++) {
                EePanelLayout.Item b = r.items().get(j);
                if (a.kind() != EePanelLayout.Kind.RULE && b.kind() != EePanelLayout.Kind.RULE) {
                    assertFalse(a.overlaps(b), "'" + a.text() + "' overlaps '" + b.text() + "'");
                }
            }
        }
    }

    @Test
    void nothingOverlapsAtAnyWidth() {
        for (int charW : new int[]{4, 5, 6, 7}) {
            TextFit.Measure m = s -> s.length() * charW;
            for (int width : new int[]{110, 130, 150, 176, 220}) {
                assertClean(EePanelLayout.layout(panel(), width, m), width);
            }
        }
    }

    @Test
    void theValuesAreShortenedNotOverlapped() {
        TextFit.Measure m = s -> s.length() * 6;
        EePanelLayout.Result r = EePanelLayout.layout(panel(), 120, m);
        EePanelLayout.Item balance = r.items().stream().filter(i -> i.kind() == EePanelLayout.Kind.ROW_VALUE && i.text().startsWith("$1,78")).findFirst().orElseThrow();
        assertTrue(balance.x() >= EePanelLayout.PAD, "a long value stays inside");
        assertTrue(r.items().stream().noneMatch(i -> i.kind() == EePanelLayout.Kind.ROW_LABEL && i.text().equals("Balance")), "its label gives way when the value needs the room");
    }

    @Test
    void theHeightGrowsWithTheContentAndTheMetricsAreBig() {
        TextFit.Measure m = s -> s.length() * 6;
        EePanelLayout.Result full = EePanelLayout.layout(panel(), 176, m);
        EePanelLayout.Result bare = EePanelLayout.layout(new EeAnalysis.Panel("ENERGY MARKET", List.of(), ""), 176, m);
        assertTrue(full.height() > bare.height() + 80, "full " + full.height() + " bare " + bare.height());
        assertTrue(full.items().stream().anyMatch(i -> i.kind() == EePanelLayout.Kind.METRIC_VALUE && i.scale() > 1.4D));
        assertEquals(2, full.items().stream().filter(i -> i.kind() == EePanelLayout.Kind.METRIC_VALUE).count());
    }

    @Test
    void anEmptyPanelIsJustTheTitle() {
        TextFit.Measure m = s -> s.length() * 6;
        EePanelLayout.Result r = EePanelLayout.layout(new EeAnalysis.Panel("ENERGY MARKET", List.of(), ""), 176, m);
        assertEquals(1, r.items().stream().filter(i -> i.kind() == EePanelLayout.Kind.TITLE).count());
        assertTrue(r.height() < 40);
    }
}
