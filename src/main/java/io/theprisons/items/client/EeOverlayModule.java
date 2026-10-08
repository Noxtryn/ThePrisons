package io.theprisons.items.client;

import io.theprisons.core.event.CoreEvents;
import io.theprisons.core.module.Category;
import io.theprisons.core.module.Module;
import io.theprisons.core.setting.Settings;
import io.theprisons.items.ItemFacts;
import io.theprisons.items.market.EeAnalysis;
import io.theprisons.items.market.EeMenu;
import io.theprisons.items.market.EePanelLayout;
import io.theprisons.items.market.EeParser;
import io.theprisons.modules.qol.market.MarketParser;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.GenericContainerScreen;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.slot.Slot;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * The energy market ({@code /ee}, "Buy Cosmic Energy") as a light layer over the server's menu - passive, it never clicks or sends anything: a card with the
 * cheapest and the typical rate, what 10k ... 100M energy cost (cheapest listings first), what your balance buys and what the energy you hold is worth, a
 * thin border on the cheapest listing and on absurd ones (ignored in every number), and the details in a listing's hover.
 *
 * <p>The menu is read when it changes (its revision), analysed once ({@link EeAnalysis}) and drawn from that. The 7-day / today averages come from the
 * "Price Analytics" menu the last time you opened it this session (nothing is shown until then).
 */
public final class EeOverlayModule extends Module {
    private static @Nullable EeOverlayModule instance;

    private final Settings.BoolSetting borders;
    private volatile @Nullable EeAnalysis analysis;
    private @Nullable Screen analysedScreen;
    private int lastRevision = Integer.MIN_VALUE;
    private double weekAvg = Double.NaN;
    private double todayAvg = Double.NaN;
    private long analyses;
    private long totalNanos;

    public EeOverlayModule() {
        super("ee_overlay", "Energy Market Overlay", Category.QOL, "Items",
                "In /ee (Buy Cosmic Energy): the cheapest and the typical price per 1k energy, what a purchase of 10k ... 100M energy costs, what your balance buys and "
                        + "what your energy is worth. Absurd offers are marked and ignored. Only information: nothing is clicked or bought for you.",
                Settings.KeybindSetting.NONE);
        borders = bool("borders", "Listing borders", true).group("Display");
        instance = this;
    }

    public static @Nullable EeOverlayModule get() {
        return instance;
    }

    @Override
    protected void onEnable() {
        on(CoreEvents.TickEnd.class, event -> tick(event.client()));
    }

    @Override
    protected void onDisable() {
        analysis = null;
        analysedScreen = null;
        lastRevision = Integer.MIN_VALUE;
    }

    private void tick(MinecraftClient client) {
        Screen screen = client.currentScreen;
        if (!(screen instanceof GenericContainerScreen container)) {
            if (analysedScreen != null) {
                analysis = null;
                analysedScreen = null;
                lastRevision = Integer.MIN_VALUE;
            }
            return;
        }
        String title = container.getTitle().getString();
        boolean ee = title.equals(MarketParser.ENERGY);
        boolean analytics = title.equals(MarketParser.ANALYTICS);
        if (!ee && !analytics) {
            return;
        }
        GenericContainerScreenHandler handler = container.getScreenHandler();
        int revision = handler.getRevision();
        if (screen == analysedScreen && revision == lastRevision) {
            return;
        }
        List<MarketParser.Item> items = new ArrayList<>();
        for (int i = 0; i < Math.min(54, handler.slots.size() - 36); i++) {
            Slot slot = handler.slots.get(i);
            if (!slot.getStack().isEmpty()) {
                ItemFacts f = ItemFactsReader.read(slot.getStack());
                items.add(new MarketParser.Item(i, f.vanillaId(), f.count(), f.name(), f.lore(), f.customId()));
            }
        }
        long t0 = System.nanoTime();
        if (analytics) {
            MarketParser.Analytics a = MarketParser.analytics(items);
            if (a != null) {
                todayAvg = a.today();
                weekAvg = a.week();
            }
        } else {
            EeMenu menu = EeParser.parse(items);
            analysis = EeAnalysis.of(menu, heldEnergy(client), weekAvg, todayAvg);
            analyses++;
            totalNanos += System.nanoTime() - t0;
        }
        analysedScreen = screen;
        lastRevision = revision;
    }

    /** The Cosmic Energy items in the inventory: their stored amounts added up (the verified {@code amount} value). */
    private static double heldEnergy(MinecraftClient client) {
        if (client.player == null) {
            return 0.0D;
        }
        double sum = 0.0D;
        var inv = client.player.getInventory();
        for (int i = 0; i < inv.size(); i++) {
            ItemStack s = inv.getStack(i);
            if (!s.isEmpty()) {
                ItemFacts f = ItemFactsReader.read(s);
                if ("cosmic_energy".equals(f.customId()) && f.values().get("amount") != null) {
                    try {
                        sum += Double.parseDouble(f.values().get("amount"));
                    } catch (NumberFormatException ignored) {
                        // not a number: not counted
                    }
                }
            }
        }
        return sum;
    }

    public boolean activeFor(Screen screen) {
        return enabled() && screen == analysedScreen && analysis != null;
    }

    public @Nullable EeAnalysis analysis() {
        return analysis;
    }

    private EePanelLayout.@Nullable Result cachedLayout;
    private @Nullable EeAnalysis cachedFor;
    private int cachedWidth;

    /** The layout of the current analysis at this width; recomputed only when the page or the width changed (never per frame). */
    public EePanelLayout.Result layout(int width, io.theprisons.gui.kit.TextFit.Measure measure) {
        EeAnalysis a = analysis;
        if (cachedLayout == null || cachedFor != a || cachedWidth != width) {
            cachedLayout = EePanelLayout.layout(a.panel(), width, measure);
            cachedFor = a;
            cachedWidth = width;
        }
        return cachedLayout;
    }

    public boolean showBorders() {
        return borders.on();
    }

    public String devLine() {
        return String.format(java.util.Locale.ROOT, "EE analyses %d · avg %.2f ms · week avg %s", analyses, analyses == 0 ? 0.0D : totalNanos / 1e6D / analyses,
                Double.isNaN(weekAvg) ? "unknown (open Price Analytics)" : String.format(java.util.Locale.ROOT, "%.0f /1k", weekAvg));
    }
}
