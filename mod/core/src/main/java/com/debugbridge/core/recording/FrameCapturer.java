package com.debugbridge.core.recording;

/**
 * Version-specific frame capture primitive used by the shared
 * {@link RecordingProvider}. One implementation per Fabric module: each wraps
 * the MC version's screenshot/readback API.
 *
 * <p>The implementation must be safe to call from the render thread (it's
 * invoked from the {@code runTick} mixin hook). Capture completion is
 * asynchronous on versions whose readback is GPU-backed (1.21.11, 26.1,
 * 26.2-dev); synchronous on older versions (1.19). Either way, exactly one of
 * {@link FrameSink#onPixels} / {@link FrameSink#onError} must fire per call.
 */
public interface FrameCapturer {
    /**
     * Kick off a single-frame capture. Returns quickly; the sink fires when
     * pixels are ready (or on failure). The sink may be invoked on any thread.
     *
     * @param downscaleFactor 1 for full resolution, 2 for half each axis, etc.
     *                        Implementations should clamp to a factor that
     *                        evenly divides the framebuffer dimensions, same
     *                        as {@code ScreenshotProvider}.
     * @param sink            receives the ARGB pixel array, dimensions, or
     *                        a failure.
     */
    void capture(int downscaleFactor, FrameSink sink);

    /**
     * Async result of a {@link #capture} call. Exactly one method must fire
     * per capture.
     */
    interface FrameSink {
        /**
         * Pixels are ready.
         *
         * @param argb   row-major ARGB ({@code 0xAARRGGBB}); length == {@code width * height}.
         * @param width  pixel width after downscale.
         * @param height pixel height after downscale.
         */
        void onPixels(int[] argb, int width, int height);

        /** Capture failed. */
        void onError(Throwable t);
    }
}
