package io.theprisons.modules.qol.bandit.nav;

import java.util.List;

/**
 * Every bandit as a danger field along a route. The route is sampled every few blocks; at each sample the bandits are where their (smoothed,
 * sanity-checked) velocity puts them by the time the player gets there, and the danger of all of them is summed: a crowd is worse than the sum of
 * its parts only in the sense that it adds up, so a lane through four bandits loses to a lane past one.
 *
 * @param nearest the closest approach of any bandit to the route (+infinity-like when there are no bandits)
 * @param average the mean summed danger over the samples
 * @param peak    the worst summed danger at one sample
 * @param gap     the narrowest side clearance to a bandit ahead (capped)
 */
public record ThreatField(double nearest, double average, double peak, double gap) {
    public static final double NO_BANDIT_DISTANCE = 60.0D;
    public static final double GAP_CAP = 12.0D;

    public static ThreatField along(List<NavBandit> bandits, ExecutedPath path, double reach, NavConfig cfg, double playerX, double playerZ) {
        if (bandits.isEmpty()) {
            return new ThreatField(NO_BANDIT_DISTANCE, 0.0D, 0.0D, GAP_CAP);
        }
        double nearest = Double.POSITIVE_INFINITY;
        double sum = 0.0D;
        double peak = 0.0D;
        int n = 0;
        double end = Math.min(reach, path.length());
        for (double d = cfg.sampleStep; ; d += cfg.sampleStep) {
            double at = Math.min(d, Math.max(end, 0.5D));
            double[] p = path.at(at);
            double t = Math.min(p[2], cfg.predictionSeconds);   // p[2] is the time in seconds the player needs to get there
            double danger = 0.0D;
            for (NavBandit b : bandits) {
                double k = Math.hypot(b.vx(), b.vz()) <= cfg.maxBanditSpeed ? 1.0D : 0.0D;
                double dist = Math.hypot(p[0] - (b.x() + k * b.vx() * t), p[1] - (b.z() + k * b.vz() * t));
                nearest = Math.min(nearest, dist);
                danger += LocalNavigator.danger(dist, cfg.minDistance, cfg.warningBand);
            }
            sum += danger;
            peak = Math.max(peak, danger);
            n++;
            if (at >= end) {
                break;
            }
        }
        double gap = GAP_CAP;
        ExecutedPath.Leg f = path.first();
        for (NavBandit b : bandits) {
            double rx = b.x() - playerX;
            double rz = b.z() - playerZ;
            double along = rx * f.dirX() + rz * f.dirZ();
            if (along > 0.0D && along < cfg.lookahead + cfg.minDistance + cfg.warningBand) {
                gap = Math.min(gap, Math.abs(rx * f.dirZ() - rz * f.dirX()));
            }
        }
        return new ThreatField(nearest, n == 0 ? 0.0D : sum / n, peak, gap);
    }
}
