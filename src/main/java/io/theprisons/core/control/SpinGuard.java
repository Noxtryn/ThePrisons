package io.theprisons.core.control;

/**
 * Detects a macro that turns and turns without getting anywhere: a lot of yaw change, hardly any position change and no
 * progress (no block changed around) within a short window. Pure logic, one sample per tick.
 *
 * <p>The first detection asks for a stabilisation and one fresh plan; a second one within {@code repeatWindowMs} asks for a safe
 * stop. Walking a big circle is not a spin (the position changes), and turning while blocks break is not either (progress).
 */
public final class SpinGuard {
    public enum Verdict {
        NONE,
        /** First spin: stop moving and turning, drop path and target, plan once more. */
        SPIN,
        /** Again within the repeat window: stop the macro. */
        SPIN_REPEATED
    }

    /** What the window looked like, for the log. */
    public record Metrics(int ticks, double yawTurnedDegrees, double maxDisplacement, double travelled, long progressInWindow) {
        public static final Metrics EMPTY = new Metrics(0, 0, 0, 0, 0);
    }

    public static final int DEFAULT_WINDOW_TICKS = 60;
    public static final double DEFAULT_YAW_LIMIT = 720.0D;
    public static final double DEFAULT_MAX_DISPLACEMENT = 2.0D;
    public static final long DEFAULT_REPEAT_WINDOW_MS = 90_000L;

    private final int window;
    private final double yawLimit;
    private final double maxDisplacement;
    private final long repeatWindowMs;
    private final double[] xs;
    private final double[] zs;
    private final float[] yaws;
    private final long[] progressAt;
    private int count;
    private int next;
    private long progress;
    private long lastSpinMs = Long.MIN_VALUE / 2L;
    private Metrics last = Metrics.EMPTY;
    private int spins;

    public SpinGuard() {
        this(DEFAULT_WINDOW_TICKS, DEFAULT_YAW_LIMIT, DEFAULT_MAX_DISPLACEMENT, DEFAULT_REPEAT_WINDOW_MS);
    }

    public SpinGuard(int windowTicks, double yawLimitDegrees, double maxDisplacement, long repeatWindowMs) {
        this.window = windowTicks;
        this.yawLimit = yawLimitDegrees;
        this.maxDisplacement = maxDisplacement;
        this.repeatWindowMs = repeatWindowMs;
        xs = new double[windowTicks];
        zs = new double[windowTicks];
        yaws = new float[windowTicks];
        progressAt = new long[windowTicks];
    }

    /** Something real happened in the world (a block changed): the macro is getting somewhere. */
    public void progress() {
        progress++;
    }

    public void reset() {
        count = 0;
        next = 0;
    }

    public Metrics metrics() {
        return last;
    }

    /** Total spins detected since the guard was created. */
    public int spins() {
        return spins;
    }

    /** One sample per tick (the final view and position of that tick). */
    public Verdict feed(long nowMs, double x, double z, float yaw) {
        xs[next] = x;
        zs[next] = z;
        yaws[next] = yaw;
        progressAt[next] = progress;
        next = (next + 1) % window;
        if (count < window) {
            count++;
        }
        if (count < window) {
            last = Metrics.EMPTY;
            return Verdict.NONE;
        }
        // Oldest sample = next (the slot about to be overwritten).
        int oldest = next;
        double turned = 0.0D;
        double travelled = 0.0D;
        double far = 0.0D;
        double ox = xs[oldest];
        double oz = zs[oldest];
        float prevYaw = yaws[oldest];
        double px = ox;
        double pz = oz;
        for (int i = 1; i < window; i++) {
            int k = (oldest + i) % window;
            turned += Math.abs(wrap(yaws[k] - prevYaw));
            prevYaw = yaws[k];
            travelled += Math.hypot(xs[k] - px, zs[k] - pz);
            px = xs[k];
            pz = zs[k];
            far = Math.max(far, Math.hypot(xs[k] - ox, zs[k] - oz));
        }
        long made = progressAt[(next + window - 1) % window] - progressAt[oldest];
        last = new Metrics(window, turned, far, travelled, made);
        if (turned >= yawLimit && far <= maxDisplacement && made == 0L) {
            Verdict verdict = nowMs - lastSpinMs <= repeatWindowMs ? Verdict.SPIN_REPEATED : Verdict.SPIN;
            lastSpinMs = nowMs;
            spins++;
            reset(); // a new window must fill up before the next verdict
            return verdict;
        }
        return Verdict.NONE;
    }

    /** Wraps an angle difference into (-180, 180]. */
    static double wrap(double degrees) {
        double d = degrees % 360.0D;
        if (d > 180.0D) {
            d -= 360.0D;
        } else if (d <= -180.0D) {
            d += 360.0D;
        }
        return d;
    }
}
