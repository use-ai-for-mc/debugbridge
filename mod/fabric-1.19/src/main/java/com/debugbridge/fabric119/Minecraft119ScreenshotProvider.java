package com.debugbridge.fabric119;

import com.debugbridge.core.screenshot.JpegEncoder;
import com.debugbridge.core.screenshot.ScreenshotProvider;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 1.19 framebuffer capture as JPEG.
 * <p>
 * {@link Screenshot#takeScreenshot(RenderTarget)} is fully synchronous on
 * this version: it binds the GL color texture, downloads pixels, and flips
 * the image. We then convert to ARGB ints via the (deprecated but still
 * functional) {@link NativeImage#makePixelArray()} and hand them to
 * {@link JpegEncoder}.
 * <p>
 * Optional CPU downscale is done via stb_image_resize through
 * {@code resizeSubRectTo} before pixel extraction.
 */
public class Minecraft119ScreenshotProvider implements ScreenshotProvider {

    private static int clampDownscale(int requested, int width, int height) {
        if (requested < 1) return 1;
        for (int f = requested; f >= 1; f--) {
            if (width % f == 0 && height % f == 0) return f;
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
                if (target == null) {
                    future.completeExceptionally(
                            new IllegalStateException("Main render target is null"));
                    return;
                }

                full = Screenshot.takeScreenshot(target);
                int srcW = full.getWidth();
                int srcH = full.getHeight();

                NativeImage out = full;
                int downscale = clampDownscale(requestedDownscale, srcW, srcH);
                if (downscale > 1) {
                    int dstW = srcW / downscale;
                    int dstH = srcH / downscale;
                    scaled = new NativeImage(dstW, dstH, false);
                    full.resizeSubRectTo(0, 0, srcW, srcH, scaled);
                    out = scaled;
                }

                int w = out.getWidth();
                int h = out.getHeight();
                int[] pixels = out.makePixelArray();   // ARGB

                Path path = JpegEncoder.writeJpegTempFile(pixels, w, h, quality);
                long size = Files.size(path);
                future.complete(new Capture(path.toString(), w, h, size));
            } catch (Throwable t) {
                future.completeExceptionally(t);
            } finally {
                if (full != null) full.close();
                if (scaled != null) scaled.close();
            }
        });

        return future.get(timeoutMs, TimeUnit.MILLISECONDS);
    }
}
