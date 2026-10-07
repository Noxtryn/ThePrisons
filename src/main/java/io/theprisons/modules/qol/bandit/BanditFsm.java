package io.theprisons.modules.qol.bandit;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * The state machine of the bandit macro (pure logic): which move is allowed, how long a state may last, and one log
 * line per move. Safety moves (retreat, recall, recover, failsafe, stop) are allowed from every active state.
 */
public final class BanditFsm {
    /** How long each state may last, in ms (0 = no limit). */
    public record Timeouts(long search, long positioning, long aiming, long throwing, long returning, long recheck,
                           long retreating, long recalling, long recovering) {
        public long of(BanditState state) {
            return switch (state) {
                case SEARCHING -> search;
                case POSITIONING -> positioning;
                case AIMING, READY_TO_THROW -> aiming;
                case THROWING -> throwing;
                case WAITING_FOR_RETURN -> returning;
                case TARGET_RECHECK, TARGET_ACQUIRED -> recheck;
                case RETREATING -> retreating;
                case RECALL_REQUIRED, RECALLING -> recalling;
                case RECOVERING -> recovering;
                default -> 0L;
            };
        }
    }

    private static final Set<BanditState> SAFETY = EnumSet.of(BanditState.RETREATING, BanditState.RECALL_REQUIRED,
            BanditState.RECOVERING, BanditState.FAILSAFE, BanditState.STOPPED);
    private static final Map<BanditState, Set<BanditState>> FLOW = new EnumMap<>(BanditState.class);

    static {
        FLOW.put(BanditState.IDLE, EnumSet.of(BanditState.SEARCHING));
        FLOW.put(BanditState.SEARCHING, EnumSet.of(BanditState.TARGET_ACQUIRED));
        FLOW.put(BanditState.TARGET_ACQUIRED, EnumSet.of(BanditState.POSITIONING, BanditState.AIMING, BanditState.SEARCHING));
        FLOW.put(BanditState.POSITIONING, EnumSet.of(BanditState.AIMING, BanditState.TARGET_ACQUIRED, BanditState.SEARCHING));
        FLOW.put(BanditState.AIMING, EnumSet.of(BanditState.READY_TO_THROW, BanditState.POSITIONING, BanditState.TARGET_ACQUIRED,
                BanditState.SEARCHING));
        FLOW.put(BanditState.READY_TO_THROW, EnumSet.of(BanditState.THROWING, BanditState.AIMING, BanditState.SEARCHING));
        FLOW.put(BanditState.THROWING, EnumSet.of(BanditState.WAITING_FOR_RETURN));
        FLOW.put(BanditState.WAITING_FOR_RETURN, EnumSet.of(BanditState.TARGET_RECHECK));
        FLOW.put(BanditState.RECALL_REQUIRED, EnumSet.of(BanditState.RECALLING, BanditState.TARGET_RECHECK));
        FLOW.put(BanditState.RECALLING, EnumSet.of(BanditState.TARGET_RECHECK));
        FLOW.put(BanditState.TARGET_RECHECK, EnumSet.of(BanditState.TARGET_ACQUIRED, BanditState.SEARCHING));
        FLOW.put(BanditState.RETREATING, EnumSet.of(BanditState.SEARCHING, BanditState.TARGET_ACQUIRED));
        FLOW.put(BanditState.RECOVERING, EnumSet.of(BanditState.SEARCHING));
        FLOW.put(BanditState.FAILSAFE, EnumSet.of(BanditState.STOPPED));
        FLOW.put(BanditState.STOPPED, EnumSet.of(BanditState.IDLE));
    }

    private final Consumer<String> log;
    private final Timeouts timeouts;
    private BanditState state = BanditState.IDLE;
    private long enteredMs;
    private String reason = "";
    private int moves;

    public BanditFsm(Timeouts timeouts, Consumer<String> log) {
        this.timeouts = timeouts;
        this.log = log;
    }

    public BanditState state() {
        return state;
    }

    public String reason() {
        return reason;
    }

    public int moves() {
        return moves;
    }

    public long elapsed(long nowMs) {
        return nowMs - enteredMs;
    }

    public boolean is(BanditState... states) {
        for (BanditState s : states) {
            if (state == s) {
                return true;
            }
        }
        return false;
    }

    public static boolean allowed(BanditState from, BanditState to) {
        if (from == to) {
            return false;
        }
        if (SAFETY.contains(to)) {
            // STOPPED / FAILSAFE from anywhere; the others only while the macro is active.
            return to == BanditState.STOPPED || to == BanditState.FAILSAFE || !from.inactive();
        }
        return FLOW.getOrDefault(from, Set.of()).contains(to);
    }

    /** Moves to {@code next}; false (and a log line) when the move is not allowed. */
    public boolean to(BanditState next, String why, long nowMs) {
        if (!allowed(state, next)) {
            log.accept("STATE " + state + " -> " + next + " refused (" + why + ")");
            return false;
        }
        log.accept("STATE " + state + " -> " + next + "  Reason=" + why);
        state = next;
        reason = why;
        enteredMs = nowMs;
        moves++;
        return true;
    }

    /** The current state ran longer than its limit. */
    public boolean timedOut(long nowMs) {
        long limit = timeouts.of(state);
        return limit > 0L && nowMs - enteredMs > limit;
    }

    public long limit() {
        return timeouts.of(state);
    }
}
