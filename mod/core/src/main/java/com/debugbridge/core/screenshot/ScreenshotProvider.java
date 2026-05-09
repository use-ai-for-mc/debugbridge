package com.debugbridge.core.screenshot;

/**
 * Captures the contents of the main framebuffer to a JPEG file on disk.
 * <p>
 * Each version-specific mod provides its own implementation since framebuffer
 * access changed substantially between MC versions (CPU readback via
 * NativeImage.downloadTexture in 1.19; GPU CommandEncoder readback in 1.21.x).
 * <p>
 * Implementations are responsible for hopping to the render thread internally
 * and returning only after the file has been fully written.
 */
public interface ScreenshotProvider {
    /**
     * Capture the current framebuffer to a JPEG temp file.
     *
     * @param downscaleFactor 1 for full resolution, 2 for half each axis, etc.
     *                        Must evenly divide both width and height; if it
     *                        does not, implementations should fall back to the
     *                        largest factor in [1, requested] that does.
     * @param quality         JPEG quality in [0.0, 1.0]. Higher = larger file.
     * @param timeoutMs       maximum time to wait for the capture to complete.
     * @return information about the written file (absolute path + dimensions).
     * @throws Exception if the capture fails or times out.
     */
    Capture capture(int downscaleFactor, float quality, long timeoutMs) throws Exception;

    /**
     * Result of a successful capture.
     */
    final class Capture {
        public final String path;
        public final int width;
        public final int height;
        public final long sizeBytes;

        public Capture(String path, int width, int height, long sizeBytes) {
            this.path = path;
            this.width = width;
            this.height = height;
            this.sizeBytes = sizeBytes;
        }
    }
}
