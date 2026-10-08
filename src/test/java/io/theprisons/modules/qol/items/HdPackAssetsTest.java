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

    private static List<Path> packModels() throws IOException {
        Path models = PACK.resolve("assets/theprisons/models");
        if (!Files.exists(models)) {
            return List.of();
        }
        try (Stream<Path> s = Files.walk(models)) {
            return s.filter(p -> p.toString().endsWith(".json")).map(PACK::relativize).sorted().toList();
        }
    }

    @Test
    void thePackHasAMcmetaTexturesAndOnlyTheTwelveRandomModelOverrides() throws IOException {
        assertTrue(Files.isRegularFile(PACK.resolve("pack.mcmeta")));
        assertFalse(Files.exists(PACK.resolve("assets/theprisons/items")), "no item definitions: the classic ones stay");
        try (Stream<Path> s = Files.walk(PACK)) {
            assertTrue(s.filter(Files::isRegularFile).allMatch(p -> p.toString().endsWith(".png") || p.toString().endsWith(".json") || p.getFileName().toString().equals("pack.mcmeta")));
        }
        List<Path> models = packModels();
        assertEquals(12, models.size(), "6 Book Random + 6 Contraband Random technical ids point to one canonical picture each: " + models);
        for (Path m : models) {
            String name = m.toString();
            assertTrue(name.matches("assets/theprisons/models/item/prisons/(book|contraband)/random_(simple|uncommon|elite|ultimate|legendary|godly)\\.json"), name);
        }
        assertEquals(98, packPngs().size(), "98 textures");
    }

    @Test
    void theRandomOverridesReplaceTheClassicModelsAtTheSamePathAndShareOnePicture() throws IOException {
        for (String family : List.of("book", "contraband")) {
            String texture = null;
            for (String tier : List.of("simple", "uncommon", "elite", "ultimate", "legendary", "godly")) {
                Path override = PACK.resolve("assets/theprisons/models/item/prisons/" + family + "/random_" + tier + ".json");
                Path classic = RES.resolve("assets/theprisons/models/item/prisons/" + family + "/random_" + tier + ".json");
                assertTrue(Files.isRegularFile(classic), "the classic model exists, so the override really replaces it: " + classic);
                com.google.gson.JsonObject o = com.google.gson.JsonParser.parseString(Files.readString(override)).getAsJsonObject();
                com.google.gson.JsonObject c = com.google.gson.JsonParser.parseString(Files.readString(classic)).getAsJsonObject();
                assertEquals(c.get("parent").getAsString(), o.get("parent").getAsString());
                assertEquals(c.getAsJsonObject("textures").keySet(), o.getAsJsonObject("textures").keySet());
                String layer = o.getAsJsonObject("textures").get("layer0").getAsString();
                assertEquals("theprisons:item/prisons/" + family + "/random", layer, "one canonical Random picture per family");
                texture = layer;
            }
            assertTrue(Files.isRegularFile(PACK.resolve("assets/theprisons/textures/item/prisons/" + family + "/random.png")), texture + " is in the pack");
        }
    }

    @Test
    void everyHdTextureReplacesAClassicOneOrIsUsedByAnOverrideModel() throws IOException {
        StringBuilder overrides = new StringBuilder();
        for (Path m : packModels()) {
            overrides.append(Files.readString(PACK.resolve(m)));
        }
        List<String> orphans = new ArrayList<>();
        for (Path rel : packPngs()) {
            String id = "theprisons:item/prisons/" + TEXTURES.relativize(rel.subpath(0, rel.getNameCount())).toString().replace(".png", "");
            boolean replaces = Files.isRegularFile(RES.resolve(rel));
            boolean used = overrides.toString().contains("\"" + id + "\"");
            if (!replaces && !used) {
                orphans.add(rel.toString());
            }
        }
        assertTrue(orphans.isEmpty(), "HD textures that would never be shown (no classic texture at the path, no override model uses them): " + orphans);
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
    void rejectedAndUnverifiedFamiliesAreNotInThePackAndKeepTheirClassicTexture() throws IOException {
        List<String> rels = packPngs().stream().map(Path::toString).toList();
        for (String skipped : List.of("/pet/", "satchel", "/reroll/", "/spear_orb/", "/shard/", "/charge_orb/", "/mask/")) {
            assertTrue(rels.stream().noneMatch(r -> r.contains(skipped)), skipped + " was rejected / skipped in the quality review and must stay classic");
        }
        for (String family : List.of("shard/godly.png", "charge_orb/stage_1.png", "charge_orb/stage_4.png", "mask/anonymous.png", "mask/turkey.png")) {
            assertTrue(Files.isRegularFile(RES.resolve(TEXTURES.resolve(family))), "the classic " + family + " is what shows now");
        }
        assertTrue(Files.exists(RES.resolve(TEXTURES.resolve("pet"))));
        assertTrue(Files.exists(RES.resolve(TEXTURES.resolve("reroll"))));
    }

    @Test
    void prestigeLevelsOneToFiveOnly() throws IOException {
        List<String> rels = packPngs().stream().map(Path::toString).toList();
        assertEquals(5, rels.stream().filter(r -> r.contains("/prestige_token/")).count());
        assertTrue(rels.stream().noneMatch(r -> r.endsWith("prestige_token/level_6.png")));
        assertTrue(Files.isRegularFile(RES.resolve(TEXTURES.resolve("prestige_token/level_6.png"))), "level 6 stays classic");
    }

    /** VISUAL_ASSET_MAP.csv is the import gate: only VERIFIED_MAPPING rows may be in the pack, everything else must be absent. */
    @Test
    void theVisualAssetMapIsTheImportGate() throws IOException {
        List<String> lines = Files.readAllLines(Path.of("docs/textures/HD_V2_VISUAL_ASSET_MAP.csv"));
        assertTrue(lines.size() > 40);
        int verified = 0;
        int blocked = 0;
        for (String line : lines.subList(1, lines.size())) {
            String[] c = line.split(",", 5);
            String technical = c[0];
            String status = c[3];
            Path texture = PACK.resolve(TEXTURES.resolve(technical + ".png"));
            Path model = PACK.resolve("assets/theprisons/models/item/prisons/" + technical + ".json");
            if (status.equals("VERIFIED_MAPPING")) {
                verified++;
                assertTrue(Files.isRegularFile(model), technical + " is verified: its override model must be in the pack");
                assertTrue(Files.isRegularFile(PACK.resolve(TEXTURES.resolve(c[1] + ".png"))), technical + " -> " + c[1] + " must exist");
            } else {
                blocked++;
                assertFalse(Files.exists(texture), technical + " is " + status + ": no texture in the pack");
                assertFalse(Files.exists(model), technical + " is " + status + ": no model in the pack");
            }
        }
        assertEquals(12, verified);
        assertTrue(blocked >= 30, "blocked rows: " + blocked);
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
