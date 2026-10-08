package io.theprisons.modules.qol.bandit.combat;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Notices a fight that goes nowhere: orbiting without getting around the target, the orbit side flipping over and over, two states
 * handing the macro back and forth, the target being picked again and again, the same path failing, turning a lot without moving.
 * Pure: the brain feeds it events and one sample per tick. After a verdict the brain calls {@link #reset()}.
 */
public final class LoopDetector {
    public enum Kind {
        NONE, ORBIT_NO_PROGRESS, DIRECTION_FLIPPING, STATE_PINGPONG, TARGET_REACQUIRE, PATH_FAILING, SPIN
    }

    public static final long FLIP_WINDOW_MS = 6_000L;
    public static final int FLIPS = 4;
    public static final long PINGPONG_WINDOW_MS = 8_000L;
    public static final int PINGPONG_MOVES = 5;
    public static final long REACQUIRE_WINDOW_MS = 10_000L;
    public static final int REACQUIRES = 3;
    public static final long PATH_WINDOW_MS = 15_000L;
    public static final int PATH_FAILURES = 3;
    public static final long ORBIT_PROGRESS_MS = 4_000L;
    public static final double ORBIT_MIN_DEGREES = 25.0D;
    public static final long SPIN_WINDOW_MS = 2_500L;
    public static final double SPIN_DEGREES = 540.0D;
    public static final double SPIN_MAX_MOVE = 1.5D;

    private record Move(long at, CombatState from, CombatState to) {
    }

    private record Pose(long at, double x, double z, float yaw) {
    }

    private final Deque<Long> flips = new ArrayDeque<>();
    private final Deque<Move> moves = new ArrayDeque<>();
    private final Deque<Long> reacquires = new ArrayDeque<>();
    private final Deque<Long> pathFailures = new ArrayDeque<>();
    private final Deque<Pose> poses = new ArrayDeque<>();
    private long orbitSince = -1L;
    private double orbitProgressAtStart;
    private double orbitX0;
    private double orbitZ0;
    private Kind lastKind = Kind.NONE;

    public void reset() {
        flips.clear();
        moves.clear();
        reacquires.clear();
        pathFailures.clear();
        poses.clear();
        orbitSince = -1L;
    }

    public Kind lastKind() {
        return lastKind;
    }

    public void onOrbitFlip(long now) {
        flips.addLast(now);
    }

    public void onTargetChosen(long now) {
        reacquires.addLast(now);
    }

    public void onPathFailure(long now) {
        pathFailures.addLast(now);
    }

    public void onState(long now, CombatState from, CombatState to) {
        moves.addLast(new Move(now, from, to));
        if (to != CombatState.ORBIT) {
            orbitSince = -1L;
        }
    }

    private static void trim(Deque<Long> times, long now, long window) {
        while (!times.isEmpty() && now - times.peekFirst() > window) {
            times.pollFirst();
        }
    }

    /** One sample per tick; returns the loop found (NONE most of the time). */
    public Kind sample(long now, CombatState state, double x, double z, float yaw, double orbitProgressDegrees) {
        trim(flips, now, FLIP_WINDOW_MS);
        trim(reacquires, now, REACQUIRE_WINDOW_MS);
        trim(pathFailures, now, PATH_WINDOW_MS);
        while (!moves.isEmpty() && now - moves.peekFirst().at() > PINGPONG_WINDOW_MS) {
            moves.pollFirst();
        }
        poses.addLast(new Pose(now, x, z, yaw));
        while (!poses.isEmpty() && now - poses.peekFirst().at() > SPIN_WINDOW_MS) {
            poses.pollFirst();
        }
        lastKind = Kind.NONE;
        if (flips.size() >= FLIPS) {
            return lastKind = Kind.DIRECTION_FLIPPING;
        }
        if (reacquires.size() >= REACQUIRES) {
            return lastKind = Kind.TARGET_REACQUIRE;
        }
        if (pathFailures.size() >= PATH_FAILURES) {
            return lastKind = Kind.PATH_FAILING;
        }
        if (pingPong()) {
            return lastKind = Kind.STATE_PINGPONG;
        }
        if (state == CombatState.ORBIT) {
            if (orbitSince < 0L) {
                orbitSince = now;
                orbitProgressAtStart = orbitProgressDegrees;
                orbitX0 = x;
                orbitZ0 = z;
            } else if (now - orbitSince >= ORBIT_PROGRESS_MS) {
                double progressed = Math.abs(orbitProgressDegrees - orbitProgressAtStart);
                double moved = Math.hypot(x - orbitX0, z - orbitZ0);
                if (progressed < ORBIT_MIN_DEGREES && moved < 4.0D) {
                    return lastKind = Kind.ORBIT_NO_PROGRESS;
                }
                // a healthy orbit restarts its window
                orbitSince = now;
                orbitProgressAtStart = orbitProgressDegrees;
                orbitX0 = x;
                orbitZ0 = z;
            }
        }
        if (state != CombatState.ENGAGE && state != CombatState.COOLDOWN && state != CombatState.SCAN && poses.size() > 10
                && now - poses.peekFirst().at() >= SPIN_WINDOW_MS - 300L) {
            double turned = 0.0D;
            double far = 0.0D;
            Pose first = poses.peekFirst();
            Pose prev = first;
            for (Pose p : poses) {
                turned += Math.abs(wrap(p.yaw() - prev.yaw()));
                far = Math.max(far, Math.hypot(p.x() - first.x(), p.z() - first.z()));
                prev = p;
            }
            if (turned >= SPIN_DEGREES && far <= SPIN_MAX_MOVE) {
                return lastKind = Kind.SPIN;
            }
        }
        return lastKind;
    }

    /** The last {@code PINGPONG_MOVES} state moves alternate between exactly two states. */
    private boolean pingPong() {
        if (moves.size() < PINGPONG_MOVES) {
            return false;
        }
        Move[] all = moves.toArray(new Move[0]);
        CombatState a = null;
        CombatState b = null;
        for (int i = all.length - PINGPONG_MOVES; i < all.length; i++) {
            Move m = all[i];
            if (a == null) {
                a = m.from();
                b = m.to();
            }
            boolean forward = m.from() == a && m.to() == b;
            boolean back = m.from() == b && m.to() == a;
            if (!forward && !back) {
                return false;
            }
        }
        // Only the positioning pairs are loops: waiting (SCAN / TARGET_SELECT) and the attack commit (ORBIT <-> ENGAGE once per throw) are not.
        return a != CombatState.SCAN && b != CombatState.SCAN && a != CombatState.TARGET_SELECT && b != CombatState.TARGET_SELECT
                && a != CombatState.ENGAGE && b != CombatState.ENGAGE;
    }

    private static double wrap(double d) {
        double v = d % 360.0D;
        if (v > 180.0D) {
            v -= 360.0D;
        } else if (v <= -180.0D) {
            v += 360.0D;
        }
        return v;
    }
}
