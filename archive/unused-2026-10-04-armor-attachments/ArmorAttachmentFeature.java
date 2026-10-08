package com.freelocs.theprisons.modules.qol.items;

import net.minecraft.client.model.ModelPart;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.command.OrderedRenderCommandQueue;
import net.minecraft.client.render.entity.feature.FeatureRenderer;
import net.minecraft.client.render.entity.feature.FeatureRendererContext;
import net.minecraft.client.render.entity.model.PlayerEntityModel;
import net.minecraft.client.render.entity.state.PlayerEntityRenderState;
import net.minecraft.client.util.math.MatrixStack;

/** Draws the {@link ArmorAttachments} at the player's body parts, so they follow every movement. */
public final class ArmorAttachmentFeature extends FeatureRenderer<PlayerEntityRenderState, PlayerEntityModel> {
    public ArmorAttachmentFeature(FeatureRendererContext<PlayerEntityRenderState, PlayerEntityModel> context) {
        super(context);
    }

    @Override
    public void render(MatrixStack matrices, OrderedRenderCommandQueue queue, int light, PlayerEntityRenderState state,
                       float limbAngle, float limbDistance) {
        ArmorAttachments.Holder holder = ((ArmorAttachments.Duck) state).theprisons$attachments();
        if (holder.used == 0 || state.invisible) {
            return;
        }
        PlayerEntityModel model = getContextModel();
        for (ArmorAttachments.Entry entry : holder.active()) {
            if (entry.state().isEmpty()) {
                continue;
            }
            ModelPart part = switch (entry.part()) {
                case HEAD -> model.head;
                case BODY -> model.body;
                case RIGHT_ARM -> model.rightArm;
                case LEFT_ARM -> model.leftArm;
                case RIGHT_LEG -> model.rightLeg;
                case LEFT_LEG -> model.leftLeg;
            };
            matrices.push();
            model.getRootPart().applyTransform(matrices);
            part.applyTransform(matrices);
            matrices.scale(1.0F, -1.0F, -1.0F);
            entry.state().render(matrices, queue, light, OverlayTexture.DEFAULT_UV, state.outlineColor);
            matrices.pop();
        }
    }
}
