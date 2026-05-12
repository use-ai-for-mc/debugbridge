package com.debugbridge.core.protocol.dto;

import java.util.List;

/**
 * Wire shape for the {@code chatHistory} endpoint.
 *
 * <p>Always emits both fields. {@code count} is redundant with
 * {@code messages.length} on the wire but kept for client convenience and
 * for parity with the existing schema.
 */
public final class ChatHistoryDto {
    public List<ChatMessageDto> messages;
    public int count;
    
    public ChatHistoryDto(List<ChatMessageDto> messages) {
        this.messages = messages;
        this.count = messages.size();
    }
}
