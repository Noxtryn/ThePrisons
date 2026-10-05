package io.theprisons.core.world;

import io.theprisons.core.nav.Pos;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.ToIntFunction;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Region files of the {@link WorldArchive}: {@code r.<rx>.<rz>.bin} holds the sections of 32x32 chunks, gzip-compressed,
 * with its own ore palette (block ids), so the files stay valid across sessions and ore selections. Minecraft-free.
 */
public final class ArchiveCodec {
    private static final int MAGIC = 0x54504152;
    private static final int VERSION = 1;
    public static final int REGION_SHIFT = 5;

    private ArchiveCodec() {
    }

    public static long regionOf(int sx, int sz) {
        return Pos.pack(sx >> REGION_SHIFT, 0, sz >> REGION_SHIFT);
    }

    public static Path file(Path dir, long region) {
        return dir.resolve("r." + Pos.x(region) + "." + Pos.z(region) + ".bin");
    }

    /**
     * @param oreIds block id per ore palette index of the archive (code {@code ORE_BASE + i} = {@code oreIds.get(i)})
     */
    public static void write(Path file, Collection<ArchiveSection> sections, List<String> oreIds) throws IOException {
        Files.createDirectories(file.getParent());
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        try (OutputStream raw = Files.newOutputStream(temp)) {
            write(raw, sections, oreIds);
        }
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    static void write(OutputStream raw, Collection<ArchiveSection> sections, List<String> oreIds) throws IOException {
        GZIPOutputStream gzip = new GZIPOutputStream(raw);
        DataOutputStream out = new DataOutputStream(new BufferedOutputStream(gzip));
        out.writeInt(MAGIC);
        out.writeInt(VERSION);
        out.writeInt(oreIds.size());
        for (String id : oreIds) {
            out.writeUTF(id);
        }
        out.writeInt(sections.size());
        for (ArchiveSection s : sections) {
            out.writeInt(s.sx());
            out.writeInt(s.sy());
            out.writeInt(s.sz());
            out.writeLong(s.scannedAtMs());
            byte[] codes = s.codes();
            if (codes == null) {
                out.writeBoolean(true);
                out.writeByte(s.uniform());
            } else {
                out.writeBoolean(false);
                out.write(codes);
            }
        }
        out.flush();
        gzip.finish();
    }

    /**
     * Reads a region file; ore codes are mapped into the archive's palette.
     *
     * @param codeOfId the archive's ore code for a block id ({@code ORE_BASE + i}), or {@link ArchiveSection#SOLID} when
     *                 the palette is full
     */
    public static List<ArchiveSection> read(Path file, ToIntFunction<String> codeOfId) throws IOException {
        try (InputStream raw = Files.newInputStream(file)) {
            return read(raw, codeOfId);
        }
    }

    static List<ArchiveSection> read(InputStream raw, ToIntFunction<String> codeOfId) throws IOException {
        DataInputStream in = new DataInputStream(new BufferedInputStream(new GZIPInputStream(raw)));
        if (in.readInt() != MAGIC || in.readInt() != VERSION) {
            throw new IOException("not a world archive file");
        }
        int paletteSize = in.readInt();
        byte[] remap = new byte[256];
        for (int i = 0; i < 256; i++) {
            remap[i] = (byte) i;
        }
        for (int i = 0; i < paletteSize; i++) {
            String id = in.readUTF();
            if (ArchiveSection.ORE_BASE + i < 256) {
                remap[ArchiveSection.ORE_BASE + i] = (byte) codeOfId.applyAsInt(id);
            }
        }
        int count = in.readInt();
        List<ArchiveSection> sections = new ArrayList<>(count);
        for (int n = 0; n < count; n++) {
            int sx = in.readInt();
            int sy = in.readInt();
            int sz = in.readInt();
            long scanned = in.readLong();
            if (in.readBoolean()) {
                byte value = remap[in.readByte() & 0xFF];
                sections.add(new ArchiveSection(sx, sy, sz, null, value, scanned));
            } else {
                byte[] codes = new byte[ArchiveSection.VOLUME];
                in.readFully(codes);
                for (int i = 0; i < codes.length; i++) {
                    codes[i] = remap[codes[i] & 0xFF];
                }
                sections.add(ArchiveSection.of(sx, sy, sz, codes, scanned));
            }
        }
        return sections;
    }
}
