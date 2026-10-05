package io.theprisons.core.control;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RotationTest {
    private static final HumanRotation.Profile PROFILE = HumanRotation.Profile.DEFAULT;
    /** 144 fps. */
    private static final double FRAME = 1.0D / 144.0D;

    @Test
    void minimumJerkTurnIsSmoothBellShapedAndLandsExactly() {
        HumanRotation rotation = new HumanRotation();
        rotation.reset(0.0F, 0.0F);
        rotation.retarget(0.0D, 120.0F, 20.0F, 12.0D, PROFILE);
        double duration = rotation.endTime();
        assertTrue(duration > 0.2D && duration < 0.6D, "a 120° turn takes human time: " + duration);
        float[] previous = rotation.sample(0.0D);
        double lastStep = 0.0D;
        double peakStep = 0.0D;
        double peakAt = 0.0D;
        double maxJump = 0.0D;
        for (double t = FRAME; t <= duration + 3 * FRAME; t += FRAME) {
            float[] view = rotation.sample(t);
            double step = RotationMath.angleBetween(previous[0], previous[1], view[0], view[1]);
            assertTrue(view[0] <= 120.0F + 1.0E-3F && view[1] <= 20.0F + 1.0E-3F, "no overshoot from rest");
            maxJump = Math.max(maxJump, Math.abs(step - lastStep));
            if (step > peakStep) {
                peakStep = step;
                peakAt = t;
            }
            lastStep = step;
            previous = view;
        }
        assertEquals(120.0F, previous[0], 1.0E-3F);
        assertEquals(20.0F, previous[1], 1.0E-3F);
        assertEquals(duration / 2.0D, peakAt, 2 * FRAME, "velocity peaks mid-movement (bell shape)");
        assertTrue(peakStep / FRAME <= PROFILE.maxSpeed() + 1.0D, "speed cap " + peakStep / FRAME);
        assertTrue(maxJump < peakStep * 0.1D, "per-frame speed changes gradually: " + maxJump + " vs peak " + peakStep);
    }

    @Test
    void fittsLawLongTurnsAndSmallTargetsTakeLonger() {
        assertTrue(PROFILE.duration(90.0D, 12.0D) > PROFILE.duration(15.0D, 12.0D));
        assertTrue(PROFILE.duration(40.0D, 4.0D) > PROFILE.duration(40.0D, 20.0D));
        assertTrue(PROFILE.scaled(2.0D).duration(90.0D, 12.0D) < PROFILE.duration(90.0D, 12.0D));
        assertTrue(PROFILE.duration(0.5D, 12.0D) >= PROFILE.minDuration());
    }

    @Test
    void retargetMidTurnKeepsVelocityContinuous() {
        HumanRotation rotation = new HumanRotation();
        rotation.reset(0.0F, 0.0F);
        rotation.retarget(0.0D, 90.0F, 0.0F, 12.0D, PROFILE);
        double t = rotation.endTime() * 0.4D;
        double before = rotation.speed(t);
        rotation.retarget(t, -30.0F, 10.0F, 12.0D, PROFILE);
        assertEquals(before, rotation.speed(t), 1.0E-6D, "no velocity jump at the retarget");
    }

    @Test
    void driftingTargetIsPursuedWithoutRestarting() {
        HumanRotation rotation = new HumanRotation();
        rotation.reset(0.0F, 30.0F);
        rotation.retarget(0.0D, 60.0F, 30.0F, 12.0D, PROFILE);
        double end = rotation.endTime();
        // The aim point drifts by 1° every tick (walking past the block).
        for (int tick = 1; tick <= 4; tick++) {
            rotation.retarget(tick * 0.05D, 60.0F + tick, 30.0F, 12.0D, PROFILE);
        }
        assertEquals(end, rotation.endTime(), 0.05D, "a drift keeps the running schedule");
        float[] view = rotation.sample(10.0D);
        assertEquals(64.0F, view[0], 1.0E-3F);
    }

    @Test
    void followingASmoothlyMovingTargetNeverWobbles() {
        // Steering: every tick the wanted direction moves on a little (an exponentially filtered heading).
        HumanRotation rotation = new HumanRotation();
        rotation.reset(0.0F, 45.0F);
        float wanted = 0.0F;
        float goal = 40.0F;
        double lastYaw = 0.0D;
        double lastSign = 0.0D;
        int reversals = 0;
        for (int tick = 1; tick <= 60; tick++) {
            wanted += (goal - wanted) * 0.35F;
            rotation.retarget(tick * 0.05D, wanted, 45.0F, 20.0D, PROFILE);
            for (int frame = 1; frame <= 3; frame++) {
                double yaw = rotation.sample(tick * 0.05D + frame / 60.0D)[0];
                double delta = yaw - lastYaw;
                if (Math.abs(delta) > 1.0E-3D) {
                    double sign = Math.signum(delta);
                    if (lastSign != 0.0D && sign != lastSign) {
                        reversals++;
                    }
                    lastSign = sign;
                }
                lastYaw = yaw;
            }
        }
        assertEquals(0, reversals, "one smooth turn, no back and forth");
        assertEquals(40.0D, lastYaw, 0.5D);
    }

    @Test
    void turnsTheShortWayAcrossTheWrap() {
        HumanRotation rotation = new HumanRotation();
        rotation.reset(170.0F, 0.0F);
        rotation.retarget(0.0D, -170.0F, 0.0F, 12.0D, PROFILE);
        assertTrue(rotation.sample(0.05D)[0] > 170.0F, "turns +20° through 180, not -340°");
        assertEquals(190.0F, rotation.sample(5.0D)[0], 1.0E-3F);
    }

    @Test
    void deterministic() {
        HumanRotation a = new HumanRotation();
        HumanRotation b = new HumanRotation();
        a.reset(0.0F, 0.0F);
        b.reset(0.0F, 0.0F);
        for (int i = 0; i < 10; i++) {
            a.retarget(i * 0.05D, 90.0F - i * 7, 10.0F, 12.0D, PROFILE);
            b.retarget(i * 0.05D, 90.0F - i * 7, 10.0F, 12.0D, PROFILE);
            assertEquals(a.sample(i * 0.05D + 0.02D)[0], b.sample(i * 0.05D + 0.02D)[0]);
        }
    }

    @Test
    void pitchIsClamped() {
        HumanRotation rotation = new HumanRotation();
        rotation.reset(0.0F, 89.0F);
        rotation.retarget(0.0D, 0.0F, 140.0F, 12.0D, PROFILE);
        for (double t = 0.0D; t < 1.0D; t += FRAME) {
            assertTrue(rotation.sample(t)[1] <= 90.0F);
        }
    }

    @Test
    void yawAndPitchConventions() {
        assertEquals(0.0F, RotationMath.yawOf(0, 1), 1.0E-4F);
        assertEquals(90.0F, RotationMath.yawOf(-1, 0), 1.0E-4F);
        assertEquals(-90.0F, RotationMath.yawOf(1, 0), 1.0E-4F);
        assertTrue(RotationMath.pitchOf(1, 2, 0) < 0.0F, "looking up is negative pitch");
        assertTrue(RotationMath.pitchOf(1, -2, 0) > 0.0F, "looking down is positive pitch");
    }

    @Test
    void modePrioritiesMiningFirst() {
        assertTrue(RotationMode.MINING.priority() > RotationMode.JUMPING.priority());
        assertTrue(RotationMode.JUMPING.priority() > RotationMode.RECOVERY.priority());
        assertTrue(RotationMode.TURNING.priority() > RotationMode.NAVIGATION.priority());
    }

}
