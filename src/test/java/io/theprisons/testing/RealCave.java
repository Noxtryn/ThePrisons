package io.theprisons.testing;

import io.theprisons.core.nav.Pos;
import io.theprisons.core.world.ArchiveCodec;
import io.theprisons.core.world.ArchiveSection;
import io.theprisons.core.world.ArchiveView;
import io.theprisons.core.world.BlockKeys;
import io.theprisons.modules.mining.ore.GuardArea;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/**
 * The real Cosmic gold mine (game 2026-10-05, 00:14 - 01:06): the macro's own saves copied from the game - the world
 * archive region {@code r.1.0} (x 512-1023, z 0-511), the guard tax zone it learned (blocks where the sidebar's
 * guard XP tax was on / gone) and the 48 guards it saw. In the game the macro left the zone at (643, 78, 195), walked
 * on to (641, 80, 187) below the guard at (639.5, 93, 191.5) and did not find back in for 52 minutes.
 */
public final class RealCave {
    private static final String DIR = "/realcave/";

    private RealCave() {
    }

    public static ArchiveView world() {
        List<String> ids = new ArrayList<>();
        List<ArchiveSection> sections;
        try {
            java.nio.file.Path file = java.nio.file.Path.of(RealCave.class.getResource(DIR + "r.1.0.bin").toURI());
            sections = ArchiveCodec.read(file, id -> {
                int i = ids.indexOf(id);
                if (i < 0) {
                    ids.add(id);
                    i = ids.size() - 1;
                }
                return ArchiveSection.ORE_BASE + i;
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (java.net.URISyntaxException e) {
            throw new IllegalStateException(e);
        }
        Long2ObjectOpenHashMap<ArchiveSection> map = new Long2ObjectOpenHashMap<>();
        for (ArchiveSection s : sections) {
            map.put(s.key(), s);
        }
        int[] keys = new int[ids.size()];
        for (int i = 0; i < keys.length; i++) {
            keys[i] = BlockKeys.key(ids.get(i));
        }
        return new ArchiveView(map, keys);
    }

    /** {taxed blocks, untaxed blocks} as saved by the module. */
    public static long[][] zone() {
        try (DataInputStream in = new DataInputStream(new GZIPInputStream(open("guardzone.bin")))) {
            long[][] out = new long[2][];
            for (int k = 0; k < 2; k++) {
                int n = in.readInt();
                out[k] = new long[n];
                for (int i = 0; i < n; i++) {
                    out[k][i] = in.readLong();
                }
            }
            return out;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static List<double[]> guards() {
        String json;
        try (InputStream in = open("guards.json")) {
            json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        List<double[]> guards = new ArrayList<>();
        Matcher m = Pattern.compile("\\[(-?[\\d.]+),(-?[\\d.]+),(-?[\\d.]+)]").matcher(json);
        while (m.find()) {
            guards.add(new double[]{Double.parseDouble(m.group(1)), Double.parseDouble(m.group(2)), Double.parseDouble(m.group(3))});
        }
        return guards;
    }

    /**
     * The guarded area as the module has it in the game: the remembered guards, the learned tax zone, and the tax line
     * seen this run (tax mode) - gone at {@code (x, y, z)} where the player stands.
     */
    public static GuardArea area(int x, int y, int z) {
        GuardArea area = new GuardArea();
        for (double[] g : guards()) {
            area.remember(g[0], g[1], g[2]);
        }
        long[][] zone = zone();
        area.restore(zone[0], zone[1]);
        // The tax line was seen this run (taxed on one of the learned blocks), now it is gone where the player stands.
        long taxed = zone[0][0];
        area.tax(Boolean.TRUE, Pos.x(taxed), Pos.y(taxed), Pos.z(taxed));
        area.tax(Boolean.FALSE, x, y, z);
        return area;
    }

    private static InputStream open(String name) {
        InputStream in = RealCave.class.getResourceAsStream(DIR + name);
        if (in == null) {
            throw new IllegalStateException("test resource missing: " + DIR + name);
        }
        return in;
    }
}
