package com.debugbridge.fabric1214;

import com.debugbridge.core.screenshot.JpegEncoder;
import com.debugbridge.core.screenshot.ScreenshotProvider;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.NativeImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;

/** Synchronous framebuffer capture used by the pre-1.21.5 render API. */
public final class Minecraft1214ScreenshotProvider implements ScreenshotProvider {
    private static int clampDownscale(int requested, int width, int height) {
        if (requested < 1) return 1;
        for (int factor = requested; factor >= 1; factor--) {
            if (width % factor == 0 && height % factor == 0) return factor;
        }
        return 1;
    }

    @Override
    @SuppressWarnings("deprecation")
    public Capture capture(int requestedDownscale, float quality, long timeoutMs) throws Exception {
        Minecraft mc = Minecraft.getInstance();
        CompletableFuture<Capture> future = new CompletableFuture<>();
        mc.execute(() -> {
            NativeImage full = null;
            NativeImage scaled = null;
            try {
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
                int[] pixels = output.makePixelArray();
                Path path = JpegEncoder.writeJpegTempFile(pixels, output.getWidth(), output.getHeight(), quality);
                future.complete(new Capture(path.toString(), output.getWidth(), output.getHeight(), Files.size(path)));
            } catch (Throwable error) {
                future.completeExceptionally(error);
            } finally {
                if (full != null) full.close();
                if (scaled != null) scaled.close();
            }
        });
        return future.get(timeoutMs, TimeUnit.MILLISECONDS);
    }
}
