package io.theprisons.items.client;

import io.theprisons.core.event.CoreEvents;
import io.theprisons.core.module.Category;
import io.theprisons.core.module.Module;
import io.theprisons.core.setting.Settings;
import io.theprisons.items.ItemFacts;
import io.theprisons.items.ItemsService;
import io.theprisons.items.market.AhAnalyzer;
import io.theprisons.items.market.AhSnapshot;
import io.theprisons.items.market.MarketCache;
import io.theprisons.items.market.ItemPrices;
import io.theprisons.items.market.ListingMeta;
import io.theprisons.items.market.MarketStats;
import io.theprisons.items.market.MarketStore;
import io.theprisons.modules.qol.market.MarketParser;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.GenericContainerScreen;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.slot.Slot;
import org.jspecify.annotations.Nullable;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A light layer over the SERVER's auction house menu (it does not replace or click anything): a thin border on a slot that is clearly cheap or expensive
 * against what this client has seen of the same item, a short percentage, and the full analysis in the item's hover. Information only.
 *
 * <p>Flow: the open menu changes (its revision) -> read the 45 slots once -> {@link AhAnalyzer} (normalise, cache, judge) -> an immutable
 * {@link AhSnapshot} -> the renderer reads it. Nothing is parsed, summed or sorted while drawing.
 */
public final class AhOverlayModule extends Module {
    private static @Nullable AhOverlayModule instance;

    private final Settings.BoolSetting borders;
    private final Settings.BoolSetting badges;
    private final MarketCache cache = new MarketCache();
    private final Path file;
    private volatile AhSnapshot snapshot = AhSnapshot.EMPTY;
    private @Nullable Screen snapshotScreen;
    private int lastRevision = Integer.MIN_VALUE;
    private int lastSyncId = Integer.MIN_VALUE;
    private long lastSaveMs;
    private long savedRevision;
    private boolean wasMarket;
    // dev numbers
    private long analyses;
    private long totalNanos;

    public AhOverlayModule(Path dataDir) {
        super("ah_overlay", "Auction Overlay", Category.QOL, "Items",
                "A thin border and a short percentage on auction listings that are far cheaper or dearer than the same item usually is, and the details when you hover. "
                        + "Only information: it learns from what the auction shows you and never clicks anything.", Settings.KeybindSetting.NONE);
        borders = bool("borders", "Slot borders", true).group("Display");
        badges = bool("badges", "Percentage badges", true).description("A small -16 % on listings 10 % or more off the usual price.").group("Display");
        this.file = dataDir.resolve("items").resolve("market_history.json");
        instance = this;
        MarketStore.load(file, cache, System.currentTimeMillis());
        savedRevision = cache.revision();
        ItemsService items = ItemsService.get();
        if (items != null) {
            items.marketDetail(this::detailLines);
        }
    }

    public static @Nullable AhOverlayModule get() {
        return instance;
    }

    @Override
    protected void onEnable() {
        on(CoreEvents.TickEnd.class, event -> tick(event.client()));
    }

    @Override
    protected void onDisable() {
        save();
        snapshot = AhSnapshot.EMPTY;
        snapshotScreen = null;
        lastRevision = Integer.MIN_VALUE;
    }

    // ── Analysis (on change only) ────────────────────────────────────────────

    private void tick(MinecraftClient client) {
        Screen screen = client.currentScreen;
        boolean market = false;
        boolean history = false;
        if (screen instanceof GenericContainerScreen container) {
            String title = container.getTitle().getString();
            market = title.equals(MarketParser.MARKET);
            history = title.equals(MarketParser.HISTORY);
            if (market || history) {
                GenericContainerScreenHandler handler = container.getScreenHandler();
                int revision = handler.getRevision();
                if (screen != snapshotScreen || revision != lastRevision || handler.syncId != lastSyncId) {
                    analyse(handler, screen, history);
                    lastRevision = revision;
                    lastSyncId = handler.syncId;
                }
            }
        }
        if (!market && !history && snapshotScreen != null) {
            snapshot = AhSnapshot.EMPTY;
            snapshotScreen = null;
            lastRevision = Integer.MIN_VALUE;
        }
        long now = System.currentTimeMillis();
        ItemsService svc = ItemsService.get();
        if (svc != null) {
            svc.prices().refresh(cache, now);
        }
        if (cache.revision() != savedRevision && (wasMarket && !market || now - lastSaveMs > 60_000L)) {
            save();
        }
        wasMarket = market || history;
    }

    private void analyse(GenericContainerScreenHandler handler, Screen screen, boolean history) {
        long now = System.currentTimeMillis();
        List<AhAnalyzer.ListingInput> listings = new ArrayList<>();
        List<AhAnalyzer.SaleInput> sales = new ArrayList<>();
        for (int i = 0; i < Math.min(MarketParser.LISTING_SLOTS, handler.slots.size()); i++) {
            Slot slot = handler.slots.get(i);
            if (slot.getStack().isEmpty()) {
                continue;
            }
            ItemFacts f = ItemFactsReader.read(slot.getStack());
            MarketParser.Item it = new MarketParser.Item(i, f.vanillaId(), f.count(), f.name(), f.lore(), f.customId());
            double total = MarketParser.totalPrice(it);
            if (total <= 0.0D) {
                continue;
            }
            ListingMeta meta = ListingMeta.parse(f.lore());
            if (history) {
                AhAnalyzer.SaleInput sale = AhAnalyzer.saleOf(it, f);
                if (sale != null) {
                    sales.add(sale);
                }
            } else {
                listings.add(new AhAnalyzer.ListingInput(i, f, total, it.count(), meta.seller(), meta.expiresInMs()));
            }
        }
        if (history) {
            AhAnalyzer.learnSales(sales, cache, now);
            snapshot = AhSnapshot.EMPTY;
        } else {
            long t0 = System.nanoTime();
            snapshot = AhAnalyzer.analyze(listings, cache, now, ((long) handler.syncId << 32) ^ handler.getRevision());
            totalNanos += System.nanoTime() - t0;
            analyses++;
        }
        snapshotScreen = screen;
    }

    private void save() {
        MarketStore.save(file, cache, System.currentTimeMillis());
        lastSaveMs = System.currentTimeMillis();
        savedRevision = cache.revision();
    }

    // ── For the item list ────────────────────────────────────────────────────

    private List<String> detailLines(String catalogKey) {
        MarketStats s = cache.statsForCatalog(catalogKey, System.currentTimeMillis());
        if (!s.known()) {
            return List.of();
        }
        return ItemPrices.detailLines(s);
    }

    // ── For the renderer (read only) ─────────────────────────────────────────

    public boolean activeFor(Screen screen) {
        return enabled() && screen == snapshotScreen;
    }

    public AhSnapshot snapshot() {
        return snapshot;
    }

    public boolean showBorders() {
        return borders.on();
    }

    public boolean showBadges() {
        return badges.on();
    }

    /** Dev profile: what the overlay did. */
    public String devLine() {
        AhSnapshot s = snapshot;
        long ageS = s.builtAtMs() == 0 ? -1 : (System.currentTimeMillis() - s.builtAtMs()) / 1000L;
        return String.format(Locale.ROOT, "AH listings %d · new %d · keys %d · cache hit/miss %d/%d · compute %.2f ms (avg %.2f) · snapshot %ds old",
                s.parsed(), s.newObservations(), cache.keys(), cache.cacheHits(), cache.cacheMisses(), s.computeNanos() / 1e6D,
                analyses == 0 ? 0.0D : totalNanos / 1e6D / analyses, ageS);
    }
}
