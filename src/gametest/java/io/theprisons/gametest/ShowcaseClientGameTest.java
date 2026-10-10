package io.theprisons.gametest;

import io.theprisons.ThePrisonsClient;
import io.theprisons.core.ThePrisonsCore;
import io.theprisons.core.module.Module;
import io.theprisons.core.setting.Settings;
import io.theprisons.gui.config.ConfigScreen;
import io.theprisons.gui.config.ConfigCategory;
import io.theprisons.gui.kit.Ui;
import io.theprisons.gui.theme.Theme;
import io.theprisons.modules.general.DesignModule;
import io.theprisons.modules.qol.players.PlayerCardModule;
import io.theprisons.modules.qol.players.PlayerListScreen;
import io.theprisons.modules.qol.storage.StorageOverlayModule;
import io.theprisons.ui.ThePrisonsHudRenderer;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.client.option.Perspective;
import net.minecraft.util.Util;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * The showcase: walks through everything the mod shows a player - dashboard and its pages (themes, darkness, font,
 * comic textures), HUD editor, scoreboard, session HUD with live XP/h and energy/h, pets / cooldowns / satchels /
 * armour widgets, notifications, Better ConfigCategory, player list and player cards, the storage overlay, every item look,
 * worn armour and masks, the comic block textures. Not the Ore Macro.
 *
 * <p>Runs only with {@code -Dtheprisons.showcase=true} ({@code tools/showcase.sh}); every scene has a caption on
 * screen, stays {@code theprisons.showcase.seconds} seconds (default 4) and is saved as a screenshot.</p>
 */
public final class ShowcaseClientGameTest implements FabricClientGameTest {
    static final boolean ENABLED = Boolean.getBoolean("theprisons.showcase");
    /** Only the market screens ({@link MarketClientGameTest}). */
    static final boolean MARKET = Boolean.getBoolean("theprisons.market");
    /** Only Tunnel Vision ({@link TunnelClientGameTest}). */
    static final boolean TUNNEL = Boolean.getBoolean("theprisons.tunnel");
    private static final Logger LOGGER = LoggerFactory.getLogger("ThePrisons/Showcase");
    private static final int SECONDS = Integer.getInteger("theprisons.showcase.seconds", 4);

    private static volatile String title = "";
    private static volatile String subtitle = "";
    private static volatile long captionMs;
    /** -PshowcaseClean: no captions in the pictures (website and README gallery). */
    private static final boolean CLEAN = Boolean.getBoolean("theprisons.showcase.clean");
    private static int shot;

    @Override
    public void runTest(ClientGameTestContext context) {
        if (!ENABLED) {
            return;
        }
        context.getInput().resizeWindow(1600, 900);
        context.runOnClient(client -> {
            try {
                java.nio.file.Files.createDirectories(client.runDirectory.toPath().resolve("saves"));
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
            client.options.getGuiScale().setValue(3);
            client.onResolutionChanged();
            HudRenderCallback.EVENT.register((ctx, counter) -> caption(ctx));
            ScreenEvents.AFTER_INIT.register((c, screen, w, h) -> ScreenEvents.afterRender(screen)
                    .register((s, ctx, mx, my, delta) -> caption(ctx)));
        });
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            TestServerContext server = singleplayer.getServer();
            setUpWorld(server, context);
            List<Runnable> scenes = List.of(
                    () -> dashboard(context),
                    () -> banditPages(context),
                    () -> design(context),
                    () -> hudEditor(context),
                    () -> scoreboardAndSessionHud(server, context),
                    () -> widgets(server, context),
                    () -> tabAndPlayers(server, context),
                    () -> storage(context),
                    () -> items(server, context),
                    () -> wornGear(server, context));
            for (Runnable scene : scenes) {
                try {
                    scene.run();
                } catch (RuntimeException | AssertionError e) {
                    LOGGER.error("[showcase] scene failed", e);
                    context.setScreen(() -> null);
                }
            }
            say(context, "That's ThePrisons", "Everything above is always on - only the look is yours to change.");
            hold(context, "end");
        }
    }

    // ── captions ─────────────────────────────────────────────────────────────

    private static void caption(DrawContext c) {
        String t = title;
        if (t.isEmpty() || CLEAN) {
            return;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        int sw = client.getWindow().getScaledWidth();
        float in = Ui.appear(captionMs, 0, 350.0F);
        int w = Math.max(Ui.width(client.textRenderer, t), Ui.width(client.textRenderer, subtitle)) + 24;
        int x = (sw - w) / 2;
        // on top while playing (the HUD's bottom is busy), at the bottom over menus (their top is busy)
        int y = client.currentScreen == null ? 6 - Math.round((1.0F - in) * 10.0F)
                : client.getWindow().getScaledHeight() - 36 + Math.round((1.0F - in) * 10.0F);
        c.getMatrices().pushMatrix();
        Ui.shadowCard(c, x, y, w, 30, in);
        Ui.flowLine(c, x, x + w, y, in);
        Ui.drawCentered(c, client.textRenderer, t, sw / 2, y + 6, Ui.theme().title(), Math.round(255 * in));
        Ui.drawCentered(c, client.textRenderer, subtitle, sw / 2, y + 17, Ui.VALUE, Math.round(230 * in));
        c.getMatrices().popMatrix();
    }

    private static void say(ClientGameTestContext context, String t, String sub) {
        context.runOnClient(client -> {
            title = t;
            subtitle = sub;
            captionMs = Util.getMeasuringTimeMs();
        });
        LOGGER.info("[showcase] {} - {}", t, sub);
    }

    /** Lets the scene play, then saves it. */
    private static void hold(ClientGameTestContext context, String name) {
        context.waitTicks(SECONDS * 20);
        LOGGER.info("[showcase] screenshot {}", context.takeScreenshot(String.format("showcase_%02d_%s", ++shot, name)));
    }

    private static Module module(String id) {
        return ThePrisonsCore.getOrNull().modules().get(id);
    }

    // ── world ────────────────────────────────────────────────────────────────

    private static void setUpWorld(TestServerContext server, ClientGameTestContext context) {
        server.runCommand("gamemode creative @a");
        server.runCommand("time set day");
        server.runCommand("gamerule doDaylightCycle false");
        server.runCommand("gamerule doWeatherCycle false");
        server.runCommand("fill -8 99 -8 8 99 8 minecraft:polished_deepslate");
        server.runCommand("fill -6 100 3 6 104 3 minecraft:redstone_ore");
        server.runCommand("fill -6 100 4 6 104 4 minecraft:stone");
        server.runCommand("tp @a 0 100 0 0 10");
        // A sidebar like Cosmic's (heading / value pairs) for the scoreboard and the session HUD.
        server.runCommand("scoreboard objectives add cosmic dummy \"COSMIC PRISONS\"");
        server.runCommand("scoreboard objectives setdisplay sidebar cosmic");
        String[] lines = {"Account Player0", " ", "Level", "81 (16,321,009 XP)", "Cosmic Energy", "34% (level 71)",
                "(429,210 / 1,259,523)", "Balance", "$2,223,016.2", "Current Zone", "Safezone", "Cosmic Coins",
                "Lifetime: 1,100"};
        for (int i = 0; i < lines.length; i++) {
            server.runCommand("scoreboard players set l" + i + " cosmic " + (lines.length - i));
            server.runCommand("scoreboard players display name l" + i + " cosmic \"" + lines[i] + "\"");
        }
        context.waitTicks(40);
        context.setScreen(() -> null);
    }

    // ── scenes ───────────────────────────────────────────────────────────────

    private static void dashboard(ClientGameTestContext context) {
        say(context, "The config GUI", "Right Shift (or Mod Menu) - the one place for every module and setting.");
        context.runOnClient(client -> client.setScreen(ThePrisonsClient.configScreen(null, ThePrisonsCore.getOrNull())));
        hold(context, "config_overview");
        configVisualQa(context);
    }

    /** Productive config screen at user window scales; generated screenshots are deliberately caption-free. */
    private static void configVisualQa(ClientGameTestContext context) {
        say(context, "", "");
        context.getInput().resizeWindow(1920, 1080);
        for (int scale = 1; scale <= 3; scale++) {
            final int currentScale = scale;
            context.runOnClient(client -> {
                client.options.getGuiScale().setValue(currentScale);
                client.onResolutionChanged();
            });
            for (ConfigCategory tab : ConfigCategory.values()) {
                showConfigTab(context, tab);
                context.waitTicks(1);
                LOGGER.info("[config-qa] scale={} tab={} screenshot={}", scale, tab,
                        context.takeScreenshot("config_scale" + scale + "_" + tab.name().toLowerCase()));
            }
        }
        context.getInput().resizeWindow(1280, 720);
        for (int scale : new int[]{1, 2, 3}) {
            final int currentScale = scale;
            context.runOnClient(client -> {
                client.options.getGuiScale().setValue(currentScale);
                client.onResolutionChanged();
            });
            for (ConfigCategory tab : ConfigCategory.values()) {
                showConfigTab(context, tab);
                context.waitTicks(1);
                LOGGER.info("[config-qa] 720p scale={} tab={} screenshot={}", scale, tab,
                        context.takeScreenshot("config_720p_scale" + scale + "_" + tab.name().toLowerCase()));
            }
        }

        context.getInput().resizeWindow(1920, 1080);
        context.runOnClient(client -> {
            client.options.getGuiScale().setValue(2);
            client.onResolutionChanged();
        });
        Module detailed = ThePrisonsCore.getOrNull().modules().all().stream()
                .filter(m -> m.settings().stream().anyMatch(s -> s instanceof io.theprisons.core.setting.Settings.EnumSetting<?>))
                .filter(m -> m.settings().stream().anyMatch(s -> s instanceof io.theprisons.core.setting.Settings.IntSetting
                        || s instanceof io.theprisons.core.setting.Settings.DoubleSetting))
                .findFirst().orElseThrow(() -> new AssertionError("No dropdown/slider module for the UI screenshot"));
        context.runOnClient(client -> {
            ConfigScreen.focus(detailed, null);
            ConfigScreen screen = new ConfigScreen(null, ThePrisonsCore.getOrNull());
            client.setScreen(screen);
        });
        context.waitTicks(1);
        LOGGER.info("[config-qa] settings screenshot={}", context.takeScreenshot("config_module_settings"));
        context.runOnClient(client -> {
            ConfigScreen screen = (ConfigScreen) client.currentScreen;
            io.theprisons.core.setting.Setting<?> choice = detailed.settings().stream()
                    .filter(s -> s instanceof io.theprisons.core.setting.Settings.EnumSetting<?>).findFirst().orElseThrow();
            setGuiField(screen, "dropdown", choice);
            setGuiField(screen, "dropdownX", client.getWindow().getScaledWidth() / 2 + 100);
            setGuiField(screen, "dropdownY", client.getWindow().getScaledHeight() / 2);
        });
        context.waitTicks(1);
        LOGGER.info("[config-qa] dropdown screenshot={}", context.takeScreenshot("config_dropdown"));

        showConfigTab(context, ConfigCategory.OVERVIEW);
        context.waitTicks(1);
        context.runOnClient(client -> {
            ConfigScreen screen = (ConfigScreen) client.currentScreen;
            setGuiField(screen, "search", "market");
            setGuiField(screen, "searchFocused", false);
        });
        context.waitTicks(1);
        LOGGER.info("[config-qa] search screenshot={}", context.takeScreenshot("config_search_market"));
        context.runOnClient(client -> {
            ConfigScreen screen = (ConfigScreen) client.currentScreen;
            setGuiField(screen, "search", "");
            screen.mouseScrolled(client.getWindow().getScaledWidth() / 2 - 100, client.getWindow().getScaledHeight() / 2, 0, -5);
        });
        context.waitTicks(1);
        LOGGER.info("[config-qa] scroll screenshot={}", context.takeScreenshot("config_scroll_overview"));
        context.getInput().resizeWindow(1600, 900);
        context.runOnClient(client -> {
            client.options.getGuiScale().setValue(3);
            client.onResolutionChanged();
        });
    }

    private static void showConfigTab(ClientGameTestContext context, ConfigCategory tab) {
        context.runOnClient(client -> {
            var modules = ThePrisonsCore.getOrNull().modules().all();
            Module module = modules.stream().filter(m -> ConfigCategory.home(m) == tab).findFirst()
                    .or(() -> modules.stream().filter(m -> m.settings().stream().anyMatch(s -> s != m.keybind() && s.visible() && ConfigCategory.of(m, s.group()) == tab)).findFirst())
                    .or(() -> modules.stream().findFirst()).orElseThrow();
            ConfigScreen.focus(module, null);
            ConfigScreen screen = new ConfigScreen(null, ThePrisonsCore.getOrNull());
            setGuiField(screen, "tab", tab);
            setGuiField(screen, "selected", module);
            client.setScreen(screen);
        });
    }

    private static void setGuiField(ConfigScreen screen, String fieldName, Object value) {
        try {
            var field = ConfigScreen.class.getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(screen, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot set ConfigScreen." + fieldName, e);
        }
    }

    /** The Bandit category: the Spear Helper and the Bandit Macro. */
    private static void banditPages(ClientGameTestContext context) {
        showConfigTab(context, ConfigCategory.BANDIT);
        say(context, "Bandit", "Spear Helper: crosshair, sight point, aim assist; Bandit Macro.");
        hold(context, "bandits_spear_helper");
        context.setScreen(() -> null);
    }

    private static void design(ClientGameTestContext context) {
        context.runOnClient(client -> {
            ConfigScreen.focus(ThePrisonsCore.getOrNull().modules().get("design"), "theme");
            client.setScreen(ThePrisonsClient.configScreen(null, ThePrisonsCore.getOrNull()));
        });
        for (Theme theme : Theme.values()) {
            context.runOnClient(client -> DesignModule.get().themeSetting().set(theme));
            say(context, "Design: " + theme.label() + " theme", "Six colour themes - the whole mod follows the one you pick.");
            context.waitTicks(Math.max(20, SECONDS * 10));
        }
        LOGGER.info("[showcase] screenshot {}", context.takeScreenshot(String.format("showcase_%02d_themes", ++shot)));
        context.runOnClient(client -> DesignModule.get().themeSetting().set(Theme.COSMIC));
        for (int darkness : new int[]{20, 90, 60}) {
            context.runOnClient(client -> DesignModule.get().darknessSetting().set(darkness));
            say(context, "Card darkness " + darkness + "%", "How dark the cards behind HUD and menus are.");
            context.waitTicks(Math.max(20, SECONDS * 10));
        }
        context.runOnClient(client -> DesignModule.get().sleekFontSetting().set(false));
        say(context, "Boxy font off", "The vanilla Minecraft font ...");
        hold(context, "font_vanilla");
        context.runOnClient(client -> DesignModule.get().sleekFontSetting().set(true));
        say(context, "Boxy font on", "... and the mod's own bold, playful pixel font.");
        hold(context, "font_boxy");
        context.setScreen(() -> null);
    }

    private static void hudEditor(ClientGameTestContext context) {
        say(context, "HUD editor", "Every widget can be dragged and scaled - positions are saved.");
        context.runOnClient(client -> client.setScreen(ThePrisonsClient.hudEditor(null, ThePrisonsCore.getOrNull())));
        hold(context, "hud_editor");
        context.setScreen(() -> null);
    }

    private static void scoreboardAndSessionHud(TestServerContext server, ClientGameTestContext context) {
        say(context, "Scoreboard", "Cosmic's sidebar read and redrawn: player, economy, world and session rows.");
        hold(context, "scoreboard");
        // Mining: the session HUD starts its clock with the first block and reads XP / energy from the sidebar.
        say(context, "Session HUD", "Named after what you mine; UpTime, ores/s, XP/h and Energy/h - live.");
        context.runOnClient(client -> {
            client.player.setYaw(0.0F);
            client.player.setPitch(10.0F);
        });
        long xp = 16_321_009L;
        long energy = 429_210L;
        for (int second = 0; second < Math.max(8, SECONDS * 2); second++) {
            context.getInput().holdKeyFor(options -> options.attackKey, 20);
            xp += 1_850L;
            energy += 3_400L;
            server.runCommand("scoreboard players display name l3 cosmic \"81 (" + String.format(java.util.Locale.ROOT, "%,d", xp) + " XP)\"");
            server.runCommand("scoreboard players display name l6 cosmic \"(" + String.format(java.util.Locale.ROOT, "%,d", energy) + " / 1,259,523)\"");
            // Cosmic's action bar shows the rates per minute; the HUD shows them per hour
            server.runCommand("title @a actionbar \"XP: 3,050/min | Energy: 5,600/min\"");
            if (second % 3 == 0) {
                server.runCommand("fill -6 100 3 6 104 3 minecraft:redstone_ore replace minecraft:air");
            }
        }
        hold(context, "session_hud");
    }

    private static void widgets(TestServerContext server, ClientGameTestContext context) {
        say(context, "Pets, trinkets and cooldowns", "Read from chat - stay correct across relogs.");
        server.runCommand("tellraw @a \"(!) Anti XP Tax Pet [LVL 3]: no Guard XP Tax for 30m.\"");
        server.runCommand("tellraw @a \"(!) Lucky Pet is on cooldown for 13m 45s\"");
        server.runCommand("tellraw @a \"(!) Blink Trinket is on cooldown for 2m 10s\"");
        server.runCommand("tellraw @a \"You have 1 hrs 20 min of Rested XP at 2x XP!\"");
        server.runCommand("tellraw @a \"Current bonus: 12% Energy Gain from 3 Charge Orbs.\"");
        server.runCommand("give @a minecraft:bundle[custom_name=\"Gold Ore Satchel (1,820 / 2,304 Ores)\"]");
        server.runCommand("give @a minecraft:bundle[custom_name=\"Redstone Ore Satchel (2,304 / 2,304 Ores)\"]");
        server.runCommand("item replace entity @a armor.chest with minecraft:diamond_chestplate[damage=480]");
        server.runCommand("item replace entity @a armor.head with minecraft:iron_helmet");
        hold(context, "widgets");
        say(context, "Notifications", "Ready alerts, mentions and private messages slide in.");
        context.runOnClient(client -> {
            ThePrisonsHudRenderer.pushNotification("Lucky Pet ready", "Use it again now", Ui.theme().accent());
            ThePrisonsHudRenderer.pushNotification("Private message", "Steve: trade?", Ui.theme().title());
        });
        hold(context, "notifications");
    }

    private static void tabAndPlayers(TestServerContext server, ClientGameTestContext context) {
        say(context, "Shift + Tab: player list", "Own tab list (also on Tab): ranks grouped, faces, ping - click a rank to hide it.");
        context.runOnClient(client -> client.setScreen(new PlayerListScreen()));
        hold(context, "player_list");
        PlayerListScreen[] screen = new PlayerListScreen[1];
        context.runOnClient(client -> {
            screen[0] = new PlayerListScreen(client.player.getUuid());
            client.setScreen(screen[0]);
        });
        // the server's answer to /playerstats (singleplayer has none: the same lines as Cosmic sends)
        context.waitTicks(5);
        context.runOnClient(client -> io.theprisons.modules.qol.players.PlayerStats.ask("Player0"));
        context.waitTicks(2);
        for (String line : new String[]{"[/playerstats Player0]", "Blocks Mined: 1,821", "Mining: 17 (5,562 xp)",
                "Balance: $1.10K", "Bandit Kills: 1", "Playtime: 1d 18h 11m 39s", "Gang: Deutsch", "[/playerstats Player0]"}) {
            server.runCommand("tellraw @a \"" + line + "\"");
        }
        for (io.theprisons.modules.qol.players.PlayerViewer.Page page
                : io.theprisons.modules.qol.players.PlayerViewer.Page.values()) {
            context.runOnClient(client -> screen[0].showPage(page));
            say(context, "Player viewer: " + page.name().charAt(0) + page.name().substring(1).toLowerCase(java.util.Locale.ROOT),
                    "Click a player: /playerstats for anyone, inventory, vaults, skills and session for you.");
            hold(context, "viewer_" + page.name().toLowerCase(java.util.Locale.ROOT));
        }
        context.setScreen(() -> null);
        say(context, "Player cards", "Right-click a player: rank, gang, health and their whole gear.");
        context.runOnClient(client -> ((PlayerCardModule) module("player_cards")).show(client.player.getUuid()));
        hold(context, "player_card");
        say(context, "Sneak Trade", "Sneak + right-click a player sends /trade <name>.");
        hold(context, "sneak_trade");
    }

    private static void storage(ClientGameTestContext context) {
        say(context, "Storage overlay", "/pv shows every private vault as a card - use them all in one place.");
        context.runOnClient(client -> {
            Module storage = module("storage_overlay");
            ((Settings.IntSetting) storage.setting("vault_count")).set(9);
            ((StorageOverlayModule) storage).openOverlay();
        });
        hold(context, "storage_overlay");
        context.runOnClient(client -> {
            if (client.player.currentScreenHandler != client.player.playerScreenHandler) {
                client.player.closeHandledScreen();
            } else {
                client.setScreen(null);
            }
        });
    }

    private static final String[][] ITEM_PAGES = {
            {"Economy items", "Shards, dust, XP bottles, pages, keys, scrolls - colour = rarity.",
                    "prismarine_shard[custom_data={PublicBukkitValues:{\"cosmicprisons:custom_item_id\":\"shard\",\"cosmicprisons:shard_tier\":\"simple\"}}]",
                    "prismarine_shard[custom_data={PublicBukkitValues:{\"cosmicprisons:custom_item_id\":\"shard\",\"cosmicprisons:shard_tier\":\"elite\"}}]",
                    "prismarine_shard[custom_data={PublicBukkitValues:{\"cosmicprisons:custom_item_id\":\"shard\",\"cosmicprisons:shard_tier\":\"godly\"}}]",
                    "sugar[custom_name=\"Legendary Dust (10%)\"]", "sugar[custom_name=\"Elite Secret Dust\"]",
                    "experience_bottle[custom_name=\"Ultimate XP Bottle\"]", "paper[custom_name=\"Uncommon Page (45%)\"]",
                    "tripwire_hook[custom_name=\"Godly Bandit Box Key\"]", "map[custom_name=\"Mystery Elite Clue Scroll\"]",
                    "paper[custom_name=\"Legendary Randomization Scroll\"]", "chest[custom_name=\"Godly Contraband\"]",
                    "paper[custom_name=\"White Scroll\"]", "paper[custom_name=\"Black Scroll (75%)\"]",
                    "paper[custom_name=\"Absorber\"]", "paper[custom_name=\"Eraser (3)\"]",
                    "magma_cream[custom_name=\"3% Charge Orb\"]", "magma_cream[custom_name=\"12% Charge Orb\"]",
                    "magma_cream[custom_name=\"19% Charge Orb\"]", "nether_star[custom_name=\"Tool Prestige Token IV\"]",
                    "paper[custom_name=\"XP Booster (Right Click)\"]", "paper[custom_name=\"Energy Booster (Right Click)\"]",
                    "paper[custom_name=\"GP Booster\"]", "sunflower[custom_name=\"Cosmic Coins\"]",
                    "paper[custom_name=\"Money Note\"]", "chest[custom_name=\"Mystery Cosmic Crate (Holiday)\"]"},
            {"Enchant books and orbs", "Books for gear, crystal-ball orbs for pickaxe and spear enchants.",
                    "book[custom_name=\"Mystery Simple Enchant\"]", "book[custom_name=\"Mystery Elite Enchant\"]",
                    "book[custom_name=\"Mystery Godly Enchant\"]", "book[custom_name=\"Aegis I (17%)\"]",
                    "book[custom_name=\"Enlighted III (51%)\"]", "ender_eye[custom_name=\"Mystery Pickaxe Enchant Orb\"]",
                    "ender_eye[custom_name=\"Mystery Elite Pickaxe Enchant Orb\"]",
                    "ender_eye[custom_name=\"Mystery Godly Tool Enchant Orb\"]",
                    "ender_eye[custom_name=\"Legendary Spear Enchant Orb\"]",
                    "ender_eye[custom_name=\"Absolute Efficiency XI (100%)\"]", "ender_eye[custom_name=\"Magnet I (40%)\"]",
                    "paper[custom_name=\"Elite Enchant Re-Roll\"]", "paper[custom_name=\"Rare Pickaxe Enchant\"]",
                    "paper[custom_name=\"Prestige Protection Scroll II (50%)\"]"},
            {"Satchels", "One look per ore - ore, deepslate, block and random satchels.",
                    "bundle[custom_name=\"Coal Ore Satchel (0 / 2,304 Ores)\"]", "bundle[custom_name=\"Iron Ore Satchel (0 / 2,304 Ores)\"]",
                    "bundle[custom_name=\"Lapis Ore Satchel (0 / 2,304 Ores)\"]", "bundle[custom_name=\"Redstone Ore Satchel (0 / 2,304 Ores)\"]",
                    "bundle[custom_name=\"Gold Ore Satchel (0 / 2,304 Ores)\"]", "bundle[custom_name=\"Diamond Ore Satchel (0 / 2,304 Ores)\"]",
                    "bundle[custom_name=\"Emerald Ore Satchel (0 / 2,304 Ores)\"]", "bundle[custom_name=\"Prismarine Ore Satchel (0 / 2,304 Ores)\"]",
                    "bundle[custom_name=\"Deepslate Gold Ore Satchel (0 / 2,304 Ores)\"]",
                    "bundle[custom_name=\"Deepslate Diamond Ore Satchel (0 / 2,304 Ores)\"]",
                    "bundle[custom_name=\"Block of Emerald Satchel (0 / 2,304 Ores)\"]",
                    "bundle[custom_name=\"Block of Redstone Satchel (0 / 2,304 Ores)\"]",
                    "bundle[custom_name=\"Random Ore Satchel\"]"},
            {"Pets, masks and more", "Every pet as its own creature, masks as helms, the rest of Cosmic's items.",
                    "player_head[custom_name=\"Anti XP Tax Pet [LVL 1]\"]", "player_head[custom_name=\"Lucky Pet [LVL 3]\"]",
                    "player_head[custom_name=\"Wormhole Powerup Pet [LVL 1]\"]", "player_head[custom_name=\"Shockwave Pet [LVL 2]\"]",
                    "player_head[custom_name=\"Blacksmith Pet [LVL 1]\"]", "player_head[custom_name=\"Bandit King Pet [LVL 1]\"]",
                    "player_head[custom_name=\"Turkey Mask\"]", "player_head[custom_name=\"Nitro Mask\"]",
                    "player_head[custom_name=\"Sentinel Mask\"]", "player_head[custom_name=\"Random Mask\"]",
                    "beacon[custom_name=\"Sludge G-Kit\"]", "redstone_torch[custom_name=\"Meteor Flare\"]",
                    "paper[custom_name=\"Blink Trinket\"]", "paper[custom_name=\"Healing Trinket\"]",
                    "paper[custom_name=\"Cosmo-Slot Bot Ticket Sleeve (5x)\"]", "paper[custom_name=\"Inmate Rations (Right Click) (60m)\"]",
                    "paper[custom_name=\"Overdrive Powerup\"]", "paper[custom_name=\"II Rare Candy II\"]",
                    "paper[custom_name=\"Skill Token\"]", "iron_door[custom_name=\"Godly Cell Door Upgrade\"]",
                    "paper[custom_name=\"Lucky Charm\"]", "rabbit_foot[custom_name=\"Rabbit's Foot\"]",
                    "name_tag[custom_name=\"Item Nametag\"]", "amethyst_shard[custom_name=\"Item Lore Crystal\"]",
                    "lead[custom_name=\"Pet Leash\"]", "egg[custom_name=\"Random Overworld Boss Egg\"]",
                    "paper[custom_name=\"Skill Tree Reset\"]", "paper[custom_name=\"KILL MESSAGE \\\"Buzzards\\\"\"]",
                    "paper[custom_name=\"+1 PV Row\"]"},
            {"Gear", "Pickaxes, swords and spears in the MMORPG look.",
                    "wooden_pickaxe", "stone_pickaxe", "iron_pickaxe", "golden_pickaxe", "diamond_pickaxe", "netherite_pickaxe",
                    "iron_sword", "diamond_sword", "netherite_sword", "iron_spear", "diamond_spear",
                    "leather_helmet", "chainmail_chestplate", "iron_leggings", "golden_boots", "diamond_helmet",
                    "netherite_chestplate"},
    };

    private static void items(TestServerContext server, ClientGameTestContext context) {
        server.runCommand("gamemode survival @a");
        for (String[] page : ITEM_PAGES) {
            server.runCommand("clear @a");
            for (int i = 2; i < page.length; i++) {
                server.runCommand("give @a minecraft:" + page[i]);
            }
            say(context, page[0], page[1]);
            context.waitTicks(10);
            context.setScreen(() -> new InventoryScreen(MinecraftClient.getInstance().player));
            context.waitTicks(10);
            // hover the first item of the main inventory for its tooltip
            double[] cursor = context.computeOnClient(client -> {
                double f = client.getWindow().getScaleFactor();
                int sx = client.getWindow().getScaledWidth() / 2 - 88 + 8 + 8;
                int sy = client.getWindow().getScaledHeight() / 2 - 83 + 84 + 8;
                return new double[]{sx * f, sy * f};
            });
            context.getInput().setCursorPos(cursor[0], cursor[1]);
            hold(context, "items_" + page[0].toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z]+", "_"));
            context.setScreen(() -> null);
        }
        server.runCommand("clear @a");
        server.runCommand("gamemode creative @a");
    }

    private static void wornGear(TestServerContext server, ClientGameTestContext context) {
        context.runOnClient(client -> client.options.setPerspective(Perspective.THIRD_PERSON_FRONT));
        server.runCommand("tp @a 0 100 0 180 0");
        String[][] sets = {{"diamond", "Diamond"}, {"netherite", "Netherite"}, {"golden", "Gold"}, {"iron", "Iron"}};
        for (String[] set : sets) {
            for (String piece : new String[]{"head:helmet", "chest:chestplate", "legs:leggings", "feet:boots"}) {
                String[] p = piece.split(":");
                server.runCommand("item replace entity @a armor." + p[0] + " with minecraft:" + set[0] + "_" + p[1]);
            }
            say(context, set[1] + " armour", "Worn armour lies thin on the body, like a skin.");
            hold(context, "armor_" + set[0]);
        }
        for (String mask : new String[]{"Turkey Mask", "Nitro Mask", "Lucky Leprechaun Mask", "Sentinel Mask"}) {
            server.runCommand("item replace entity @a armor.head with minecraft:player_head[custom_name=\"" + mask + "\"]");
            say(context, mask, "Masks are drawn as full fantasy helms - also when attached to a helmet.");
            hold(context, "mask_" + mask.toLowerCase(java.util.Locale.ROOT).replace(' ', '_'));
        }
        context.runOnClient(client -> client.options.setPerspective(Perspective.FIRST_PERSON));
        server.runCommand("clear @a");
    }
}
