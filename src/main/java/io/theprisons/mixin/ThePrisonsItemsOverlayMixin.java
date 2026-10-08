package io.theprisons.mixin;

import io.theprisons.items.client.AhOverlayRender;
import io.theprisons.items.client.EnergyOverlayRender;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.slot.Slot;
import net.minecraft.text.Text;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;

/** The auction house overlay on the server's menu: a border per slot, the analysis in the hover, the dev numbers. Reads a prepared snapshot only. */
@Mixin(HandledScreen.class)
public abstract class ThePrisonsItemsOverlayMixin {
    @Shadow
    protected @Nullable Slot focusedSlot;

    @Inject(method = "drawSlot", at = @At("TAIL"), require = 0)
    private void theprisons$ahSlot(DrawContext context, Slot slot, int mouseX, int mouseY, CallbackInfo ci) {
        AhOverlayRender.drawSlot(context, (HandledScreen<?>) (Object) this, slot);
    }

    @Inject(method = "getTooltipFromItem", at = @At("RETURN"), cancellable = true, require = 0)
    private void theprisons$ahTooltip(ItemStack stack, CallbackInfoReturnable<List<Text>> cir) {
        Slot slot = focusedSlot;
        if (slot != null && slot.getStack() == stack) {
            List<Text> out = new ArrayList<>(cir.getReturnValue());
            int before = out.size();
            AhOverlayRender.extendTooltip((HandledScreen<?>) (Object) this, slot, stack, cir.getReturnValue(), out);
            if (out.size() != before) {
                cir.setReturnValue(out);
            }
        }
    }

    @Inject(method = "render", at = @At("TAIL"), require = 0)
    private void theprisons$itemsOverlays(DrawContext context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        AhOverlayRender.drawDev(context, (HandledScreen<?>) (Object) this);
        EnergyOverlayRender.draw(context, (HandledScreen<?>) (Object) this);
    }
}
