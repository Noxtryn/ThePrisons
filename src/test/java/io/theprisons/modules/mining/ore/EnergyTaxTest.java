package io.theprisons.modules.mining.ore;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class EnergyTaxTest {
    private long energy = 1_000L;

    /** {@code ores} ores in one rise, {@code perOre} energy each (rounded like the lore). */
    private EnergyTax.Change mine(EnergyTax tax, int ores, double perOre) {
        tax.ore(ores);
        energy += Math.round(ores * perOre);
        return tax.energy("pick", energy);
    }

    private EnergyTax started(double perOre) {
        EnergyTax tax = new EnergyTax();
        tax.assume(true);
        tax.energy("pick", energy);
        for (int i = 0; i < EnergyTax.MIN_ORES * 2; i++) {
            mine(tax, 1, perOre);
        }
        return tax;
    }

    @Test
    void leavingTheZoneIsSeenByFourPercentMorePerOre() {
        EnergyTax tax = started(96);
        for (int i = 0; i < 10; i++) {
            assertNull(mine(tax, i % 3 == 0 ? 2 : 1, 96));
        }
        EnergyTax.Change change = null;
        for (int i = 0; i < 40 && change == null; i++) {
            change = mine(tax, 1, 100);
        }
        assertNotNull(change);
        assertFalse(change.taxed());
        assertEquals(Boolean.FALSE, tax.taxed());
    }

    @Test
    void comingBackInIsSeenByLessPerOre() {
        EnergyTax tax = started(96);
        for (int i = 0; i < 40; i++) {
            mine(tax, 1, 100);
        }
        assertEquals(Boolean.FALSE, tax.taxed());
        EnergyTax.Change change = null;
        for (int i = 0; i < 40 && change == null; i++) {
            change = mine(tax, 1, 96);
        }
        assertNotNull(change);
        assertTrue(change.taxed());
    }

    @Test
    void boostersChangeNothing() {
        for (double factor : new double[]{1.25, 1.5, 2.0}) {
            EnergyTax tax = started(96);
            for (int i = 0; i < 20; i++) {
                assertNull(mine(tax, 1, 96 * factor), "booster x" + factor);
            }
            assertEquals(Boolean.TRUE, tax.taxed());
            // The booster ends: back to the old value, still inside.
            for (int i = 0; i < 20; i++) {
                assertNull(mine(tax, 1, 96), "booster end x" + factor);
            }
            assertEquals(Boolean.TRUE, tax.taxed());
        }
    }

    @Test
    void moreOresAtOnceAndOrbsDoNotLookLikeAChange() {
        EnergyTax tax = started(96);
        for (int i = 0; i < 30; i++) {
            assertNull(mine(tax, 1 + i % 3, 96));
            if (i % 7 == 0) {
                // An absorbed orb: a big rise, no ore broken.
                energy += 5_000L;
                assertNull(tax.energy("pick", energy));
            }
        }
        assertEquals(Boolean.TRUE, tax.taxed());
    }

    @Test
    void creditedForSeveralOresAtOnce() {
        EnergyTax tax = started(96);
        for (int i = 0; i < 12; i++) {
            assertNull(mine(tax, 6 + i % 4, 96));
        }
        EnergyTax.Change change = null;
        for (int i = 0; i < 12 && change == null; i++) {
            change = mine(tax, 6 + i % 4, 100);
        }
        assertNotNull(change);
        assertFalse(change.taxed());
    }

    @Test
    void aCompBoosterAnnouncedInChatIsNotTheTax() {
        EnergyTax tax = started(96);
        tax.rebase();
        for (int i = 0; i < 40; i++) {
            assertNull(mine(tax, 1, 96 * 1.1));
        }
        assertEquals(Boolean.TRUE, tax.taxed());
    }

    @Test
    void anotherOreTypeStartsANewReference() {
        EnergyTax tax = started(96);
        for (int i = 0; i < 40; i++) {
            tax.ore(1, 7);
            energy += 104;
            assertNull(tax.energy("pick", energy));
        }
        assertEquals(Boolean.TRUE, tax.taxed());
    }

    @Test
    void walkingSlowlyCloserToAGuardStaysInside() {
        EnergyTax tax = started(96);
        double perOre = 96;
        for (int i = 0; i < 60; i++) {
            perOre -= 0.1;
            assertNull(mine(tax, 1, perOre));
        }
        assertEquals(Boolean.TRUE, tax.taxed());
    }
}
