package io.theprisons.modules.qol.items;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The bundled HD V2 pack: it overrides PNGs at existing paths and nothing else, so every missing texture falls through to the classic one. */
class HdPackAssetsTest {
    private static final Path RES = Path.of("src/main/resources");
    private static final Path PACK = RES.resolve("resourcepacks/theprisons_items_hd");
    private static final Path TEXTURES = Path.of("assets/theprisons/textures/item/prisons");

    private static List<Path> packPngs() throws IOException {
        try (Stream<Path> s = Files.walk(PACK)) {
            return s.filter(p -> p.toString().endsWith(".png")).map(PACK::relativize).sorted().toList();
        }
    }

    @Test
    void thePackIsThereWithAMcmetaAndOnlyTextures() throws IOException {
        assertTrue(Files.isRegularFile(PACK.resolve("pack.mcmeta")));
        assertFalse(Files.exists(PACK.resolve("assets/theprisons/models")), "no duplicated model json: the pack only replaces PNGs");
        assertFalse(Files.exists(PACK.resolve("assets/theprisons/items")));
        try (Stream<Path> s = Files.walk(PACK)) {
            assertTrue(s.filter(Files::isRegularFile).allMatch(p -> p.toString().endsWith(".png") || p.getFileName().toString().equals("pack.mcmeta")));
        }
        assertEquals(123 - 1, packPngs().size(), "122 textures (the export has 122 PNGs and the mcmeta)");
    }

    @Test
    void everyHdTextureReplacesAClassicOneAtTheSamePath() throws IOException {
        List<String> orphans = new ArrayList<>();
        for (Path rel : packPngs()) {
            if (!Files.isRegularFile(RES.resolve(rel))) {
                orphans.add(rel.toString());
            }
        }
        assertTrue(orphans.isEmpty(), "HD textures with no classic texture at their path (they would never be shown): " + orphans);
    }

    @Test
    void powerupsUseTheNamesOfTheirModels() throws IOException {
        // the models are item/prisons/powerup/<family>.png and <family>_<tier>.png, not a folder per family
        assertTrue(Files.isRegularFile(PACK.resolve(TEXTURES.resolve("powerup/bogo_elite.png"))));
        assertTrue(Files.isRegularFile(PACK.resolve(TEXTURES.resolve("powerup/bogo.png"))));
        assertTrue(Files.isRegularFile(PACK.resolve(TEXTURES.resolve("powerup/double_tap_godly.png"))));
        assertFalse(Files.exists(PACK.resolve(TEXTURES.resolve("powerup/bogo"))), "no per-family folder");
        long powerups = packPngs().stream().filter(p -> p.toString().contains("/powerup/")).count();
        assertEquals(28, powerups, "4 families x (base + 6 tiers)");
    }

    @Test
    void skippedFamiliesAreNotInThePackAndKeepTheirClassicTexture() throws IOException {
        List<String> rels = packPngs().stream().map(Path::toString).toList();
        for (String skipped : List.of("/pet/", "satchel", "/reroll/", "/spear_orb/", "/shard/")) {
            assertTrue(rels.stream().noneMatch(r -> r.contains(skipped)), skipped + " was skipped in the quality review and must stay classic");
        }
        // and the classic art for them exists, so the fallback has something to show
        assertTrue(Files.isRegularFile(RES.resolve("assets/theprisons/textures/item/prisons/shard/godly.png")));
        assertTrue(Files.exists(RES.resolve("assets/theprisons/textures/item/prisons/pet")));
        assertTrue(Files.exists(RES.resolve("assets/theprisons/textures/item/prisons/reroll")));
    }

    @Test
    void onlyPartiallyDeliveredFamiliesHaveWhatWasDelivered() throws IOException {
        List<String> rels = packPngs().stream().map(Path::toString).toList();
        assertEquals(5, rels.stream().filter(r -> r.contains("/prestige_token/")).count(), "prestige levels 1-5 only");
        assertTrue(rels.stream().noneMatch(r -> r.endsWith("prestige_token/level_6.png")));
        assertEquals(10, rels.stream().filter(r -> r.contains("/mask/")).count(), "10 of 15 masks");
        for (String missing : List.of("simple", "turkey", "ultimate", "uncommon", "valor")) {
            assertTrue(rels.stream().noneMatch(r -> r.endsWith("mask/" + missing + ".png")), missing + " mask is not delivered yet");
            assertTrue(Files.isRegularFile(RES.resolve(TEXTURES.resolve("mask/" + missing + ".png"))), "classic " + missing + " mask stays");
        }
    }

    @Test
    void everyTextureIs256SquareWithATransparentBackground() throws IOException {
        for (Path rel : packPngs()) {
            BufferedImage img = ImageIO.read(PACK.resolve(rel).toFile());
            assertEquals(256, img.getWidth(), rel.toString());
            assertEquals(256, img.getHeight(), rel.toString());
            assertTrue(img.getColorModel().hasAlpha(), rel + " has no alpha channel");
            for (int[] corner : new int[][]{{0, 0}, {255, 0}, {0, 255}, {255, 255}}) {
                assertEquals(0, img.getRGB(corner[0], corner[1]) >>> 24, rel + ": the corners must be transparent (no baked background)");
            }
            boolean opaquePixel = false;
            for (int y = 0; y < 256 && !opaquePixel; y += 4) {
                for (int x = 0; x < 256; x += 4) {
                    if ((img.getRGB(x, y) >>> 24) > 200) {
                        opaquePixel = true;
                        break;
                    }
                }
            }
            assertTrue(opaquePixel, rel + " is empty");
        }
    }

    // ── the texture source setting is untouched ─────────────────────────────

    @Test
    void theTextureSourceLogicDoesNotKnowTheHdPack() throws IOException {
        String src = Files.readString(Path.of("src/main/java/io/theprisons/modules/qol/items/ItemLookModule.java"));
        int start = src.indexOf("public Identifier model(ItemStack stack, Identifier current)");
        int end = src.indexOf("private static final Map<net.minecraft.item.Item, Identifier> GEAR");
        assertTrue(start > 0 && end > start);
        String body = src.substring(start, end);
        assertTrue(body.contains("source.get() == Source.OFF"));
        assertTrue(body.contains("source.get() == Source.COSMIC_FIRST && COSMIC_TEXTURES && changedByOthers"));
        assertFalse(body.contains("texturePack") || body.contains("HD") || body.contains("hdV2"), "HD V2 chooses the art, never the model: the source priority stays as before");
    }

    @Test
    void theLookPackIsStillAppendedByTheMixinAndPrisonsItemsIsNotTouchedByTheHdPack() throws IOException {
        String mixin = Files.readString(Path.of("src/main/java/io/theprisons/mixin/ThePrisonsResourcePackManagerMixin.java"));
        assertTrue(mixin.contains("resourcepacks/theprisons_look") && mixin.contains("\"theprisons_look\""));
        assertTrue(mixin.contains("HdPackSync.PACK_PATH"));
        assertEquals("theprisons_items_hd", HdPackSync.PACK_ID);
        assertEquals("ThePrisons HD V2 Items", HdPackSync.PACK_NAME);
    }
}
