package com.debugbridge.fabric12111;

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
 * 1.21.11 framebuffer capture as JPEG.
 * <p>
 * Uses {@link Screenshot#takeScreenshot(RenderTarget, int, java.util.function.Consumer)}
 * which submits a GPU command-encoder readback. The Consumer fires once the
 * GPU buffer copy completes (typically a frame later); from inside that
 * callback we extract ARGB pixels via {@link NativeImage#getPixels()},
 * release the NativeImage, and hand the pixel array to {@link JpegEncoder}.
 * <p>
 * The WebSocket worker thread blocks on a {@link CompletableFuture} until the
 * file is fully written.
 */
public class Minecraft12111ScreenshotProvider implements ScreenshotProvider {
    
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
        CompletableFuture<Capture> future = new CompletableFuture<>();
        
        mc.execute(() -> {
            try {
                RenderTarget target = mc.getMainRenderTarget();
                if (target == null) {
                    future.completeExceptionally(
                            new IllegalStateException("Main render target is null"));
                    return;
                }
                int srcW = target.width;
                int srcH = target.height;
                int downscale = clampDownscale(requestedDownscale, srcW, srcH);
                
                Screenshot.takeScreenshot(target, downscale, image -> {
                    try {
                        int w = image.getWidth();
                        int h = image.getHeight();
                        int[] pixels = image.getPixels();   // ARGB
                        // NativeImage no longer needed; release immediately so the
                        // off-heap buffer doesn't outlive the JPEG encode.
                        image.close();
                        
                        Path path = JpegEncoder.writeJpegTempFile(pixels, w, h, quality);
                        long size = Files.size(path);
                        future.complete(new Capture(path.toString(), w, h, size));
                    } catch (Throwable t) {
                        future.completeExceptionally(t);
                    }
                });
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        
        return future.get(timeoutMs, TimeUnit.MILLISECONDS);
    }
}
