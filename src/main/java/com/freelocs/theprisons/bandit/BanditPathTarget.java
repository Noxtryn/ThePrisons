package com.freelocs.theprisons.bandit;

import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.util.UUID;

record BanditPathTarget(UUID uuid, Box box, Vec3d position, double distance) {
}
