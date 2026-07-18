package com.debugbridge.fabric1214;

import com.debugbridge.core.recording.FrameCapturer;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;

/** Synchronous frame capture for the 1.21.4 render API. */
public final class Minecraft1214FrameCapturer implements FrameCapturer {
    private static int clampDownscale(int requested, int width, int height) {
        if (requested < 1) return 1;
        for (int factor = requested; factor >= 1; factor--) {
            if (width % factor == 0 && height % factor == 0) return factor;
        }
        return 1;
    }

    @Override
    @SuppressWarnings("deprecation")
    public void capture(int requestedDownscale, FrameSink sink) {
        NativeImage full = null;
        NativeImage scaled = null;
        try {
            Minecraft mc = Minecraft.getInstance();
            RenderTarget target = mc.getMainRenderTarget();
            if (target == null) throw new IllegalStateException("main render target is null");
            full = Screenshot.takeScreenshot(target);
            int factor = clampDownscale(requestedDownscale, full.getWidth(), full.getHeight());
            NativeImage output = full;
            if (factor > 1) {
                scaled = new NativeImage(full.getWidth() / factor, full.getHeight() / factor, false);
                full.resizeSubRectTo(0, 0, full.getWidth(), full.getHeight(), scaled);
                output = scaled;
            }
            sink.onPixels(output.makePixelArray(), output.getWidth(), output.getHeight());
        } catch (Throwable error) {
            sink.onError(error);
        } finally {
            if (full != null) full.close();
            if (scaled != null) scaled.close();
        }
    }
}
