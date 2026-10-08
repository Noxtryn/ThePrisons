package com.freelocs.theprisons.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.model.Dilation;
import net.minecraft.client.render.entity.model.BipedEntityModel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

import java.util.function.Function;

/**
 * Worn armour hugs the body like a skin: vanilla inflates the armour boxes by 1 px (helmet, chestplate, boots) and
 * 0.5 px (leggings); here they sit just above the skin's own outer layer (jacket / sleeves / pants 0.25 px, hat 0.5 px).
 * The model data is built once per piece in this order: helmet, chestplate, leggings, boots.
 */
@Mixin(BipedEntityModel.class)
public abstract class ThePrisonsBipedEquipmentMixin {
    @Unique
    private static final Dilation[] THEPRISONS$THIN = {
            new Dilation(0.56F), // helmet: just above the hat layer
            new Dilation(0.34F), // chestplate
            new Dilation(0.27F), // leggings: under the chestplate and boots
            new Dilation(0.31F)  // boots
    };

    @WrapOperation(method = "createEquipmentModelData(Ljava/util/function/Function;Lnet/minecraft/client/model/Dilation;Lnet/minecraft/client/model/Dilation;)Lnet/minecraft/client/render/entity/model/EquipmentModelData;",
            at = @At(value = "INVOKE", target = "Ljava/util/function/Function;apply(Ljava/lang/Object;)Ljava/lang/Object;", ordinal = 0))
    private static Object theprisons$thinHelmet(Function<Object, Object> f, Object dilation, Operation<Object> original) {
        return original.call(f, THEPRISONS$THIN[0]);
    }

    @WrapOperation(method = "createEquipmentModelData(Ljava/util/function/Function;Lnet/minecraft/client/model/Dilation;Lnet/minecraft/client/model/Dilation;)Lnet/minecraft/client/render/entity/model/EquipmentModelData;",
            at = @At(value = "INVOKE", target = "Ljava/util/function/Function;apply(Ljava/lang/Object;)Ljava/lang/Object;", ordinal = 1))
    private static Object theprisons$thinChest(Function<Object, Object> f, Object dilation, Operation<Object> original) {
        return original.call(f, THEPRISONS$THIN[1]);
    }

    @WrapOperation(method = "createEquipmentModelData(Ljava/util/function/Function;Lnet/minecraft/client/model/Dilation;Lnet/minecraft/client/model/Dilation;)Lnet/minecraft/client/render/entity/model/EquipmentModelData;",
            at = @At(value = "INVOKE", target = "Ljava/util/function/Function;apply(Ljava/lang/Object;)Ljava/lang/Object;", ordinal = 2))
    private static Object theprisons$thinLegs(Function<Object, Object> f, Object dilation, Operation<Object> original) {
        return original.call(f, THEPRISONS$THIN[2]);
    }

    @WrapOperation(method = "createEquipmentModelData(Ljava/util/function/Function;Lnet/minecraft/client/model/Dilation;Lnet/minecraft/client/model/Dilation;)Lnet/minecraft/client/render/entity/model/EquipmentModelData;",
            at = @At(value = "INVOKE", target = "Ljava/util/function/Function;apply(Ljava/lang/Object;)Ljava/lang/Object;", ordinal = 3))
    private static Object theprisons$thinFeet(Function<Object, Object> f, Object dilation, Operation<Object> original) {
        return original.call(f, THEPRISONS$THIN[3]);
    }
}
