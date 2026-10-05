package com.freelocs.theprisons.modules.qol.bandit;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.projectile.ProjectileEntity;
import net.minecraft.particle.ParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.Registries;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Util;
import net.minecraft.util.math.Vec3d;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

/**
 * Throw and return effects: a thrown spear leaves a trail of sparks (the "bullet"), and when it is gone from the world
 * - back in the thrower's hand - a lightning bolt strikes from where it was to the player. Also drives the HUD
 * animations (recoil kick, muzzle flash, return flash).
 */
final class SpearEffects {
    enum Trail {
        LIGHTNING("Lightning sparks"), FIRE("Fire"), SOUL("Soul fire"), STARDUST("Stardust");

        private final String label;

        Trail(String label) {
            this.label = label;
        }

        String label() {
            return label;
        }
    }

    record Options(boolean trail, Trail style, boolean bolt, boolean sound, float volume, boolean hand) {
    }

    private final Random random = new Random();
    private final Map<Integer, Vec3d> flying = new HashMap<>();
    private Vec3d boltFrom = Vec3d.ZERO;
    private int boltFrames;
    private long throwMs;
    private long returnMs;
    private @org.jspecify.annotations.Nullable Vec3d spearPos;
    private Vec3d spearVel = Vec3d.ZERO;

    /** The thrown spear's position now (null when none is in the air) and its velocity in blocks/tick. */
    @org.jspecify.annotations.Nullable Vec3d spearPos() {
        return flying.isEmpty() ? null : spearPos;
    }

    Vec3d spearVel() {
        return spearVel;
    }

    long throwStartMs() {
        return throwMs;
    }

    /** 1 right at the throw, fading to 0 over 0.35 s (recoil + muzzle flash). */
    float kick() {
        return fade(throwMs, 350.0F);
    }

    /** 1 right when the spear returns, fading to 0 over 0.5 s (blue-white flash). */
    float zap() {
        return fade(returnMs, 500.0F);
    }

    private static float fade(long since, float ms) {
        if (since == 0L) {
            return 0.0F;
        }
        float f = 1.0F - (Util.getMeasuringTimeMs() - since) / ms;
        return f <= 0.0F ? 0.0F : f * f;
    }

    boolean active() {
        return !flying.isEmpty();
    }

    void clear() {
        flying.clear();
        spearPos = null;
        boltFrames = 0;
    }

    void tick(MinecraftClient client, ClientPlayerEntity player, Options o) {
        ClientWorld world = client.world;
        if (world == null) {
            return;
        }
        Map<Integer, Vec3d> now = new HashMap<>();
        for (ProjectileEntity e : world.getEntitiesByClass(ProjectileEntity.class, player.getBoundingBox().expand(160.0D),
                p -> p.getOwner() == player && spearLike(p))) {
            Vec3d pos = e.getEntityPos();
            Vec3d last = flying.get(e.getId());
            now.put(e.getId(), pos);
            spearPos = pos;
            spearVel = last == null ? Vec3d.ZERO : pos.subtract(last);
            if (last == null) {
                throwMs = Util.getMeasuringTimeMs();
                if (o.hand()) {
                    burst(world, player.getEyePos().add(player.getRotationVec(1.0F).multiply(0.8D)), ParticleTypes.ELECTRIC_SPARK, 14);
                }
                if (o.sound()) {
                    world.playSoundClient(SoundEvents.ENTITY_FIREWORK_ROCKET_BLAST, SoundCategory.PLAYERS, 0.5F * o.volume(), 1.7F);
                }
            } else if (o.trail()) {
                trail(world, last, pos, o.style());
            }
        }
        for (Map.Entry<Integer, Vec3d> gone : flying.entrySet()) {
            if (!now.containsKey(gone.getKey()) && o.bolt()) {
                strike(world, player, gone.getValue(), o);
            }
        }
        flying.clear();
        flying.putAll(now);
        if (boltFrames > 0) {
            boltFrames--;
            bolt(world, boltFrom, player.getEntityPos().add(0.0D, player.getHeight() * 0.7D, 0.0D));
        }
    }

    private static boolean spearLike(ProjectileEntity p) {
        String path = Registries.ENTITY_TYPE.getId(p.getType()).getPath();
        return path.contains("spear") || path.contains("trident");
    }

    private void strike(ClientWorld world, ClientPlayerEntity player, Vec3d from, Options o) {
        boltFrom = from;
        boltFrames = 5;
        returnMs = Util.getMeasuringTimeMs();
        burst(world, player.getEntityPos().add(0.0D, player.getHeight() * 0.7D, 0.0D), ParticleTypes.ELECTRIC_SPARK, 24);
        if (o.sound()) {
            world.playSoundClient(SoundEvents.ENTITY_LIGHTNING_BOLT_IMPACT, SoundCategory.PLAYERS, 0.6F * o.volume(), 1.6F);
            world.playSoundClient(SoundEvents.ITEM_TRIDENT_RETURN, SoundCategory.PLAYERS, 0.8F * o.volume(), 1.2F);
        }
    }

    private void trail(ClientWorld world, Vec3d a, Vec3d b, Trail style) {
        double len = a.distanceTo(b);
        int steps = Math.max(1, (int) Math.ceil(len / 0.35D));
        for (int i = 0; i < steps; i++) {
            Vec3d p = a.lerp(b, (i + random.nextDouble()) / steps);
            switch (style) {
                case LIGHTNING -> {
                    spark(world, ParticleTypes.ELECTRIC_SPARK, p, 0.08D);
                    if (random.nextInt(4) == 0) {
                        spark(world, ParticleTypes.END_ROD, p, 0.02D);
                    }
                }
                case FIRE -> spark(world, ParticleTypes.FLAME, p, 0.03D);
                case SOUL -> spark(world, ParticleTypes.SOUL_FIRE_FLAME, p, 0.03D);
                case STARDUST -> {
                    spark(world, ParticleTypes.END_ROD, p, 0.05D);
                    spark(world, ParticleTypes.CRIT, p, 0.12D);
                }
            }
        }
    }

    private void spark(ClientWorld world, ParticleEffect type, Vec3d p, double spread) {
        world.addParticleClient(type, p.x, p.y, p.z, (random.nextDouble() - 0.5D) * spread, (random.nextDouble() - 0.5D) * spread,
                (random.nextDouble() - 0.5D) * spread);
    }

    private void burst(ClientWorld world, Vec3d p, ParticleEffect type, int count) {
        for (int i = 0; i < count; i++) {
            world.addParticleClient(type, p.x, p.y, p.z, (random.nextDouble() - 0.5D) * 0.6D, (random.nextDouble() - 0.5D) * 0.6D,
                    (random.nextDouble() - 0.5D) * 0.6D);
        }
    }

    /** A jagged bolt of sparks from {@code from} (high above) to {@code to}; re-rolled every tick it is shown, so it flickers. */
    private void bolt(ClientWorld world, Vec3d from, Vec3d to) {
        Vec3d dir = to.subtract(from);
        double len = dir.length();
        if (len < 0.5D) {
            return;
        }
        int segments = Math.max(6, (int) (len / 1.2D));
        Vec3d prev = from;
        for (int i = 1; i <= segments; i++) {
            double f = i / (double) segments;
            Vec3d p = from.add(dir.multiply(f));
            if (i < segments) {
                double j = 0.45D * Math.sin(Math.PI * f) + 0.1D;
                p = p.add((random.nextDouble() - 0.5D) * 2 * j, (random.nextDouble() - 0.5D) * j, (random.nextDouble() - 0.5D) * 2 * j);
            }
            trailLine(world, prev, p);
            if (random.nextInt(5) == 0) {
                Vec3d branch = p.add((random.nextDouble() - 0.5D) * 1.6D, (random.nextDouble() - 0.5D) * 1.0D, (random.nextDouble() - 0.5D) * 1.6D);
                trailLine(world, p, branch);
            }
            prev = p;
        }
    }

    private void trailLine(ClientWorld world, Vec3d a, Vec3d b) {
        int steps = Math.max(1, (int) Math.ceil(a.distanceTo(b) / 0.25D));
        for (int i = 0; i <= steps; i++) {
            Vec3d p = a.lerp(b, i / (double) steps);
            world.addParticleClient(ParticleTypes.ELECTRIC_SPARK, p.x, p.y, p.z, 0.0D, 0.0D, 0.0D);
            if (i % 3 == 0) {
                world.addParticleClient(ParticleTypes.END_ROD, p.x, p.y, p.z, 0.0D, 0.0D, 0.0D);
            }
        }
    }
}
