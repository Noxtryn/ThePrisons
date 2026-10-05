package io.theprisons.core.render;

import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.RenderLayers;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.VertexRendering;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShapes;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * Minimal world overlay drawing for modules (lines and block outlines), used from a
 * {@code CoreEvents.WorldRender} listener. Modules draw from data they captured at tick time, never by querying
 * the world while rendering.
 */
public final class Overlay {
    private final MatrixStack matrices;
    private final VertexConsumerProvider consumers;
    private VertexConsumer lines;
    private final Vec3d camera;

    private Overlay(MatrixStack matrices, VertexConsumerProvider consumers, Vec3d camera) {
        this.matrices = matrices;
        this.consumers = consumers;
        this.lines = consumers.getBuffer(RenderLayers.lines());
        this.camera = camera;
    }

    /** {@code null} when the context cannot draw (no consumers). */
    public static @Nullable Overlay begin(WorldRenderContext context) {
        if (context.consumers() == null) {
            return null;
        }
        Vec3d camera = MinecraftClient.getInstance().gameRenderer.getCamera().getCameraPos();
        return new Overlay(context.matrices(), context.consumers(), camera);
    }

    public Vec3d camera() {
        return camera;
    }

    public void line(double x1, double y1, double z1, double x2, double y2, double z2, int color, float width) {
        MatrixStack.Entry entry = matrices.peek();
        Vector3f normal = new Vector3f((float) (x2 - x1), (float) (y2 - y1), (float) (z2 - z1));
        if (normal.lengthSquared() < 1.0E-6F) {
            return;
        }
        normal.normalize();
        lines.vertex(entry, (float) (x1 - camera.x), (float) (y1 - camera.y), (float) (z1 - camera.z)).color(color).normal(entry, normal).lineWidth(width);
        lines.vertex(entry, (float) (x2 - camera.x), (float) (y2 - camera.y), (float) (z2 - camera.z)).color(color).normal(entry, normal).lineWidth(width);
    }

    /**
     * Text facing the camera like a name tag, visible through blocks, readable from afar (grows with distance).
     */
    public void label(double x, double y, double z, String text, int color) {
        MinecraftClient client = MinecraftClient.getInstance();
        TextRenderer font = client.textRenderer;
        double distance = Math.sqrt(camera.squaredDistanceTo(x, y, z));
        float scale = 0.025F * (float) Math.max(1.0D, distance / 8.0D);
        matrices.push();
        matrices.translate(x - camera.x, y - camera.y, z - camera.z);
        matrices.multiply(client.gameRenderer.getCamera().getRotation());
        matrices.scale(scale, -scale, scale);
        font.draw(text, -font.getWidth(text) / 2.0F, 0.0F, color, false, matrices.peek().getPositionMatrix(), consumers,
                TextRenderer.TextLayerType.SEE_THROUGH, 0x90000000, LightmapTextureManager.MAX_LIGHT_COORDINATE);
        matrices.pop();
        // Text uses another buffer; lines drawn after it need a fresh one.
        lines = consumers.getBuffer(RenderLayers.lines());
    }

    public void blockOutline(int x, int y, int z, int color, float width) {
        VertexRendering.drawOutline(matrices, lines, VoxelShapes.fullCube(), x - camera.x, y - camera.y, z - camera.z, color, width);
    }
}
