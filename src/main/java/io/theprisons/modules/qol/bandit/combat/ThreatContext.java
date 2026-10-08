package io.theprisons.modules.qol.bandit.combat;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * The local danger picture: the primary target, every other bandit near the player and the nearest of them. A second bandit changes how
 * the player positions without becoming the target.
 *
 * @param primary         the locked target (may be null)
 * @param nearby          other bandits within the threat radius of the player (not the primary), nearest first
 * @param nearestDistance distance to the nearest threat incl. the primary when it is within the radius; {@code Double.NaN} = none
 * @param count           bandits within the threat radius of the player incl. the primary
 */
public record ThreatContext(@Nullable Foe primary, List<Foe> nearby, double nearestDistance, int count) {
    public ThreatContext {
        nearby = List.copyOf(nearby);
    }

    public static ThreatContext of(List<Foe> bandits, @Nullable Foe primary, double px, double pz, double radius) {
        List<Foe> others = new ArrayList<>();
        double nearest = Double.NaN;
        int count = 0;
        for (Foe b : bandits) {
            double d = b.distanceTo(px, pz);
            if (d > radius) {
                continue;
            }
            count++;
            if (Double.isNaN(nearest) || d < nearest) {
                nearest = d;
            }
            if (primary == null || !b.id().equals(primary.id())) {
                others.add(b);
            }
        }
        others.sort((a, b) -> Double.compare(a.distanceTo(px, pz), b.distanceTo(px, pz)));
        return new ThreatContext(primary, others, nearest, count);
    }

    public boolean any() {
        return count > 0;
    }
}
