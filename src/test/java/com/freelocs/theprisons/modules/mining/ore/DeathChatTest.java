package com.freelocs.theprisons.modules.mining.ore;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The real lines of the game's death (2026-10-05 01:06:41) and look-alikes that are no death. */
class DeathChatTest {
    private static final String ME = "M4cL4ren";

    @Test
    void cosmicDeathLinesAreADeath() {
        assertTrue(Chores.died("× [Payney head]Payney threw M4cL4ren off the runaway train using Iron Sword 30.", ME));
        assertTrue(Chores.died("× Payney threw M4cL4ren off the runaway train using Iron Sword 30.", ME));
        assertTrue(Chores.died("WHITE SCROLL PROTECTED", ME));
        assertTrue(Chores.died("You kept your pickaxe but lost 642,185 energy", ME));
    }

    @Test
    void otherKillsAndOwnKillsAreNot() {
        assertFalse(Chores.died("× [Icecloak head]Icecloak killed FiksimForkse_ with Iron Sword 30", ME));
        assertFalse(Chores.died("× [M4cL4ren head]M4cL4ren killed Payney with Iron Sword 30", ME), "the killer");
        assertFalse(Chores.died("(35) <Trainee> M4cL4ren: who threw M4cL4ren off", ME), "player chat");
        assertFalse(Chores.died("2. [M4cL4ren head]M4cL4ren (50,037 Ores) (1.4x XP Booster / 10m)", ME));
    }

    @Test
    void combatTag() {
        assertTrue(Chores.combat("(!) You have entered combat. Do not log out for 10s!"));
        assertFalse(Chores.combat("(!) You cannot fight with a pickaxe."));
    }
}
