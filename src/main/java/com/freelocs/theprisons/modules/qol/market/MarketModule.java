package com.freelocs.theprisons.modules.qol.market;

import com.freelocs.theprisons.ThePrisonsClient;
import com.freelocs.theprisons.core.client.ClientReadouts;
import com.freelocs.theprisons.core.client.TextStrip;
import com.freelocs.theprisons.core.command.CommandService;
import com.freelocs.theprisons.core.event.CoreEvents;
import com.freelocs.theprisons.core.event.EventBus;
import com.freelocs.theprisons.core.module.Category;
import com.freelocs.theprisons.core.module.Module;
import com.freelocs.theprisons.core.setting.Settings;
import com.freelocs.theprisons.modules.mining.ore.OreMacroModule;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.jspecify.annotations.Nullable;

import java.nio.file.Path;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Market prices of every Cosmic item: read from the server's menus whenever one is open (yours or the automatic scan),
 * kept with the date they were seen ({@link PriceBook}, {@code config/theprisons/market/prices.json}) and shown in the
 * item search ({@link MarketSearch}) with their value in energy at the cheapest /ee offer.
 *
 * <p>Automatic scan (every {@code interval} minutes): /ah → "Category View" (every item kind with its lowest price,
 * all pages) → "Auction House History" (real sales; after joining as far back as it goes - the sales of the time
 * offline) → /ee (the cheapest offer). The Ground Zero and Prison Break shops right after they change (their own clock)
 * and every day at 22:00. While it scans the ore macro stands still; it never starts in combat, in a menu, or while the
 * macro is on a chore / outside the guarded zone.</p>
 */
public final class MarketModule extends Module {
    private static final int STABLE_TICKS = 6;
    private static final int PLAYER_SLOTS = 36;
    private static final long STEP_TIMEOUT_MS = 6_000L;
    private static final int HISTORY_PAGES_JOIN = 12;
    private static final int HISTORY_PAGES = 2;
    private static final Pattern CUSTOM_ID = Pattern.compile("\"cosmicprisons:custom_item_id\":\"([^\"]+)\"");
    private static @Nullable MarketModule instance;

    private final OreMacroModule macro;
    private final PriceBook book = new PriceBook();
    /** Shop title → its offers as last seen, and when / when it changes next. */
    private final Map<String, ShopRecord> shops = new LinkedHashMap<>();
    private final Settings.BoolSetting autoScan;
    private final Settings.IntSetting interval;
    private final Settings.BoolSetting shopScan;
    private boolean loaded;
    private boolean dirty;

    public record ShopRecord(String title, long seenMs, long resetAtMs, List<MarketParser.ShopOffer> offers) {
    }

    // Page reading
    private int lastHash;
    private int stableTicks;
    private int handledHash;
    private int pagesRead;
    private String lastTitle = "";
    private List<MarketParser.Item> lastItems = List.of();

    // Automatic scan
    private enum Step { IDLE, AH_OPEN, AH_CATEGORIES, AH_HISTORY, EE_OPEN, SHOP_MENU, SHOP_PAGE, CLOSE }

    private Step step = Step.IDLE;
    private long stepSince;
    private int stepPages;
    private int pagesAtStep;
    private long nextScanMs;
    private boolean joinedScan = true;
    private final List<String> shopQueue = new ArrayList<>();
    private long combatUntilMs;
    private long hurtAtMs;
    private int lastDailyDay = -1;
    private long lastTick;

    public MarketModule(OreMacroModule macro) {
        super("market", "Market Prices", Category.QOL, "Market",
                "Every Cosmic item with its lowest AH price and its worth in energy (cheapest /ee offer), searchable above "
                        + "the hotbar in your inventory. Prices are read from the AH, the AH history, /ee and the "
                        + "Ground Zero / Prison Break shops and kept with their date.", Settings.KeybindSetting.NONE);
        this.macro = macro;
        autoScan = bool("auto_scan", "Scan automatically", true)
                .description("Opens /ah (categories, history) and /ee by itself every few minutes. The ore macro stands "
                        + "still for those seconds. Never in combat or while a menu is open.").group("Scan");
        interval = integer("interval", "Every (minutes)", 5, 2, 60, 1).group("Scan").visibleWhen(autoScan::on);
        shopScan = bool("shop_scan", "Check the /gz and /pb shops", true)
                .description("Right after they change and every day at 22:00: the new items and what they are worth "
                        + "(random items: the average of their kind).").group("Scan");
        instance = this;
    }

    public static @Nullable MarketModule get() {
        return instance;
    }

    @Override
    public boolean enabledByDefault() {
        return true;
    }

    public PriceBook book() {
        ensureLoaded();
        return book;
    }

    public Map<String, ShopRecord> shops() {
        return shops;
    }

    public void register(EventBus bus, CommandService commands) {
        bus.subscribe(CoreEvents.TickEnd.class, this, e -> {
            if (enabled()) {
                tick(e.client());
            }
        });
        bus.subscribe(CoreEvents.ChatReceived.class, this, e -> {
            if (!e.fromPlayer() && TextStrip.strip(e.message().getString()).toLowerCase(Locale.ROOT)
                    .contains("you have entered combat")) {
                combatUntilMs = System.currentTimeMillis() + 12_000L;
            }
        });
        bus.subscribe(CoreEvents.PlayerHurt.class, this, e -> hurtAtMs = System.currentTimeMillis());
        bus.subscribe(CoreEvents.WorldChanged.class, this, e -> {
            if (e.previous() == null && e.current() != null) {
                // Joined: soon a scan that reads the history as far back as it goes (the time offline).
                joinedScan = true;
                nextScanMs = System.currentTimeMillis() + 20_000L;
            }
            if (e.current() == null) {
                abort("left the world");
                save();
            }
        });
        commands.contribute(root -> root
                .then(ClientCommandManager.literal("price")
                        .then(ClientCommandManager.argument("item", StringArgumentType.greedyString())
                                .executes(ctx -> price(ctx.getSource(), StringArgumentType.getString(ctx, "item")))))
                .then(ClientCommandManager.literal("market")
                        .then(ClientCommandManager.literal("scan").executes(ctx -> {
                            nextScanMs = 0L;
                            ctx.getSource().sendFeedback(Text.literal("Market scan starts as soon as nothing is open.").formatted(Formatting.GRAY));
                            return 1;
                        }))
                        .then(ClientCommandManager.literal("shops").executes(ctx -> shopReport(ctx.getSource())))));
    }

    // ── Reading the open menu ────────────────────────────────────────────────

    private void tick(MinecraftClient client) {
        ensureLoaded();
        long now = System.currentTimeMillis();
        readOpenMenu(client, now);
        scan(client, now);
        if (dirty && now - lastTick > 10_000L) {
            lastTick = now;
            dirty = false;
            save();
        }
    }

    private void readOpenMenu(MinecraftClient client, long now) {
        if (!(client.currentScreen instanceof HandledScreen<?> screen) || client.player == null) {
            stableTicks = 0;
            lastHash = 0;
            return;
        }
        ScreenHandler handler = screen.getScreenHandler();
        if (handler == client.player.playerScreenHandler) {
            return;
        }
        String title = TextStrip.strip(screen.getTitle().getString());
        int menu = Math.max(0, handler.slots.size() - PLAYER_SLOTS);
        List<MarketParser.Item> items = new ArrayList<>();
        int hash = title.hashCode();
        for (int i = 0; i < menu; i++) {
            ItemStack stack = handler.slots.get(i).getStack();
            if (stack.isEmpty()) {
                continue;
            }
            List<String> lore = ClientReadouts.lore(stack);
            String name = TextStrip.strip(stack.getName().getString());
            hash = hash * 31 + i * 7 + name.hashCode() + lore.hashCode();
            items.add(new MarketParser.Item(i, Registries.ITEM.getId(stack.getItem()).toString(), stack.getCount(), name, lore,
                    customId(stack)));
        }
        if (hash != lastHash) {
            lastHash = hash;
            stableTicks = 0;
            return;
        }
        if (++stableTicks != STABLE_TICKS || hash == handledHash) {
            return;
        }
        handledHash = hash;
        lastTitle = title;
        lastItems = items;
        pagesRead++;
        handle(title, items, now);
    }

    private static @Nullable String customId(ItemStack stack) {
        NbtComponent data = stack.get(DataComponentTypes.CUSTOM_DATA);
        if (data == null) {
            return null;
        }
        Matcher m = CUSTOM_ID.matcher(data.copyNbt().toString());
        return m.find() ? m.group(1) : null;
    }

    /** One stable page of a server menu: its prices into the book. */
    void handle(String title, List<MarketParser.Item> items, long now) {
        int before = book.all().size();
        switch (title) {
            case MarketParser.MARKET -> {
                for (MarketParser.Listing l : MarketParser.listings(items)) {
                    PriceBook.Entry old = book.get(PriceBook.key(l.customId(), l.name()));
                    boolean fresh = old == null || now - old.seenMs() > 4L * 60_000L;
                    book.seen(PriceBook.key(l.customId(), l.name()), l.name(), category(l.name(), l.customId()), l.unitPrice(), now,
                            "ah", fresh, l.icon());
                }
            }
            case MarketParser.CATEGORIES -> {
                for (MarketParser.Kind k : MarketParser.kinds(items)) {
                    String category = category(k.name(), k.customId());
                    book.seen(PriceBook.key(k.customId(), k.name()), k.name(), category, k.lowest(), now,
                            "ah", true, k.icon());
                    for (String contained : k.contains()) {
                        book.remember(PriceBook.key(null, contained), contained, category(contained, null), now, "ah", k.icon());
                    }
                }
            }
            case MarketParser.HISTORY -> {
                for (MarketParser.Sale s : MarketParser.sales(items)) {
                    long at = now - s.agoMs();
                    PriceBook.Entry old = book.get(PriceBook.key(s.customId(), s.name()));
                    if (old == null || old.seenMs() < at) {
                        // A sale newer than what is known (also one while offline): its price, at its time.
                        book.seen(PriceBook.key(s.customId(), s.name()), s.name(), category(s.name(), s.customId()), s.unitPrice(), at,
                                "sold", true, s.icon());
                    }
                }
            }
            case MarketParser.ENERGY -> {
                double rate = MarketParser.energyRate(items);
                if (rate > 0.0D) {
                    book.energyOffer(rate * 1000.0D, 1000.0D, now);
                    ThePrisonsClient.LOGGER.info("[market] /ee: ${} per 1k energy", String.format(Locale.ROOT, "%.0f", rate * 1000.0D));
                }
            }
            default -> {
                if (title.endsWith("Shop") && (title.startsWith("Ground Zero") || title.startsWith("Prison Break"))) {
                    List<MarketParser.ShopOffer> offers = MarketParser.shop(items);
                    long reset = MarketParser.shopResetMs(items);
                    ShopRecord old = shops.get(title);
                    shops.put(title, new ShopRecord(title, now, reset < 0L ? -1L : now + reset, offers));
                    for (MarketParser.ShopOffer offer : offers) {
                        book.remember(PriceBook.key(null, offer.name()), offer.name(), category(offer.name(), null), now,
                                "shop", offer.icon());
                        for (String loot : offer.loot()) {
                            String clean = loot.replaceFirst("^\\d+x\\s+", "").strip();
                            if (!clean.isEmpty()) {
                                book.remember(PriceBook.key(null, clean), clean, category(clean, null), now, "shop", offer.icon());
                            }
                        }
                    }
                    if (old == null || !sameOffers(old.offers(), offers)) {
                        announce(title, offers);
                    }
                }
            }
        }
        dirty = true;
        if (book.all().size() != before) {
            ThePrisonsClient.LOGGER.info("[market] '{}': {} items known", title, book.all().size());
        }
    }

    private static String category(String name, @Nullable String customId) {
        MarketCategory.Sorted s = MarketCategory.of(name, customId);
        return s.group().label + "/" + s.kind();
    }

    private static boolean sameOffers(List<MarketParser.ShopOffer> a, List<MarketParser.ShopOffer> b) {
        if (a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            if (!a.get(i).name().equals(b.get(i).name()) || a.get(i).points() != b.get(i).points()) {
                return false;
            }
        }
        return true;
    }

    /** The shop's items in the chat with what they are worth. */
    private void announce(String title, List<MarketParser.ShopOffer> offers) {
        ClientPlayerEntity player = MinecraftClient.getInstance().player;
        if (player == null || offers.isEmpty()) {
            return;
        }
        player.sendMessage(Text.literal("ThePrisons » " + title).formatted(Formatting.AQUA, Formatting.BOLD), false);
        for (MarketParser.ShopOffer o : offers) {
            player.sendMessage(Text.literal("  " + o.name() + "  ").formatted(Formatting.WHITE)
                    .append(Text.literal(String.format(Locale.ROOT, "%,d pts", o.points())).formatted(Formatting.GRAY))
                    .append(Text.literal("  ≈ " + worth(ShopValue.offer(o, book))).formatted(Formatting.GREEN)), false);
        }
    }

    /** "$20.0M (8.7M energy)" or "unknown". */
    public String worth(double money) {
        if (money < 0.0D) {
            return "unknown";
        }
        double energy = book.inEnergy(money);
        return "$" + Money.compact(money) + (energy < 0.0D ? "" : " (" + Money.compact(energy) + " energy)");
    }

    // ── Automatic scan ───────────────────────────────────────────────────────

    private boolean mayStart(MinecraftClient client, long now) {
        ClientPlayerEntity player = client.player;
        return player != null && !player.isDead() && client.currentScreen == null && client.getNetworkHandler() != null
                && now >= combatUntilMs && now - hurtAtMs > 10_000L && !macro.busy();
    }

    private void scan(MinecraftClient client, long now) {
        ClientPlayerEntity player = client.player;
        if (player == null) {
            return;
        }
        if (step == Step.IDLE) {
            boolean marketDue = autoScan.on() && now >= nextScanMs;
            boolean shopDue = shopScan.on() && shopsDue(now);
            if ((marketDue || shopDue) && mayStart(client, now)) {
                start(player, now, marketDue, shopDue);
            }
            return;
        }
        macro.holdForMenu(now + 1_500L);
        if (now - hurtAtMs < 1_000L || now < combatUntilMs) {
            abort("attacked");
            return;
        }
        if (now - stepSince > STEP_TIMEOUT_MS) {
            abort("no answer from the server at " + step);
            return;
        }
        boolean newPage = pagesRead != pagesAtStep;
        switch (step) {
            case AH_OPEN -> {
                if (newPage && lastTitle.equals(MarketParser.MARKET)) {
                    click(client, MarketParser.slotNamed(lastItems, "Category View"), Step.AH_CATEGORIES, now);
                }
            }
            case AH_CATEGORIES -> {
                if (newPage && lastTitle.equals(MarketParser.CATEGORIES)) {
                    int next = MarketParser.nextPageSlot(lastItems);
                    if (next >= 0) {
                        click(client, next, Step.AH_CATEGORIES, now);
                    } else {
                        // Back to the market, then its history.
                        click(client, MarketParser.slotNamed(lastItems, "Main Market Menu"), Step.AH_HISTORY, now);
                        stepPages = -1;
                    }
                }
            }
            case AH_HISTORY -> {
                if (!newPage) {
                    return;
                }
                if (lastTitle.equals(MarketParser.MARKET) && stepPages < 0) {
                    stepPages = 0;
                    click(client, MarketParser.slotNamed(lastItems, "Auction House History"), Step.AH_HISTORY, now);
                } else if (lastTitle.equals(MarketParser.HISTORY)) {
                    int next = MarketParser.slotNamed(lastItems, "Next Page ->");
                    if (++stepPages < (joinedScan ? HISTORY_PAGES_JOIN : HISTORY_PAGES) && next >= 0) {
                        click(client, next, Step.AH_HISTORY, now);
                    } else {
                        joinedScan = false;
                        command(player, "ee", Step.EE_OPEN, now);
                    }
                }
            }
            case EE_OPEN -> {
                if (newPage && lastTitle.equals(MarketParser.ENERGY)) {
                    nextShopOrClose(player, now);
                }
            }
            case SHOP_MENU -> {
                if (newPage && (lastTitle.equals("Ground Zero") || lastTitle.equals("Prison Break"))) {
                    int slot = MarketParser.slotNamed(lastItems, lastTitle.equals("Ground Zero") ? "Ground Zero Shop" : "Prison Break: Shop");
                    click(client, slot, Step.SHOP_PAGE, now);
                }
            }
            case SHOP_PAGE -> {
                if (newPage && lastTitle.endsWith("Shop")) {
                    nextShopOrClose(player, now);
                }
            }
            case CLOSE -> {
                player.closeHandledScreen();
                step = Step.IDLE;
                ThePrisonsClient.LOGGER.info("[market] scan done: {} items, ${} per 1k energy", book.all().size(),
                        String.format(Locale.ROOT, "%.0f", book.moneyPerEnergy() * 1000.0D));
                save();
            }
            default -> {
            }
        }
    }

    private void start(ClientPlayerEntity player, long now, boolean market, boolean shopsToo) {
        shopQueue.clear();
        if (shopsToo) {
            shopQueue.add("gz");
            shopQueue.add("pb");
        }
        if (market) {
            nextScanMs = now + interval.value() * 60_000L;
            ThePrisonsClient.LOGGER.info("[market] scan: /ah categories, history{}, /ee{}", joinedScan ? " (since joining)" : "",
                    shopsToo ? ", shops" : "");
            command(player, "ah", Step.AH_OPEN, now);
        } else {
            nextShopOrClose(player, now);
        }
    }

    private void nextShopOrClose(ClientPlayerEntity player, long now) {
        if (shopQueue.isEmpty()) {
            step = Step.CLOSE;
            stepSince = now;
            return;
        }
        String shop = shopQueue.remove(0);
        player.closeHandledScreen();
        command(player, shop, Step.SHOP_MENU, now);
    }

    private void command(ClientPlayerEntity player, String command, Step next, long now) {
        player.networkHandler.sendChatCommand(command);
        step = next;
        stepSince = now;
        pagesAtStep = pagesRead;
    }

    private void click(MinecraftClient client, int slot, Step next, long now) {
        ClientPlayerEntity player = client.player;
        if (slot < 0 || player == null || client.interactionManager == null) {
            abort("button not found at " + step);
            return;
        }
        ScreenHandler handler = player.currentScreenHandler;
        client.interactionManager.clickSlot(handler.syncId, slot, 0, SlotActionType.PICKUP, player);
        step = next;
        stepSince = now;
        pagesAtStep = pagesRead;
    }

    private void abort(String why) {
        if (step == Step.IDLE) {
            return;
        }
        ThePrisonsClient.LOGGER.info("[market] scan stopped: {}", why);
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null && client.currentScreen instanceof HandledScreen<?>) {
            client.player.closeHandledScreen();
        }
        step = Step.IDLE;
        nextScanMs = System.currentTimeMillis() + 60_000L;
    }

    /** A shop changed since it was last seen (its own clock), or it is 22:00 and today's check has not run. */
    private boolean shopsDue(long now) {
        // The shop itself exposes a reset timer. If we have not seen a shop yet, check it on the next safe scan.
        for (ShopRecord r : shops.values()) {
            if (r.resetAtMs() > 0L && now >= r.resetAtMs()) return true;
        }
        // Daily safety check: once the client is running after 22:00, do not wait for an exact one-minute window.
        java.time.LocalDate today = java.time.LocalDate.now();
        long daily = today.atTime(22, 0).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
        if (now >= daily && lastDailyDay != today.getDayOfYear()) {
            lastDailyDay = today.getDayOfYear();
            return true;
        }
        return shops.isEmpty();
    }

    // ── Commands ─────────────────────────────────────────────────────────────

    private int price(FabricClientCommandSource source, String query) {
        ensureLoaded();
        List<PriceBook.Entry> found = book.search(query);
        if (found.isEmpty()) {
            double v = ShopValue.item(query, book);
            source.sendFeedback(Text.literal(query + ": " + worth(v)).formatted(Formatting.GRAY));
            return 1;
        }
        for (PriceBook.Entry e : found.subList(0, Math.min(8, found.size()))) {
            source.sendFeedback(Text.literal(e.name() + "  ").formatted(Formatting.WHITE)
                    .append(Text.literal(worhith(e.price())).formatted(Formatting.GREEN))
                    .append(Text.literal("  " + MarketSearch.age(System.currentTimeMillis() - e.seenMs())).formatted(Formatting.DARK_GRAY)));
        }
        return 1;
    }

    private int shopReport(FabricClientCommandSource source) {
        if (shops.isEmpty()) {
            source.sendFeedback(Text.literal("No shop seen yet - /gz, /pb or /theprisons market scan.").formatted(Formatting.GRAY));
        }
        for (ShopRecord r : shops.values()) {
            announce(r.title(), r.offers());
        }
        return 1;
    }

    // ── File ─────────────────────────────────────────────────────────────────

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("theprisons").resolve("market").resolve("prices.json");
    }

    private void ensureLoaded() {
        if (!loaded) {
            loaded = true;
            PriceStore.load(file(), book);
        }
    }

    private void save() {
        if (loaded) {
            PriceStore.save(file(), book);
        }
    }

    @Override
    protected void onDisable() {
        abort("turned off");
        save();
    }
}
