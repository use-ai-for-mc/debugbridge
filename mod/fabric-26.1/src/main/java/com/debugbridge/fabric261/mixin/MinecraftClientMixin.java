package com.debugbridge.fabric261.mixin;

import com.debugbridge.fabric261.DebugBridgeMod;
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

    @Inject(method = "runTick", at = @At("TAIL"))
    private void onEndRunTick(boolean tick, CallbackInfo ci) {
        DebugBridgeMod.onRenderFrame((Minecraft) (Object) this);
    }

    @Inject(method = "close", at = @At("HEAD"))
    private void onClose(CallbackInfo ci) {
        DebugBridgeMod.onClientClose((Minecraft) (Object) this);
    }
}
