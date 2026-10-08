package io.theprisons.modules.qol.bandit.dodge;

import io.theprisons.modules.qol.bandit.combat.Terrain;

/**
 * Sweeps the player BODY along an {@link ExecutedPath}: on every leg the centre line and both body edges (half width from the player) are cast, so a
 * corner that the centre line passes but the 0.6 wide body clips is a wall, and a doorway the body does not fit through is closed.
 *
 * @param free        blocks of the route that the whole body can walk
 * @param center      the same for the centre line only, {@code left} / {@code right} for the body edges (each until its own first obstacle)
 * @param stop        what ends the route (CLEAR = nothing within the route)
 * @param jumpAt      blocks until a step up that EVERY body edge climbs (-1 = none)
 * @param sideLeft    free room beside the body at the start (left / right of the first leg), capped
 * @param sideRight
 */
public record BodyClearance(double free, double center, double left, double right, Terrain.Stop stop, double jumpAt, double sideLeft, double sideRight) {
    private static final double SIDE_PROBE = 1.0D;

    public static BodyClearance sweep(Terrain terrain, double y, ExecutedPath path, double halfWidth, double inset) {
        double hw = Math.max(0.0D, halfWidth - inset);
        double walked = 0.0D;
        double jumpAt = -1.0D;
        Terrain.Stop stop = Terrain.Stop.CLEAR;
        // how far each of the three lines of the body gets along the whole route (each until its own first obstacle)
        double cumC = 0.0D;
        double cumL = 0.0D;
        double cumR = 0.0D;
        boolean doneC = false;
        boolean doneL = false;
        boolean doneR = false;
        boolean ended = false;
        for (ExecutedPath.Leg leg : path.legs()) {
            double rx = -leg.dirZ();
            double rz = leg.dirX();
            Terrain.Ray c = terrain.cast(leg.x(), y, leg.z(), leg.dirX(), leg.dirZ(), leg.length());
            Terrain.Ray l = terrain.cast(leg.x() - rx * hw, y, leg.z() - rz * hw, leg.dirX(), leg.dirZ(), leg.length());
            Terrain.Ray r = terrain.cast(leg.x() + rx * hw, y, leg.z() + rz * hw, leg.dirX(), leg.dirZ(), leg.length());
            if (!doneC) {
                cumC += c.free();
                doneC = c.free() < leg.length() - 1e-9 && c.stop() != Terrain.Stop.CLEAR;
            }
            if (!doneL) {
                cumL += l.free();
                doneL = l.free() < leg.length() - 1e-9 && l.stop() != Terrain.Stop.CLEAR;
            }
            if (!doneR) {
                cumR += r.free();
                doneR = r.free() < leg.length() - 1e-9 && r.stop() != Terrain.Stop.CLEAR;
            }
            if (jumpAt < 0.0D && c.jumpAt() >= 0.0D
                    && !(l.jumpAt() < 0.0D && l.free() <= c.jumpAt() + 0.3D) && !(r.jumpAt() < 0.0D && r.free() <= c.jumpAt() + 0.3D)) {
                jumpAt = walked + c.jumpAt();
            }
            Terrain.Ray worst = c;
            if (l.free() < worst.free()) {
                worst = l;
            }
            if (r.free() < worst.free()) {
                worst = r;
            }
            if (worst.free() < leg.length() - 1e-9 && worst.stop() != Terrain.Stop.CLEAR) {
                walked += worst.free();
                stop = worst.stop();
                ended = true;
                break;
            }
            walked += leg.length();
        }
        if (!ended) {
            stop = Terrain.Stop.CLEAR;
        }
        // room beside the body at the start (a player scraping along a wall has none)
        ExecutedPath.Leg f = path.first();
        double rx = -f.dirZ();
        double rz = f.dirX();
        double sl = terrain.cast(f.x() - rx * (hw + 0.02D), y, f.z() - rz * (hw + 0.02D), -rx, -rz, SIDE_PROBE).free();
        double sr = terrain.cast(f.x() + rx * (hw + 0.02D), y, f.z() + rz * (hw + 0.02D), rx, rz, SIDE_PROBE).free();
        return new BodyClearance(walked, cumC, cumL, cumR, stop, jumpAt, sl, sr);
    }
}
