package io.theprisons.modules.general.tunnel;

import io.theprisons.ThePrisonsClient;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;
import org.jspecify.annotations.Nullable;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * The pictures the tunnel can show behind the player: the bundled default, every png / jpg / gif in
 * {@code config/theprisons/tunnel/} (the user drops files there) and the animated "Nebula" that needs no file.
 */
final class TunnelBackdrops {
    /** The loaded picture: its texture and size. */
    record Picture(Identifier texture, int width, int height) {
    }

    static final String NEBULA = "nebula";
    private static final Identifier BUNDLED = Identifier.of("theprisons", "tunnel/default_map.jpg");
    private static final int MAX_SIDE = 2048;

    private static final Map<String, Picture> LOADED = new HashMap<>();
    private static final Map<String, Boolean> FAILED = new HashMap<>();

    private TunnelBackdrops() {
    }

    static Path folder() {
        return FabricLoader.getInstance().getConfigDir().resolve("theprisons").resolve("tunnel");
    }

    /** File names the user put into the folder. */
    static List<String> files() {
        List<String> out = new ArrayList<>();
        try {
            Files.createDirectories(folder());
            try (Stream<Path> list = Files.list(folder())) {
                list.map(p -> p.getFileName().toString()).filter(TunnelBackdrops::image).sorted().forEach(out::add);
            }
        } catch (IOException ignored) {
            // no folder, no extra pictures
        }
        return out;
    }

    private static boolean image(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return n.endsWith(".png") || n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".gif") || n.endsWith(".bmp");
    }

    /** The picture for a setting value ("" = the bundled default, "nebula" = none: the animated nebula); null while / if it cannot load. */
    static @Nullable Picture picture(String id) {
        if (NEBULA.equals(id)) {
            return null;
        }
        String key = id.isEmpty() ? "<default>" : id;
        Picture loaded = LOADED.get(key);
        if (loaded != null) {
            return loaded;
        }
        if (FAILED.containsKey(key)) {
            return null;
        }
        try (InputStream in = open(id)) {
            BufferedImage src = ImageIO.read(in);
            if (src == null) {
                throw new IOException("unreadable picture");
            }
            BufferedImage img = shrink(src);
            NativeImage image = new NativeImage(img.getWidth(), img.getHeight(), false);
            for (int y = 0; y < img.getHeight(); y++) {
                for (int x = 0; x < img.getWidth(); x++) {
                    image.setColorArgb(x, y, img.getRGB(x, y) | 0xFF000000);
                }
            }
            Identifier texture = Identifier.of("theprisons", "tunnel/bg_" + Integer.toHexString(key.hashCode()));
            NativeImageBackedTexture backed = new NativeImageBackedTexture(() -> "theprisons tunnel " + key, image);
            MinecraftClient.getInstance().getTextureManager().registerTexture(texture, backed);
            Picture picture = new Picture(texture, img.getWidth(), img.getHeight());
            LOADED.put(key, picture);
            return picture;
        } catch (Exception e) {
            ThePrisonsClient.LOGGER.warn("[tunnel] background '{}' cannot be loaded: {}", key, e.toString());
            FAILED.put(key, Boolean.TRUE);
            return null;
        }
    }

    private static InputStream open(String id) throws IOException {
        if (id.isEmpty()) {
            return MinecraftClient.getInstance().getResourceManager().open(BUNDLED);
        }
        return Files.newInputStream(folder().resolve(id));
    }

    private static BufferedImage shrink(BufferedImage src) {
        int longest = Math.max(src.getWidth(), src.getHeight());
        if (longest <= MAX_SIDE) {
            return src;
        }
        double f = MAX_SIDE / (double) longest;
        int w = Math.max(1, (int) Math.round(src.getWidth() * f));
        int h = Math.max(1, (int) Math.round(src.getHeight() * f));
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        var g = out.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(src, 0, 0, w, h, null);
        g.dispose();
        return out;
    }
}
