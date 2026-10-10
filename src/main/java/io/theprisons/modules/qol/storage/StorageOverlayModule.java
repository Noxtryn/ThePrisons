package io.theprisons.modules.qol.storage;

import io.theprisons.ThePrisonsClient;
import io.theprisons.core.event.CoreEvents;
import io.theprisons.core.event.EventBus;
import io.theprisons.core.module.Category;
import io.theprisons.core.module.Module;
import io.theprisons.core.setting.Settings;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.c2s.play.CloseHandledScreenC2SPacket;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.text.Text;
import net.minecraft.util.Util;
import org.jspecify.annotations.Nullable;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Replaces Cosmic Prisons' {@code /pv} with the storage overlay: every private vault as a card (three per row), the
 * open vault fully usable inside its card, the others shown as last seen. Typing {@code /pv} opens the overlay instead
 * of sending the command; clicking a card sends {@code /pv <n>} and the vault the server opens is drawn into that card
 * ({@link #interceptOpen}). How many vaults the player owns is found by a short probe ({@link VaultScan}) the first
 * time and afterwards learned from every page that opens or is refused.
 */
public final class StorageOverlayModule extends Module {
    private static final long REQUEST_TIMEOUT_MS = 5_000L;
    private static final long PROBE_TIMEOUT_MS = 2_500L;
    private static final long PROBE_SPACING_MS = 650L;
    private static final long SAVE_EVERY_MS = 10_000L;

    private static @Nullable StorageOverlayModule instance;

    private final Settings.TextSetting aliases;
    private final Settings.IntSetting columns;
    private final Settings.IntSetting countOverride;
    private final Settings.BoolSetting inventory;

    private @Nullable VaultCache cache;
    private @Nullable String cacheKey;
    private boolean sendingInternally;
    private @Nullable Request pending;
    private @Nullable VaultScan scan;
    private long nextProbeAtMs;
    private long lastSaveMs;
    private String status = "";
    private long statusUntilMs;
    /** Shared by all overlay screen instances of one session (page switches swap the screen). */
    private final OverlayState state = new OverlayState();

    /** A {@code /pv <page>} that was sent and waits for the server. */
    private record Request(int page, boolean probe, long sentMs) {
    }

    /** UI state that survives screen swaps while the overlay stays open. */
    static final class OverlayState {
        long openedMs;
        double scroll;
        double scrollTarget;
        final java.util.Map<Integer, Float> hover = new java.util.HashMap<>();
        final Set<Integer> loadedOnce = new HashSet<>();
    }

    public StorageOverlayModule() {
        super("storage_overlay", "Storage Overlay", Category.QOL, "Storage",
                "Shows all private vaults (/pv) as cards: open, sort and move items of every page in one overlay.",
                Settings.KeybindSetting.NONE);
        aliases = text("commands", "Commands", "pv,vault,vaults,playervault,playervaults", 96)
                .description("Commands that open the overlay instead of the server menu (comma separated, no slash).")
                .group("General");
        columns = integer("columns", "Cards per row", 3, 1, 6, 1)
                .description("Fewer are used automatically when the window is too narrow.").group("Layout");
        inventory = bool("inventory", "Show inventory", true)
                .description("The main inventory as a card above the hotbar.").group("Layout");
        countOverride = integer("vault_count", "Vault count", 0, 0, 200, 1)
                .description("0 = detect automatically (probes /pv once and learns from the server's replies).")
                .group("Vaults");
        action("rescan", "Rescan vaults", "Rescan", this::rescan)
                .description("Finds the number of vaults again on the next /pv.").group("Vaults");
        action("clear", "Forget cached pages", "Clear", this::clearCache)
                .description("Removes the stored contents of every vault for this server and account.").group("Vaults");
        instance = this;
    }

    public static @Nullable StorageOverlayModule get() {
        return instance;
    }

    /** Part of the shipped profile: on for a new player, who may switch it off in the config GUI. */
    @Override
    public boolean enabledByDefault() {
        return true;
    }

    @Override
    public boolean toggleable() {
        return false;
    }

    @Override
    public boolean onKeybind() {
        MinecraftClient.getInstance().execute(this::openOverlay);
        return true;
    }

    /** Hooks the command, tick and chat listeners once; the module is always active. */
    public void register(EventBus bus) {
        ClientSendMessageEvents.ALLOW_COMMAND.register(this::allowCommand);
        always(CoreEvents.TickEnd.class, event -> tick(event.client()));
        always(CoreEvents.ChatReceived.class, event -> {
            if (!event.fromPlayer() && !event.overlay()) {
                onChat(event.message());
            }
        });
        always(CoreEvents.WorldChanged.class, event -> {
            pending = null;
            scan = null;
        });
    }

    // ── Command ──────────────────────────────────────────────────────────────

    private boolean allowCommand(String command) {
        if (!enabled() || sendingInternally) {
            return true;
        }
        String[] parts = command.trim().split("\\s+");
        if (parts.length == 0 || parts.length > 2 || !isAlias(parts[0])) {
            return true;
        }
        int page = 0;
        if (parts.length == 2) {
            try {
                page = Integer.parseInt(parts[1]);
            } catch (NumberFormatException e) {
                return true; // "/pv <player>" (staff) etc. stays a server command
            }
            if (page <= 0) {
                return true;
            }
        }
        int requested = page;
        // The chat screen closes right after sending; open the overlay after that.
        MinecraftClient.getInstance().send(() -> {
            openOverlay();
            if (requested > 0) {
                openPage(requested);
            }
        });
        return false;
    }

    private boolean isAlias(String root) {
        String needle = root.toLowerCase(Locale.ROOT);
        for (String alias : aliases.get().split(",")) {
            if (alias.trim().replaceFirst("^/", "").toLowerCase(Locale.ROOT).equals(needle)) {
                return true;
            }
        }
        return false;
    }

    private String pvCommand() {
        for (String alias : aliases.get().split(",")) {
            String a = alias.trim().replaceFirst("^/", "");
            if (!a.isEmpty()) {
                return a;
            }
        }
        return "pv";
    }

    private void send(String command) {
        ClientPlayerEntity player = MinecraftClient.getInstance().player;
        if (player == null) {
            return;
        }
        sendingInternally = true;
        try {
            player.networkHandler.sendChatCommand(command);
        } finally {
            sendingInternally = false;
        }
    }

    // ── Overlay ──────────────────────────────────────────────────────────────

    /** Opens the overlay with no vault open (just the cards and the hotbar). */
    public void openOverlay() {
        MinecraftClient client = MinecraftClient.getInstance();
        ClientPlayerEntity player = client.player;
        if (player == null || client.currentScreen instanceof StorageOverlayScreen) {
            return;
        }
        ensureCache(client);
        state.openedMs = Util.getMeasuringTimeMs();
        state.hover.clear();
        if (player.currentScreenHandler != player.playerScreenHandler) {
            player.closeHandledScreen();
        }
        client.setScreen(new StorageOverlayScreen(this, player.playerScreenHandler, 0));
        if (countOverride.get() == 0 && cache != null && cache.count() == VaultCache.UNKNOWN && scan == null) {
            startScan();
        }
    }

    /** Asks the server for a vault page; the open vault (if any) is closed first. */
    public void openPage(int page) {
        MinecraftClient client = MinecraftClient.getInstance();
        ClientPlayerEntity player = client.player;
        if (player == null) {
            return;
        }
        if (player.currentScreenHandler != player.playerScreenHandler) {
            if (client.currentScreen instanceof StorageOverlayScreen screen) {
                snapshot(screen.page(), player.currentScreenHandler);
            }
            player.networkHandler.sendPacket(new CloseHandledScreenC2SPacket(player.currentScreenHandler.syncId));
            player.currentScreenHandler = player.playerScreenHandler;
            showScreen(client, player.playerScreenHandler, 0);
        }
        pending = new Request(page, false, Util.getMeasuringTimeMs());
        send(pvCommand() + " " + page);
    }

    private void showScreen(MinecraftClient client, ScreenHandler handler, int page) {
        if (client.currentScreen instanceof StorageOverlayScreen old) {
            old.markReplaced();
        } else {
            state.openedMs = Util.getMeasuringTimeMs();
            state.hover.clear();
        }
        client.setScreen(new StorageOverlayScreen(this, handler, page));
    }

    /**
     * Called for every screen the server opens. A chest-type menu that answers a pending {@code /pv <n>} (or whose
     * title names a vault while the overlay is up) becomes the open page of the overlay; probe answers are closed
     * right away. Returns true when the vanilla screen must not open.
     */
    public boolean interceptOpen(ScreenHandlerType<?> type, MinecraftClient client, int syncId, Text title) {
        ClientPlayerEntity player = client.player;
        if (!enabled() || player == null) {
            return false;
        }
        long now = Util.getMeasuringTimeMs();
        Request request = pending;
        if (request != null && now - request.sentMs() > (request.probe() ? PROBE_TIMEOUT_MS : REQUEST_TIMEOUT_MS)) {
            request = null;
        }
        Integer titled = VaultText.pageOfTitle(title.getString());
        boolean overlayOpen = client.currentScreen instanceof StorageOverlayScreen;
        if (request == null && (titled == null || !overlayOpen)) {
            return false;
        }
        ScreenHandler created = type.create(syncId, player.getInventory());
        if (!(created instanceof GenericContainerScreenHandler handler)) {
            return false;
        }
        int page = titled != null ? titled : request.page();
        pending = null;
        ensureCache(client);
        if (cache != null) {
            cache.seen(page);
        }
        if (request != null && request.probe()) {
            player.networkHandler.sendPacket(new CloseHandledScreenC2SPacket(syncId));
            probeAnswered(request.page(), true);
            return true;
        }
        state.loadedOnce.add(page);
        player.currentScreenHandler = handler;
        showScreen(client, handler, page);
        ThePrisonsClient.LOGGER.info("[storage] PV {} opened in the overlay ({} rows, title '{}')", page, handler.getRows(),
                title.getString());
        return true;
    }

    /** The server closed its screen: keep the overlay (without the vault) instead of leaving it. */
    public boolean onServerClose() {
        MinecraftClient client = MinecraftClient.getInstance();
        ClientPlayerEntity player = client.player;
        if (!enabled() || player == null || !(client.currentScreen instanceof StorageOverlayScreen screen)) {
            return false;
        }
        if (screen.page() > 0) {
            snapshot(screen.page(), player.currentScreenHandler);
            player.currentScreenHandler = player.playerScreenHandler;
            showScreen(client, player.playerScreenHandler, 0);
        }
        return true;
    }

    /** The overlay was closed by the player (not swapped for another page). */
    void onOverlayClosed() {
        scan = null;
        if (pending != null && pending.probe()) {
            pending = null;
        }
        save();
    }

    /** Stores the contents of the open vault page in the cache. */
    void snapshot(int page, ScreenHandler handler) {
        if (page <= 0 || cache == null || !(handler instanceof GenericContainerScreenHandler generic)) {
            return;
        }
        int size = generic.getRows() * 9;
        List<ItemStack> items = new ArrayList<>(size);
        for (int i = 0; i < size && i < handler.slots.size(); i++) {
            items.add(handler.slots.get(i).getStack());
        }
        cache.put(page, generic.getRows(), items, System.currentTimeMillis());
    }

    // ── Vault count ──────────────────────────────────────────────────────────

    private void startScan() {
        int known = cache != null ? cache.highestSeen() : 0;
        scan = new VaultScan(known);
        nextProbeAtMs = 0L;
        setStatus("Scanning vaults…", 60_000L);
    }

    private void rescan() {
        if (cache != null) {
            cache.setCount(VaultCache.UNKNOWN);
        }
        scan = null;
        setStatus("Vaults are scanned again on the next /pv", 4_000L);
        if (MinecraftClient.getInstance().currentScreen instanceof StorageOverlayScreen) {
            startScan();
        }
    }

    private void clearCache() {
        if (cache != null) {
            cache.clear();
            save();
        }
        state.loadedOnce.clear();
        setStatus("Cached pages cleared", 3_000L);
    }

    private void probeAnswered(int page, boolean open) {
        VaultScan current = scan;
        if (current == null) {
            return;
        }
        current.record(page, open);
        nextProbeAtMs = Util.getMeasuringTimeMs() + PROBE_SPACING_MS;
        if (current.done()) {
            finishScan(current.result());
        }
    }

    private void finishScan(int count) {
        scan = null;
        if (cache != null) {
            cache.setCount(count);
        }
        save();
        setStatus(count == 1 ? "1 vault found" : count + " vaults found", 3_000L);
        ThePrisonsClient.LOGGER.info("[storage] vault count: {}", count);
    }

    private void tick(MinecraftClient client) {
        long now = Util.getMeasuringTimeMs();
        Request request = pending;
        if (request != null) {
            long timeout = request.probe() ? PROBE_TIMEOUT_MS : REQUEST_TIMEOUT_MS;
            if (now - request.sentMs() > timeout) {
                pending = null;
                if (request.probe()) {
                    probeAnswered(request.page(), false);
                } else {
                    setStatus("PV " + request.page() + " did not open", 3_000L);
                }
            }
        }
        boolean overlayOpen = client.currentScreen instanceof StorageOverlayScreen;
        VaultScan current = scan;
        if (current != null && overlayOpen && pending == null && now >= nextProbeAtMs && client.player != null
                && client.player.currentScreenHandler == client.player.playerScreenHandler) {
            int next = current.next();
            if (next == 0) {
                finishScan(current.result());
            } else {
                pending = new Request(next, true, now);
                send(pvCommand() + " " + next);
            }
        }
        if (cache != null && now - lastSaveMs > SAVE_EVERY_MS) {
            save();
        }
    }

    private void onChat(Text message) {
        int denied = VaultText.deniedPage(message.getString());
        if (denied <= 0) {
            return;
        }
        if (cache != null) {
            cache.denied(denied);
        }
        Request request = pending;
        if (request != null && request.page() == denied) {
            pending = null;
            if (request.probe()) {
                probeAnswered(denied, false);
            } else {
                setStatus("PV " + denied + " is locked", 3_000L);
            }
        }
    }

    // ── Cache ────────────────────────────────────────────────────────────────

    private void ensureCache(MinecraftClient client) {
        ClientPlayerEntity player = client.player;
        if (player == null || client.world == null) {
            return;
        }
        ServerInfo server = client.getCurrentServerEntry();
        String serverName = server != null ? server.address : "singleplayer";
        String key = (serverName + "_" + player.getUuidAsString()).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "_");
        if (key.equals(cacheKey) && cache != null) {
            return;
        }
        save();
        Path file = FabricLoader.getInstance().getConfigDir().resolve("theprisons").resolve("storage").resolve(key + ".dat");
        cache = VaultCache.load(file, client.world.getRegistryManager());
        cacheKey = key;
        state.loadedOnce.clear();
    }

    private void save() {
        MinecraftClient client = MinecraftClient.getInstance();
        lastSaveMs = Util.getMeasuringTimeMs();
        if (cache != null && client.world != null) {
            cache.saveIfDirty(client.world.getRegistryManager());
        }
    }

    // ── For the screen ───────────────────────────────────────────────────────

    /** Number of cards: the override, the known count, or what the running scan has found so far. */
    int vaultCount() {
        if (countOverride.get() > 0) {
            return countOverride.get();
        }
        VaultCache c = cache;
        if (c == null) {
            return 0;
        }
        if (c.count() != VaultCache.UNKNOWN) {
            return Math.max(c.count(), 0);
        }
        VaultScan current = scan;
        return Math.max(c.highestSeen(), current != null ? current.result() : 0);
    }

    boolean scanning() {
        return scan != null;
    }

    /** The items of every vault seen so far (page number -> items), for the player viewer. */
    public java.util.Map<Integer, java.util.List<net.minecraft.item.ItemStack>> knownVaults() {
        java.util.Map<Integer, java.util.List<net.minecraft.item.ItemStack>> out = new java.util.TreeMap<>();
        int count = Math.max(vaultCount(), cache != null ? cache.highestSeen() : 0);
        for (int page = 1; page <= count; page++) {
            VaultCache.Page p = cachedPage(page);
            if (p != null) {
                out.put(page, p.items());
            }
        }
        return out;
    }

    VaultCache.@Nullable Page cachedPage(int page) {
        return cache != null ? cache.page(page) : null;
    }

    int typicalRows() {
        return cache != null ? cache.typicalRows() : 6;
    }

    /** The page waiting for the server (not a probe), or 0. */
    int pendingPage() {
        Request request = pending;
        return request != null && !request.probe() ? request.page() : 0;
    }

    String statusText() {
        return Util.getMeasuringTimeMs() < statusUntilMs ? status : "";
    }

    private void setStatus(String text, long forMs) {
        status = text;
        statusUntilMs = Util.getMeasuringTimeMs() + forMs;
    }

    int columns() {
        return columns.get();
    }

    boolean animations() {
        return io.theprisons.modules.general.DesignModule.animations();
    }

    /** Card background alpha, 0-255. */
    int backgroundAlpha() {
        return io.theprisons.modules.general.DesignModule.cardAlpha();
    }

    boolean showInventory() {
        return inventory.on();
    }

    OverlayState state() {
        return state;
    }

    void requestRescan() {
        rescan();
    }
}
