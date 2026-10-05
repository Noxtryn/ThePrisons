package io.theprisons.modules.mining.ore;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ItemSorterTest {
    private static ItemSorter.Item item(String id, String name) {
        return new ItemSorter.Item(id, name, false);
    }

    private static ItemSorter.Item[] inventoryWithShards(int stacks) {
        ItemSorter.Item[] inventory = new ItemSorter.Item[36];
        Arrays.fill(inventory, ItemSorter.Item.EMPTY);
        for (int i = 0; i < stacks; i++) {
            inventory[i] = item(ItemSorter.SHARD, "Prismarine Shard");
        }
        return inventory;
    }

    @Test
    void startsAtThirtyFivePercentShardStacks() {
        assertEquals(13, ItemSorter.SHARD_STACKS, "35 % of 36 slots, rounded up");
        assertFalse(ItemSorter.due(inventoryWithShards(12), List.of()));
        assertTrue(ItemSorter.due(inventoryWithShards(13), List.of()));
    }

    @Test
    void startsWhenHalfTheSlotsAreItemsThatAreNoBlocks() {
        assertEquals(18, ItemSorter.LOOSE_STACKS, "50 % of 36 slots");
        ItemSorter.Item[] inventory = new ItemSorter.Item[36];
        Arrays.fill(inventory, ItemSorter.Item.EMPTY);
        for (int i = 0; i < 17; i++) {
            inventory[i] = item("minecraft:redstone", "Redstone");
        }
        // Blocks do not count, however many: ores, deepslate ores, ore blocks.
        inventory[20] = new ItemSorter.Item("minecraft:deepslate_iron_ore", "Deepslate Iron Ore", false, true);
        inventory[21] = new ItemSorter.Item("minecraft:lapis_block", "Block of Lapis Lazuli", false, true);
        inventory[22] = new ItemSorter.Item("minecraft:diamond_ore", "Diamond Ore", false, true);
        // Items the macro keeps do not count either.
        inventory[23] = new ItemSorter.Item("minecraft:diamond_pickaxe", "Pickaxe", true, false);
        inventory[24] = item(ItemSorter.SPONGE, "Absorber");
        assertEquals(17, ItemSorter.looseStacks(inventory, List.of()));
        assertFalse(ItemSorter.due(inventory, List.of()), "17 of 36: not yet");
        inventory[17] = item("minecraft:emerald", "Emerald");
        assertTrue(ItemSorter.due(inventory, List.of()), "exactly 18 of 36 = 50 %");
    }

    @Test
    void vaultNumbers() {
        assertEquals(7, ItemSorter.vault("7"));
        assertEquals(8, ItemSorter.vault(" 8 "));
        assertEquals(-1, ItemSorter.vault(""), "empty = sorter off");
        assertEquals(-1, ItemSorter.vault("abc"));
        assertEquals(List.of(8, 10, 11), ItemSorter.vaults("8, 10 11"));
        assertEquals(8, ItemSorter.vault("8, 10"));
    }

    @Test
    void keepsWhatTheMacroNeeds() {
        List<String> abilities = List.of("fireball");
        assertTrue(ItemSorter.keep(item("minecraft:chest", "Deepslate Redstone Ore Satchel"), abilities));
        assertTrue(ItemSorter.keep(new ItemSorter.Item("minecraft:diamond_pickaxe", "Pickaxe", true), abilities));
        assertTrue(ItemSorter.keep(item(ItemSorter.SPONGE, "Absorber"), abilities));
        // Energy goes into its own vault, contrabands are opened: neither stays nor goes into the other vault.
        assertFalse(ItemSorter.keep(item(ItemSorter.LIGHT_BLUE_DYE, "Cosmic Energy"), abilities));
        assertTrue(ItemSorter.energy(item(ItemSorter.LIGHT_BLUE_DYE, "Cosmic Energy")));
        assertFalse(ItemSorter.other(item(ItemSorter.LIGHT_BLUE_DYE, "Cosmic Energy"), abilities));
        assertFalse(ItemSorter.other(item(ItemSorter.CONTRABAND, "Contraband"), abilities));
        assertTrue(ItemSorter.keep(item(ItemSorter.PLAYER_HEAD, "Anti XP Tax Pet [LVL 3]"), abilities));
        assertTrue(ItemSorter.keep(item("minecraft:fire_charge", "Fireball"), abilities));

        assertTrue(ItemSorter.other(item("minecraft:book", "Simple Enchant Book"), abilities));
        assertFalse(ItemSorter.other(item("minecraft:redstone", "Redstone"), abilities), "plain ore drops are sold");
        assertFalse(ItemSorter.other(item(ItemSorter.SHARD, "Prismarine Shard"), abilities), "shards go to their own vault");
        assertFalse(ItemSorter.other(ItemSorter.Item.EMPTY, abilities));
        assertTrue(ItemSorter.shard(item(ItemSorter.SHARD, "Prismarine Shard")));
    }

    @Test
    void shardTiersLowestFirst() {
        assertEquals(0, ItemSorter.shardRank("Simple Shard"));
        assertTrue(ItemSorter.shardRank("Uncommon Shard") < ItemSorter.shardRank("Elite Shard"));
        assertTrue(ItemSorter.shardRank("Elite Shard") < ItemSorter.shardRank("Ultimate Shard"));
        assertTrue(ItemSorter.shardRank("Ultimate Shard") < ItemSorter.shardRank("Legendary Shard"));
        assertTrue(ItemSorter.shardRank("Legendary Shard") < ItemSorter.shardRank("Godly Shard"));
        assertEquals(-1, ItemSorter.shardRank("Prismarine Shard"));
    }

    @Test
    void hotbarSwapNeverTakesPickaxeShardsContrabandsOrMoney() {
        assertTrue(ItemSorter.swappable(ItemSorter.Item.EMPTY));
        assertTrue(ItemSorter.swappable(item("minecraft:redstone", "Redstone")));
        assertFalse(ItemSorter.swappable(new ItemSorter.Item("minecraft:diamond_pickaxe", "Pickaxe", true)));
        assertFalse(ItemSorter.swappable(item(ItemSorter.SHARD, "Elite Shard")));
        assertFalse(ItemSorter.swappable(item(ItemSorter.CONTRABAND, "Contraband")));
        assertFalse(ItemSorter.swappable(item(ItemSorter.MONEY, "$5,000 Bank Note")));
    }

    @Test
    void energyInAnEnergyItem() {
        assertEquals(8_000_000L, ItemSorter.energyAmount(List.of("8,000,000 Cosmic Energy")));
        assertEquals(8_500_000L, ItemSorter.energyAmount(List.of("Cosmic Energy", "Energy: 8.5M")));
        assertEquals(0L, ItemSorter.energyAmount(List.of("Right click to absorb")));
        assertEquals(5_000_000L, ItemSorter.moneyAmount(List.of("$5,000,000 Bank Note")));
        assertEquals(2_500_000L, ItemSorter.moneyAmount(List.of("Bank Note", "Value: $2.5M")));
    }

    @Test
    void satchelsOfTheChosenOresStay() {
        List<String> gold = List.of("gold");
        assertFalse(ItemSorter.otherSatchel(item("minecraft:chest", "Gold Ore Satchel"), gold));
        assertFalse(ItemSorter.otherSatchel(item("minecraft:chest", "Gold Ingot Satchel"), gold));
        assertTrue(ItemSorter.otherSatchel(item("minecraft:chest", "Deepslate Gold Ore Satchel"), gold));
        assertTrue(ItemSorter.otherSatchel(item("minecraft:chest", "Deepslate Redstone Ore Satchel"), gold));
        List<String> deep = List.of("deepslate_gold");
        assertFalse(ItemSorter.otherSatchel(item("minecraft:chest", "Deepslate Gold Ore Satchel"), deep));
        assertFalse(ItemSorter.otherSatchel(item("minecraft:chest", "Gold Block Satchel"), deep));
        assertTrue(ItemSorter.otherSatchel(item("minecraft:chest", "Gold Ore Satchel"), deep));
        List<String> both = List.of("gold", "deepslate_gold");
        assertFalse(ItemSorter.otherSatchel(item("minecraft:chest", "Gold Ingot Satchel"), both));
        assertFalse(ItemSorter.otherSatchel(item("minecraft:chest", "Gold Block Satchel"), both));
        assertFalse(ItemSorter.otherSatchel(item("minecraft:redstone", "Redstone"), gold), "not a satchel");
    }

    @Test
    void plainOresIngotsAndBlocksAreSoldNotVaulted() {
        assertTrue(ItemSorter.sellable(new ItemSorter.Item("minecraft:gold_ore", "Gold Ore", false, true, true)));
        assertTrue(ItemSorter.sellable(new ItemSorter.Item("minecraft:deepslate_gold_ore", "Deepslate Gold Ore", false, true, true)));
        assertTrue(ItemSorter.sellable(new ItemSorter.Item("minecraft:gold_ingot", "Gold Ingot", false, false, true)));
        assertTrue(ItemSorter.sellable(new ItemSorter.Item("minecraft:gold_block", "Block of Gold", false, true, true)));
        // The same id with Cosmic's lore: a special item, not sold. A satchel is a gold_ore item (game log 2026-10-03).
        assertFalse(ItemSorter.sellable(new ItemSorter.Item("minecraft:gold_ingot", "Godly Ingot", false, false, false)));
        assertFalse(ItemSorter.sellable(new ItemSorter.Item("minecraft:gold_ore", "Gold Ore Satchel (505 / 2,304 Ores)", false, true, false)));
        assertFalse(ItemSorter.sellable(new ItemSorter.Item("minecraft:gold_ore", "Gold Ore Satchel (0 / 2,304 Ores)", false, true, true)),
                "never a satchel, even without lore");
        assertFalse(ItemSorter.otherSatchel(new ItemSorter.Item("minecraft:gold_ore", "Gold Ore Satchel (505 / 2,304 Ores)", false, true, false),
                List.of("gold")), "the chosen ore's satchel stays");
        assertFalse(ItemSorter.other(new ItemSorter.Item("minecraft:gold_ore", "Gold Ore", false, true, true), List.of()));
        assertTrue(ItemSorter.other(new ItemSorter.Item("minecraft:gold_ingot", "Godly Ingot", false, false, false), List.of()));
    }

    @Test
    void teleportMessage() {
        assertTrue(ItemSorter.teleporting("Teleporting you to dl home in 1 seconds... (DO NOT MOVE)"));
        assertFalse(ItemSorter.teleporting("Teleporting you to tmp home in 3 seconds... (DO NOT MOVE)"), "only the 1 second line");
        assertFalse(ItemSorter.teleporting("Teleporting you to spawn in 11 seconds... (DO NOT MOVE)"), "11 is not 1");
        assertFalse(ItemSorter.teleporting("Teleporting you to spawn in 10 seconds... (DO NOT MOVE)"));
        assertFalse(ItemSorter.teleporting("Teleporting you to spawn in 21 seconds... (DO NOT MOVE)"));
        assertTrue(ItemSorter.teleporting("Teleporting you to spawn in  1 seconds... (DO NOT MOVE)"));
        assertFalse(ItemSorter.teleporting("You have been teleported."));
    }

    @org.junit.jupiter.api.Test
    void inventoryLimitCountsNoOreBlocks() {
        ItemSorter.Item[] inv = new ItemSorter.Item[ItemSorter.INVENTORY_SLOTS];
        java.util.Arrays.fill(inv, new ItemSorter.Item("minecraft:gold_ore", "Gold Ore", false, true));
        org.junit.jupiter.api.Assertions.assertFalse(ItemSorter.due(inv, java.util.List.of(), 80), "full of ore: sold, not sorted");
        for (int i = 0; i < 30; i++) {
            inv[i] = new ItemSorter.Item("minecraft:book", "Mystery Book", false, false);
        }
        org.junit.jupiter.api.Assertions.assertTrue(ItemSorter.due(inv, java.util.List.of(), 80), "30 of 36 slots of loot");
    }

    @Test
    void itemsAreMatchedWithAVaultByIdAndName() {
        assertEquals(ItemSorter.key(item("minecraft:book", "Simple Enchant Book")), ItemSorter.key(item("minecraft:book", "Simple Enchant Book")));
        assertFalse(ItemSorter.key(item("minecraft:book", "Simple Enchant Book")).equals(ItemSorter.key(item("minecraft:book", "Elite Enchant Book"))));
    }

    @Test
    void oresAreSoldSatchelsAndPickaxesNeverVaulted() {
        List<String> none = List.of();
        assertFalse(ItemSorter.other(new ItemSorter.Item("minecraft:gold_ore", "Gold Ore", false, true, true), none), "ore: sold");
        assertFalse(ItemSorter.other(new ItemSorter.Item("minecraft:gold_ore", "Gold Ore Satchel (1 / 2,304 Ores)", false, true, false), none),
                "satchel (same id): kept");
        assertFalse(ItemSorter.other(new ItemSorter.Item("minecraft:diamond_pickaxe", "Pickaxe", true), none), "pickaxes go to /tinker");
        assertTrue(ItemSorter.other(item("minecraft:book", "Simple Enchant Book"), none));
    }
}
