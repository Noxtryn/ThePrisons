package io.theprisons.modules.qol.items;

import net.minecraft.nbt.NbtCompound;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PrisonsItemsTest {
    private static NbtCompound values(String... keyValues) {
        NbtCompound nbt = new NbtCompound();
        for (int i = 0; i < keyValues.length; i += 2) {
            nbt.putString("cosmicprisons:" + keyValues[i], keyValues[i + 1]);
        }
        return nbt;
    }

    @Test
    void tieredFamilies() {
        PrisonsItems.Info shard = PrisonsItems.resolve(values("custom_item_id", "shard", "shard_tier", "elite"), null);
        assertEquals("theprisons:prisons/shard/elite", shard.model().toString());
        assertEquals(PrisonsItems.Tier.ELITE, shard.tier());

        PrisonsItems.Info dust = PrisonsItems.resolve(values("custom_item_id", "pickaxe_enchant_dust", "pickaxe_dust_tier", "GODLY"), null);
        assertEquals("theprisons:prisons/dust/godly", dust.model().toString());

        PrisonsItems.Info contraband = PrisonsItems.resolve(values("custom_item_id", "contraband_legendary"), null);
        assertEquals("theprisons:prisons/contraband/legendary", contraband.model().toString());
    }

    @Test
    void badges() {
        NbtCompound orb = values("custom_item_id", "charge_orb");
        orb.putInt("cosmicprisons:charge_orb_percent", 7);
        assertEquals("7%", PrisonsItems.resolve(orb, null).badge());

        NbtCompound book = values("custom_item_id", "gear_enchant_book", "gear_enchant_tier", "ULTIMATE");
        book.putInt("cosmicprisons:gear_enchant_level", 4);
        PrisonsItems.Info info = PrisonsItems.resolve(book, null);
        assertEquals("IV", info.badge());
        assertEquals("theprisons:prisons/book_revealed/ultimate", info.model().toString());
        assertEquals("theprisons:prisons/book_revealed/simple", info.plainModel().toString());

        NbtCompound scroll = values("custom_item_id", "randomization_scroll");
        scroll.putInt("cosmicprisons:randomization_scroll_tier", 0);
        assertEquals(PrisonsItems.Tier.SIMPLE, PrisonsItems.resolve(scroll, null).tier());
    }

    @Test
    void unknownItemsKeepTheirTierOnly() {
        PrisonsItems.Info other = PrisonsItems.resolve(values("custom_item_id", "something_new", "satchel_tier", "UNCOMMON"), null);
        assertNull(other.model());
        assertEquals(PrisonsItems.Tier.UNCOMMON, other.tier());
        assertEquals(PrisonsItems.Info.NONE, PrisonsItems.resolve(new NbtCompound(), null));
        assertEquals("XIV", PrisonsItems.roman(14));
    }

    @Test
    void stagesLevelsAndNamedItems() {
        NbtCompound orb = values("custom_item_id", "charge_orb");
        orb.putInt("cosmicprisons:charge_orb_percent", 17);
        PrisonsItems.Info info = PrisonsItems.resolve(orb, null);
        assertEquals("theprisons:prisons/charge_orb/stage_4", info.model().toString());
        assertEquals("theprisons:prisons/charge_orb/stage_1", info.plainModel().toString());

        NbtCompound token = values("custom_item_id", "pickaxe_prestige_token");
        token.putInt("cosmicprisons:prestige_token_level", 12);
        assertEquals("theprisons:prisons/prestige_token/level_10", PrisonsItems.resolve(token, null).model().toString());

        assertEquals("theprisons:prisons/secret_dust/elite",
                PrisonsItems.resolve(values("x", "y"), "Elite Secret Dust").model().toString());
        assertEquals("theprisons:prisons/misc/gang_points",
                PrisonsItems.resolve(values("custom_item_id", "gang_point_note"), null).model().toString());
        assertEquals("theprisons:prisons/book/legendary", PrisonsItems.resolve(values("custom_item_id", "mystery_chest",
                "mysterychestid", "special_randomlegendaryenchantbook"), null).model().toString());
        assertEquals("theprisons:prisons/booster/gp", PrisonsItems.resolve(values("x", "y"), "1.5x GP Booster").model().toString());
    }

    @Test
    void plainItemsNeedAClearMajority() {
        java.util.Map<String, Integer> counts = new java.util.HashMap<>();
        assertNull(VanillaBases.decide(counts));
        counts.put("theprisons:prisons/shard/simple", 2);
        assertNull(VanillaBases.decide(counts), "too few sightings");
        counts.put("theprisons:prisons/shard/simple", 9);
        assertEquals("theprisons:prisons/shard/simple", VanillaBases.decide(counts).toString());
        counts.put("theprisons:prisons/misc/money_note", 5);
        assertNull(VanillaBases.decide(counts), "shared base item stays vanilla");
    }

    @Test
    void masksPetsAndRarityHints() {
        PrisonsItems.Info mask = PrisonsItems.resolve(new NbtCompound(), "Glitch Mask", PrisonsItems.Tier.GODLY);
        assertNull(mask.model(), "masks keep the server's look for now");
        assertEquals(PrisonsItems.Tier.GODLY, mask.tier());
        PrisonsItems.Info pet = PrisonsItems.resolve(new NbtCompound(), "Miner Pet", null);
        assertTrue(pet.model().toString().startsWith("theprisons:prisons/pet/"), "generic animal");
        assertEquals("theprisons:prisons/pet/anti_xp_tax_elite",
                PrisonsItems.resolve(new NbtCompound(), "Anti XP Tax Pet [LVL 1]", PrisonsItems.Tier.ELITE).model().toString());
        assertEquals("theprisons:prisons/pet/wormhole_simple",
                PrisonsItems.resolve(new NbtCompound(), "Wormhole Powerup Pet [LVL 1]", null).model().toString());
        assertEquals("theprisons:prisons/misc/pet_leash",
                String.valueOf(PrisonsItems.resolve(new NbtCompound(), "Pet Leash", null).model()), "not a pet");
        assertEquals("signal_jammer", PrisonsItems.petCreature("Signal Jammer Pet"));
        assertEquals("bandit_king", PrisonsItems.petCreature("Bandit King Pet [LVL 2]"));
        assertEquals("blacksmith", PrisonsItems.petCreature("Blacksmith Pet"));
        // unknown pets: always the same animal for the same name
        assertEquals(PrisonsItems.petCreature("Shadow Pet"), PrisonsItems.petCreature("Shadow Pet"));
        assertEquals("theprisons:prisons/enchant_orb/godly",
                PrisonsItems.resolve(new NbtCompound(), "Godly Enchant Orb", null).model().toString());
        assertEquals("theprisons:prisons/reroll/elite",
                PrisonsItems.resolve(new NbtCompound(), "Elite Enchant Re-Roll", null).model().toString());
        // Armour with only a rarity hint: frame colour, no own model (the material icon is chosen elsewhere).
        PrisonsItems.Info helmet = PrisonsItems.resolve(new NbtCompound(), "Hero Helmet", PrisonsItems.Tier.ELITE);
        assertNull(helmet.model());
        assertEquals(PrisonsItems.Tier.ELITE, helmet.tier());
        assertNull(PrisonsItems.resolve(new NbtCompound(), "Carpet", null).model());
        assertEquals(PrisonsItems.Tier.LEGENDARY, PrisonsItems.colourTier(0xFFAA00));
        assertNull(PrisonsItems.colourTier(0xFFFFFF));
    }

    /** Real item names from the Cosmic logs (items often come without data). */
    @Test
    void randomItemsAreBlackVersionsOfWhatTheyAre() {
        String[][] cases = {
                {"Random Elite Enchant Book", "theprisons:prisons/book/random_elite"},
                {"Random Godly Page", "theprisons:prisons/page/random_godly"},
                {"Random Tool Prestige Token I-III", "theprisons:prisons/prestige_token/random_level_3"},
                {"Random Overworld Boss Egg", "theprisons:prisons/misc/boss_egg_random"},
                {"Random Ore Satchel", "theprisons:prisons/satchel/random"},
                {"Elite Randomization Scroll", "theprisons:prisons/randomization_scroll/elite"},
        };
        for (String[] c : cases) {
            PrisonsItems.Info info = PrisonsItems.resolve(new NbtCompound(), c[0], null);
            org.junit.jupiter.api.Assertions.assertNotNull(info.model(), c[0]);
            assertEquals(c[1], info.model().toString(), c[0]);
        }
    }

    @Test
    void everyRandomFamilyHasItsBlackTextures() {
        for (String family : PrisonsItems.RANDOM_FAMILIES) {
            java.io.File dir = new java.io.File("src/main/resources/assets/theprisons/textures/item/prisons/" + family);
            String[] files = dir.list((d, n) -> n.endsWith(".png") && !n.startsWith("random_"));
            org.junit.jupiter.api.Assertions.assertTrue(files != null && files.length > 0, family);
            for (String f : files) {
                org.junit.jupiter.api.Assertions.assertTrue(new java.io.File(dir, "random_" + f).isFile(), family + "/" + f);
            }
        }
    }

    @Test
    void recognisedByNameAlone() {
        String[][] cases = {
                {"12% Charge Orb", "charge_orb/stage_3"}, {"Charge Orb Slot", "misc/charge_orb_slot"},
                {"Cosmo-Slot Bot Ticket Sleeve (2x)", "misc/ticket_sleeve"}, {"Cosmo-Slot Bot Ticket Scrap", "misc/ticket_scrap"},
                {"Inmate Rations (Right Click) (10m)", "misc/inmate_rations"}, {"Mystery Expander", "misc/expander"},
                {"Random Spear Prestige Modifier (5%)", "misc/prestige_modifier_random"},
                {"Tool Prestige Token IV", "prestige_token/level_4"}, {"Executive Time Extender", "misc/time_extender"},
                {"Random Wormhole Powerup", "powerup/random"}, {"Overdrive Powerup", "powerup/overdrive_simple"}, {"Elite Wormhole Powerup (BOGO)", "powerup/bogo_elite"},
                {"Godly Wormhole Powerup (Double Tap)", "powerup/double_tap_godly"},
                {"II Rare Candy II", "candy/uncommon"}, {"Skill Token", "misc/skill_token"},
                {"Mystery Fractured G-Kit Flare", "flare/fractured"}, {"Meteor Flare", "flare/meteor"},
                {"Sludge G-Kit", "gkit/sludge"}, {"Godly Cell Door Upgrade", "upgrade/godly"},
                {"Blink Trinket", "trinket/blink"}, {"Random Trinket", "trinket/random"},
                {"Next Page", "menu/next"}, {"Previous Page", "menu/previous"}, {"Refresh", "menu/refresh"},
                {"Elite Contraband", "contraband/elite"}, {"Simple Dust (35%)", "dust/simple"},
                {"Elite Shard", "shard/elite"}, {"Uncommon Page (45%)", "page/uncommon"}, {"White Scroll", "enchant/white_scroll"},
        };
        for (String[] c : cases) {
            PrisonsItems.Info info = PrisonsItems.resolve(new NbtCompound(), c[0], null);
            assertEquals("theprisons:prisons/" + c[1], String.valueOf(info.model()), c[0]);
        }
        assertEquals("12%", PrisonsItems.resolve(new NbtCompound(), "12% Charge Orb", null).badge());
    }

    /** Enchant orbs have their own look - never the enchant book's (real names from the Cosmic logs). */
    @Test
    void enchantOrbsAreNotBooks() {
        String[][] cases = {
                {"Mystery Godly Tool Enchant Orb", "enchant_orb/godly"}, {"Mystery Godly Pickaxe Enchant Orb", "enchant_orb/godly"},
                {"Mystery Pickaxe Enchant Orb", "enchant_orb/simple"}, {"Godly Spear Enchant Orb", "spear_orb/godly"},
                {"Whistle IV (Enchant Orb)", "enchant_orb/simple"}, {"Mystery Godly Enchant Book", "book/godly"},
                {"Pickaxe Prestige Modifier (Orb Mastery)", "misc/prestige_modifier"},
        };
        for (String[] c : cases) {
            PrisonsItems.Info info = PrisonsItems.resolve(new NbtCompound(), c[0], null);
            assertEquals("theprisons:prisons/" + c[1], String.valueOf(info.model()), c[0]);
        }
        assertEquals("?", PrisonsItems.resolve(new NbtCompound(), "Mystery Godly Tool Enchant Orb", null).badge());
        NbtCompound book = new NbtCompound();
        book.putString("cosmicprisons:custom_item_id", "mystery_enchant_book");
        book.putString("cosmicprisons:mystery_tier", "ELITE");
        assertEquals("theprisons:prisons/enchant_orb/elite",
                String.valueOf(PrisonsItems.resolve(book, "Mystery Elite Pickaxe Enchant Orb", null).model()));
    }

    /** The rest of the items found in the Cosmic logs. */
    @Test
    void lastItemsFromTheLogs() {
        String[][] cases = {
                {"Deepslate Gold Ore Satchel (0 / 2,304 Ores)", "satchel/gold_deepslate"},
                {"Block of Emerald Satchel (0 / 2,304 Ores)", "satchel/emerald_block"},
                {"Redstone Satchel (0 / 2,304 Ores)", "satchel/redstone_block"}, {"Iron Ore Satchel (0 / 2,304 Ores)", "satchel/iron"},
                {"Random Refined Ore Satchel", "satchel/random"}, {"Lucky Charm", "misc/lucky_charm"},
                {"Item Nametag", "misc/nametag"}, {"KILL MESSAGE \"Buzzards\"", "misc/kill_message"},
                {"TITLE \"Tombstone\"", "misc/title"}, {"+1 PV Row", "misc/pv_expander"},
                {"Aegis I (17%)", "book_revealed/simple"}, {"Absolute Efficiency XI (100%)", "enchant_orb/simple"},
                {"Magnet I (40%)", "enchant_orb/simple"}, {"Prestige Protection Scroll II (50%)", "enchant/white_scroll"},
        };
        for (String[] c : cases) {
            assertEquals("theprisons:prisons/" + c[1], String.valueOf(PrisonsItems.resolve(new NbtCompound(), c[0], null).model()), c[0]);
        }
    }
}
