package com.debugbridge.fabric12111.mixin;

import com.debugbridge.core.entity.ClientEntityGlowManager;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Forces Entity.isCurrentlyGlowing() to return true for entities the user
 * has selected in the DebugBridge UI, so they render with an outline without
 * requiring server-side authority.
 */
@Mixin(Entity.class)
public class EntityGlowMixin {
    
    @Inject(method = "isCurrentlyGlowing", at = @At("HEAD"), cancellable = true)
    private void debugbridge$forceGlow(CallbackInfoReturnable<Boolean> cir) {
        Entity self = (Entity) (Object) this;
        if (ClientEntityGlowManager.isGlowing(self.getId())) {
            cir.setReturnValue(true);
        }
    }
}
