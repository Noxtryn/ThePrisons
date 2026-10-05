package io.theprisons.gametest;

import io.theprisons.core.ThePrisonsCore;
import io.theprisons.modules.qol.market.MarketModule;
import io.theprisons.modules.qol.market.MarketSearch;
import io.theprisons.modules.qol.market.PriceBook;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code ./gradlew runClientGameTest -Pmarket}: the user build (feature profile enforced) with seeded prices - the
 * inventory search above the hotbar, the auction house and /ee in the mod's design. Screenshots in
 * build/run/clientGameTest/screenshots.
 */
public final class MarketClientGameTest implements FabricClientGameTest {
    private static final Logger LOGGER = LoggerFactory.getLogger("ThePrisons/MarketTest");

    @Override
    public void runTest(ClientGameTestContext context) {
        if (!ShowcaseClientGameTest.MARKET) {
            return;
        }
        context.getInput().resizeWindow(1600, 900);
        context.runOnClient(client -> {
            client.options.getGuiScale().setValue(3);
            client.onResolutionChanged();
            // No automatic /ah, /ee, /gz scan against the test world.
            ((io.theprisons.core.setting.Settings.BoolSetting) MarketModule.get().setting("auto_scan")).set(false);
            ((io.theprisons.core.setting.Settings.BoolSetting) MarketModule.get().setting("shop_scan")).set(false);
        });
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            singleplayer.getServer().runCommand("gamemode survival @a");
            singleplayer.getServer().runCommand("give @a minecraft:theprisons");
            context.waitTicks(40);
            context.runOnClient(client -> {
                boolean has = client.player.getInventory().contains(new net.minecraft.item.ItemStack(net.minecraft.registry.Registries.ITEM.get(net.minecraft.util.Identifier.ofVanilla("theprisons"))));
                LOGGER.info("[market-test] logo item minecraft:theprisons registered={} in inventory={}",
                        net.minecraft.registry.Registries.ITEM.containsId(net.minecraft.util.Identifier.ofVanilla("theprisons")), has);
                MarketModule market = MarketModule.get();
                LOGGER.info("[market-test] module present={} enabled={}", market != null, market != null && market.enabled());
                if (market == null || !market.enabled()) {
                    throw new AssertionError("market module is not enabled in the user build");
                }
                seed(market.book());
            });
            // 1. Inventory: only the search bar (nothing typed: the HUD stays); double click = every item.
            context.setScreen(() -> new InventoryScreen(MinecraftClient.getInstance().player));
            context.waitTicks(10);
            LOGGER.info("[market-test] inventory idle {}", context.takeScreenshot("market_0_inventory_idle"));
            context.runOnClient(client -> {
                int w = client.getWindow().getScaledWidth();
                int h = client.getWindow().getScaledHeight();
                double bx = w / 2.0D;
                double by = h - 22 - 4 - 8 - 9;
                MarketSearch.click(bx, by, w, h);
                MarketSearch.click(bx, by, w, h);
                LOGGER.info("[market-test] after double click: list open={}", MarketSearch.open());
            });
            context.waitTicks(10);
            LOGGER.info("[market-test] show all {}", context.takeScreenshot("market_1a_showall"));
            context.runOnClient(client -> {
                int w = client.getWindow().getScaledWidth();
                int h = client.getWindow().getScaledHeight();
                MarketSearch.click(w / 2.0D, h - 22 - 4 - 8 - 9, w, h);
                MarketSearch.click(w / 2.0D, h - 22 - 4 - 8 - 9, w, h);
                LOGGER.info("[market-test] after second double click: list open={}", MarketSearch.open());
            });
            context.runOnClient(client -> {
                for (char c : "shard".toCharArray()) {
                    MarketSearch.charTyped(c);
                }
                MarketSearch.debugOpen("shard");
            });
            context.waitTicks(10);
            LOGGER.info("[market-test] dropdown {}", context.takeScreenshot("market_1b_dropdown"));
            int[] drop = new int[4];
            context.runOnClient(client -> System.arraycopy(MarketSearch.debugDrop(), 0, drop, 0, 4));
            double scale = 3.0D;
            context.getInput().setCursorPos((drop[0] + 4 + 28 * 4 + 14) * scale, (drop[1] + 14) * scale);
            context.waitTicks(12);
            LOGGER.info("[market-test] dropdown hover {}", context.takeScreenshot("market_1c_dropdown_hover"));
            // The base cell: only the family's name ("Shard").
            context.runOnClient(client -> MarketSearch.debugOpen(null));
            int[] cell = new int[2];
            context.waitTicks(3);
            context.runOnClient(client -> {
                int[] at = MarketSearch.debugCell("shard");
                if (at != null) {
                    System.arraycopy(at, 0, cell, 0, 2);
                }
            });
            context.getInput().setCursorPos((cell[0] + 13) * scale, (cell[1] + 12) * scale);
            context.waitTicks(10);
            LOGGER.info("[market-test] base hover {}", context.takeScreenshot("market_1c2_base_hover"));
            context.runOnClient(client -> {
                for (int i = 0; i < 6; i++) {
                    MarketSearch.keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_BACKSPACE, 0, false);
                }
                for (char c : "gold satchel".toCharArray()) {
                    MarketSearch.charTyped(c);
                }
                MarketSearch.debugOpen("gold satchel");
            });
            context.waitTicks(10);
            LOGGER.info("[market-test] satchels {}", context.takeScreenshot("market_1c3_satchels"));
            context.runOnClient(client -> {
                for (int i = 0; i < 12; i++) {
                    MarketSearch.keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_BACKSPACE, 0, false);
                }
                for (char c : "mask".toCharArray()) {
                    MarketSearch.charTyped(c);
                }
            });
            context.waitTicks(10);
            LOGGER.info("[market-test] inventory {}", context.takeScreenshot("market_1d_inventory_search"));
            context.runOnClient(client -> {
                for (int i = 0; i < 4; i++) {
                    MarketSearch.keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_BACKSPACE, 0, false);
                }
                LOGGER.info("[market-test] list open after deleting the text: {}", MarketSearch.open());
            });
            context.setScreen(() -> null);

            // 2. The auction house.
            open(context, "Market", market());
            context.waitTicks(10);
            LOGGER.info("[market-test] market {}", context.takeScreenshot("market_2_ah"));
            context.setScreen(() -> null);

            // 3. /ee
            open(context, "Buy Cosmic Energy", energy());
            context.waitTicks(10);
            LOGGER.info("[market-test] ee {}", context.takeScreenshot("market_3_ee"));
            context.setScreen(() -> null);

            // 3b. The Tinkerer: status card + offer area + inventory
            context.runOnClient(client -> {
                boolean ok = MarketModule.get().interceptOpen(ScreenHandlerType.GENERIC_9X6, client, 78, Text.literal("Tinkerer"));
                LOGGER.info("[market-test] Tinkerer intercepted={} screen={}", ok, client.currentScreen == null ? "null" : client.currentScreen.getClass().getSimpleName());
                ScreenHandler handler = client.player.currentScreenHandler;
                for (Entry e : tinker()) {
                    handler.getSlot(e.slot).setStack(stack(e));
                }
            });
            context.waitTicks(10);
            LOGGER.info("[market-test] tinker {}", context.takeScreenshot("market_3b_tinker"));
            context.setScreen(() -> null);

            // 4. Your listings (a menu without a price per piece)
            open(context, "Your Current Listings", yours());
            context.waitTicks(10);
            LOGGER.info("[market-test] yours {}", context.takeScreenshot("market_4_your_listings"));
            context.setScreen(() -> null);
        }
    }

    private static void seed(PriceBook book) {
        long now = System.currentTimeMillis();
        Object[][] items = {
                {"Turkey Mask", "Cosmetics/Masks", 42_000_000D, "minecraft:player_head"},
                {"Pumpkin Mask", "Cosmetics/Masks", 18_000_000D, "minecraft:carved_pumpkin"},
                {"Uncommon Cell Door Upgrade", "Cosmetics/Cell", 8_000_000D, "minecraft:iron_door"},
                {"Legendary Dust (10%)", "Upgrades/Dust", 125_000D, "minecraft:sugar"},
                {"Godly Shard", "Upgrades/Shards", 3_500_000D, "minecraft:prismarine_shard"},
                {"Godly Pickaxe", "Mining/Tools", 90_000_000D, "minecraft:diamond_pickaxe"},
                {"Sludge Helmet", "Combat/Armor", 12_000_000D, "minecraft:iron_helmet"},
                {"Godly Sword", "Combat/Weapons", 55_000_000D, "minecraft:diamond_sword"},
                {"Godly Contraband", "Other/Crates", 2_000_000D, "minecraft:chest"},
        };
        for (Object[] i : items) {
            book.seen(((String) i[0]).toLowerCase(), (String) i[0], (String) i[1], (Double) i[2], now - 7 * 60_000L, "ah", true, (String) i[3]);
        }
        String[] tiers = {"Uncommon", "Elite", "Ultimate", "Legendary", "Godly"};
        long[] tierPrice = {1_000_000L, 2_500_000L, 4_400_000L, 7_000_000L, 25_500_000L};
        for (int i = 0; i < tiers.length; i++) {
            String name = tiers[i] + " Contraband";
            book.seen(PriceBook.key(null, name), name, "Other/Crates", tierPrice[i], now - (i + 2) * 60_000L, "ah", true, "minecraft:ender_chest");
        }
        book.seen(PriceBook.key(null, "II Rare Candy II"), "II Rare Candy II", "Upgrades/Skills", 3_000_000D, now - 600_000L, "ah", true, "minecraft:gold_nugget");
        book.seen(PriceBook.key(null, "Golden Boots 0"), "Golden Boots 0", "Other/NPC Items", 50_000_000D, now - 900_000L, "ah", true, "minecraft:golden_boots");
        // not items: an upgraded instance and a shop description - they must not show up
        book.seen(PriceBook.key(null, "Iron Boots 17"), "Iron Boots 17", "Combat/Armor", 30_000_000D, now, "ah", true, "minecraft:iron_boots");
        book.remember(PriceBook.key(null, "Random Mask"), "Random Mask", "Cosmetics/Masks", now, "shop", "minecraft:player_head");
        book.remember("random mask", "Random Mask", "Cosmetics/Masks", now, "shop", "minecraft:player_head");
        book.energyOffer(2_299D, 1000D, now - 3 * 60_000L);
        book.energyAverages(2_015.81D, 2_102D);
    }

    private static void open(ClientGameTestContext context, String title, List<Entry> entries) {
        context.runOnClient(client -> {
            boolean ok = MarketModule.get().interceptOpen(ScreenHandlerType.GENERIC_9X6, client, 77, Text.literal(title));
            LOGGER.info("[market-test] '{}' intercepted={} screen={}", title, ok, client.currentScreen == null ? "null" : client.currentScreen.getClass().getSimpleName());
            if (!ok) {
                throw new AssertionError("'" + title + "' was not replaced");
            }
            ScreenHandler handler = client.player.currentScreenHandler;
            for (Entry e : entries) {
                handler.getSlot(e.slot).setStack(stack(e));
            }
        });
    }

    private record Entry(int slot, Item item, String name, List<String> lore) {
    }

    private static ItemStack stack(Entry e) {
        ItemStack s = new ItemStack(e.item);
        s.set(DataComponentTypes.CUSTOM_NAME, Text.literal(e.name));
        List<Text> lines = new ArrayList<>();
        for (String l : e.lore) {
            lines.add(Text.literal(l));
        }
        s.set(DataComponentTypes.LORE, new LoreComponent(lines));
        return s;
    }

    private static List<Entry> market() {
        List<Entry> out = new ArrayList<>();
        Object[][] rows = {
                {Items.PLAYER_HEAD, "Turkey Mask", 42_000_000L, 1}, {Items.CARVED_PUMPKIN, "Pumpkin Mask", 18_000_000L, 1},
                {Items.IRON_DOOR, "Uncommon Cell Door Upgrade", 8_000_000L, 1}, {Items.SUGAR, "Legendary Dust (10%)", 8_000_000L, 64},
                {Items.PRISMARINE_SHARD, "Godly Shard", 3_000_000L, 1}, {Items.DIAMOND_PICKAXE, "Godly Pickaxe", 80_000_000L, 1},
                {Items.IRON_HELMET, "Sludge Helmet", 12_000_000L, 1}, {Items.DIAMOND_SWORD, "Godly Sword", 50_000_000L, 1},
                {Items.CHEST, "Godly Contraband", 2_000_000L, 1}, {Items.PAPER, "White Scroll", 1_500_000L, 1},
                {Items.MAGMA_CREAM, "12% Charge Orb", 900_000L, 1}, {Items.SUGAR, "Elite Secret Dust", 400_000L, 1},
        };
        List<Object[]> all = new ArrayList<>(List.of(rows));
        for (int i = 0; i < 18; i++) {
            Object[] base = rows[i % rows.length];
            all.add(new Object[]{base[0], base[1] + " " + (i + 2), ((Long) base[2]) / 2 + i * 10_000L, base[3]});
        }
        for (int i = 0; i < all.size(); i++) {
            Object[] row = all.get(i);
            long price = (Long) row[2];
            int count = (Integer) row[3];
            out.add(new Entry(i, (Item) row[0], (String) row[1], List.of("",
                    String.format(java.util.Locale.ROOT, "Price: $%,d ($%,d / item)", price, price / count), "Seller: Tester", "Expires: 3d", "", "CLICK TO BUY")));
        }
        String[][] buttons = {{"Your Listings"}, {"Collection Bin"}, {"Auction House History"}, {"Previous Page"}, {"Refresh Market"},
                {"Next Page"}, {"Category View"}, {"Filter"}, {"Guide"}};
        Item[] icons = {Items.DIAMOND, Items.ENDER_CHEST, Items.BOOK, Items.ARROW, Items.CHEST, Items.ARROW, Items.CHEST_MINECART,
                Items.ANVIL, Items.BOOK};
        for (int i = 0; i < 9; i++) {
            out.add(new Entry(45 + i, icons[i], buttons[i][0], List.of("click")));
        }
        return out;
    }

    private static List<Entry> tinker() {
        List<Entry> out = new ArrayList<>();
        out.add(new Entry(4, Items.LIGHT_BLUE_DYE, "ACCEPT (Click)", List.of("", "Tinkerer will transform offer into:", "* 2,500 Cosmic Energy", "",
                "------- WARNING -------", "ALL items offered will be LOST forever!")));
        out.add(new Entry(18, Items.GOLD_INGOT, "Gold Satchel (0 / 2,304 Ores)", List.of("", "Automatically collects Gold")));
        out.add(new Entry(19, Items.DIAMOND_SWORD, "Godly Sword", List.of("")));
        return out;
    }

    private static List<Entry> yours() {
        List<Entry> out = new ArrayList<>();
        out.add(new Entry(0, Items.GOLD_ORE, "Gold Ore Satchel (0 / 2,304 Ores)", List.of("", "Automatically collects Gold Ore", "", "Click item to cancel listing.", "", "Price: $7,770,000", "Expires: 15h")));
        out.add(new Entry(1, Items.DEEPSLATE_REDSTONE_ORE, "Deepslate Redstone Ore Satchel (0 / 2,304 Ores)", List.of("", "Click item to cancel listing.", "Price: $3,500,000")));
        out.add(new Entry(2, Items.DIAMOND_SWORD, "Godly Sword", List.of("", "Price: $50,000,000")));
        out.add(new Entry(45, Items.CHEST, "Back", List.of("Click here to go back.")));
        out.add(new Entry(53, Items.BOOK, "Tutorial", List.of("These are your current listings")));
        return out;
    }

    private static List<Entry> energy() {
        List<Entry> out = new ArrayList<>();
        out.add(new Entry(4, Items.LIGHT_BLUE_DYE, "Cosmic Energy", List.of("", "Contains 507,874,300 Cosmic Energy", "", "MARKET INFO",
                "Available: 507.9m", "From: $2,299 /1k", "Sellers: 8", "", "Price increases in: 23h 40m")));
        String[] sellers = {"ImKoby", "Lazerjl", "Just_P3chyTTV", "Mara", "Nox", "Quill", "Rhea", "Sol", "Tamsin"};
        for (int i = 0; i < sellers.length; i++) {
            long amount = 1_000_000L * (i + 1);
            double rate = 2299 + i * 40;
            out.add(new Entry(9 + i, Items.PLAYER_HEAD, sellers[i], List.of("", "Cosmic Energy", String.format(java.util.Locale.ROOT, "Amount: %,d", amount),
                    String.format(java.util.Locale.ROOT, "Price: $%,.0f (%,.0f /1k)", amount / 1000.0D * rate, rate), "Expires: 23h 40m 49s", "", "CLICK TO BUY")));
        }
        out.add(new Entry(46, Items.CHEST, "Your Listings", List.of("click")));
        out.add(new Entry(47, Items.LIGHT_BLUE_DYE, "Sell Yours", List.of("click")));
        out.add(new Entry(49, Items.GOLD_INGOT, "Buy Cheapest", List.of("click")));
        out.add(new Entry(51, Items.WRITABLE_BOOK, "Price Analytics", List.of("click")));
        return out;
    }
}
