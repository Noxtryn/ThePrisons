package io.theprisons.testing;

import io.theprisons.modules.qol.bandit.dodge.BanditDodgePlanner;
import io.theprisons.modules.qol.bandit.dodge.DodgeBandit;
import io.theprisons.modules.qol.bandit.dodge.DodgeConfig;
import io.theprisons.modules.qol.bandit.dodge.DodgeDecision;
import io.theprisons.modules.qol.bandit.dodge.DodgeInputs;
import io.theprisons.modules.qol.bandit.dodge.SpearAreaState;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs the dodge planner over time in a flat arena: the player moves 5.6 blocks per second along the decision (stopping at walls), bandits
 * stand still or walk with a given velocity. 20 ticks per second. Not Minecraft physics - it only shows what the decisions add up to.
 */
public final class DodgeSim {
    public final DodgeConfig cfg;
    public final BanditDodgePlanner planner;
    public final GridTerrain terrain;
    public double x;
    public double z;
    public double vx;
    public double vz;
    public long now = 1_000_000L;
    public SpearAreaState area = SpearAreaState.UNKNOWN;
    public final Map<String, double[]> bandits = new LinkedHashMap<>(); // x, z, vx, vz
    public final List<DodgeDecision> decisions = new ArrayList<>();
    public double minDistanceSeen = Double.MAX_VALUE;
    public int ticks;

    public DodgeSim(DodgeConfig cfg, GridTerrain terrain, double x, double z, double vx, double vz) {
        this.cfg = cfg;
        this.planner = new BanditDodgePlanner(cfg);
        this.terrain = terrain;
        this.x = x;
        this.z = z;
        this.vx = vx;
        this.vz = vz;
    }

    public DodgeSim bandit(String id, double bx, double bz, double bvx, double bvz) {
        bandits.put(id, new double[]{bx, bz, bvx, bvz});
        return this;
    }

    public DodgeInputs inputs() {
        List<DodgeBandit> list = new ArrayList<>();
        for (Map.Entry<String, double[]> e : bandits.entrySet()) {
            double[] b = e.getValue();
            list.add(new DodgeBandit(e.getKey(), b[0], b[1], b[2], b[3]));
        }
        return new DodgeInputs(now, x, 64.0D, z, vx, vz, 0.0D, true, list, terrain, area);
    }

    public DodgeDecision tick() {
        DodgeDecision d = planner.plan(inputs());
        decisions.add(d);
        ticks++;
        now += 50L;
        double speed = 5.6D;
        double stepLen = speed * 0.05D;
        var ray = terrain.cast(x, 64.0D, z, d.dirX(), d.dirZ(), stepLen + 0.3D);
        double walk = ray.free() >= stepLen + 0.3D - 1e-9 ? stepLen : Math.max(0.0D, ray.free() - 0.3D);
        double nx = d.dirX() * walk;
        double nz = d.dirZ() * walk;
        vx = nx / 0.05D;
        vz = nz / 0.05D;
        x += nx;
        z += nz;
        for (double[] b : bandits.values()) {
            b[0] += b[2] * 0.05D;
            b[1] += b[3] * 0.05D;
        }
        for (double[] b : bandits.values()) {
            minDistanceSeen = Math.min(minDistanceSeen, Math.hypot(b[0] - x, b[1] - z));
        }
        return d;
    }

    public void run(int n) {
        for (int i = 0; i < n; i++) {
            tick();
        }
    }

    public DodgeDecision last() {
        return decisions.get(decisions.size() - 1);
    }
}
