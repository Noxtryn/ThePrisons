package io.theprisons.core.event;

import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.text.Text;
import org.jspecify.annotations.Nullable;

/**
 * Events the core publishes on the {@link EventBus}. All of them are posted on the client / render thread.
 */
public final class CoreEvents {
    private CoreEvents() {
    }

    /** Start of a client tick, before vanilla input handling and the world tick. */
    public record TickStart(MinecraftClient client) {
    }

    /** End of a client tick. Modules do their per-tick work here. */
    public record TickEnd(MinecraftClient client) {
    }

    /** The client world instance changed (join, leave, dimension change). */
    public record WorldChanged(@Nullable ClientWorld previous, @Nullable ClientWorld current) {
    }

    public record ChunkLoaded(ClientWorld world, int chunkX, int chunkZ) {
    }

    public record ChunkUnloaded(ClientWorld world, int chunkX, int chunkZ) {
    }

    /**
     * A block changed because the server said so (single update or part of a delta update).
     *
     * @param previous the block before the update (an ore turning into stone = mined, by the player or a proc)
     */
    public record BlockChanged(ClientWorld world, long pos, BlockState state, BlockState previous) {
    }

    /** The local player finished breaking a block (client-side prediction). */
    public record PlayerBrokeBlock(ClientWorld world, long pos, BlockState state) {
    }

    /**
     * @param fromPlayer true for chat sent by a player, false for server / system messages
     */
    public record ChatReceived(Text message, boolean overlay, boolean fromPlayer) {
    }

    /**
     * The local player took damage (the server's damage packet).
     *
     * @param attacker who is responsible (the shooter for projectiles), {@code null} for fall, fire, lava and the like
     */
    public record PlayerHurt(net.minecraft.entity.@Nullable Entity attacker) {
    }


    /** World rendering after entities; overlays draw here. */
    public record WorldRender(WorldRenderContext context) {
    }
}
