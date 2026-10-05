package io.theprisons.modules.qol.storage;

import io.theprisons.ThePrisonsClient;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.NbtSizeTracker;
import net.minecraft.registry.RegistryOps;
import net.minecraft.registry.RegistryWrapper;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * What the player last saw in each private vault, per server and account, kept on disk
 * ({@code config/theprisons/storage/<server>_<uuid>.dat}) so the overlay can show the pages before they are opened
 * again. Items are stored with the full item codec (components included), so custom names / models of server items
 * render exactly as in the vault - including the textures of resource mods like Cosmic Textures.
 */
final class VaultCache {
    static final int UNKNOWN = -1;
    private static final int VERSION = 1;
    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "ThePrisons-VaultCache");
        thread.setDaemon(true);
        return thread;
    });

    /** One vault page as last seen: its rows and its stacks (size rows * 9, empty stacks included). */
    record Page(int rows, List<ItemStack> items, long seenMs) {
        int filled() {
            int n = 0;
            for (ItemStack stack : items) {
                if (!stack.isEmpty()) {
                    n++;
                }
            }
            return n;
        }
    }

    private final Path file;
    private final Map<Integer, Page> pages = new TreeMap<>();
    private int count = UNKNOWN;
    private int highestSeen;
    private boolean dirty;

    private VaultCache(Path file) {
        this.file = file;
    }

    static VaultCache load(Path file, RegistryWrapper.WrapperLookup registries) {
        VaultCache cache = new VaultCache(file);
        if (!Files.isRegularFile(file)) {
            return cache;
        }
        try {
            NbtCompound root = NbtIo.readCompressed(file, NbtSizeTracker.ofUnlimitedBytes());
            RegistryOps<NbtElement> ops = registries.getOps(NbtOps.INSTANCE);
            cache.count = root.getInt("count", UNKNOWN);
            cache.highestSeen = root.getInt("highest", 0);
            NbtList list = root.getListOrEmpty("pages");
            for (int i = 0; i < list.size(); i++) {
                NbtCompound page = list.getCompoundOrEmpty(i);
                int number = page.getInt("page", 0);
                int rows = Math.max(1, Math.min(6, page.getInt("rows", 6)));
                if (number <= 0) {
                    continue;
                }
                List<ItemStack> items = new ArrayList<>(Collections.nCopies(rows * 9, ItemStack.EMPTY));
                NbtList slots = page.getListOrEmpty("items");
                for (int s = 0; s < slots.size(); s++) {
                    NbtCompound entry = slots.getCompoundOrEmpty(s);
                    int slot = entry.getInt("slot", -1);
                    NbtElement item = entry.get("item");
                    if (slot < 0 || slot >= items.size() || item == null) {
                        continue;
                    }
                    ItemStack.OPTIONAL_CODEC.parse(ops, item).result().ifPresent(stack -> items.set(slot, stack));
                }
                cache.pages.put(number, new Page(rows, List.copyOf(items), page.getLong("seen", 0L)));
            }
        } catch (IOException | RuntimeException e) {
            ThePrisonsClient.LOGGER.warn("[storage] could not read {}", file, e);
        }
        return cache;
    }

    /** Writes the cache when it changed; encoding runs here (registries), the file write on a background thread. */
    void saveIfDirty(RegistryWrapper.WrapperLookup registries) {
        if (!dirty) {
            return;
        }
        dirty = false;
        RegistryOps<NbtElement> ops = registries.getOps(NbtOps.INSTANCE);
        NbtCompound root = new NbtCompound();
        root.putInt("version", VERSION);
        root.putInt("count", count);
        root.putInt("highest", highestSeen);
        NbtList list = new NbtList();
        for (Map.Entry<Integer, Page> entry : pages.entrySet()) {
            Page page = entry.getValue();
            NbtCompound tag = new NbtCompound();
            tag.putInt("page", entry.getKey());
            tag.putInt("rows", page.rows());
            tag.putLong("seen", page.seenMs());
            NbtList slots = new NbtList();
            for (int i = 0; i < page.items().size(); i++) {
                ItemStack stack = page.items().get(i);
                if (stack.isEmpty()) {
                    continue;
                }
                int slot = i;
                ItemStack.OPTIONAL_CODEC.encodeStart(ops, stack).result().ifPresent(encoded -> {
                    NbtCompound item = new NbtCompound();
                    item.putInt("slot", slot);
                    item.put("item", encoded);
                    slots.add(item);
                });
            }
            tag.put("items", slots);
            list.add(tag);
        }
        root.put("pages", list);
        IO.execute(() -> {
            try {
                Files.createDirectories(file.getParent());
                Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
                NbtIo.writeCompressed(root, tmp);
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                ThePrisonsClient.LOGGER.warn("[storage] could not write {}", file, e);
            }
        });
    }

    @Nullable Page page(int number) {
        return pages.get(number);
    }

    /** Stores what a vault holds now; returns true when it differs from the cached page. */
    boolean put(int number, int rows, List<ItemStack> items, long nowMs) {
        Page old = pages.get(number);
        if (old != null && old.rows() == rows && sameStacks(old.items(), items)) {
            return false;
        }
        List<ItemStack> copy = new ArrayList<>(items.size());
        for (ItemStack stack : items) {
            copy.add(stack.copy());
        }
        pages.put(number, new Page(rows, List.copyOf(copy), nowMs));
        seen(number);
        dirty = true;
        return true;
    }

    /** A page that exists (it opened). */
    void seen(int number) {
        if (number > highestSeen) {
            highestSeen = number;
            dirty = true;
        }
        if (count != UNKNOWN && number > count) {
            count = number;
            dirty = true;
        }
    }

    /** A page the server refused: the player owns fewer vaults. */
    void denied(int number) {
        if (number - 1 < highestSeen) {
            return;
        }
        if (count == UNKNOWN || count >= number) {
            count = number - 1;
            dirty = true;
        }
    }

    void setCount(int value) {
        if (count != value) {
            count = value;
            dirty = true;
        }
    }

    int count() {
        return count;
    }

    int highestSeen() {
        return highestSeen;
    }

    /** Rows used for pages never opened: the most common size of the known pages, else a double chest. */
    int typicalRows() {
        int[] votes = new int[7];
        for (Page page : pages.values()) {
            votes[page.rows()]++;
        }
        int best = 6;
        for (int rows = 1; rows <= 6; rows++) {
            if (votes[rows] > votes[best]) {
                best = rows;
            }
        }
        return best;
    }

    void clear() {
        pages.clear();
        count = UNKNOWN;
        highestSeen = 0;
        dirty = true;
    }

    private static boolean sameStacks(List<ItemStack> a, List<ItemStack> b) {
        if (a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            if (!ItemStack.areEqual(a.get(i), b.get(i))) {
                return false;
            }
        }
        return true;
    }
}
