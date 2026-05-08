package com.debugbridge.fabric262.mixin;

import com.debugbridge.fabric262.DebugBridgeMod;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public class MinecraftClientMixin {
    @Inject(method = "tick", at = @At("TAIL"))
    private void onEndTick(CallbackInfo ci) {
        DebugBridgeMod.onClientTick((Minecraft) (Object) this);
    }
}
