package com.freelocs.theprisons.modules.mining.ore;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ChoresTest {
    @Test
    void recognisesTheServerMessages() {
        assertEquals(Chores.Kind.EXTRACT_ENERGY,
                Chores.forMessage("[!] Your pickaxe energy is full! Please /extract or level it up to continue mining."));
        assertEquals(Chores.Kind.SELL_ALL_NOW, Chores.forMessage("[!] Your Ore Satchel is now full!"));
        assertEquals(Chores.Kind.SELL_ALL_NOW, Chores.forMessage("[!] Your inventory is full!"));
        assertEquals(Chores.Kind.EXTRACT_ENERGY, Chores.forMessage("(!) Your pickaxe is full of energy!"));
        assertEquals(Chores.Kind.NONE, Chores.forMessage("[!] Your pickaxe gained 20 energy"));
        assertEquals(Chores.Kind.NONE, Chores.forMessage("(!) Your pickaxe is full!"));
        // Current wording on the server: "(!)" and the ore in the satchel's name.
        assertEquals(Chores.Kind.SELL_ALL_NOW, Chores.forMessage("(!) Your Lapis Ore Satchel is full!"));
        assertEquals(Chores.Kind.SELL_ALL_NOW, Chores.forMessage("(!) Your Deepslate Redstone Ore Satchel is full!"));
        assertEquals(Chores.Kind.SELL_ALL_NOW, Chores.forMessage("(!) Your inventory is full! Extra mined ores were deleted."));
        assertEquals(Chores.Kind.NONE, Chores.forMessage("(!) Sold 25,344 ores for $8,396.21 (satchel)"));
        assertEquals(Chores.Kind.NONE, Chores.forMessage("» 1x Gold Ore Satchel (0 / 2,304 Ores) « offers"));
    }

    @Test
    void readsTheCooldownTheServerNames() {
        Object[] cooldown = Chores.cooldown("(!) Anti XP Tax Pet is on cooldown for 35m 4s");
        assertEquals("anti xp tax pet", cooldown[0]);
        assertEquals(35 * 60_000L + 4_000L, cooldown[1]);
        assertEquals(3_600_000L + 5_000L, Chores.cooldown("[!] Fireball is on cooldown for 1h 5s")[1]);
        assertEquals(null, Chores.cooldown("<Player> Anti XP Tax Pet is on cooldown for 35m"));
    }

    @Test
    void takesTheFirstSpongeAndTheFirstHotbarPickaxeFromTheLeft() {
        String[] ids = new String[46];
        Arrays.fill(ids, "minecraft:air");
        boolean[] pickaxes = new boolean[46];
        ids[14] = Chores.SPONGE;
        ids[30] = Chores.SPONGE;
        ids[38] = Chores.SPONGE;
        pickaxes[12] = true; // a pickaxe in the main inventory does not count
        pickaxes[39] = true;
        pickaxes[42] = true;
        assertArrayEquals(new int[]{14, 39}, Chores.slots(ids, pickaxes));
        Arrays.fill(ids, "minecraft:air");
        assertArrayEquals(new int[]{-1, 39}, Chores.slots(ids, pickaxes), "no sponge");
    }

    @Test
    void findsTheFirstReadyPetOrAbilityInTheHotbar() {
        String[] names = {"Diamond Pickaxe", "Anti XP Tax Pet [LVL 3]", "", "Fireball", "Meteor Wand", "", "", "", ""};
        boolean[] ready = {true, true, false, true, true, false, false, false, false};
        assertEquals(1, Chores.readySlot(names, ready, Chores.nameParts("anti xp tax pet")));
        assertEquals(3, Chores.readySlot(names, ready, Chores.nameParts(" Fireball , meteor ")));
        ready[3] = false; // the fireball is on cooldown
        assertEquals(4, Chores.readySlot(names, ready, Chores.nameParts("fireball, meteor")));
        ready[1] = false;
        assertEquals(-1, Chores.readySlot(names, ready, Chores.nameParts("anti xp tax pet")), "pet on cooldown");
        assertEquals(-1, Chores.readySlot(names, ready, Chores.nameParts(" , ")), "nothing configured");
    }

    @org.junit.jupiter.api.Test
    void pickaxeFullFromItsLore() {
        // The lore as Cosmic writes it (game log 2026-10-03); the Battery block below has the same form.
        org.junit.jupiter.api.Assertions.assertTrue(Chores.pickaxeFull(java.util.List.of("Feed", "", "Cosmic Energy",
                "|||||||||| 100.0%", "(351,762 / 351,762)", "", "Battery", "|||| 0.0%", "(0 / 58,627)")));
        org.junit.jupiter.api.Assertions.assertFalse(Chores.pickaxeFull(java.util.List.of("Cosmic Energy",
                "|||||||||| 82.0%", "(242,159 / 293,135)", "", "Battery", "|||| 100.0%", "(58,627 / 58,627)")));
        org.junit.jupiter.api.Assertions.assertFalse(Chores.pickaxeFull(java.util.List.of("Efficiency V")));
    }
}
