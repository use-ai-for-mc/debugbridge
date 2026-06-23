package com.debugbridge.fabric261;

import com.debugbridge.core.recording.FrameCapturer;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;

/**
 * 26.1 per-frame capture for the recording pipeline. Called from the
 * render thread (mixin tail of {@code Minecraft.runTick}); uses the
 * backend-neutral {@code Screenshot.takeScreenshot(target, downscale,
 * Consumer)} which fires the consumer once the image is available.
 *
 * <p>Mirrors {@link Minecraft261ScreenshotProvider} but skips the
 * {@code mc.execute} hop (we're already on the render thread) and hands raw
 * ARGB to the sink instead of writing a JPEG temp file.
 */
public final class Minecraft261FrameCapturer implements FrameCapturer {

    private static int clampDownscale(int requested, int width, int height) {
        if (requested < 1) return 1;
        for (int f = requested; f >= 1; f--) {
            if (width % f == 0 && height % f == 0) return f;
        }
        return 1;
    }

    @Override
    public void capture(int requestedDownscale, FrameSink sink) {
        Minecraft mc = Minecraft.getInstance();
        try {
            RenderTarget target = mc.getMainRenderTarget();
            if (target == null) {
                sink.onError(new IllegalStateException("Main render target is null"));
                return;
            }
            int srcW = target.width;
            int srcH = target.height;
            int downscale = clampDownscale(requestedDownscale, srcW, srcH);

            Screenshot.takeScreenshot(target, downscale, (NativeImage image) -> {
                try {
                    int w = image.getWidth();
                    int h = image.getHeight();
                    int[] pixels = image.getPixels();
                    // NativeImage#getPixels() returns a detached ARGB int[] in
                    // exact 26.1, so it is safe for RecordingSession to encode
                    // after this callback while we still close the NativeImage
                    // promptly below.
                    sink.onPixels(pixels, w, h);
                } catch (Throwable t) {
                    // Treat sink-side rejection as capture failure too. The
                    // recording session uses onError to decrement in-flight
                    // accounting and abort cleanly if the worker is already
                    // shutting down.
                    sink.onError(t);
                } finally {
                    try {
                        image.close();
                    } catch (Throwable ignored) {
                        // The FrameCapturer contract allows exactly one sink
                        // callback per capture. Do not emit a second failure if
                        // the image close itself fails after pixels/error.
                    }
                }
            });
        } catch (Throwable t) {
            sink.onError(t);
        }
    }
}
