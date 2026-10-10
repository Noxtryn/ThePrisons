package io.theprisons.modules.qol.market;

import io.theprisons.ThePrisonsClient;
import io.theprisons.core.client.ClientReadouts;
import io.theprisons.core.client.TextStrip;
import io.theprisons.core.command.CommandService;
import io.theprisons.core.event.CoreEvents;
import io.theprisons.core.event.EventBus;
import io.theprisons.core.module.Category;
import io.theprisons.core.module.Module;
import io.theprisons.core.setting.Settings;
import io.theprisons.modules.mining.ore.OreMacroModule;
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
import net.minecraft.network.packet.c2s.play.CloseHandledScreenC2SPacket;
import net.minecraft.registry.Registries;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.ScreenHandlerType;
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
    private final ItemCatalog catalog = new ItemCatalog();
    /** Shop title → its offers as last seen, and when / when it changes next. */
    private final Map<String, ShopRecord> shops = new LinkedHashMap<>();
    private final Settings.BoolSetting autoScan;
    private final Settings.IntSetting interval;
    private final Settings.BoolSetting shopScan;
    private final Settings.BoolSetting scanWhileMacro;
    private final Settings.BoolSetting redesign;
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
    private enum Step { IDLE, AH_OPEN, AH_CATEGORIES, AH_LISTINGS, AH_HISTORY, EE_OPEN, EE_ANALYTICS, SHOP_MENU, SHOP_PAGE, CLOSE }

    /** Pages of listings one scan walks at most (45 listings each). */
    private static final int MAX_LISTING_PAGES = 150;

    private Step step = Step.IDLE;
    private long stepSince;
    private int stepPages;
    private int pagesAtStep;
    private long nextScanMs;
    /** Earliest time of the next shop check (an unknown or failing shop is not retried every tick). */
    private long nextShopMs;
    private boolean joinedScan = true;
    private final List<String> shopQueue = new ArrayList<>();
    /** The menu the scan has open: it is never shown (no screen), the scan reads and clicks it in the background. */
    private @Nullable ScreenHandler bgHandler;
    private String bgTitle = "";
    /** The last command the scan sent and when (the server's wrong-command answer is matched to it). */
    private String lastCommand = "";
    private long commandSentMs;
    /**
     * After the scan was stopped with a menu still on its way (the server answers /ah or /ee a moment later): menus
     * of the scan's kind that arrive until then are closed again without ever being shown.
     */
    private long strayUntilMs;
    /**
     * The automatic scan is silent: the server's sounds that answer its commands and clicks (menu open, button click)
     * are dropped for a moment after each of them ({@link #silent()}, used by the network handler mixin).
     */
    private static volatile long quietUntilMs;
    private static volatile boolean scanSending;
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
                .description("Reads /ah (categories, history) and /ee by itself every few minutes, completely in the "
                        + "background: no screen, no sound, no chat message. Never in combat or while a menu is open.").group("Scan");
        interval = integer("interval", "Pause between scans (minutes)", 5, 1, 60, 1).group("Scan").visibleWhen(autoScan::on);
        scanWhileMacro = bool("scan_while_macro", "Scan while the Ore Macro runs", false)
                .description("Off (recommended): the server blocks mining while a menu is open, so a scan during the macro stops it "
                        + "and can walk it out of the guarded zone. The scan then waits until the macro is off.")
                .group("Scan").visibleWhen(autoScan::on);
        shopScan = bool("shop_scan", "Check the /gz and /pb shops", true)
                .description("Right after they change and every day at 22:00: the new items and what they are worth "
                        + "(random items: the average of their kind).").group("Scan");
        redesign = bool("redesign", "Own /ah and /ee menus", true)
                .description("Shows the auction house (categories Cosmetics / Upgrades / Mining / Combat / Other, search) and "
                        + "/ee in the mod's design. Clicks go to the server's menu as usual.").group("Menus");
        instance = this;
    }

    /** True while the server's sounds belong to the scan's own menu actions: they are not played. */
    public static boolean silent() {
        return System.currentTimeMillis() < quietUntilMs;
    }

    /** A command the player typed (not the scan's own): a menu it opens within the stray window is wanted. */
    public static void onPlayerCommand() {
        MarketModule m = instance;
        if (m != null && !scanSending) {
            m.strayUntilMs = 0L;
        }
    }

    private static void quiet(long ms) {
        quietUntilMs = Math.max(quietUntilMs, System.currentTimeMillis() + ms);
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

    /** The real items (stacks) of the price book's entries. */
    public ItemCatalog catalog() {
        ensureLoaded();
        return catalog;
    }

    /** True while the automatic scan has a menu open or is about to. */
    public boolean scanning() {
        return step != Step.IDLE;
    }

    /**
     * Called for every screen the server opens: the auction house menus and /ee become {@link MarketScreen} (not the
     * ones the automatic scan opens - they close again at once). True = the vanilla screen must not open.
     */
    public boolean interceptOpen(ScreenHandlerType<?> type, MinecraftClient client, int syncId, Text title) {
        ClientPlayerEntity player = client.player;
        if (enabled() && player != null && step == Step.IDLE && System.currentTimeMillis() < strayUntilMs
                && isScanMenu(TextStrip.strip(title.getString()))) {
            // A late answer to a scan that was stopped: the menu must not pop up in front of the player - also not while the
            // player has another screen open (that is usually why the scan stopped). Only the stray menu is closed.
            ScreenHandler stray = type.create(syncId, player.getInventory());
            if (stray != null) {
                quiet(800L);
                player.networkHandler.sendPacket(new CloseHandledScreenC2SPacket(syncId));
                ThePrisonsClient.LOGGER.info("[market] late scan menu '{}' closed unseen (screen: {})", title.getString(),
                    client.currentScreen == null ? "none" : client.currentScreen.getClass().getSimpleName());
                return true;
            }
        }
        if (enabled() && player != null && step != Step.IDLE && !expects(TextStrip.strip(title.getString()))) {
            // Not the menu the scan waits for: the player opened something. The scan gives way, the menu opens normally.
            abort("another menu was opened", true);
        }
        if (enabled() && player != null && step != Step.IDLE) {
            // The scan's own menu: kept in the background, no screen at all.
            ScreenHandler hidden = type.create(syncId, player.getInventory());
            if (!(hidden instanceof GenericContainerScreenHandler)) {
                return false;
            }
            quiet(800L);
            player.currentScreenHandler = hidden;
            bgHandler = hidden;
            bgTitle = TextStrip.strip(title.getString());
            handledHash = 0;
            lastHash = 0;
            stableTicks = 0;
            return true;
        }
        if (enabled() && redesign.on() && player != null && TinkerScreen.TITLE.equals(TextStrip.strip(title.getString()))) {
            ScreenHandler tinker = type.create(syncId, player.getInventory());
            if (tinker instanceof GenericContainerScreenHandler h && h.getRows() == 6) {
                player.currentScreenHandler = h;
                client.setScreen(new TinkerScreen(h, title));
                return true;
            }
            return false;
        }
        if (enabled() && redesign.on() && player != null && ShopView.handles(TextStrip.strip(title.getString()))) {
            ScreenHandler shop = type.create(syncId, player.getInventory());
            if (shop instanceof GenericContainerScreenHandler h && h.getRows() >= 3 && h.getRows() <= 4) {
                player.currentScreenHandler = h;
                client.setScreen(new ShopScreen(this, h, title));
                return true;
            }
            return false;
        }
        if (!enabled() || !redesign.on() || player == null || !MarketScreen.handles(TextStrip.strip(title.getString()))) {
            return false;
        }
        ScreenHandler created = type.create(syncId, player.getInventory());
        if (!(created instanceof GenericContainerScreenHandler handler) || handler.getRows() != 6) {
            return false;
        }
        player.currentScreenHandler = handler;
        client.setScreen(new MarketScreen(this, handler, title));
        return true;
    }

    /** Menus the automatic scan opens (the AH, /ee and the two shops). */
    private static boolean isScanMenu(String title) {
        return title.equals(MarketParser.MARKET) || title.equals(MarketParser.CATEGORIES) || title.equals(MarketParser.HISTORY)
                || title.equals(MarketParser.ENERGY) || title.equals(MarketParser.ANALYTICS)
                || title.equals("Ground Zero") || title.equals("Prison Break") || title.endsWith("Shop");
    }

    /** The title of the menu the running scan step waits for. */
    private boolean expects(String title) {
        return switch (step) {
            case AH_OPEN -> title.equals(MarketParser.MARKET);
            case AH_CATEGORIES -> title.equals(MarketParser.CATEGORIES);
            case AH_LISTINGS -> title.equals(MarketParser.MARKET);
            case AH_HISTORY -> title.equals(MarketParser.MARKET) || title.equals(MarketParser.HISTORY);
            case EE_OPEN -> title.equals(MarketParser.ENERGY);
            case EE_ANALYTICS -> title.equals(MarketParser.ANALYTICS);
            case SHOP_MENU -> title.equals("Ground Zero") || title.equals("Prison Break");
            case SHOP_PAGE -> title.endsWith("Shop");
            default -> false;
        };
    }

    public Map<String, ShopRecord> shops() {
        return shops;
    }

    public void register(EventBus bus, CommandService commands) {
        new MenuRecorder().register(bus);
        always(CoreEvents.TickEnd.class, e -> {
            if (enabled()) {
                tick(e.client());
            }
        });
        always(CoreEvents.ChatReceived.class, e -> {
            String text = TextStrip.strip(e.message().getString()).toLowerCase(Locale.ROOT);
            if (!e.fromPlayer() && text.contains("you have entered combat")) {
                combatUntilMs = System.currentTimeMillis() + 12_000L;
            }
            long now = System.currentTimeMillis();
            if (!e.fromPlayer() && step != Step.IDLE && now - commandSentMs < 5_000L
                    && (text.contains("wrong command") || text.contains("unknown command") || text.contains("unknown or incomplete")
                    || text.contains("market is disabled") || text.contains("is disabled in the"))) {
                // The server does not know the command here (the spawn, another world): stop and try much later.
                ThePrisonsClient.LOGGER.info("[market] /{} is not available here - backing off", lastCommand);
                String failed = lastCommand;
                abort("/" + failed + " is not available here");
                long later = now + 30L * 60_000L;
                if (failed.equals("gz") || failed.equals("pb")) {
                    nextShopMs = later;
                } else {
                    nextScanMs = later;
                }
            }
        });
        always(CoreEvents.PlayerHurt.class, e -> hurtAtMs = System.currentTimeMillis());
        always(CoreEvents.WorldChanged.class, e -> {
            if (e.previous() == null && e.current() != null) {
                // Joined: soon a scan that reads the history as far back as it goes (the time offline).
                joinedScan = true;
                nextScanMs = System.currentTimeMillis() + 20_000L;
            }
            if (e.current() == null) {
                abort("left the world", false);
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
        ScreenHandler handler;
        String title;
        if (client.player != null && client.currentScreen instanceof HandledScreen<?> screen) {
            handler = screen.getScreenHandler();
            if (handler == client.player.playerScreenHandler) {
                return;
            }
            title = TextStrip.strip(screen.getTitle().getString());
        } else if (client.player != null && bgHandler != null && client.player.currentScreenHandler == bgHandler) {
            handler = bgHandler;
            title = bgTitle;
        } else {
            stableTicks = 0;
            lastHash = 0;
            return;
        }
        int menu = Math.max(0, handler.slots.size() - PLAYER_SLOTS);
        List<MarketParser.Item> items = new ArrayList<>();
        Map<Integer, ItemStack> stacks = new LinkedHashMap<>();
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
            stacks.put(i, stack);
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
        handle(title, items, stacks, now);
    }

    static @Nullable String customId(ItemStack stack) {
        NbtComponent data = stack.get(DataComponentTypes.CUSTOM_DATA);
        if (data == null) {
            return null;
        }
        Matcher m = CUSTOM_ID.matcher(data.copyNbt().toString());
        return m.find() ? m.group(1) : null;
    }

    /** One stable page of a server menu: its prices into the book. */
    void handle(String title, List<MarketParser.Item> items, Map<Integer, ItemStack> stacks, long now) {
        int before = book.all().size();
        switch (title) {
            case MarketParser.MARKET -> {
                for (MarketParser.Listing l : MarketParser.listings(items)) {
                    PriceBook.Entry old = book.get(PriceBook.key(l.customId(), l.name()));
                    boolean fresh = old == null || old.source().equals("kind") || now - old.seenMs() > 4L * 60_000L;
                    book.seen(PriceBook.key(l.customId(), l.name()), l.name(), category(l.name(), l.customId()), l.unitPrice(), now,
                            "ah", fresh, l.icon());
                }
                keepItems(items, stacks);
            }
            case MarketParser.CATEGORIES -> {
                for (MarketParser.Kind k : MarketParser.kinds(items)) {
                    String category = category(k.name(), k.customId());
                    if (k.contains().isEmpty()) {
                        // A kind that lists its variants ("Pickaxe Dust", "Contraband") is only a heading; one that does not is the item.
                        book.seen(PriceBook.key(k.customId(), k.name()), k.name(), category, k.lowest(), now,
                                "ah", true, k.icon());
                    }
                    for (String contained : k.contains()) {
                        String key = PriceBook.key(null, contained);
                        PriceBook.Entry old = book.get(key);
                        if (old == null || old.price() <= 0.0D || old.source().equals("kind")) {
                            // Only the kind's lowest price is known: the variant costs at least this ("~" in the search).
                            book.seen(key, contained, category(contained, null), k.lowest(), now, "kind", true, k.icon());
                        }
                    }
                }
            }
            case MarketParser.HISTORY -> {
                keepItems(items, stacks);
                for (MarketParser.Sale s : MarketParser.sales(items)) {
                    long at = now - s.agoMs();
                    PriceBook.Entry old = book.get(PriceBook.key(s.customId(), s.name()));
                    if (old == null || old.price() <= 0.0D || old.seenMs() < at || old.source().equals("kind")) {
                        // A sale newer than what is known (also one while offline): its price, at its time.
                        book.seen(PriceBook.key(s.customId(), s.name()), s.name(), category(s.name(), s.customId()), s.unitPrice(), at,
                                "sold", true, s.icon());
                    }
                }
            }
            case MarketParser.ANALYTICS -> {
                MarketParser.Analytics a = MarketParser.analytics(items);
                if (a != null) {
                    book.energyAverages(a.today(), a.week());
                    ThePrisonsClient.LOGGER.info("[market] energy sales: ${} per 1k today, ${} over the week",
                            String.format(Locale.ROOT, "%.0f", a.today()), String.format(Locale.ROOT, "%.0f", a.week()));
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
                learnGkitOrder(title, items);
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
                    if ((old == null || !sameOffers(old.offers(), offers)) && step == Step.IDLE) {
                        // Only for a shop the player opened himself; the background scan never writes to the chat.
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

    /** The /gkit menu lists the kits in their order: "Astronaut G-Kit" ... → the item list shows them in that order. */
    private void learnGkitOrder(String title, List<MarketParser.Item> items) {
        String lower = title.toLowerCase(Locale.ROOT);
        if (!lower.contains("gkit") && !lower.contains("g-kit")) {
            return;
        }
        List<String> kits = new ArrayList<>();
        for (MarketParser.Item it : items) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?i)^([\\w'-]+)\\s+g-?kit\\b.*$").matcher(it.name());
            if (it.slot() < MarketParser.LISTING_SLOTS && m.matches() && !kits.contains(m.group(1))) {
                kits.add(m.group(1));
            }
        }
        if (kits.size() >= 2 && !kits.equals(ItemIdentity.gkitOrder())) {
            ItemIdentity.setGkitOrder(kits);
            book.touch();
            dirty = true;
            ThePrisonsClient.LOGGER.info("[market] G-Kit order from the menu '{}': {}", title, kits);
        }
    }

    /** The real stack of every priced slot of a listing / sale page, for the item list. */
    private void keepItems(List<MarketParser.Item> items, Map<Integer, ItemStack> stacks) {
        for (MarketParser.Item it : items) {
            ItemStack stack = stacks.get(it.slot());
            if (it.slot() < MarketParser.LISTING_SLOTS && stack != null && MarketParser.unitPrice(it) > 0.0D) {
                catalog.put(PriceBook.key(it.customId(), it.name()), stack);
            }
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
                && now >= combatUntilMs && now - hurtAtMs > 10_000L && !macro.busy() && bgHandler == null
                && (scanWhileMacro.on() || !macro.enabled());
    }

    private void scan(MinecraftClient client, long now) {
        ClientPlayerEntity player = client.player;
        if (player == null) {
            return;
        }
        if (step == Step.IDLE) {
            boolean marketDue = autoScan.on() && now >= nextScanMs;
            boolean shopDue = shopScan.on() && now >= nextShopMs && shopsDue(now);
            if ((marketDue || shopDue) && mayStart(client, now)) {
                start(player, now, marketDue, shopDue);
            }
            return;
        }
        if (macro.enabled() && !scanWhileMacro.on()) {
            abort("the ore macro is running");
            return;
        }
        if (client.currentScreen != null) {
            abort("a screen was opened (" + client.currentScreen.getClass().getSimpleName() + ")");
            return;
        }
        if (bgHandler != null && player.currentScreenHandler != bgHandler) {
            abort("another menu was opened", true);
            return;
        }
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
                        // Back to the market: every page of the listings, then the history.
                        click(client, MarketParser.slotNamed(lastItems, "Main Market Menu"), Step.AH_LISTINGS, now);
                        stepPages = 0;
                    }
                }
            }
            case AH_LISTINGS -> {
                if (newPage && lastTitle.equals(MarketParser.MARKET)) {
                    int next = MarketParser.nextPageSlot(lastItems);
                    boolean fullPage = MarketParser.listings(lastItems).size() >= MarketParser.LISTING_SLOTS;
                    if (next >= 0 && fullPage && ++stepPages < MAX_LISTING_PAGES) {
                        click(client, next, Step.AH_LISTINGS, now);
                    } else {
                        ThePrisonsClient.LOGGER.info("[market] listings: {} pages", stepPages + 1);
                        stepPages = 0;
                        click(client, MarketParser.slotNamed(lastItems, "Auction House History"), Step.AH_HISTORY, now);
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
                    int analytics = MarketParser.slotNamed(lastItems, "Price Analytics");
                    if (analytics >= 0) {
                        click(client, analytics, Step.EE_ANALYTICS, now);
                    } else {
                        nextShopOrClose(player, now);
                    }
                }
            }
            case EE_ANALYTICS -> {
                if (newPage && lastTitle.equals(MarketParser.ANALYTICS)) {
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
                closeMenu(player);
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
            nextShopMs = now + 5 * 60_000L;
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
        closeMenu(player);
        command(player, shop, Step.SHOP_MENU, now);
    }

    private void closeMenu(ClientPlayerEntity player) {
        quiet(800L);
        bgHandler = null;
        player.closeHandledScreen();
    }

    private void command(ClientPlayerEntity player, String command, Step next, long now) {
        quiet(800L);
        lastCommand = command;
        commandSentMs = now;
        scanSending = true;
        try {
            player.networkHandler.sendChatCommand(command);
        } finally {
            scanSending = false;
        }
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
        quiet(800L);
        client.interactionManager.clickSlot(handler.syncId, slot, 0, SlotActionType.PICKUP, player);
        step = next;
        stepSince = now;
        pagesAtStep = pagesRead;
    }

    private void abort(String why) {
        abort(why, true);
    }

    /**
     * @param late true when the server may still answer the last command / click with a menu (everything but "the player
     *             opened another menu", leaving the world and turning the module off)
     */
    private void abort(String why, boolean late) {
        if (step == Step.IDLE) {
            return;
        }
        ThePrisonsClient.LOGGER.info("[market] scan stopped: {}", why);
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null && bgHandler != null) {
            releaseMenu(client, client.player);
        }
        step = Step.IDLE;
        long now = System.currentTimeMillis();
        nextScanMs = now + 60_000L;
        strayUntilMs = late ? now + 15_000L : 0L;
    }

    /**
     * Gives the scan's menu up without touching what the player sees: only the close packet goes to the server. (A
     * plain closeHandledScreen would also close the inventory the player has just opened with E - the reason the key
     * had to be pressed twice.)
     */
    private void releaseMenu(MinecraftClient client, ClientPlayerEntity player) {
        ScreenHandler hidden = bgHandler;
        bgHandler = null;
        if (hidden == null) {
            return;
        }
        quiet(800L);
        if (client.currentScreen == null) {
            player.closeHandledScreen();
        } else {
            player.networkHandler.sendPacket(new CloseHandledScreenC2SPacket(hidden.syncId));
            if (player.currentScreenHandler == hidden) {
                player.currentScreenHandler = player.playerScreenHandler;
            }
        }
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
        return false;
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
                    .append(Text.literal(worth(e.price())).formatted(Formatting.GREEN))
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
            catalog.load(file().resolveSibling("catalog.json"));
        }
    }

    private void save() {
        if (loaded) {
            PriceStore.save(file(), book);
            if (catalog.dirty()) {
                catalog.save(file().resolveSibling("catalog.json"));
            }
        }
    }

    @Override
    protected void onDisable() {
        abort("turned off", false);
        save();
    }
}
