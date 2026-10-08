package io.theprisons.testing;

import io.theprisons.modules.qol.bandit.combat.AttackPhase;
import io.theprisons.modules.qol.bandit.combat.CombatBrain;
import io.theprisons.modules.qol.bandit.combat.CombatConfig;
import io.theprisons.modules.qol.bandit.combat.CombatInputs;
import io.theprisons.modules.qol.bandit.combat.CombatState;
import io.theprisons.modules.qol.bandit.combat.Decision;
import io.theprisons.modules.qol.bandit.combat.Foe;
import io.theprisons.modules.qol.bandit.combat.SpearStatus;
import io.theprisons.core.control.RotationMath;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A tiny world for the combat controller: 20 ticks per second, the player walks 4.3 blocks per second (5.6 sprinting) along what the
 * decision asks and stops at walls, drops and hazards; the view turns at once; bandits are where the scenario puts them (optionally walking
 * to the player); the spear attack is a short script (aim, commit, flight) while the brain allows it. It is a way to run decisions over time -
 * not a model of Minecraft physics.
 */
public final class CombatSim {
    public final CombatBrain brain;
    public final CombatConfig cfg;
    public final GridTerrain terrain;
    public final List<String> infoLog = new ArrayList<>();
    public final List<String> traceLog = new ArrayList<>();
    public final Set<CombatState> seen = EnumSet.noneOf(CombatState.class);
    public final List<CombatState> history = new ArrayList<>();

    public double x;
    public double y = 64.0D;
    public double z;
    public float yaw;
    public double health = 20.0D;
    public boolean manual;
    public SpearStatus spear = SpearStatus.UNKNOWN;
    public boolean pathWorks = true;
    public int pathFailures;
    public long now = 100_000L;
    public final Map<String, double[]> bandits = new LinkedHashMap<>();
    public final Map<String, double[]> players = new LinkedHashMap<>();
    /** Bandits walk towards the player at this speed (blocks per second) when set. */
    public double banditSpeed;
    public Decision last;
    public boolean attackAllowedSeen;
    public String stopReason;
    private AttackPhase attack = AttackPhase.NONE;
    private int attackTick;
    public int ticks;

    public CombatSim(CombatConfig cfg, GridTerrain terrain, double x, double z) {
        this.cfg = cfg;
        this.terrain = terrain;
        this.x = x;
        this.z = z;
        this.brain = new CombatBrain(cfg, infoLog::add, traceLog::add);
    }

    public CombatSim bandit(String id, double bx, double bz) {
        bandits.put(id, new double[]{bx, bz, 20.0D, 20.0D});
        return this;
    }

    public CombatSim player(String id, double px, double pz) {
        players.put(id, new double[]{px, pz, 20.0D, 20.0D});
        return this;
    }

    public void start() {
        brain.start(now);
        record();
    }

    private void record() {
        CombatState s = brain.state();
        seen.add(s);
        if (history.isEmpty() || history.get(history.size() - 1) != s) {
            history.add(s);
        }
    }

    private List<Foe> foes(Map<String, double[]> map, String kind) {
        List<Foe> out = new ArrayList<>();
        for (Map.Entry<String, double[]> e : map.entrySet()) {
            double[] v = e.getValue();
            Foe.Sight sight = terrain.sees(x, z, v[0], v[1]) ? Foe.Sight.YES : Foe.Sight.NO;
            out.add(new Foe(e.getKey(), kind, v[0], y, v[1], 0, 0, v[2], v[3], sight));
        }
        return out;
    }

    /** One tick of 50 ms. */
    public Decision tick() {
        now += 50L;
        ticks++;
        CombatInputs in = new CombatInputs(now, new CombatInputs.Me(x, y, z, yaw, health, true, manual), foes(bandits, "ORE_BANDIT"),
                foes(players, "PLAYER"), terrain, spear, attack, pathWorks ? CombatInputs.PathStatus.FOLLOWING : CombatInputs.PathStatus.FAILED,
                pathFailures);
        Decision d = brain.tick(in);
        last = d;
        record();
        if (d.stopReason() != null) {
            stopReason = d.stopReason();
        }
        applyAttack(d);
        applyMove(d);
        if (banditSpeed > 0.0D) {
            for (double[] b : bandits.values()) {
                double dx = x - b[0];
                double dz = z - b[1];
                double len = Math.hypot(dx, dz);
                if (len > 2.0D) {
                    b[0] += dx / len * banditSpeed * 0.05D;
                    b[1] += dz / len * banditSpeed * 0.05D;
                }
            }
        }
        return d;
    }

    public void run(int n) {
        for (int i = 0; i < n; i++) {
            tick();
        }
    }

    /** Ticks until the predicate holds or {@code max} ticks passed; returns whether it held. */
    public boolean runUntil(java.util.function.BooleanSupplier done, int max) {
        for (int i = 0; i < max; i++) {
            if (done.getAsBoolean()) {
                return true;
            }
            tick();
        }
        return done.getAsBoolean();
    }

    private void applyAttack(Decision d) {
        if (d.attackAllowed()) {
            attackAllowedSeen = true;
        }
        switch (attack) {
            case NONE -> {
                if (d.attackAllowed()) {
                    attack = AttackPhase.AIMING;
                    attackTick = 0;
                }
            }
            case AIMING -> {
                if (++attackTick >= 8) {
                    attack = AttackPhase.COMMIT;
                    attackTick = 0;
                }
            }
            case COMMIT -> {
                if (++attackTick >= 4) {
                    attack = AttackPhase.IN_FLIGHT;
                    attackTick = 0;
                }
            }
            case IN_FLIGHT -> {
                if (++attackTick >= 60) {
                    attack = AttackPhase.NONE;
                    attackTick = 0;
                }
            }
        }
    }

    private void applyMove(Decision d) {
        Foe t = null;
        if (d.targetId() != null && bandits.containsKey(d.targetId())) {
            double[] b = bandits.get(d.targetId());
            t = new Foe(d.targetId(), "ORE_BANDIT", b[0], y, b[1], 0, 0, b[2], b[3], Foe.Sight.UNKNOWN);
        }
        double dx = 0.0D;
        double dz = 0.0D;
        double speed = 0.0D;
        switch (d.move().kind()) {
            case DIRECTION -> {
                dx = d.move().dx();
                dz = d.move().dz();
                speed = d.move().sprint() ? 5.6D : 4.3D;
            }
            case PATH_TO -> {
                if (pathWorks) {
                    dx = d.move().goalX() - x;
                    dz = d.move().goalZ() - z;
                    speed = 4.3D;
                }
            }
            default -> {
            }
        }
        float lookYaw = yaw;
        switch (d.look()) {
            case TARGET -> {
                if (t != null) {
                    lookYaw = RotationMath.yawOf(t.x() - x, t.z() - z);
                }
            }
            case MOVEMENT -> {
                if (Math.hypot(dx, dz) > 1e-6) {
                    lookYaw = RotationMath.yawOf(dx, dz);
                }
            }
            default -> {
            }
        }
        yaw = lookYaw;
        double len = Math.hypot(dx, dz);
        if (len > 1e-6 && speed > 0.0D) {
            double stepLen = speed * 0.05D;
            var ray = terrain.cast(x, y, z, dx, dz, stepLen + 0.3D);
            double walk = Math.min(stepLen, Math.max(0.0D, ray.free() - 0.3D));
            if (ray.free() >= stepLen + 0.3D - 1e-9) {
                walk = stepLen;
            }
            x += dx / len * walk;
            z += dz / len * walk;
        }
    }

    public double distanceToBandit(String id) {
        double[] b = bandits.get(id);
        return Math.hypot(b[0] - x, b[1] - z);
    }
}
