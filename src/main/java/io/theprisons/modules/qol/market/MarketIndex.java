package io.theprisons.modules.qol.market;

import io.theprisons.core.client.TextStrip;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.GenericContainerScreenHandler;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Every listing of the auction house, not just the server page the player is on: while there is a search text, the
 * server's pages are walked once (back to the first page, then forward with the "next page" button) and every item is
 * remembered with its page. The screens of the server are replaced on every page switch, so all state is static and is
 * driven by the tick of whichever {@link MarketScreen} is open. A click on a result walks to its page the same way and
 * clicks the real slot there.
 */
final class MarketIndex {
    private enum State { IDLE, REWIND, SCAN, DONE }

    private static final int STABLE_TICKS = 3;
    private static final int MAX_PAGES = 120;
    private static final long CLICK_GAP_MS = 400L;
    private static final long BOUNDARY_MS = 1_500L;
    private static final long STALE_MS = 45_000L;
    private static final long GIVE_UP_MS = 150_000L;

    private static State state = State.IDLE;
    private static String indexKind = "";
    private static List<MarketScreen.Cell> entries = List.of();
    private static final List<MarketScreen.Cell> building = new ArrayList<>();
    private static final Map<Integer, Integer> pageBySig = new HashMap<>();
    private static int pages;
    private static int lastSig;
    private static int sameTicks;
    private static boolean clicked;
    private static int clickedSig;
    private static long clickedMs;
    private static long lastStepMs;
    private static long startMs;
    private static long doneMs;
    private static int goalPage = -1;
    private static int goalSlot = -1;
    private static String goalName = "";
    /** Whether the slot the last {@link #step} returned is the goal item (not a page button). */
    private static boolean finalClick;

    private MarketIndex() {
    }

    /** What identifies a server page: the names and counts of the 45 listing slots (lore holds running timers). */
    static int signature(GenericContainerScreenHandler handler) {
        int sig = 17;
        int count = Math.min(handler.slots.size() - 36, 45);
        for (int i = 0; i < count; i++) {
            ItemStack stack = handler.slots.get(i).getStack();
            if (!stack.isEmpty()) {
                sig = sig * 31 + TextStrip.strip(stack.getName().getString()).hashCode() * 7 + stack.getCount() + i;
            }
        }
        return sig;
    }

    /** True while the index of this menu is being built or is ready. */
    static boolean active(String kind) {
        return state != State.IDLE && kind.equals(indexKind);
    }

    static boolean busy() {
        return state == State.REWIND || state == State.SCAN;
    }

    static int pagesRead() {
        return pages;
    }

    static boolean goalPending() {
        return goalPage >= 0;
    }

    static boolean finalClick() {
        return finalClick;
    }

    static String goalName() {
        return goalName;
    }

    static List<MarketScreen.Cell> display() {
        return entries.isEmpty() ? building : entries;
    }

    /** Walk to the page of a result and click its slot there (only once the index is complete). */
    static void go(MarketScreen.Cell cell) {
        if (state == State.DONE && cell.page() >= 0) {
            goalPage = cell.page();
            goalSlot = cell.slot();
            goalName = cell.name();
        }
    }

    static void forget() {
        goalPage = -1;
        state = State.IDLE;
    }

    private static void reset() {
        state = State.IDLE;
        entries = List.of();
        building.clear();
        pageBySig.clear();
        pages = 0;
        goalPage = -1;
        clicked = false;
    }

    private static void start(long now) {
        building.clear();
        pageBySig.clear();
        pages = 0;
        state = State.REWIND;
        clicked = false;
        startMs = now;
    }

    private static void finish(long now) {
        List<MarketScreen.Cell> sorted = new ArrayList<>(building);
        sorted.sort(Comparator.comparingInt((MarketScreen.Cell c) -> c.group().ordinal()).thenComparing(MarketScreen.Cell::kind));
        entries = sorted;
        state = State.DONE;
        doneMs = now;
        clicked = false;
    }

    private static int click(int slot, int sig, long now) {
        clicked = true;
        clickedSig = sig;
        clickedMs = now;
        return slot;
    }

    /**
     * One tick of an open menu. Returns the slot to click, or -1.
     *
     * @param sig  {@link #signature} of the menu now
     * @param cells the items of the menu now (already parsed)
     */
    static int step(String kind, int sig, List<MarketScreen.Cell> cells, int prevSlot, int nextSlot, boolean wanted) {
        long now = System.currentTimeMillis();
        finalClick = false;
        if (!kind.equals(indexKind)) {
            reset();
            indexKind = kind;
        }
        if (busy() && now - lastStepMs > 5_000L) {
            // the player left the menu in the middle of the walk
            state = State.IDLE;
        }
        lastStepMs = now;
        if (sig == lastSig) {
            sameTicks++;
        } else {
            lastSig = sig;
            sameTicks = 0;
        }
        if (state == State.DONE && wanted && goalPage < 0 && now - doneMs > STALE_MS) {
            state = State.IDLE;
        }
        if (state == State.IDLE) {
            if (!wanted) {
                return -1;
            }
            start(now);
        }
        if (busy() && now - startMs > GIVE_UP_MS) {
            finish(now);
            return -1;
        }
        boolean waitedOut = clicked && now - clickedMs > BOUNDARY_MS;
        if (cells.isEmpty()) {
            if (busy() && sameTicks > 40) {
                finish(now);
            }
            if (!(clicked && now - clickedMs > 3_000L)) {
                return -1;
            }
        }
        if (sameTicks < STABLE_TICKS) {
            return -1;
        }
        if (clicked && sig != clickedSig) {
            clicked = false;
        }
        boolean boundary = clicked && sig == clickedSig && waitedOut;
        if (clicked && !boundary) {
            return -1;
        }
        if (now - clickedMs < CLICK_GAP_MS) {
            return -1;
        }
        switch (state) {
            case REWIND -> {
                if (prevSlot < 0 || boundary) {
                    state = State.SCAN;
                    clicked = false;
                    return scan(sig, cells, nextSlot, false, now);
                }
                return click(prevSlot, sig, now);
            }
            case SCAN -> {
                return scan(sig, cells, nextSlot, boundary, now);
            }
            case DONE -> {
                return goal(sig, prevSlot, nextSlot, now);
            }
            default -> {
                return -1;
            }
        }
    }

    private static int scan(int sig, List<MarketScreen.Cell> cells, int nextSlot, boolean boundary, long now) {
        if (!boundary) {
            if (pageBySig.containsKey(sig) || pages >= MAX_PAGES) {
                finish(now);
                return -1;
            }
            pageBySig.put(sig, pages);
            for (MarketScreen.Cell c : cells) {
                building.add(c.onPage(pages));
            }
            pages++;
        }
        if (boundary || nextSlot < 0) {
            finish(now);
            return -1;
        }
        return click(nextSlot, sig, now);
    }

    private static int goal(int sig, int prevSlot, int nextSlot, long now) {
        if (goalPage < 0) {
            return -1;
        }
        Integer current = pageBySig.get(sig);
        if (current == null) {
            // the listings moved on since the walk: build the index again
            if (sameTicks > 20) {
                goalPage = -1;
                state = State.IDLE;
            }
            return -1;
        }
        if (current == goalPage) {
            int slot = goalSlot;
            goalPage = -1;
            finalClick = true;
            return slot;
        }
        int slot = goalPage > current ? nextSlot : prevSlot;
        if (slot < 0) {
            goalPage = -1;
            state = State.IDLE;
            return -1;
        }
        return click(slot, sig, now);
    }
}
