package com.debugbridge.core.recording;

import java.time.Instant;
import java.util.List;

/**
 * Result of a successful recording. Two flavors map to the two
 * {@code output} modes in the protocol — {@link Grid} composes all frames
 * into one JPEG, {@link Frames} writes one JPEG per frame.
 */
public abstract class RecordingResult {
    public final RecordingRequest.Storage storage;
    public final String directory;
    public final Instant expiresAt;
    public final int frameWidth;
    public final int frameHeight;
    public final int frameCount;
    public final long captureMs;
    public final double meanIntervalMs;
    public final int dropped;

    protected RecordingResult(
            RecordingRequest.Storage storage,
            String directory,
            Instant expiresAt,
            int frameWidth,
            int frameHeight,
            int frameCount,
            long captureMs,
            double meanIntervalMs,
            int dropped) {
        this.storage = storage;
        this.directory = directory;
        this.expiresAt = expiresAt;
        this.frameWidth = frameWidth;
        this.frameHeight = frameHeight;
        this.frameCount = frameCount;
        this.captureMs = captureMs;
        this.meanIntervalMs = meanIntervalMs;
        this.dropped = dropped;
    }

    public static final class Grid extends RecordingResult {
        public final String path;
        public final int width;
        public final int height;
        public final long sizeBytes;
        public final int gridCols;
        public final int gridRows;

        public Grid(
                RecordingRequest.Storage storage,
                String directory,
                Instant expiresAt,
                String path,
                int width,
                int height,
                long sizeBytes,
                int gridCols,
                int gridRows,
                int frameWidth,
                int frameHeight,
                int frameCount,
                long captureMs,
                double meanIntervalMs,
                int dropped) {
            super(
                    storage,
                    directory,
                    expiresAt,
                    frameWidth,
                    frameHeight,
                    frameCount,
                    captureMs,
                    meanIntervalMs,
                    dropped);
            this.path = path;
            this.width = width;
            this.height = height;
            this.sizeBytes = sizeBytes;
            this.gridCols = gridCols;
            this.gridRows = gridRows;
        }
    }

    public static final class Frames extends RecordingResult {
        public final List<String> paths;
        public final long sizeBytes;

        public Frames(
                RecordingRequest.Storage storage,
                String directory,
                Instant expiresAt,
                List<String> paths,
                long sizeBytes,
                int frameWidth,
                int frameHeight,
                int frameCount,
                long captureMs,
                double meanIntervalMs,
                int dropped) {
            super(
                    storage,
                    directory,
                    expiresAt,
                    frameWidth,
                    frameHeight,
                    frameCount,
                    captureMs,
                    meanIntervalMs,
                    dropped);
            this.paths = paths;
            this.sizeBytes = sizeBytes;
        }
    }
}
