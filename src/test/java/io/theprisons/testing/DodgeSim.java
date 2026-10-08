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
    public GridTerrain terrain;
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
    public int contactTicks;
    public int dropTicks;
    public static final double HALF = 0.3D;
    /** Realistic mode: keys from the view yaw (8 directions), the view follows the heading, walking 4.3 / sprinting 5.6 blocks per second. */
    public boolean realistic = true;
    public float yaw;
    public int sprintTicks;
    public double maxPlanVsActual;

    public DodgeSim(DodgeConfig cfg, GridTerrain terrain, double x, double z, double vx, double vz) {
        this.cfg = cfg;
        this.planner = new BanditDodgePlanner(cfg);
        this.terrain = terrain;
        this.x = x;
        this.z = z;
        this.vx = vx;
        this.vz = vz;
        this.yaw = (float) io.theprisons.modules.qol.bandit.combat.Geo.yawOf(vx, vz);
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
        return new DodgeInputs(now, x, 64.0D, z, vx, vz, yaw, true, list, terrain, area);
    }

    public DodgeDecision tick() {
        DodgeDecision d = planner.plan(inputs());
        decisions.add(d);
        ticks++;
        now += 50L;
        double speed = 5.6D;
        double mx = d.dirX();
        double mz = d.dirZ();
        if (realistic) {
            var keys = io.theprisons.modules.qol.bandit.dodge.DodgeDrive.keys(d.dirX(), d.dirZ(), yaw, d.jump());
            double[] f = io.theprisons.modules.qol.bandit.combat.Geo.forward(yaw);
            double[] r = io.theprisons.modules.qol.bandit.combat.Geo.right(yaw);
            double kf = (keys.forward() ? 1 : 0) - (keys.back() ? 1 : 0);
            double kr = (keys.right() ? 1 : 0) - (keys.left() ? 1 : 0);
            double[] u = io.theprisons.modules.qol.bandit.combat.Geo.unit(f[0] * kf + r[0] * kr, f[1] * kf + r[1] * kr);
            mx = u[0];
            mz = u[1];
            speed = keys.sprint() ? 5.6D : 4.3D;
            if (keys.sprint()) {
                sprintTicks++;
            }
            maxPlanVsActual = Math.max(maxPlanVsActual, io.theprisons.modules.qol.bandit.combat.Geo.angleBetween(mx, mz, d.dirX(), d.dirZ()));
            yaw = io.theprisons.modules.qol.bandit.dodge.DodgeDrive.nextYaw(yaw, d.dirX(), d.dirZ());
        }
        double stepLen = speed * 0.05D;
        // Minecraft-like collision: the 0.6 wide body slides along solid cells, axis by axis; every blocked axis counts as a contact tick.
        double ox = x;
        double oz = z;
        boolean contact = false;
        if (!solid(x + mx * stepLen, z)) {
            x += mx * stepLen;
        } else if (Math.abs(mx) > 1e-6) {
            contact = true;
        }
        if (!solid(x, z + mz * stepLen)) {
            z += mz * stepLen;
        } else if (Math.abs(mz) > 1e-6) {
            contact = true;
        }
        if (contact) {
            contactTicks++;
        }
        if (terrain.at((int) Math.floor(x), (int) Math.floor(z)) == ' ') {
            dropTicks++;
        }
        vx = (x - ox) / 0.05D;
        vz = (z - oz) / 0.05D;
        for (double[] b : bandits.values()) {
            b[0] += b[2] * 0.05D;
            b[1] += b[3] * 0.05D;
        }
        for (double[] b : bandits.values()) {
            minDistanceSeen = Math.min(minDistanceSeen, Math.hypot(b[0] - x, b[1] - z));
        }
        return d;
    }

    /** True when the player body centred at (cx, cz) overlaps a wall cell. */
    public boolean solid(double cx, double cz) {
        for (int ix = (int) Math.floor(cx - HALF); ix <= (int) Math.floor(cx + HALF); ix++) {
            for (int iz = (int) Math.floor(cz - HALF); iz <= (int) Math.floor(cz + HALF); iz++) {
                if (terrain.at(ix, iz) == '#') {
                    return true;
                }
            }
        }
        return false;
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
