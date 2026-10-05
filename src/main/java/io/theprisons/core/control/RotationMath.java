package io.theprisons.core.control;

/**
 * Angle helpers shared by the rotation and movement controllers. Uses Minecraft's convention:
 * yaw 0 looks towards +z, yaw 90 towards -x; positive pitch looks down.
 */
public final class RotationMath {
    private RotationMath() {
    }

    public static float wrap(float degrees) {
        float value = degrees % 360.0F;
        if (value >= 180.0F) {
            value -= 360.0F;
        }
        if (value < -180.0F) {
            value += 360.0F;
        }
        return value;
    }

    /** Yaw that faces along the horizontal vector (dx, dz). */
    public static float yawOf(double dx, double dz) {
        return wrap((float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0D));
    }

    /** {yaw, pitch} looking from one point to another. */
    public static float[] anglesTo(double fromX, double fromY, double fromZ, double toX, double toY, double toZ) {
        double dx = toX - fromX;
        double dy = toY - fromY;
        double dz = toZ - fromZ;
        return new float[]{yawOf(dx, dz), pitchOf(dx, dy, dz)};
    }

    /** Angular distance between two view directions (shortest way for yaw). */
    public static float angleBetween(float yaw, float pitch, float otherYaw, float otherPitch) {
        float dy = wrap(otherYaw - yaw);
        float dp = otherPitch - pitch;
        return (float) Math.sqrt(dy * dy + dp * dp);
    }

    public static float pitchOf(double dx, double dy, double dz) {
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        return (float) Math.max(-90.0D, Math.min(90.0D, -Math.toDegrees(Math.atan2(dy, horizontal))));
    }

}
