package com.freelocs.theprisons.bandit;

import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

final class BanditPathingBehavior {
    private static final long RECALC_COOLDOWN_MS = 180L;
    private static final int MAX_NODES = 6_000;
    private static final int MAX_RADIUS = 56;
    private static final int GOAL_DISTANCE_SQUARED = 4;

    private BanditPathTarget goal;
    private GroundPathSnapshot currentPath = GroundPathSnapshot.empty();
    private long lastRecalcAtMs;

    boolean hasGoal() {
        return goal != null;
    }

    boolean hasPath() {
        return currentPath.hasPath();
    }

    BanditPathTarget getGoal() {
        return goal;
    }

    void cancelEverything() {
        goal = null;
        currentPath = GroundPathSnapshot.empty();
        lastRecalcAtMs = 0L;
    }

    void forceCancel() {
        cancelEverything();
    }

    void update(ClientWorld world, ClientPlayerEntity player, BanditPathTarget target, double scanRange, long nowMs) {
        if (target == null) {
            cancelEverything();
            return;
        }

        if (target.distance() < 3.5D) {
            cancelEverything();
            return;
        }

        boolean goalChanged = goal == null
                || !goal.uuid().equals(target.uuid())
                || goal.position().squaredDistanceTo(target.position()) > 1.0D
                || goal.box().getCenter().squaredDistanceTo(target.box().getCenter()) > 1.0D;

        Vec3d playerPos = new Vec3d(player.getX(), player.getY(), player.getZ());
        boolean playerMoved = !currentPath.playerPos.equals(player.getBlockPos())
                || currentPath.playerStart.squaredDistanceTo(playerPos) > 0.09D;
        boolean cooldownElapsed = nowMs - lastRecalcAtMs >= RECALC_COOLDOWN_MS;

        goal = target;
        if (!goalChanged && !playerMoved && !cooldownElapsed && currentPath.hasPath()) {
            return;
        }

        currentPath = PathFinder.update(world, player, target.position(), target.box(), scanRange, nowMs, currentPath);
        lastRecalcAtMs = nowMs;
    }

    List<Vec3d> visiblePoints(PlayerEntity player) {
        return currentPath.visiblePoints(player);
    }

    private static final class GroundPathSnapshot {
        private final List<Vec3d> points;
        private final Vec3d playerStart;
        private final BlockPos playerPos;
        private final Vec3d targetPos;
        private final long updatedAtMs;

        private GroundPathSnapshot(List<Vec3d> points, Vec3d playerStart, BlockPos playerPos, Vec3d targetPos, long updatedAtMs) {
            this.points = points;
            this.playerStart = playerStart;
            this.playerPos = playerPos;
            this.targetPos = targetPos;
            this.updatedAtMs = updatedAtMs;
        }

        private static GroundPathSnapshot empty() {
            return new GroundPathSnapshot(List.of(), Vec3d.ZERO, new BlockPos(0, 0, 0), Vec3d.ZERO, 0L);
        }

        private boolean hasPath() {
            return !points.isEmpty();
        }

        private List<Vec3d> visiblePoints(PlayerEntity player) {
            if (points.size() < 2) {
                return points;
            }

            Vec3d current = new Vec3d(player.getX(), player.getY(), player.getZ());
            int closestIndex = 0;
            double best = Double.MAX_VALUE;
            for (int i = 0; i < points.size(); i++) {
                double distance = points.get(i).squaredDistanceTo(current);
                if (distance < best) {
                    best = distance;
                    closestIndex = i;
                }
            }

            int start = Math.min(Math.max(closestIndex, 0), points.size() - 1);
            List<Vec3d> visible = new ArrayList<>(points.subList(start, points.size()));
            return visible.size() >= 2 ? visible : points;
        }
    }

    private static final class PathFinder {
        private PathFinder() {
        }

        private static GroundPathSnapshot update(ClientWorld world, ClientPlayerEntity player, Vec3d targetPosition, Box targetBox, double scanRange, long nowMs, GroundPathSnapshot previous) {
            Vec3d playerPos = new Vec3d(player.getX(), player.getY(), player.getZ());
            if (nowMs - previous.updatedAtMs < RECALC_COOLDOWN_MS
                    && previous.playerPos.equals(player.getBlockPos())
                    && previous.playerStart.squaredDistanceTo(playerPos) < 0.09D
                    && previous.targetPos.squaredDistanceTo(targetPosition) < 1.0D) {
                return previous;
            }

            BlockPos start = findStandable(world, BlockPos.ofFloored(playerPos), player.getBlockY());
            if (start == null) {
                return GroundPathSnapshot.empty();
            }

            List<BlockPos> goals = findGoals(world, targetPosition, targetBox, start.getY());
            if (goals.isEmpty()) {
                return GroundPathSnapshot.empty();
            }

            List<BlockPos> path = search(world, start, goals, scanRange);
            if (path.isEmpty()) {
                path = buildFallbackPath(world, start, targetPosition, targetBox);
                if (path.isEmpty()) {
                    return GroundPathSnapshot.empty();
                }
            }

            List<Vec3d> points = new ArrayList<>(path.size());
            for (BlockPos pos : path) {
                points.add(new Vec3d(pos.getX() + 0.5D, pos.getY() + 0.05D, pos.getZ() + 0.5D));
            }
            return new GroundPathSnapshot(points, playerPos, start, targetPosition, nowMs);
        }

        private static List<BlockPos> buildFallbackPath(ClientWorld world, BlockPos start, Vec3d targetPosition, Box targetBox) {
            List<BlockPos> path = new ArrayList<>();
            path.add(start);

            Vec3d startCenter = new Vec3d(start.getX() + 0.5D, start.getY(), start.getZ() + 0.5D);
            Vec3d targetCenter = targetBox.getCenter().subtract(0.0D, 0.5D, 0.0D);
            Vec3d delta = targetCenter.subtract(startCenter);
            int steps = Math.max(6, (int) Math.ceil(delta.length() / 0.75D));

            BlockPos last = start;
            for (int step = 1; step <= steps; step++) {
                double t = step / (double) steps;
                Vec3d sample = new Vec3d(
                        startCenter.x + delta.x * t,
                        startCenter.y + delta.y * t,
                        startCenter.z + delta.z * t
                );
                BlockPos around = BlockPos.ofFloored(sample);
                BlockPos standable = findStandable(world, around, around.getY());
                if (standable != null && !standable.equals(last)) {
                    path.add(standable);
                    last = standable;
                }
            }

            BlockPos targetBlock = findStandable(world, BlockPos.ofFloored(targetPosition), BlockPos.ofFloored(targetPosition).getY());
            if (targetBlock != null && !targetBlock.equals(last)) {
                path.add(targetBlock);
            }

            return path;
        }

        private static List<BlockPos> search(ClientWorld world, BlockPos start, List<BlockPos> goals, double scanRange) {
            PriorityQueue<Node> open = new PriorityQueue<>(Comparator.comparingDouble(node -> node.fScore));
            Map<Long, Node> best = new HashMap<>();
            Set<Long> closed = new HashSet<>();

            Node startNode = new Node(start, null, 0.0D, heuristic(start, goals));
            open.add(startNode);
            best.put(key(start), startNode);

            int explored = 0;
            while (!open.isEmpty() && explored < MAX_NODES) {
                Node current = open.poll();
                long currentKey = key(current.pos);
                if (!closed.add(currentKey)) {
                    continue;
                }
                explored++;

                if (isGoal(current.pos, goals)) {
                    return reconstruct(current);
                }

                for (BlockPos next : neighbors(world, start, current.pos, scanRange)) {
                    long nextKey = key(next);
                    if (closed.contains(nextKey) || !canStandAt(world, next)) {
                        continue;
                    }

                    double tentative = current.gScore + movementCost(current.pos, next);
                    Node known = best.get(nextKey);
                    if (known != null && tentative >= known.gScore) {
                        continue;
                    }

                    Node node = new Node(next, current, tentative, tentative + heuristic(next, goals));
                    best.put(nextKey, node);
                    open.add(node);
                }
            }

            return List.of();
        }

        private static List<BlockPos> reconstruct(Node node) {
            List<BlockPos> path = new ArrayList<>();
            Node cursor = node;
            while (cursor != null) {
                path.add(cursor.pos);
                cursor = cursor.parent;
            }
            List<BlockPos> reversed = new ArrayList<>(path.size());
            for (int i = path.size() - 1; i >= 0; i--) {
                reversed.add(path.get(i));
            }
            return reversed;
        }

        private static List<BlockPos> findGoals(ClientWorld world, Vec3d targetPosition, Box targetBox, int referenceY) {
            List<BlockPos> goals = new ArrayList<>();
            int minX = (int) Math.floor(targetBox.minX) - 1;
            int maxX = (int) Math.floor(targetBox.maxX) + 1;
            int minZ = (int) Math.floor(targetBox.minZ) - 1;
            int maxZ = (int) Math.floor(targetBox.maxZ) + 1;
            int minY = Math.max(world.getBottomY(), referenceY - 2);
            int maxY = Math.min(319, referenceY + 2);

            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    for (int y = minY; y <= maxY; y++) {
                        BlockPos pos = new BlockPos(x, y, z);
                        if (canStandAt(world, pos)) {
                            goals.add(pos);
                        }
                    }
                }
            }

            if (goals.isEmpty()) {
                BlockPos center = BlockPos.ofFloored(targetPosition);
                if (canStandAt(world, center)) {
                    goals.add(center);
                }
            }
            return goals;
        }

        private static List<BlockPos> neighbors(ClientWorld world, BlockPos origin, BlockPos pos, double scanRange) {
            List<BlockPos> neighbors = new ArrayList<>(6);
            double maxDistanceSquared = Math.pow(Math.max(8.0D, Math.min(MAX_RADIUS, scanRange)), 2.0D);
            int x = pos.getX();
            int y = pos.getY();
            int z = pos.getZ();
            BlockPos[] candidates = {
                    new BlockPos(x + 1, y, z),
                    new BlockPos(x - 1, y, z),
                    new BlockPos(x, y, z + 1),
                    new BlockPos(x, y, z - 1),
                    new BlockPos(x, y + 1, z),
                    new BlockPos(x, y - 1, z)
            };

            for (BlockPos candidate : candidates) {
                if (candidate.getSquaredDistance(origin.getX(), origin.getY(), origin.getZ()) > maxDistanceSquared) {
                    continue;
                }
                BlockPos standable = findStandable(world, candidate, y);
                if (standable != null && Math.abs(standable.getY() - y) <= 1) {
                    neighbors.add(standable);
                }
            }

            return neighbors;
        }

        private static BlockPos findStandable(ClientWorld world, BlockPos around, int referenceY) {
            for (int delta = 0; delta <= 3; delta++) {
                int[] candidates = delta == 0
                        ? new int[]{referenceY}
                        : new int[]{referenceY + delta, referenceY - delta};
                for (int y : candidates) {
                    if (y <= world.getBottomY() || y >= 318) {
                        continue;
                    }
                    BlockPos pos = new BlockPos(around.getX(), y, around.getZ());
                    if (canStandAt(world, pos)) {
                        return pos;
                    }
                }
            }

            return canStandAt(world, around) ? around : null;
        }

        private static boolean canStandAt(ClientWorld world, BlockPos pos) {
            if (pos.getY() <= world.getBottomY() || pos.getY() >= 318) {
                return false;
            }

            BlockPos feet = pos;
            BlockPos head = pos.up();
            BlockPos floor = pos.down();

            return isEmpty(world, feet) && isEmpty(world, head) && !isEmpty(world, floor);
        }

        private static boolean isEmpty(ClientWorld world, BlockPos pos) {
            return world.getBlockState(pos).getCollisionShape(world, pos).isEmpty();
        }

        private static boolean isGoal(BlockPos pos, List<BlockPos> goals) {
            for (BlockPos goal : goals) {
                if (pos.getSquaredDistance(goal.getX(), goal.getY(), goal.getZ()) <= GOAL_DISTANCE_SQUARED) {
                    return true;
                }
            }
            return false;
        }

        private static double movementCost(BlockPos from, BlockPos to) {
            return 1.0D + Math.abs(from.getY() - to.getY()) * 0.5D;
        }

        private static double heuristic(BlockPos pos, List<BlockPos> goals) {
            double best = Double.MAX_VALUE;
            for (BlockPos goal : goals) {
                double distance = pos.getSquaredDistance(goal.getX(), goal.getY(), goal.getZ());
                best = Math.min(best, distance);
            }
            return Math.sqrt(best);
        }

        private static long key(BlockPos pos) {
            return (((long) pos.getX()) & 0x3FFFFFFL) << 38 | (((long) pos.getZ()) & 0x3FFFFFFL) << 12 | ((long) pos.getY() & 0xFFFL);
        }

        private static final class Node {
            private final BlockPos pos;
            private final Node parent;
            private final double gScore;
            private final double fScore;

            private Node(BlockPos pos, Node parent, double gScore, double fScore) {
                this.pos = pos;
                this.parent = parent;
                this.gScore = gScore;
                this.fScore = fScore;
            }
        }
    }
}
