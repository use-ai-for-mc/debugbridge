package com.debugbridge.core.protocol.dto;

import com.google.gson.JsonElement;

/**
 * One entry in the {@code chatHistory} response.
 *
 * <p>Fields:
 * <ul>
 *   <li>{@code plain} — always present; flat-text view of the message.</li>
 *   <li>{@code addedTime} — usually present; the tick on which the message was
 *       added to the chat overlay. Null when the underlying reflective field
 *       lookup misses (the 1.19 path can hit this); omitted on the wire.</li>
 *   <li>{@code json} — present only when the request set {@code includeJson};
 *       arbitrary JSON for the styled {@code Component} (colors, click events,
 *       hover events). Omitted on the wire when null.</li>
 * </ul>
 *
 * <p>Serialized via the omit-nulls Gson in {@link
 * com.debugbridge.core.server.BridgeServer}.
 */
public final class ChatMessageDto {
    public String plain;
    public Integer addedTime;
    public JsonElement json;
}
