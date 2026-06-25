package com.debugbridge.core.recording;

import com.debugbridge.core.screenshot.JpegEncoder;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * State machine for one in-flight {@code record_video} request. Created by
 * {@link RecordingProvider} per call; lives for the duration of the capture.
 *
 * <p>Frame lifecycle (per slot {@code i ∈ [0, frames)}):
 * <ol>
 *   <li>Render thread calls {@link #onFrame()}. Interval check decides skip/capture.
 *   <li>If capture: {@link FrameCapturer#capture} is invoked with a {@link FrameCapturer.FrameSink}.
 *   <li>The sink hands pixels to {@link #worker} (off-render-thread).
 *   <li>Worker writes per-frame JPEG ({@code frames} mode) or buffers ARGB ({@code grid} mode).
 *   <li>When the last frame is encoded, worker finalizes (grid composition if needed) and completes {@link #future}.
 * </ol>
 *
 * <p>Frame dimensions are locked at the first arriving frame. Any subsequent
 * frame with different dimensions aborts the recording with
 * {@link RecordingException.FramebufferResized}.
 */
final class RecordingSession {
    private static final Logger LOG = Logger.getLogger("DebugBridge");

    /**
     * Max concurrent in-flight captures when interval = "frame". One being
     * read back, one queued behind it. Beyond this, drop the request and
     * increment {@link #dropped}.
     */
    private static final int MAX_IN_FLIGHT = 2;

    private enum SessionState {
        ACTIVE,
        ABORTING,
        DONE,
        FAILED;
    }

    private final RecordingRequest req;
    private final Path recordingDir;
    private final FrameCapturer capturer;
    private final ExecutorService worker;
    private final CompletableFuture<RecordingResult> future = new CompletableFuture<>();

    /** Ordered slot for the next capture attempt. Render-thread driven. */
    private final AtomicInteger nextSlot = new AtomicInteger(0);
    /** Counts only successfully-encoded frames. Drives finalization. */
    private final AtomicInteger framesEncoded = new AtomicInteger(0);

    private final AtomicInteger inFlight = new AtomicInteger(0);
    private final AtomicInteger dropped = new AtomicInteger(0);
    private final AtomicReference<SessionState> state = new AtomicReference<>(SessionState.ACTIVE);

    /** Capture timestamps in ns; index by slot. Empty slots = 0. */
    private final long[] captureTimes;

    private volatile long lastCaptureNanos = 0L;
    private volatile long startNanos = 0L;
    private volatile long endNanos = 0L;

    /** Locked-in frame dimensions, set by the first arriving worker task. */
    private volatile int frameWidth = -1;

    private volatile int frameHeight = -1;

    // Output storage. Exactly one is populated based on req.output:
    private final int[][] gridPixels; // grid mode: slot → ARGB pixels (null in frames mode)
    private final Path[] framePaths; // frames mode: slot → file path  (null in grid mode)

    RecordingSession(RecordingRequest req, Path baseDir, FrameCapturer capturer) {
        this.req = req;
        this.recordingDir = baseDir.resolve(req.requestId);
        this.capturer = capturer;
        this.worker = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "DebugBridge-Recording-" + req.requestId);
            t.setDaemon(true);
            return t;
        });
        this.captureTimes = new long[req.frames];
        if (req.output == RecordingRequest.OutputMode.GRID) {
            this.gridPixels = new int[req.frames][];
            this.framePaths = null;
        } else {
            this.gridPixels = null;
            this.framePaths = new Path[req.frames];
        }
    }

    /**
     * Prepare the output directory. Throws before we start hooking the render
     * thread so the caller can return an early error.
     */
    void start() throws RecordingException {
        try {
            Files.createDirectories(recordingDir);
        } catch (IOException e) {
            throw new RecordingException.IoError("could not create " + recordingDir + ": " + e.getMessage(), e);
        }
        LOG.info("[DebugBridge] Recording " + req.requestId + " started: frames=" + req.frames + " interval="
                + (req.isFrameInterval() ? "frame" : req.intervalMs + "ms") + " output=" + req.output + " storage="
                + req.storage.wireName());
    }

    /**
     * Block the caller until the session resolves. Mapped exceptions surface
     * the protocol error code.
     */
    RecordingResult awaitResult() throws RecordingException, InterruptedException {
        try {
            return future.get();
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RecordingException re) throw re;
            if (cause instanceof RuntimeException rte) throw rte;
            throw new RecordingException.IoError(
                    "unexpected failure: " + (cause == null ? "null" : cause.getMessage()), cause);
        }
    }

    /**
     * Stop the worker executor. Called by the provider after
     * {@link #awaitResult()} returns (success or failure), so anything still
     * in flight is harmless.
     */
    void shutdown() {
        worker.shutdown();
        try {
            if (!worker.awaitTermination(5, TimeUnit.SECONDS)) {
                worker.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            worker.shutdownNow();
        }
    }

    /**
     * Render-thread tick. Decides whether to capture this frame.
     */
    void onFrame() {
        if (state.get() != SessionState.ACTIVE || future.isDone()) return;
        int slot = nextSlot.get();
        if (slot >= req.frames) return;

        long now = System.nanoTime();
        if (!req.isFrameInterval()) {
            // Numeric interval: skip until enough wall-clock time has elapsed.
            if (lastCaptureNanos != 0L) {
                long elapsedMs = (now - lastCaptureNanos) / 1_000_000L;
                if (elapsedMs < req.intervalMs) return;
            }
        } else {
            // "frame" interval: drop if encoder is falling behind to avoid
            // stalling the render thread.
            if (inFlight.get() >= MAX_IN_FLIGHT) {
                dropped.incrementAndGet();
                return;
            }
        }

        if (!nextSlot.compareAndSet(slot, slot + 1)) return;

        lastCaptureNanos = now;
        captureTimes[slot] = now;
        if (slot == 0) startNanos = now;
        inFlight.incrementAndGet();

        capturer.capture(req.downscale, new FrameCapturer.FrameSink() {
            @Override
            public void onPixels(int[] argb, int width, int height) {
                worker.submit(() -> handlePixels(slot, argb, width, height));
            }

            @Override
            public void onError(Throwable t) {
                inFlight.decrementAndGet();
                abortWith(new RecordingException.IoError("frame " + slot + " capture failed: " + t.getMessage(), t));
            }
        });
    }

    /** Worker-thread per-frame handler. Encodes / buffers / finalizes. */
    private void handlePixels(int slot, int[] argb, int width, int height) {
        inFlight.decrementAndGet();
        if (state.get() != SessionState.ACTIVE) return;

        // Lock dimensions on first arrival; reject any later frame that
        // disagrees (window-resize-mid-recording case).
        if (frameWidth == -1) {
            synchronized (this) {
                if (frameWidth == -1) {
                    frameWidth = width;
                    frameHeight = height;
                }
            }
        }
        if (width != frameWidth || height != frameHeight) {
            abortWith(new RecordingException.FramebufferResized(frameWidth, frameHeight, width, height));
            return;
        }

        try {
            if (req.output == RecordingRequest.OutputMode.FRAMES) {
                Path dest = recordingDir.resolve(String.format("frame-%04d.jpg", slot));
                JpegEncoder.writeJpeg(argb, width, height, dest, req.quality);
                framePaths[slot] = dest;
            } else {
                gridPixels[slot] = argb;
            }
        } catch (IOException e) {
            abortWith(new RecordingException.IoError("frame " + slot + " write failed: " + e.getMessage(), e));
            return;
        }

        int encoded = framesEncoded.incrementAndGet();
        if (encoded == req.frames) {
            endNanos = System.nanoTime();
            try {
                finalizeRecording();
            } catch (RecordingException e) {
                abortWith(e);
            } catch (Throwable t) {
                abortWith(new RecordingException.IoError("finalize failed: " + t.getMessage(), t));
            }
        }
    }

    /** Worker-thread finalization once all frames are encoded. */
    private void finalizeRecording() throws RecordingException, IOException {
        if (!state.compareAndSet(SessionState.ACTIVE, SessionState.DONE)) {
            return;
        }

        long captureMs = (endNanos - startNanos) / 1_000_000L;
        double meanIntervalMs = computeMeanIntervalMs();

        if (req.output == RecordingRequest.OutputMode.GRID) {
            BufferedImage composed =
                    GridComposer.compose(Arrays.asList(gridPixels), frameWidth, frameHeight, req.gridCols);
            Path gridPath = recordingDir.resolve("recording.jpg");
            try {
                JpegEncoder.writeJpeg(composed, gridPath, req.quality);
            } catch (IOException e) {
                throw new RecordingException.IoError("grid write failed: " + e.getMessage(), e);
            }
            long size = Files.size(gridPath);
            future.complete(new RecordingResult.Grid(
                    req.storage,
                    recordingDir.toAbsolutePath().toString(),
                    expiresAt(),
                    gridPath.toAbsolutePath().toString(),
                    composed.getWidth(),
                    composed.getHeight(),
                    size,
                    req.gridCols,
                    req.gridRows(),
                    frameWidth,
                    frameHeight,
                    req.frames,
                    captureMs,
                    meanIntervalMs,
                    dropped.get()));
        } else {
            long totalSize = 0;
            List<String> paths = new ArrayList<>(req.frames);
            for (Path p : framePaths) {
                paths.add(p.toAbsolutePath().toString());
                totalSize += Files.size(p);
            }
            future.complete(new RecordingResult.Frames(
                    req.storage,
                    recordingDir.toAbsolutePath().toString(),
                    expiresAt(),
                    paths,
                    totalSize,
                    frameWidth,
                    frameHeight,
                    req.frames,
                    captureMs,
                    meanIntervalMs,
                    dropped.get()));
        }
    }

    Path recordingDir() {
        return recordingDir;
    }

    private Instant expiresAt() {
        if (req.storage != RecordingRequest.Storage.TEMP) return null;
        return Instant.now().plusSeconds(req.ttlHours * 3600L);
    }

    private double computeMeanIntervalMs() {
        if (req.frames < 2) return 0.0;
        // First and last capture timestamps bound the wall-clock duration of
        // the captured interval; divide by gaps for the mean.
        long firstNs = captureTimes[0];
        long lastNs = captureTimes[req.frames - 1];
        if (firstNs == 0L || lastNs == 0L) return 0.0;
        double totalMs = (lastNs - firstNs) / 1_000_000.0;
        return totalMs / (req.frames - 1);
    }

    /**
     * Fail the recording and clean up any partial output. Idempotent; the
     * first abort wins, subsequent calls are no-ops.
     */
    private void abortWith(RecordingException reason) {
        if (!state.compareAndSet(SessionState.ACTIVE, SessionState.ABORTING)) return;
        cleanupPartialOutput();
        state.set(SessionState.FAILED);
        future.completeExceptionally(reason);
    }

    private void cleanupPartialOutput() {
        if (framePaths != null) {
            for (Path p : framePaths) {
                if (p == null) continue;
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    LOG.log(Level.WARNING, "[DebugBridge] Could not delete partial frame " + p, e);
                }
            }
        }
        Path gridFile = recordingDir.resolve("recording.jpg");
        try {
            Files.deleteIfExists(gridFile);
        } catch (IOException e) {
            LOG.log(Level.WARNING, "[DebugBridge] Could not delete partial grid " + gridFile, e);
        }
        try {
            Files.deleteIfExists(recordingDir);
        } catch (IOException e) {
            // Non-empty (e.g. user dropped a file in there) — leave it.
            LOG.log(Level.FINE, "[DebugBridge] Recording dir not empty after cleanup: " + recordingDir, e);
        }
    }
}
