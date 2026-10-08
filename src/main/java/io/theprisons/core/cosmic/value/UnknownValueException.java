package io.theprisons.core.cosmic.value;

/** Thrown by {@link GameValue#require()} when a feature insists on a value the game model does not know. */
public final class UnknownValueException extends RuntimeException {
    public UnknownValueException(String message) {
        super(message);
    }
}
