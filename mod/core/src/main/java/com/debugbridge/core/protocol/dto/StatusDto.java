package com.debugbridge.core.protocol.dto;

/**
 * Wire shape for the {@code status} endpoint.
 *
 * <p>Field names match the JSON keys exactly (Gson default name mapping). Null
 * fields are omitted on the wire — use the no-args defaults for fields that
 * should be absent. The path/log fields are populated together when the host
 * mod has set a game directory; they're absent otherwise.
 *
 * <p>Serialized via the omit-nulls Gson in {@link
 * com.debugbridge.core.server.BridgeServer}.
 */
public final class StatusDto {
    public String version;
    public String mappingStatus; // "mojang" | "passthrough"
    public boolean obfuscated;
    public int refs;

    // Optional log-path block. Populated together when gameDir is non-null.
    public String gameDir;
    public String logsDir;
    public String latestLog;
    public Boolean latestLogExists;
    public String debugLog;
    public Boolean debugLogExists;
}
