package io.theprisons.modules.qol.bandit.dodge;

import java.util.Locale;

/**
 * Turns the facts about one candidate route into a score. Every term is kept so a decision can be explained ("why not left?") in the log and in the
 * tests. Positive terms: free body path, distance from every bandit, gap width, keeping the heading. Negative: the crowd, wall pressure, dead end,
 * the turn, a reversal, the error between desired and executed direction, a jump, a recently failed heading, scraping a wall.
 */
public final class CandidateScorer {
    private CandidateScorer() {
    }

    /** The inputs of one candidate. */
    public record Facts(double reach, boolean endsInObstacle, double free, double pressure, double nearest, double threatAverage, double threatPeak,
                        double gap, double dot, double turnDegrees, double executionErrorDegrees, boolean needsJump, boolean jumpTooSoon, boolean failedRecently,
                        boolean committed, double areaPull, double sideLeft, double sideRight, boolean breach) {
    }

    /** The terms of a score (all signed: what they added). */
    public record Terms(double free, double separation, double crowd, double peak, double gap, double forward, double turn, double deadEnd, double reversal,
                        double execution, double wall, double jump, double failed, double area, double scrape) {
        public double total() {
            return free + separation + crowd + peak + gap + forward + turn + deadEnd + reversal + execution + wall + jump + failed + area + scrape;
        }

        @Override
        public String toString() {
            return String.format(Locale.ROOT, "free %.1f sep %.1f crowd %.1f peak %.1f gap %.1f fwd %.1f turn %.1f dead %.1f rev %.1f exec %.1f wall %.1f "
                            + "jump %.1f failed %.1f area %.1f scrape %.1f = %.1f", free, separation, crowd, peak, gap, forward, turn, deadEnd, reversal, execution,
                    wall, jump, failed, area, scrape, total());
        }
    }

    public static Terms score(DodgeConfig cfg, Facts f) {
        // More distance than half the warning band past the minimum is not worth leaving the run for.
        double sepCap = cfg.minDistance + cfg.warningBand * 0.5D;
        double wSep = cfg.wSeparation * (f.breach() ? 2.0D : 1.0D);
        double wFwd = cfg.wForward * (f.breach() ? 0.3D : 1.0D);
        double wTurn = cfg.wTurn * (f.breach() ? 0.3D : 1.0D);
        double scrape = 0.0D;
        double room = Math.min(f.sideLeft(), f.sideRight());
        if (room < cfg.sideComfort) {
            scrape = -cfg.wScrape * (cfg.sideComfort - room) / cfg.sideComfort;
        }
        return new Terms(
                cfg.wFree * f.reach(),
                wSep * Math.min(f.nearest(), sepCap),
                -cfg.wThreat * f.threatAverage(),
                -cfg.wPeak * f.threatPeak(),
                cfg.wGap * Math.min(f.gap(), ThreatField.GAP_CAP),
                wFwd * f.dot(),
                -wTurn * f.turnDegrees() / 180.0D,
                f.endsInObstacle() ? -cfg.wDeadEnd * (cfg.lookahead - f.free()) : 0.0D,
                f.dot() < -0.5D ? -cfg.wReversal * (f.committed() ? 2.0D : 1.0D) : 0.0D,
                -cfg.wExecError * f.executionErrorDegrees(),
                -f.pressure(),
                f.needsJump() ? -cfg.wJump * (f.jumpTooSoon() ? 4.0D : 1.0D) : 0.0D,
                f.failedRecently() ? -cfg.wFailed : 0.0D,
                cfg.wArea * f.areaPull(),
                scrape);
    }
}
