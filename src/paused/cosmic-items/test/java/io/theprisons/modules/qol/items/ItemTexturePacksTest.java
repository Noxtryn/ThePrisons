package io.theprisons.modules.qol.items;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ItemTexturePacksTest {
    static final String PAUSED = "src/paused/cosmic-items/resources";
    private static final Path PACK = Path.of(PAUSED, "resourcepacks/theprisons_items_standard");
    private static final Path TEXTURES = PACK.resolve("assets/theprisons/textures/item/prisons");
    private static final List<String> FAMILIES = List.of("shard", "contraband", "book", "book_revealed", "key", "charge_orb");

    @Test
    void standardPackContainsExactlyTheApprovedModelPathsAndNoModels() throws IOException {
        assertTrue(Files.isRegularFile(PACK.resolve("pack.mcmeta")));
        assertFalse(Files.exists(PACK.resolve("assets/theprisons/models")), "existing model JSONs remain authoritative");
        List<Path> pngs;
        try (Stream<Path> stream = Files.walk(TEXTURES)) {
            pngs = stream.filter(path -> path.toString().endsWith(".png")).map(TEXTURES::relativize).sorted().toList();
        }
        assertEquals(35, pngs.size());
        for (Path rel : pngs) {
            assertTrue(FAMILIES.stream().anyMatch(family -> rel.startsWith(family)), rel.toString());
            assertTrue(Files.isRegularFile(Path.of(PAUSED, "assets/theprisons/textures/item/prisons").resolve(rel)), "classic fallback/model path " + rel);
        }
    }

    @Test
    void allImportedAssetsAreNormalizedTransparent256SquarePngsWithContent() throws IOException {
        try (Stream<Path> stream = Files.walk(TEXTURES)) {
            for (Path png : stream.filter(path -> path.toString().endsWith(".png")).toList()) {
                BufferedImage image = ImageIO.read(png.toFile());
                assertNotNull(image, png.toString());
                assertEquals(256, image.getWidth(), png.toString());
                assertEquals(256, image.getHeight(), png.toString());
                assertTrue(image.getColorModel().hasAlpha(), png + " has no alpha");
                assertEquals(0, image.getRGB(0, 0) >>> 24, png + " top-left background must be transparent");
                assertTrue(hasOpaquePixel(image), png + " is empty");
            }
        }
    }

    @Test
    void exactModelPathsAreRecognisedAndRandomOrUnapprovedVariantsFallThrough() {
        assertTrue(ItemTexturePacks.approvedModel("theprisons", "prisons/misc/executive_shard"));
        assertTrue(ItemTexturePacks.approvedModel("theprisons", "prisons/charge_orb/stage_4"));
        assertTrue(ItemTexturePacks.approvedModel("theprisons", "prisons/book_revealed/godly"));
        assertFalse(ItemTexturePacks.approvedModel("theprisons", "prisons/book/random_godly"));
        assertFalse(ItemTexturePacks.approvedModel("theprisons", "prisons/shard/random_executive"));
        assertFalse(ItemTexturePacks.approvedModel("other", "prisons/shard/godly"));
    }

    @Test
    void executiveShardIsTheOnlyCorrectedModelReference() throws IOException {
        String executive = Files.readString(Path.of(PAUSED, "assets/theprisons/models/item/prisons/misc/executive_shard.json"));
        assertTrue(executive.contains("theprisons:item/prisons/shard/executive"));
    }

    @Test
    void standardOverlayIsAddedOnceAfterPlayerPacksAndBeforeLookPack() {
        List<String> once = ItemTexturePacks.assemble(List.of("vanilla", "player"), "standard", "look");
        assertEquals(List.of("vanilla", "player", "standard", "look"), once);
        assertEquals(once, ItemTexturePacks.assemble(once, "standard", "look"));
    }

    @Test
    void manifestHashesEveryImportedAssetAndRetiredPacksAreArchiveOnly() throws Exception {
        JsonObject manifest = JsonParser.parseString(Files.readString(PACK.resolve("manifest.json"))).getAsJsonObject();
        assertEquals(35, manifest.getAsJsonArray("assets").size());
        for (var asset : manifest.getAsJsonArray("assets")) {
            JsonObject entry = asset.getAsJsonObject();
            Path png = PACK.resolve(entry.get("path").getAsString());
            assertTrue(Files.isRegularFile(png), png.toString());
            assertEquals(entry.get("sha256").getAsString(), sha256(png), png.toString());
        }
        assertFalse(Files.exists(Path.of("src/main/resources/resourcepacks/theprisons_items_hd")));
        assertFalse(Files.exists(Path.of("src/main/resources/resourcepacks/theprisons_items_hd_v4")));
        assertTrue(Files.isRegularFile(Path.of("archive/legacy-texturepacks/README.md")));
        assertTrue(Files.isRegularFile(Path.of("archive/legacy-texturepacks/hd-v2-2026-10-08/pack.mcmeta")));
        assertTrue(Files.isRegularFile(Path.of("archive/legacy-texturepacks/hd-v4-staging-2026-10-08/pack.mcmeta")));
    }

    private static boolean hasOpaquePixel(BufferedImage image) {
        for (int y = 0; y < image.getHeight(); y += 4) {
            for (int x = 0; x < image.getWidth(); x += 4) {
                if ((image.getRGB(x, y) >>> 24) > 200) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String sha256(Path file) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file));
        StringBuilder hex = new StringBuilder(digest.length * 2);
        for (byte b : digest) {
            hex.append(String.format(java.util.Locale.ROOT, "%02x", b));
        }
        return hex.toString();
    }
}
