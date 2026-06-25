package com.debugbridge.core.recording;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Parser and validation for {@code record_video} payloads.
 */
public final class RecordingRequestParams {

    private static final int MAX_RECORDING_REQUEST_ID_LEN = 128;

    private RecordingRequestParams() {}

    public static RecordingRequest fromPayload(String requestId, JsonObject payload) {
        JsonObject p = payload;
        if (p == null || !p.has("frames")) {
            throw new IllegalArgumentException("'frames' is required");
        }

        int frames;
        try {
            frames = p.get("frames").getAsInt();
        } catch (Exception e) {
            throw new IllegalArgumentException("'frames' must be an integer");
        }
        if (frames < 1) {
            throw new IllegalArgumentException("frames=" + frames + " must be >= 1");
        }
        if (frames > RecordingRequest.MAX_FRAMES) {
            throw new IllegalArgumentException(
                    "frames=" + frames + " exceeds MAX_FRAMES=" + RecordingRequest.MAX_FRAMES);
        }

        long intervalMs = RecordingRequest.INTERVAL_EVERY_FRAME;
        if (p.has("interval") && !p.get("interval").isJsonNull()) {
            JsonElement iv = p.get("interval");
            if (iv.isJsonPrimitive() && iv.getAsJsonPrimitive().isString()) {
                String s = iv.getAsString();
                if (!"frame".equals(s)) {
                    throw new IllegalArgumentException("interval string must be \"frame\", got \"" + s + "\"");
                }
                // intervalMs stays at INTERVAL_EVERY_FRAME.
            } else if (iv.isJsonPrimitive() && iv.getAsJsonPrimitive().isNumber()) {
                double ms = iv.getAsDouble();
                if (ms < 1.0) {
                    throw new IllegalArgumentException("interval=" + ms + " must be >= 1 ms");
                }
                intervalMs = Math.round(ms);
            } else {
                throw new IllegalArgumentException("interval must be \"frame\" or a number of ms");
            }
        }

        RecordingRequest.OutputMode output = RecordingRequest.OutputMode.GRID;
        if (p.has("output") && !p.get("output").isJsonNull()) {
            String s = p.get("output").getAsString();
            switch (s) {
                case "grid" -> output = RecordingRequest.OutputMode.GRID;
                case "frames" -> output = RecordingRequest.OutputMode.FRAMES;
                default ->
                    throw new IllegalArgumentException("output must be \"grid\" or \"frames\", got \"" + s + "\"");
            }
        }

        int gridCols = (int) Math.max(1, Math.ceil(Math.sqrt(frames)));
        if (p.has("gridCols") && !p.get("gridCols").isJsonNull()) {
            try {
                gridCols = p.get("gridCols").getAsInt();
            } catch (Exception e) {
                throw new IllegalArgumentException("'gridCols' must be an integer");
            }
            if (gridCols < 1 || gridCols > frames) {
                throw new IllegalArgumentException("gridCols=" + gridCols + " must be in [1, " + frames + "]");
            }
        }

        int downscale = 2;
        if (p.has("downscale") && !p.get("downscale").isJsonNull()) {
            try {
                downscale = p.get("downscale").getAsInt();
            } catch (Exception e) {
                throw new IllegalArgumentException("'downscale' must be an integer");
            }
            if (downscale < 1) {
                throw new IllegalArgumentException("downscale=" + downscale + " must be >= 1");
            }
        }

        float quality = 0.75f;
        if (p.has("quality") && !p.get("quality").isJsonNull()) {
            try {
                quality = p.get("quality").getAsFloat();
            } catch (Exception e) {
                throw new IllegalArgumentException("'quality' must be a number");
            }
            if (quality < 0.05f || quality > 1.0f) {
                throw new IllegalArgumentException("quality=" + quality + " must be in [0.05, 1.0]");
            }
        }

        RecordingRequest.Storage storage = RecordingRequest.Storage.TEMP;
        if (p.has("storage") && !p.get("storage").isJsonNull()) {
            if (!p.get("storage").isJsonPrimitive()
                    || !p.get("storage").getAsJsonPrimitive().isString()) {
                throw new IllegalArgumentException("storage must be \"temp\" or \"persistent\"");
            }
            storage = RecordingRequest.Storage.fromWireName(p.get("storage").getAsString());
        }

        int ttlHours = RecordingRequest.DEFAULT_TEMP_TTL_HOURS;
        if (p.has("ttlHours") && !p.get("ttlHours").isJsonNull()) {
            try {
                ttlHours = p.get("ttlHours").getAsInt();
            } catch (Exception e) {
                throw new IllegalArgumentException("'ttlHours' must be an integer");
            }
            if (ttlHours < RecordingRequest.MIN_TEMP_TTL_HOURS || ttlHours > RecordingRequest.MAX_TEMP_TTL_HOURS) {
                throw new IllegalArgumentException("ttlHours=" + ttlHours + " must be in ["
                        + RecordingRequest.MIN_TEMP_TTL_HOURS + ", " + RecordingRequest.MAX_TEMP_TTL_HOURS + "]");
            }
        }

        return new RecordingRequest(
                requestIdFromPayload(requestId, p),
                storage,
                ttlHours,
                frames,
                intervalMs,
                output,
                gridCols,
                downscale,
                quality);
    }

    private static String requestIdFromPayload(String requestId, JsonObject payload) {
        if (payload.has("requestId") && !payload.get("requestId").isJsonNull()) {
            if (!payload.get("requestId").isJsonPrimitive()
                    || !payload.get("requestId").getAsJsonPrimitive().isString()) {
                throw new IllegalArgumentException("'requestId' must be a string");
            }
            return sanitizeRequestId(payload.get("requestId").getAsString());
        }
        return sanitizeRequestId(
                (requestId != null && !requestId.isBlank()) ? requestId : "rec-" + System.currentTimeMillis());
    }

    private static String sanitizeRequestId(String id) {
        StringBuilder sb = new StringBuilder(id.length());
        for (int i = 0; i < id.length(); i++) {
            char c = id.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '-' || c == '_' || c == '.') {
                sb.append(c);
            } else {
                sb.append('_');
            }
        }
        String sanitized = sb.toString();
        if (sanitized.length() > MAX_RECORDING_REQUEST_ID_LEN) {
            sanitized = sanitized.substring(0, MAX_RECORDING_REQUEST_ID_LEN);
        }
        if (sanitized.isEmpty() || sanitized.chars().allMatch(c -> c == '.')) {
            return "rec-" + System.currentTimeMillis();
        }
        return sanitized;
    }
}
