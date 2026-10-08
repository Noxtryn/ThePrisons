package io.theprisons.items.client;

import io.theprisons.core.event.CoreEvents;
import io.theprisons.core.module.Category;
import io.theprisons.core.module.Module;
import io.theprisons.core.setting.Settings;
import io.theprisons.items.energy.EnergyExtractorState;
import io.theprisons.items.energy.EnergyOverlayModel;
import io.theprisons.items.energy.EnergyReader;
import io.theprisons.items.energy.EnergyReading;
import io.theprisons.items.energy.EnergyTracker;
import io.theprisons.mixin.ThePrisonsHandledAccessor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.slot.Slot;
import org.jspecify.annotations.Nullable;

import java.util.Locale;

/**
 * Shows the energy of the thing you are looking at - a pickaxe or satchel in an inventory, a Cosmic Energy item, an extractor menu - and only while you are
 * looking at it. No permanent HUD. Only values the game really shows are used (item lore in the known form, the stored amount); capacity, rate and ETA
 * appear only when they are known, rate and ETA only from observed changes.
 *
 * <p>The item is read when it changes (its stack or the menu revision), not per frame; the drawn model is rebuilt only when the state revision changed.
 */
public final class EnergyOverlayModule extends Module {
    public enum Mode {
        COMPACT("Compact"), DETAILED("Detailed");

        private final String label;

        Mode(String label) {
            this.label = label;
        }
    }

    private static final long LINGER_MS = 2500L;
    private static @Nullable EnergyOverlayModule instance;

    private final Settings.EnumSetting<Mode> mode;
    private final EnergyTracker tracker = new EnergyTracker();
    private volatile @Nullable EnergyOverlayModel model;
    private @Nullable EnergyReading lastReading;
    private long visibleUntilMs;
    private @Nullable ItemStack lastStack;
    private @Nullable ItemStack readingStack;
    private int lastStackCount = -1;
    private int lastRevision = Integer.MIN_VALUE;
    private int reads;
    private boolean viaExtractorTitle;

    public EnergyOverlayModule() {
        super("energy_overlay", "Energy Overlay", Category.QOL, "Items",
                "Shows the energy of the pickaxe, satchel, energy item or extractor you are looking at: stored, capacity and - when they can be worked out from what "
                        + "the game shows - the rate and the time left. Appears only while you look at it; unknown values are left out, never guessed.",
                Settings.KeybindSetting.NONE);
        mode = choice("mode", "Mode", Mode.DETAILED, m -> m.label).group("Display");
        instance = this;
    }

    public static @Nullable EnergyOverlayModule get() {
        return instance;
    }

    @Override
    protected void onEnable() {
        on(CoreEvents.TickEnd.class, event -> tick(event.client()));
    }

    @Override
    protected void onDisable() {
        tracker.clear();
        model = null;
        lastStack = null;
    }

    private void tick(MinecraftClient client) {
        Screen screen = client.currentScreen;
        long now = System.currentTimeMillis();
        if (screen instanceof HandledScreen<?> hs) {
            Slot focused = ((ThePrisonsHandledAccessor) hs).theprisons$focusedSlot();
            ItemStack stack = focused == null ? ItemStack.EMPTY : focused.getStack();
            int revision = hs.getScreenHandler().getRevision();
            boolean extractorTitle = EnergyReader.mentionsExtractor(hs.getTitle().getString());
            if (stack != lastStack || stack.getCount() != lastStackCount || revision != lastRevision) {
                lastStack = stack;
                lastStackCount = stack.getCount();
                lastRevision = revision;
                EnergyReading reading = null;
                if (!stack.isEmpty()) {
                    reading = EnergyReader.read(ItemFactsReader.read(stack));
                    reads++;
                }
                if (reading == null && extractorTitle) {
                    reading = firstEnergyIn(hs);
                }
                if (reading != null) {
                    readingStack = stack.isEmpty() ? null : stack;
                    lastReading = reading;
                    tracker.update(reading, now);
                    visibleUntilMs = now + LINGER_MS;
                    viaExtractorTitle = extractorTitle;
                }
            } else if (lastReading != null && (!stack.isEmpty() && stack == readingStack || extractorTitle && readingStack == null)) {
                // still looking at the item (or still in the extractor menu) that gave the reading: keep the panel up; the model's age keeps running
                visibleUntilMs = Math.max(visibleUntilMs, now + 400L);
            }
        }
        EnergyExtractorState state = tracker.state();
        EnergyOverlayModel current = model;
        if (state != null && (current == null || current.revision() != state.revision() || current.stale() != state.stale(now))) {
            model = EnergyOverlayModel.of(state, now);
        }
    }

    /** In an extractor menu: the first slot whose item carries an energy section. */
    private @Nullable EnergyReading firstEnergyIn(HandledScreen<?> hs) {
        for (Slot slot : hs.getScreenHandler().slots) {
            if (!slot.getStack().isEmpty()) {
                EnergyReading r = EnergyReader.read(ItemFactsReader.read(slot.getStack()));
                reads++;
                if (r != null) {
                    return r;
                }
            }
        }
        return null;
    }

    // ── For the renderer ─────────────────────────────────────────────────────

    public @Nullable EnergyOverlayModel visibleModel() {
        return System.currentTimeMillis() <= visibleUntilMs ? model : null;
    }

    public boolean detailed() {
        return mode.get() == Mode.DETAILED;
    }

    public String devLine() {
        EnergyExtractorState s = tracker.state();
        if (s == null) {
            return "Energy: no reading";
        }
        EnergyOverlayModel m = model;
        return String.format(Locale.ROOT, "Energy: source %s · updated %ds ago · known: stored%s%s · unknown: %s · reads %d · extractor menu %s", s.source(),
                (System.currentTimeMillis() - s.lastUpdateMs()) / 1000L, s.capacity() != null ? ", capacity" : "", s.rateMin() != null ? ", rate" : "",
                m == null ? "-" : String.join(", ", m.unknown()), reads, viaExtractorTitle ? "yes" : "no");
    }
}
