package io.theprisons.core.cosmic;

import io.theprisons.core.cosmic.parse.BanditClassifier;
import io.theprisons.core.cosmic.parse.EventParser;
import io.theprisons.core.cosmic.parse.PickaxeLore;
import io.theprisons.core.cosmic.parse.PrivacyFilter;
import io.theprisons.core.cosmic.parse.SidebarParser;
import io.theprisons.core.cosmic.parse.SpearRule;
import io.theprisons.core.cosmic.parse.ZoneParser;
import io.theprisons.core.cosmic.value.Confidence;
import io.theprisons.modules.hud.CosmicStats;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ParsersTest {
    // ── zones ────────────────────────────────────────────────────────────────

    @Test
    void zoneMessagesWork() {
        assertEquals("diamond", ZoneParser.next("", "you entered a diamond zone"));
        assertEquals("gold", ZoneParser.next("diamond", "(!) you have entered the gold zone!"));
        assertEquals("spawn", ZoneParser.next("diamond", "welcome to spawn"));
        assertEquals("", ZoneParser.next("diamond", "welcome, someone to cosmicprisons!"));
        assertEquals("mine", ZoneParser.next("diamond", "welcome to the diamond mine"));
        assertEquals("diamond", ZoneParser.next("diamond", "somebody sold 3 diamonds"), "unrelated lines change nothing");
    }

    @Test
    void zoneParserMatchesWhatTheCoreDidBefore() {
        // The old inline rule of ThePrisonsCore, kept here as the oracle.
        java.util.regex.Pattern zone = java.util.regex.Pattern.compile("you (?:have )?entered (?:a|an|the) (.+?) zone");
        String[] lines = {"you entered a diamond zone", "you have entered an emerald zone", "welcome to spawn", "x to cosmicprisons y",
                "welcome to the redstone mine", "hello", "", "you entered the zone"};
        for (String line : lines) {
            String expected = "before";
            java.util.regex.Matcher m = zone.matcher(line);
            if (m.find()) {
                expected = m.group(1).strip();
            } else if (line.contains("welcome to spawn")) {
                expected = "spawn";
            } else if (line.contains("to cosmicprisons")) {
                expected = "";
            } else if (line.matches(".*welcome to the .+ mine.*")) {
                expected = "mine";
            }
            assertEquals(expected, ZoneParser.next("before", line), line);
        }
    }

    // ── events ───────────────────────────────────────────────────────────────

    @Test
    void meteorEvent() {
        assertEquals(EventParser.METEOR, EventParser.parse("A meteor has crashed at spawn!"));
        assertEquals(EventParser.METEOR, EventParser.parse("(!) A Meteor is falling"));
        assertNull(EventParser.parse("meteor"), "the word alone is no announcement");
        assertEquals(0L, EventParser.activeMs(null));
        assertTrue(EventParser.activeMs(EventParser.METEOR) > 0L);
    }

    // ── pickaxe lore ─────────────────────────────────────────────────────────

    @Test
    void pickaxeEnergyFormats() {
        assertEquals(242_159L, PickaxeLore.energy(List.of("Fractured III", "Cosmic Energy", "||||||||||| 82.0%", "(242,159 / 293,135)",
                "Battery", "(10 / 100)")));
        PickaxeLore.Energy read = PickaxeLore.read(List.of("Cosmic Energy", "|||| 82.0%", "(242,159 / 293,135)"));
        assertNotNull(read);
        assertEquals(293_135L, read.capacity());
        assertEquals(1234L, PickaxeLore.energy(List.of("Energy: 1,234 / 50,000")));
        assertEquals(-1L, PickaxeLore.energy(List.of("+34% Energy Gain from 6 Charge Orbs")), "not the pickaxe's energy");
        assertEquals(-1L, PickaxeLore.energy(List.of()));
        assertEquals(-1L, PickaxeLore.energy(List.of("", "   ", "Efficiency VI")));
        assertNull(PickaxeLore.read(List.of("Cosmic Energy")), "heading without numbers");
    }

    @Test
    void cosmicStatsDelegatesToThePickaxeReader() {
        List<List<String>> samples = List.of(List.of("Energy: 1,234 / 50,000"), List.of("Cosmic Energy", "x", "(1,000 / 2,000)"),
                List.of("+34% Energy Gain from 6 Charge Orbs"), List.of());
        for (List<String> lore : samples) {
            assertEquals(PickaxeLore.energy(lore), CosmicStats.loreEnergy(lore));
        }
    }

    @Test
    void malformedLoreDoesNotThrow() {
        assertEquals(-1L, PickaxeLore.energy(List.of("Cosmic Energy", "(abc / def)", "(,, / ,,)")));
        assertEquals(-1L, PickaxeLore.energyOfLine("energy / /"));
    }

    // ── sidebar ──────────────────────────────────────────────────────────────

    @Test
    void sidebarNumbers() {
        SidebarParser s = SidebarParser.parse(List.of("Account Player0", "Level", "95 (54,186,166 XP)", "Guard XP Tax", "10%", "Cosmic Energy",
                "34% (level 71)", "(456,410 / 1,259,523)"));
        assertEquals(10.0D, s.taxPercent());
        assertEquals(456_410L, s.energyNow());
        assertEquals(1_259_523L, s.energyMax());
        assertEquals(71, s.energyLevel());
        assertEquals(34, s.energyPercent());
        assertEquals(54_186_166L, s.totalXp());
    }

    @Test
    void sidebarTaxOnTheSameLineAndNone() {
        assertEquals(9.0D, SidebarParser.parse(List.of("Guard XP Tax 9%")).taxPercent());
        assertEquals(0.0D, SidebarParser.parse(List.of("Guard XP Tax: none")).taxPercent());
        assertNull(SidebarParser.parse(List.of("Guard XP Tax")).taxPercent(), "a tax line without a number is unknown, not 0");
    }

    @Test
    void missingOrOddSidebarIsUnknownNotZero() {
        assertEquals(SidebarParser.EMPTY, SidebarParser.parse(null));
        assertEquals(SidebarParser.EMPTY, SidebarParser.parse(List.of()));
        SidebarParser odd = SidebarParser.parse(List.of("hello", "(1 / 2)", "Cosmic Energy"));
        assertNull(odd.energyNow(), "the number line must follow the heading");
        assertNull(odd.totalXp());
    }

    // ── bandits ──────────────────────────────────────────────────────────────

    @Test
    void banditKinds() {
        assertEquals(BanditClassifier.Kind.ORE_BANDIT, BanditClassifier.classify("bandit_ae_821e4c", "bandit_ae_821e4c").kind());
        assertEquals(BanditClassifier.Kind.BOSS, BanditClassifier.classify("bandit_ae_821e4c", "bandit_ae_821e4c boss").kind());
        assertEquals(BanditClassifier.Kind.SPECIAL, BanditClassifier.classify("Gold Bandit", "gold bandit").kind());
        BanditClassifier.Classification elite = BanditClassifier.classify("Elite Bandit", "elite bandit");
        assertEquals(BanditClassifier.Kind.ELITE, elite.kind());
        assertEquals(Confidence.UNKNOWN, elite.confidence(), "'elite' is a keyword guess");
        assertFalse(BanditClassifier.classify("Steve", "steve").isBandit());
        assertFalse(BanditClassifier.classify("", "").isBandit());
    }

    /** The rule BanditScan.isBandit had before it delegated; the delegation must not change a single answer. */
    private static boolean oldBanditScan(String name, String shown, boolean any) {
        String[] ores = {"coal", "iron", "gold", "diamond", "emerald"};
        java.util.regex.Pattern named = java.util.regex.Pattern.compile("bandit_[0-9a-f]{2}_[0-9a-f]{4,8}");
        if (named.matcher(name.toLowerCase(Locale.ROOT)).matches()) {
            return any || !shown.contains("boss");
        }
        if (!shown.contains("bandit")) {
            return false;
        }
        if (any) {
            return true;
        }
        for (String ore : ores) {
            if (shown.contains(ore)) {
                return true;
            }
        }
        return false;
    }

    @Test
    void delegationKeepsTheOldYesNo() {
        String[] names = {"bandit_ae_821e4c", "bandit_zz_1", "Gold Bandit", "diamond bandit boss", "Bandit", "steve", "bandit_01_abcdef12", "elite bandit"};
        String[] extras = {"", " boss", " diamond", " bandit", " iron bandit boss", " elite"};
        int checked = 0;
        for (String name : names) {
            for (String extra : extras) {
                String shown = (name.toLowerCase(Locale.ROOT) + extra);
                for (boolean any : new boolean[]{false, true}) {
                    assertEquals(oldBanditScan(name, shown, any), BanditClassifier.isBandit(BanditClassifier.classify(name, shown), any),
                            name + " | " + shown + " | any=" + any);
                    checked++;
                }
            }
        }
        assertTrue(checked > 90);
    }

    // ── spear / privacy ──────────────────────────────────────────────────────

    @Test
    void spearRule() {
        assertTrue(SpearRule.isSpear("minecraft:trident"));
        assertTrue(SpearRule.isSpear("minecraft:netherite_spear"));
        assertFalse(SpearRule.isSpear("minecraft:diamond_sword"));
        assertFalse(SpearRule.isSpear(""));
        assertTrue(SpearRule.isSpearPath("iron_spear"));
    }

    @Test
    void privacyFilterRemovesSecrets() {
        String jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.dBjftJeZ4CVPmB92K27uhbUJU1p1r";
        assertFalse(PrivacyFilter.redact("token " + jwt + " end").contains("eyJhbGci"));
        assertFalse(PrivacyFilter.redact("access_token: abc123def456").contains("abc123def456"));
        assertFalse(PrivacyFilter.redact("Authorization: Bearer abcdefghijklmnopqrstuvwxyz0123").contains("abcdefghijklmnop"));
        assertFalse(PrivacyFilter.redact("connect to 192.168.1.20:25565 now").contains("192.168"));
        assertFalse(PrivacyFilter.redact("mail me at someone@example.com").contains("someone@"));
        assertFalse(PrivacyFilter.redact("session 0123456789abcdef0123456789abcdef0123").contains("0123456789abcdef0123456789abcdef"));
        assertEquals("Fractured III 12%", PrivacyFilter.redact("Fractured III 12%"), "game text is left alone");
        assertEquals("", PrivacyFilter.redact(null));
    }

    @Test
    void privateMessagesAreDropped() {
        assertTrue(PrivacyFilter.isPrivateMessage("[Steve -> me] hello"));
        assertTrue(PrivacyFilter.isPrivateMessage("From Steve: hi"));
        assertTrue(PrivacyFilter.isPrivateMessage("Steve whispers to you: psst"));
        assertNull(PrivacyFilter.safeLine("[Steve -> me] hello"));
        assertEquals("(!) A meteor is falling", PrivacyFilter.safeLine("(!) A meteor is falling"));
    }
}
