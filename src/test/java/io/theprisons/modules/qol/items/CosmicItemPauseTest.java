package io.theprisons.modules.qol.items;

import io.theprisons.modules.FeatureProfile;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Cosmic item look is paused (docs/development/COSMIC_ITEM_PAUSE_PLAN.md): its code, models, textures, the 35 standard textures,
 * the comic filter and the tooltip look pack live in src/paused/cosmic-items and must not reach the shipped mod.
 */
class CosmicItemPauseTest {
    private static final Path MAIN = Path.of("src/main/resources");
    private static final Path PAUSED = Path.of("src/paused/cosmic-items");

    @Test
    void pausedAssetsAreNotInTheShippedResources() {
        for (String path : List.of("assets/theprisons/items", "assets/theprisons/models", "assets/theprisons/textures/item",
                "assets/theprisons/textures/entity", "assets/theprisons/textures/gui/sprites/tooltip",
                "assets/theprisons/textures/gui/sprites/tier_frame", "resourcepacks")) {
            assertFalse(Files.exists(MAIN.resolve(path)), path);
            if (!path.equals("resourcepacks")) {
                assertTrue(Files.isDirectory(PAUSED.resolve("resources").resolve(path)), "kept in the paused tree: " + path);
            }
        }
        assertTrue(Files.isDirectory(PAUSED.resolve("resources/resourcepacks/theprisons_items_standard")));
        assertTrue(Files.isDirectory(PAUSED.resolve("resources/resourcepacks/theprisons_look")));
    }

    @Test
    void pausedMixinsAreNotRegistered() throws IOException {
        String shipped = Files.readString(MAIN.resolve("theprisons.mixins.json"));
        String fabric = Files.readString(MAIN.resolve("fabric.mod.json"));
        for (String mixin : List.of("ThePrisonsItemModelManagerMixin", "ThePrisonsDrawContextMixin", "ThePrisonsSpriteContentsMixin",
                "ThePrisonsTextureContentsMixin", "ThePrisonsResourcePackManagerMixin", "ThePrisonsHandledScreenTooltipMixin")) {
            assertFalse(shipped.contains(mixin), mixin);
            assertFalse(Files.exists(Path.of("src/main/java/io/theprisons/mixin", mixin + ".java")), mixin);
        }
        assertFalse(fabric.contains("cosmic-items"), "the paused mixin config is not loaded");
    }

    @Test
    void itemLookIsNotPartOfTheFeatureProfile() {
        assertFalse(FeatureProfile.ON.contains("item_look"));
        assertFalse(FeatureProfile.FREE.contains("item_look"));
        assertFalse(Files.exists(Path.of("src/main/java/io/theprisons/modules/qol/items/ItemLookModule.java")));
        assertFalse(Files.exists(Path.of("src/main/java/io/theprisons/modules/general/look")));
    }
}
