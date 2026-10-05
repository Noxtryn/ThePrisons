public final class RotationUtils {

    private RotationUtils() {}

    /**
     * Interpoliert einen Winkel über den kürzesten Weg.
     *
     * @param current aktueller Winkel
     * @param target Zielwinkel
     * @param factor Interpolationsfaktor [0, 1]
     */
    public static double lerpAngle(
            double current,
            double target,
            double factor
    ) {
        factor = Math.clamp(factor, 0.0, 1.0);

        double delta = wrapDegrees(target - current);

        return current + delta * factor;
    }

    /**
     * Interpoliert Yaw und Pitch gemeinsam.
     */
    public static Rotation lerpRotation(
            Rotation current,
            Rotation target,
            double factor
    ) {
        return new Rotation(
                lerpAngle(current.yaw(), target.yaw(), factor),
                lerpAngle(current.pitch(), target.pitch(), factor)
        );
    }

    private static double wrapDegrees(double angle) {
        angle %= 360.0;

        if (angle >= 180.0) {
            angle -= 360.0;
        }

        if (angle < -180.0) {
            angle += 360.0;
        }

        return angle;
    }

    public record Rotation(double yaw, double pitch) {}
}
