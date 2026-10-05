import java.util.concurrent.ThreadLocalRandom;

public final class TimingUtils {

    private TimingUtils() {}

    /**
     * Erzeugt eine normalverteilte Verzögerung im Bereich [10, 60] ms.
     *
     * @param centerMs gleitender Mittelpunkt
     * @param stdDevMs Standardabweichung der Normalverteilung
     */
    public static long gaussianDelay(double centerMs, double stdDevMs) {
        ThreadLocalRandom random = ThreadLocalRandom.current();

        // Box-Muller
        double u1 = Math.max(random.nextDouble(), Double.MIN_VALUE);
        double u2 = random.nextDouble();

        double gaussian =
                Math.sqrt(-2.0 * Math.log(u1))
                * Math.cos(2.0 * Math.PI * u2);

        double delay = centerMs + gaussian * stdDevMs;

        return Math.round(Math.clamp(delay, 10.0, 60.0));
    }

    public static void sleepGaussian(double centerMs, double stdDevMs)
            throws InterruptedException {
        Thread.sleep(gaussianDelay(centerMs, stdDevMs));
    }
}
