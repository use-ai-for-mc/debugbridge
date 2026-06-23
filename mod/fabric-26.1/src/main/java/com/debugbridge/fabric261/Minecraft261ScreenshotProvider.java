package com.debugbridge.fabric261;

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

/**
 * Exact 26.1 framebuffer capture as JPEG.
 * <p>
 * Uses {@link Screenshot#takeScreenshot(RenderTarget, int, java.util.function.Consumer)}
 * which performs a backend-neutral readback through the client screenshot API.
 * The callback fires once the image is available; from inside that callback we
 * extract ARGB pixels via {@link NativeImage#getPixels()}, release the image,
 * and hand the pixel array to {@link JpegEncoder}.
 * <p>
 * The caller blocks on a {@link CompletableFuture} until the JPEG temp file has
 * been fully written.
 */
public class Minecraft261ScreenshotProvider implements ScreenshotProvider {

    private static int clampDownscale(int requested, int width, int height) {
        if (requested < 1) return 1;
        for (int f = requested; f >= 1; f--) {
            if (width % f == 0 && height % f == 0) return f;
        }
        return 1;
    }

    @Override
    public Capture capture(int requestedDownscale, float quality, long timeoutMs) throws Exception {
        Minecraft mc = Minecraft.getInstance();
        if (mc.isSameThread()) {
            throw new IllegalStateException(
                    "26.1 screenshot capture must be requested off the Minecraft client thread; "
                            + "Screenshot.takeScreenshot completes through a later fenced GPU callback");
        }
        CompletableFuture<Capture> future = new CompletableFuture<>();

        mc.execute(() -> {
            try {
                RenderTarget target = mc.getMainRenderTarget();
                if (target == null) {
                    future.completeExceptionally(new IllegalStateException("Main render target is null"));
                    return;
                }

                int srcW = target.width;
                int srcH = target.height;
                int downscale = clampDownscale(requestedDownscale, srcW, srcH);

                Screenshot.takeScreenshot(target, downscale, image -> {
                    try {
                        int w = image.getWidth();
                        int h = image.getHeight();
                        int[] pixels = image.getPixels();

                        Path path = JpegEncoder.writeJpegTempFile(pixels, w, h, quality);
                        long size = Files.size(path);
                        future.complete(new Capture(path.toString(), w, h, size));
                    } catch (Throwable t) {
                        future.completeExceptionally(t);
                    } finally {
                        try {
                            image.close();
                        } catch (Throwable t) {
                            future.completeExceptionally(t);
                        }
                    }
                });
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });

        return future.get(timeoutMs, TimeUnit.MILLISECONDS);
    }
}
