package io.theprisons.core.cosmic.value;

import org.jspecify.annotations.Nullable;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.function.Function;

/**
 * One piece of Cosmic Prisons knowledge together with how sure we are about it.
 *
 * @param value      the value; {@code null} exactly when the confidence is {@link Confidence#UNKNOWN}
 * @param confidence how far it can be trusted
 * @param source     where it comes from ("sidebar", "pickaxe lore", "owner", "registry:zones.json" ...)
 * @param observedAtMs when it was read / recorded (epoch ms; 0 = not tied to a moment, e.g. a registry default)
 * @param context    the game context it is valid for: Minecraft version, map / season ("mc1.21.11" or "mc1.21.11 season:3");
 *                   "" = not bound to one
 * @param note       free text for humans (why this confidence), may be null
 */
public record GameValue<T>(@Nullable T value, Confidence confidence, String source, long observedAtMs, String context,
                           @Nullable String note) {
    public GameValue {
        Objects.requireNonNull(confidence, "confidence");
        source = source == null ? "" : source;
        context = context == null ? "" : context;
        if (value == null || confidence == Confidence.UNKNOWN) {
            // UNKNOWN carries no value, and a value without a confidence is not a value: both normalise to UNKNOWN.
            value = null;
            confidence = Confidence.UNKNOWN;
        }
    }

    public static <T> GameValue<T> unknown(String source) {
        return new GameValue<>(null, Confidence.UNKNOWN, source, 0L, "", null);
    }

    public static <T> GameValue<T> unknown(String source, @Nullable String note) {
        return new GameValue<>(null, Confidence.UNKNOWN, source, 0L, "", note);
    }

    public static <T> GameValue<T> of(@Nullable T value, Confidence confidence, String source, long observedAtMs, String context,
                                      @Nullable String note) {
        return new GameValue<>(value, confidence, source, observedAtMs, context, note);
    }

    /** A value read live from the game. */
    public static <T> GameValue<T> live(@Nullable T value, String source, long observedAtMs) {
        return new GameValue<>(value, Confidence.VERIFIED_LIVE, source, observedAtMs, "", null);
    }

    public static <T> GameValue<T> observed(@Nullable T value, String source, long observedAtMs, @Nullable String note) {
        return new GameValue<>(value, Confidence.OBSERVED, source, observedAtMs, "", note);
    }

    public boolean isKnown() {
        return confidence.known();
    }

    /** The value when it is known, empty otherwise - never a made-up default. */
    public Optional<T> known() {
        return Optional.ofNullable(value);
    }

    /** The value when it is known and at least {@code minimum} trustworthy. */
    public Optional<T> atLeast(Confidence minimum) {
        return confidence.atLeast(minimum) && confidence.known() ? Optional.ofNullable(value) : Optional.empty();
    }

    /** The value, or {@code fallback} when unknown. The fallback is the caller's explicit decision, not a model value. */
    public T orElse(T fallback) {
        return value == null ? fallback : value;
    }

    /** The value; throws {@link UnknownValueException} when unknown. */
    public T require() {
        if (value == null) {
            throw new UnknownValueException("value from '" + source + "' is unknown");
        }
        return value;
    }

    public <R> GameValue<R> map(Function<? super T, ? extends R> mapper) {
        if (value == null) {
            return new GameValue<>(null, Confidence.UNKNOWN, source, observedAtMs, context, note);
        }
        return new GameValue<>(mapper.apply(value), confidence, source, observedAtMs, context, note);
    }

    /** The number as a double when the value is a number and at least {@code minimum} trustworthy. */
    public OptionalDouble asDouble(Confidence minimum) {
        if (value instanceof Number number && confidence.atLeast(minimum) && confidence.known()) {
            return OptionalDouble.of(number.doubleValue());
        }
        return OptionalDouble.empty();
    }

    /** True when the value was read more than {@code maxAgeMs} before {@code nowMs} (values without a time are never stale). */
    public boolean isStale(long nowMs, long maxAgeMs) {
        return observedAtMs > 0L && nowMs - observedAtMs > maxAgeMs;
    }

    /** The same value bound to another context (map / season). */
    public GameValue<T> inContext(String newContext) {
        return new GameValue<>(value, confidence, source, observedAtMs, newContext, note);
    }

    /** Short text for logs and captures. */
    public String describe() {
        return confidence == Confidence.UNKNOWN ? "UNKNOWN" : value + " [" + confidence + ", " + source + "]";
    }
}
