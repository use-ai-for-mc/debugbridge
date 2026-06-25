package com.debugbridge.core.recording;

/**
 * Validated parameters for one {@code record_video} request. Built and
 * validated by {@code BridgeServer.handleRecordVideo} before the recording
 * pipeline sees the payload; field values are already in-range and defaulted.
 *
 * <p>See {@code RECORD_VIDEO_PROTOCOL.md} §1 / §3 for the wire shape and caps.
 */
public final class RecordingRequest {
    /** Hard cap on {@code frames} per the protocol (5s at 60Hz). */
    public static final int MAX_FRAMES = 300;

    public static final int DEFAULT_TEMP_TTL_HOURS = 24;
    public static final int MIN_TEMP_TTL_HOURS = 1;
    public static final int MAX_TEMP_TTL_HOURS = 24 * 7;

    /** Capture every render tick. */
    public static final long INTERVAL_EVERY_FRAME = -1L;

    public final String requestId;
    public final Storage storage;
    public final int ttlHours;
    public final int frames;
    /** Either {@link #INTERVAL_EVERY_FRAME} ("frame" mode) or a positive ms gap. */
    public final long intervalMs;

    public final OutputMode output;
    public final int gridCols;
    public final int downscale;
    public final float quality;

    public RecordingRequest(
            String requestId,
            Storage storage,
            int ttlHours,
            int frames,
            long intervalMs,
            OutputMode output,
            int gridCols,
            int downscale,
            float quality) {
        this.requestId = requestId;
        this.storage = storage;
        this.ttlHours = ttlHours;
        this.frames = frames;
        this.intervalMs = intervalMs;
        this.output = output;
        this.gridCols = gridCols;
        this.downscale = downscale;
        this.quality = quality;
    }

    public boolean isFrameInterval() {
        return intervalMs == INTERVAL_EVERY_FRAME;
    }

    public int gridRows() {
        return (frames + gridCols - 1) / gridCols;
    }

    public enum OutputMode {
        GRID,
        FRAMES
    }

    public enum Storage {
        TEMP("temp"),
        PERSISTENT("persistent");

        private final String wireName;

        Storage(String wireName) {
            this.wireName = wireName;
        }

        public String wireName() {
            return wireName;
        }

        public static Storage fromWireName(String value) {
            return switch (value) {
                case "temp" -> TEMP;
                case "persistent" -> PERSISTENT;
                default ->
                    throw new IllegalArgumentException(
                            "storage must be \"temp\" or \"persistent\", got \"" + value + "\"");
            };
        }
    }
}
