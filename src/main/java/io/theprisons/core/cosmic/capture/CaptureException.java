package io.theprisons.core.cosmic.capture;

/** A capture file that cannot be used: unreadable, not a capture, or from another schema ("stale"). */
public final class CaptureException extends Exception {
    private final boolean stale;

    public CaptureException(String message, boolean stale) {
        super(message);
        this.stale = stale;
    }

    public CaptureException(String message, Throwable cause) {
        super(message, cause);
        this.stale = false;
    }

    /** True when the file is a capture of an older schema: re-record it or migrate it. */
    public boolean stale() {
        return stale;
    }
}
