package io.theprisons.items;

import io.theprisons.ThePrisonsClient;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The one shared item platform of the mod: the registry (and its list model) that the item list, the auction house overlay and the tooltips read. It is
 * created once, seeded from the family directory, and extended with the real items of the market catalog the mod saved earlier (parsed on a worker
 * thread into immutable facts, handed back to the client thread). Client thread only after that.
 */
public final class ItemsService {
    private static ItemsService instance;

    private final ItemRegistry registry = ItemRegistry.seeded();
    private final ItemListModel listModel = new ItemListModel(registry);
    private final io.theprisons.items.market.ItemPrices prices = new io.theprisons.items.market.ItemPrices();
    private final Path dir;
    private boolean catalogLoading;
    /** What the detail panel shows about an item's market (set by the market part; empty until there is data). */
    private java.util.function.Function<String, List<String>> marketDetail = key -> List.of();

    private ItemsService(Path dir) {
        this.dir = dir;
    }

    public static synchronized ItemsService init(Path dataDir) {
        if (instance == null) {
            instance = new ItemsService(dataDir);
        }
        return instance;
    }

    public static ItemsService get() {
        return instance;
    }

    public ItemRegistry registry() {
        return registry;
    }

    /** The prices the item list shows (prepared from the market memory; read-only for the renderer). */
    public io.theprisons.items.market.ItemPrices prices() {
        return prices;
    }

    public ItemListModel list() {
        return listModel;
    }

    public void marketDetail(java.util.function.Function<String, List<String>> f) {
        this.marketDetail = f;
    }

    public List<String> marketDetail(String catalogKey) {
        return marketDetail.apply(catalogKey);
    }

    public Path dir() {
        return dir;
    }

    /**
     * Learns the items of {@code market/catalog.json} once (the items the auction house showed). The file is read and parsed off the client thread; the
     * registry changes only inside {@code onClientThread}.
     */
    public void loadCatalogOnce(Path catalogFile, Consumer<Runnable> onClientThread) {
        if (catalogLoading) {
            return;
        }
        catalogLoading = true;
        Thread worker = new Thread(() -> {
            List<ItemFacts> facts = new ArrayList<>();
            try {
                if (Files.isRegularFile(catalogFile)) {
                    JsonObject root = JsonParser.parseString(Files.readString(catalogFile)).getAsJsonObject();
                    for (Map.Entry<String, com.google.gson.JsonElement> e : root.entrySet()) {
                        if (e.getValue().isJsonObject()) {
                            facts.add(ItemFactsJson.read(e.getValue().getAsJsonObject()));
                        }
                    }
                }
            } catch (IOException | RuntimeException e) {
                ThePrisonsClient.LOGGER.warn("[items] could not read {}", catalogFile, e);
            }
            List<ItemFacts> done = List.copyOf(facts);
            onClientThread.accept(() -> {
                int learned = 0;
                for (ItemFacts f : done) {
                    if (registry.learn(f, f.vanillaId())) {
                        learned++;
                    }
                }
                ThePrisonsClient.LOGGER.info("[items] registry: {} items ({} learned from the market catalog)", registry.size(), learned);
            });
        }, "theprisons-items-catalog");
        worker.setDaemon(true);
        worker.start();
    }
}
