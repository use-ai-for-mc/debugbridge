package com.debugbridge.fabric261.mixin;

import com.debugbridge.core.block.ClientBlockGlowManager;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
public class BlockGlowGizmoMixin {
    private static final int DEBUGBRIDGE_BLOCK_HIGHLIGHT_STROKE = 0xE000FF00;
    private static final int DEBUGBRIDGE_BLOCK_HIGHLIGHT_FILL = 0x3000FF00;
    private static final float DEBUGBRIDGE_BLOCK_HIGHLIGHT_STROKE_WIDTH = 2.0F;
    private static final int DEBUGBRIDGE_BLOCK_HIGHLIGHT_TEXT_COLOR = 0xFFFFFFFF;
    private static final float DEBUGBRIDGE_BLOCK_HIGHLIGHT_PADDING = 0.02F;
    private static final float DEBUGBRIDGE_BLOCK_HIGHLIGHT_TEXT_SCALE = 0.16F;

    @Inject(
            method = "extractLevel",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/client/renderer/debug/GameTestBlockHighlightRenderer;emitGizmos()V",
                            shift = At.Shift.AFTER))
    private void debugbridge$emitBlockGlowGizmos(
            DeltaTracker deltaTracker, Camera camera, float deltaPartialTick, CallbackInfo ci) {
        // This injection point is inside LevelRenderer's debug/gizmo extraction
        // phase, where a GizmoCollector is already registered. Keep this path
        // independent from GameTestBlockHighlightRenderer marker storage so
        // DebugBridge block glow never clears unrelated vanilla GameTest markers.
        var glowing = ClientBlockGlowManager.snapshot();
        if (glowing.isEmpty()) return;

        GizmoStyle style = GizmoStyle.strokeAndFill(
                DEBUGBRIDGE_BLOCK_HIGHLIGHT_STROKE,
                DEBUGBRIDGE_BLOCK_HIGHLIGHT_STROKE_WIDTH,
                DEBUGBRIDGE_BLOCK_HIGHLIGHT_FILL);
        for (var p : glowing) {
            BlockPos pos = new BlockPos(p.x(), p.y(), p.z());
            Gizmos.cuboid(pos, DEBUGBRIDGE_BLOCK_HIGHLIGHT_PADDING, style);
            Gizmos.billboardTextOverBlock(
                    pos.toShortString(),
                    pos,
                    0,
                    DEBUGBRIDGE_BLOCK_HIGHLIGHT_TEXT_COLOR,
                    DEBUGBRIDGE_BLOCK_HIGHLIGHT_TEXT_SCALE);
        }
    }
}
