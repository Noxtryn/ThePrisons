package com.freelocs.theprisons.gametest;

import com.freelocs.theprisons.core.ThePrisonsCore;
import com.freelocs.theprisons.core.module.Module;
import com.freelocs.theprisons.core.setting.Settings;
import com.freelocs.theprisons.modules.qol.storage.StorageOverlayModule;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.client.option.Perspective;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

/**
 * Visual check of the item look and the storage overlay: worn masks (themes, tier masks, a mask on a helmet) from
 * the front, the inventory with rarity frames, and the storage overlay layout. Writes screenshots (see the log) -
 * no assertions on pixels, the pictures are reviewed by hand.
 */
public final class ItemLookClientGameTest implements FabricClientGameTest {
    private static final Logger LOGGER = LoggerFactory.getLogger("ThePrisons/GameTest/Look");

    @Override
    public void runTest(ClientGameTestContext context) {
        if (ShowcaseClientGameTest.ENABLED) {
            return; // the showcase run shows only the showcase
        }
        // No welcome setup on this first join: it would cover the screenshots.
        context.runOnClient(client -> ((Settings.BoolSetting) ThePrisonsCore.getOrNull().modules().get("click_gui")
                .setting("setup_done")).set(true));
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            var server = singleplayer.getServer();
            server.runCommand("gamemode creative @a");
            server.runCommand("time set day");
            server.runCommand("gamerule doDaylightCycle false");
            server.runCommand("tp @a 0 200 0 0 0");
            server.runCommand("setblock 0 199 0 minecraft:glass");
            context.waitTicks(40);
            context.setScreen(() -> null);
            context.runOnClient(client -> {
                client.options.setPerspective(Perspective.THIRD_PERSON_FRONT);
                client.options.hudHidden = true;
            });
            String[] heads = {
                    "minecraft:player_head[custom_name=\"Turkey Mask\"]",
                    "minecraft:player_head[custom_name=\"Nitro Mask\"]",
                    "minecraft:player_head[custom_name=\"Outpost Mask\"]",
                    "minecraft:player_head[custom_name=\"Lucky Leprechaun Mask\"]",
                    "minecraft:player_head[custom_name=\"Valor Mask\"]",
                    "minecraft:player_head[custom_name=\"Random Mask\"]",
                    "minecraft:player_head[custom_name=\"Sentinel Mask\"]",
                    "minecraft:player_head[custom_name=\"Anonymous Mask\"]",
                    "minecraft:player_head[custom_name=\"Prisoner Mask\"]",
                    "minecraft:player_head[custom_name=\"Sludge Helmet 30\",lore=[\"Mask (Clue Master Mask)\"]]",
                    "minecraft:diamond_helmet[lore=[\"Attached: Nitro Mask\"]]",
            };
            String[] names = {"turkey", "nitro", "outpost", "leprechaun", "valor", "simple", "sentinel", "anonymous", "prisoner",
                    "helmet_clue", "helmet_nitro"};
            for (int i = 0; i < heads.length; i++) {
                server.runCommand("item replace entity @a armor.head with " + heads[i]);
                server.runCommand("tp @a 0 200 0 0 0");
                context.waitTicks(10);
                Path shot = context.takeScreenshot("mask_" + names[i]);
                LOGGER.info("[look] screenshot {}", shot.toAbsolutePath());
            }
            // Worn armour (equipment textures) front and back.
            String[][] sets = {{"diamond", "diamond"}, {"netherite", "netherite"}, {"golden", "gold"},
                    {"leather", "leather_red"}};
            for (String[] set : sets) {
                String m = set[0];
                String dye = set[1].equals("leather_red") ? "[dyed_color=11546150]" : "";
                server.runCommand("item replace entity @a armor.head with minecraft:" + m + "_helmet" + dye);
                server.runCommand("item replace entity @a armor.chest with minecraft:" + m + "_chestplate" + dye);
                server.runCommand("item replace entity @a armor.legs with minecraft:" + m + "_leggings" + dye);
                server.runCommand("item replace entity @a armor.feet with minecraft:" + m + "_boots" + dye);
                server.runCommand("tp @a 0 200 0 0 0");
                context.waitTicks(10);
                LOGGER.info("[look] screenshot {}", context.takeScreenshot("armor_" + set[1]).toAbsolutePath());
            }
            server.runCommand("tp @a 0 200 0 180 0");
            context.waitTicks(10);
            LOGGER.info("[look] screenshot {}", context.takeScreenshot("armor_back").toAbsolutePath());
            server.runCommand("item replace entity @a armor.chest with minecraft:air");
            server.runCommand("item replace entity @a armor.legs with minecraft:air");
            server.runCommand("item replace entity @a armor.feet with minecraft:air");

            // Side view of the turkey (tail, comb).
            server.runCommand("item replace entity @a armor.head with " + heads[0]);
            server.runCommand("tp @a 0 200 0 90 0");
            context.waitTicks(10);
            LOGGER.info("[look] screenshot {}", context.takeScreenshot("mask_turkey_side").toAbsolutePath());

            // A wall of the retextured blocks, seen in first person.
            context.runOnClient(client -> client.options.setPerspective(Perspective.FIRST_PERSON));
            String[] wall = {"stone", "cobblestone", "deepslate", "dirt", "grass_block", "sand", "gravel", "oak_planks",
                    "oak_log", "coal_ore", "iron_ore", "copper_ore", "gold_ore", "redstone_ore", "lapis_ore", "diamond_ore",
                    "emerald_ore", "deepslate_diamond_ore", "nether_quartz_ore", "gold_block", "diamond_block",
                    "redstone_block", "emerald_block", "obsidian"};
            for (int i = 0; i < wall.length; i++) {
                int bx = -6 + i % 12;
                int by = 201 - i / 12;
                server.runCommand("setblock " + bx + " " + by + " 6 minecraft:" + wall[i]);
            }
            server.runCommand("tp @a 0 200 0 0 8");
            context.waitTicks(30);
            LOGGER.info("[look] screenshot {}", context.takeScreenshot("blocks_wall").toAbsolutePath());

            context.runOnClient(client -> {
                client.options.setPerspective(Perspective.FIRST_PERSON);
                client.options.hudHidden = false;
            });
            server.runCommand("gamemode survival @a");
            server.runCommand("clear @a");
            String[] items = {
                    "minecraft:diamond_pickaxe", "minecraft:iron_chestplate", "minecraft:golden_helmet",
                    "minecraft:netherite_sword", "minecraft:diamond_spear",
                    "minecraft:player_head[custom_name=\"Turkey Mask\"]",
                    "minecraft:player_head[custom_name=\"Anti XP Tax Pet [LVL 1]\"]",
                    "minecraft:prismarine_shard[custom_data={PublicBukkitValues:{\"cosmicprisons:custom_item_id\":\"shard\",\"cosmicprisons:shard_tier\":\"godly\"}}]",
                    "minecraft:sugar[custom_data={PublicBukkitValues:{\"cosmicprisons:custom_item_id\":\"pickaxe_enchant_dust\",\"cosmicprisons:pickaxe_dust_tier\":\"ELITE\"}}]",
                    "minecraft:magma_cream[custom_data={PublicBukkitValues:{\"cosmicprisons:custom_item_id\":\"charge_orb\",\"cosmicprisons:charge_orb_percent\":12}}]",
                    "minecraft:diamond_sword", "minecraft:golden_pickaxe", "minecraft:netherite_chestplate",
                    "minecraft:player_head[custom_name=\"Lucky Pet [LVL 3]\"]",
                    "minecraft:player_head[custom_name=\"Wormhole Powerup Pet [LVL 1]\"]",
                    "minecraft:ender_eye[custom_name=\"Godly Enchant Orb\"]",
                    "minecraft:paper[custom_name=\"Elite Enchant Re-Roll\"]",
                    "minecraft:paper[custom_data={PublicBukkitValues:{\"cosmicprisons:custom_item_id\":\"money_note\"}}]",
                    "minecraft:book[custom_data={PublicBukkitValues:{\"cosmicprisons:custom_item_id\":\"mystery_enchant_book\",\"cosmicprisons:mystery_tier\":\"LEGENDARY\"}}]",
                    // enchant orbs (not books), satchels and the last items found in the logs
                    "minecraft:ender_eye[custom_name=\"Mystery Godly Tool Enchant Orb\"]",
                    "minecraft:ender_eye[custom_name=\"Mystery Elite Pickaxe Enchant Orb\"]",
                    "minecraft:ender_eye[custom_name=\"Legendary Spear Enchant Orb\"]",
                    "minecraft:ender_eye[custom_name=\"Absolute Efficiency XI (100%)\"]",
                    "minecraft:book[custom_name=\"Aegis I (17%)\"]",
                    "minecraft:bundle[custom_name=\"Gold Ore Satchel (0 / 2,304 Ores)\"]",
                    "minecraft:bundle[custom_name=\"Deepslate Diamond Ore Satchel (0 / 2,304 Ores)\"]",
                    "minecraft:bundle[custom_name=\"Block of Emerald Satchel (0 / 2,304 Ores)\"]",
                    "minecraft:bundle[custom_name=\"Random Ore Satchel\"]",
                    "minecraft:paper[custom_name=\"Lucky Charm\"]", "minecraft:name_tag[custom_name=\"Item Nametag\"]",
                    "minecraft:paper[custom_name=\"KILL MESSAGE \\\"Buzzards\\\"\"]", "minecraft:lead[custom_name=\"Pet Leash\"]",
                    "minecraft:egg[custom_name=\"Random Overworld Boss Egg\"]", "minecraft:paper[custom_name=\"Skill Tree Reset\"]",
            };
            for (String item : items) {
                server.runCommand("give @a " + item);
            }
            context.waitTicks(10);
            context.setScreen(() -> new InventoryScreen(net.minecraft.client.MinecraftClient.getInstance().player));
            context.waitTicks(10);
            LOGGER.info("[look] screenshot {}", context.takeScreenshot("inventory_rarity").toAbsolutePath());
            context.setScreen(() -> null);

            // Storage overlay layout with 6 (unopened) vaults.
            context.runOnClient(client -> {
                Module storage = ThePrisonsCore.getOrNull().modules().get("storage_overlay");
                ((Settings.IntSetting) storage.setting("vault_count")).set(6);
                // a sidebar like the server's, for the custom scoreboard
                ((StorageOverlayModule) storage).openOverlay();
            });
            context.waitTicks(30);
            LOGGER.info("[look] screenshot {}", context.takeScreenshot("storage_overlay").toAbsolutePath());
            context.runOnClient(client -> client.player.closeHandledScreen());
            context.waitTicks(5);

            // Scoreboard on the HUD, the dashboard pages and the HUD editor.
            context.waitTicks(20);
            LOGGER.info("[look] screenshot {}", context.takeScreenshot("hud_scoreboard").toAbsolutePath());
            context.runOnClient(client -> client.setScreen(com.freelocs.theprisons.ThePrisonsClient.dashboard(null, ThePrisonsCore.getOrNull())));
            context.waitTicks(30);
            LOGGER.info("[look] screenshot {}", context.takeScreenshot("dashboard_overview").toAbsolutePath());
            for (String name : new String[]{"dashboard_design", "dashboard_controls", "dashboard_hud"}) {
                context.getInput().pressKey(org.lwjgl.glfw.GLFW.GLFW_KEY_TAB);
                context.waitTicks(20);
                LOGGER.info("[look] screenshot {}", context.takeScreenshot(name).toAbsolutePath());
            }
            context.runOnClient(client -> client.setScreen(com.freelocs.theprisons.ThePrisonsClient.hudEditor(null, ThePrisonsCore.getOrNull())));
            context.waitTicks(30);
            LOGGER.info("[look] screenshot {}", context.takeScreenshot("hud_editor").toAbsolutePath());
            context.setScreen(() -> null);
            context.getInput().holdKey(org.lwjgl.glfw.GLFW.GLFW_KEY_TAB);
            context.waitTicks(10);
            LOGGER.info("[look] screenshot {}", context.takeScreenshot("better_tab").toAbsolutePath());
            context.getInput().releaseKey(org.lwjgl.glfw.GLFW.GLFW_KEY_TAB);
            context.waitTicks(5);

            // Player cards: Shift + Tab opens the player list, a selected player shows the card; the HUD card.
            context.getInput().holdKey(org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_SHIFT);
            context.getInput().holdKey(org.lwjgl.glfw.GLFW.GLFW_KEY_TAB);
            context.waitTicks(5);
            context.getInput().releaseKey(org.lwjgl.glfw.GLFW.GLFW_KEY_TAB);
            context.getInput().releaseKey(org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_SHIFT);
            context.waitTicks(15);
            LOGGER.info("[look] screenshot {} (screen: {})", context.takeScreenshot("player_list").toAbsolutePath(),
                    context.computeOnClient(client -> String.valueOf(client.currentScreen)));
            context.runOnClient(client -> client.setScreen(
                    new com.freelocs.theprisons.modules.qol.players.PlayerListScreen(client.player.getUuid())));
            context.waitTicks(15);
            LOGGER.info("[look] screenshot {}", context.takeScreenshot("player_list_card").toAbsolutePath());
            context.setScreen(() -> null);
            context.runOnClient(client -> ((com.freelocs.theprisons.modules.qol.players.PlayerCardModule)
                    ThePrisonsCore.getOrNull().modules().get("player_cards")).show(client.player.getUuid()));
            context.waitTicks(15);
            LOGGER.info("[look] screenshot {}", context.takeScreenshot("player_card_hud").toAbsolutePath());
        }
    }
}
