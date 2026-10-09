package io.theprisons.modules.general.look;

import io.theprisons.ThePrisonsClient;
import net.minecraft.client.resource.metadata.AnimationResourceMetadata;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.SpriteDimensions;
import net.minecraft.util.Identifier;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Applies {@link ComicFilter} to textures while the game loads them: atlas sprites (blocks, items, particles, GUI
 * sprites ...) and single textures (mobs, GUI backgrounds ...). The mod's own art (already drawn in this style), and
 * textures the game reads pixel by pixel (clouds, colour maps, fonts, maps) stay as they are.
 */
public final class ComicTextures {
    private static final AtomicInteger PROCESSED = new AtomicInteger();

    private ComicTextures() {
    }

    /**
     * Paused: the switch used to be DesignModule's "comic_textures" setting (default on, reloads the resources on
     * change). Reactivation wires it back there, see src/paused/cosmic-items/README.md.
     */
    private static volatile boolean enabled = true;

    public static boolean enabled() {
        return enabled;
    }

    public static void setEnabled(boolean on) {
        enabled = on;
    }

    static boolean skip(Identifier id) {
        String path = id.getPath();
        // blocks stay as they are (vanilla or the player's texture pack)
        return "theprisons".equals(id.getNamespace()) || path.equals("missingno") || path.startsWith("block/")
                || path.contains("/block/") || path.contains("environment/")
                || path.contains("colormap/") || path.contains("font/") || path.contains("map/") || path.contains("misc/")
                || path.contains("effect/") || path.contains("trims/color_palettes") || path.startsWith("tooltip/");
    }

    /** Upscale factor of an atlas sprite (0 = leave untouched, 1 = style only). */
    public static int spriteFactor(Identifier id, int frameWidth, int frameHeight, Optional<AnimationResourceMetadata> animation) {
        if (!enabled() || skip(id)) {
            return 0;
        }
        if (animation.isPresent() && (animation.get().width().isPresent() || animation.get().height().isPresent())) {
            return 1; // explicit frame size in the .mcmeta: the size must stay
        }
        int factor = ComicFilter.factor(frameWidth, frameHeight, true);
        return factor == 1 && Math.max(frameWidth, frameHeight) >= 64 ? 0 : factor; // hand-made HD art stays as is
    }

    public static SpriteDimensions dimensions(Identifier id, SpriteDimensions dims, Optional<AnimationResourceMetadata> animation) {
        int factor = spriteFactor(id, dims.width(), dims.height(), animation);
        return factor > 1 ? new SpriteDimensions(dims.width() * factor, dims.height() * factor) : dims;
    }

    public static NativeImage sprite(Identifier id, NativeImage image, Optional<AnimationResourceMetadata> animation) {
        SpriteDimensions frame = animation.map(a -> a.getSize(image.getWidth(), image.getHeight()))
                .orElse(new SpriteDimensions(image.getWidth(), image.getHeight()));
        int factor = spriteFactor(id, frame.width(), frame.height(), animation);
        return factor == 0 ? image : apply(image, factor);
    }

    /** Single textures (not in an atlas). */
    public static NativeImage texture(Identifier id, NativeImage image) {
        if (!enabled() || skip(id)) {
            return image;
        }
        return apply(image, ComicFilter.factor(image.getWidth(), image.getHeight(), false));
    }

    private static NativeImage apply(NativeImage image, int factor) {
        int w = image.getWidth();
        int h = image.getHeight();
        int[] px = new int[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                px[y * w + x] = image.getColorArgb(x, y);
            }
        }
        int[] out = ComicFilter.process(px, w, h, factor);
        int ow = w * factor;
        int oh = h * factor;
        NativeImage result = factor == 1 ? image : new NativeImage(ow, oh, false);
        for (int y = 0; y < oh; y++) {
            for (int x = 0; x < ow; x++) {
                result.setColorArgb(x, y, out[y * ow + x]);
            }
        }
        if (result != image) {
            image.close();
        }
        int n = PROCESSED.incrementAndGet();
        if (n % 500 == 0) {
            ThePrisonsClient.LOGGER.info("[comic_textures] {} textures styled", n);
        }
        return result;
    }
}
