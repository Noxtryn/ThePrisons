package io.theprisons.gui.kit;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TextFitTest {
    private static final TextFit.Measure CHAR6 = s -> s.length() * 6;       // every character 6 px wide

    @Test
    void textThatFitsIsUntouched() {
        assertEquals("Mining Gold", TextFit.ellipsize("Mining Gold", 66, CHAR6));
    }

    @Test
    void longTextIsCutWithAnEllipsisAndNeverExceedsTheWidth() {
        String cut = TextFit.ellipsize("Walking to the far waypoint of the diamond route", 90, CHAR6);
        assertTrue(cut.endsWith(TextFit.ELLIPSIS), cut);
        assertTrue(CHAR6.width(cut) <= 90, cut);
        for (int w = 0; w < 200; w += 7) {
            assertTrue(CHAR6.width(TextFit.ellipsize("Walking to the far waypoint of the diamond route", w, CHAR6)) <= w, "width " + w);
        }
    }

    @Test
    void noRoomMeansNoText() {
        assertEquals("", TextFit.ellipsize("anything", 0, CHAR6));
        assertEquals("", TextFit.ellipsize("anything", 5, CHAR6), "not even the ellipsis fits");
    }

    @Test
    void wrapKeepsEveryLineInsideAndMarksCutText() {
        List<String> lines = TextFit.wrap("Charge Orb +12% Energy bonus while mining the gold lane", 90, 2, CHAR6);
        assertEquals(2, lines.size());
        for (String l : lines) {
            assertTrue(CHAR6.width(l) <= 90, l);
        }
        assertTrue(lines.get(1).endsWith(TextFit.ELLIPSIS), lines.toString());
        assertEquals(List.of("Short text"), TextFit.wrap("Short text", 120, 2, CHAR6));
    }

    @Test
    void aSingleWordLongerThanALineIsCut() {
        List<String> lines = TextFit.wrap("Supercalifragilisticexpialidocious", 60, 2, CHAR6);
        assertEquals(1, lines.size());
        assertTrue(CHAR6.width(lines.get(0)) <= 60);
    }

    @Test
    void aRowShortensTheLabelBeforeTheValue() {
        String[] row = TextFit.row("A very long label that cannot fit", "$1.48M", 6, 120, CHAR6);
        assertEquals("$1.48M", row[1]);
        assertTrue(CHAR6.width(row[0]) + 6 + CHAR6.width(row[1]) <= 120, row[0]);
        String[] tiny = TextFit.row("Label", "$1,234,567,890", 6, 60, CHAR6);
        assertEquals("", tiny[0]);
        assertTrue(CHAR6.width(tiny[1]) <= 60);
    }
}
