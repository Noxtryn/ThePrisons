import java.awt.Toolkit;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;

public final class LocalFailsafe {

    private final Runnable shutdownAction;

    private final List<String> signalWords = List.of(
            "afk",
            "hier"
    );

    private final double maxPositionDelta;

    public LocalFailsafe(
            double maxPositionDelta,
            Runnable shutdownAction
    ) {
        this.maxPositionDelta = maxPositionDelta;
        this.shutdownAction = shutdownAction;
    }

    /**
     * Prüft einen lokalen Textstream.
     */
    public boolean inspectText(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }

        String normalized = text
                .replaceAll("§[0-9a-fk-or]", "")
                .toLowerCase(Locale.ROOT);

        for (String signal : signalWords) {
            if (normalized.contains(signal)) {
                trigger();
                return true;
            }
        }

        return false;
    }

    /**
     * Prüft eine Positionsdiskontinuität.
     */
    public boolean inspectPosition(
            double previousX,
            double previousY,
            double previousZ,
            double currentX,
            double currentY,
            double currentZ
    ) {
        double dx = currentX - previousX;
        double dy = currentY - previousY;
        double dz = currentZ - previousZ;

        double distanceSquared =
                dx * dx +
                dy * dy +
                dz * dz;

        if (distanceSquared >= maxPositionDelta * maxPositionDelta) {
            trigger();
            return true;
        }

        return false;
    }

    private void trigger() {
        Toolkit.getDefaultToolkit().beep();

        /*
         * Die eigentliche Beendigung bleibt in der Client-Architektur.
         * Hier können z.B. Navigation-Worker, Scheduler und Sessions
         * kontrolliert gestoppt werden.
         */
        if (shutdownAction != null) {
            shutdownAction.run();
        }
    }
}
