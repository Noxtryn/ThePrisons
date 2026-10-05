package com.freelocs.theprisons.core.control;

import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import org.jspecify.annotations.Nullable;

/**
 * Breaks blocks the way a player does: only the block the crosshair is really on, through the regular interaction
 * manager (so instant-break setups break it the same tick), with the fastest suitable hotbar tool. Shared by every
 * mining module. Client thread only.
 */
public final class BlockBreaker {
    private static final int MIN_DURABILITY_PERCENT = 5;

    /** Result of a raycast along the current view. */
    public record Aim(BlockPos pos, Direction side) {
    }

    private @Nullable BlockPos breaking;
    private int breakingTicks;

    /** The block the current view hits within reach, or {@code null}. Uses the rotation applied this tick. */
    public static @Nullable Aim crosshair(ClientPlayerEntity player, double reach) {
        HitResult hit = player.raycast(reach, 1.0F, false);
        if (hit instanceof BlockHitResult blockHit && hit.getType() == HitResult.Type.BLOCK) {
            return new Aim(blockHit.getBlockPos(), blockHit.getSide());
        }
        return null;
    }


    /**
     * Hits the block if the crosshair is on it this tick.
     *
     * @return true when a hit was sent
     */
    public boolean hit(MinecraftClient client, ClientPlayerEntity player, Aim aim) {
        if (client.interactionManager == null || client.world == null) {
            return false;
        }
        BlockState state = client.world.getBlockState(aim.pos());
        if (state.isAir()) {
            return false;
        }
        selectTool(player, state);
        if (!aim.pos().equals(breaking)) {
            breaking = aim.pos().toImmutable();
            breakingTicks = 0;
        }
        breakingTicks++;
        client.interactionManager.updateBlockBreakingProgress(aim.pos(), aim.side());
        player.swingHand(Hand.MAIN_HAND);
        return true;
    }

    /** Ticks spent on the current block. */
    public int breakingTicks() {
        return breakingTicks;
    }

    public void cancel(MinecraftClient client) {
        if (breaking != null && client.interactionManager != null) {
            client.interactionManager.cancelBlockBreaking();
        }
        breaking = null;
        breakingTicks = 0;
    }


    /** Holds the fastest suitable hotbar tool for the block, skipping tools that are about to break. */
    public static void selectTool(ClientPlayerEntity player, BlockState state) {
        PlayerInventory inventory = player.getInventory();
        int bestSlot = -1;
        float bestSpeed = 1.0F;
        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (stack.isEmpty() || nearlyBroken(stack)) {
                continue;
            }
            float speed = stack.getMiningSpeedMultiplier(state);
            if (state.isToolRequired() && !stack.isSuitableFor(state)) {
                speed = Math.min(speed, 1.0F);
            }
            if (speed > bestSpeed) {
                bestSpeed = speed;
                bestSlot = slot;
            }
        }
        if (bestSlot >= 0 && bestSlot != inventory.getSelectedSlot()) {
            inventory.setSelectedSlot(bestSlot);
        }
    }

    private static boolean nearlyBroken(ItemStack stack) {
        if (!stack.isDamageable()) {
            return false;
        }
        int max = Math.max(1, stack.getMaxDamage());
        return (max - stack.getDamage()) * 100 / max < MIN_DURABILITY_PERCENT;
    }
}
