package com.debugbridge.core.screen;

import com.debugbridge.core.protocol.dto.ScreenInspectDto;

/**
 * Inspects the screen the player currently has open. For container screens
 * (chests, anvils, brewing stands, etc.) emits per-slot item info in a single
 * native pass — avoids the per-call Java↔Lua bridge cost that times out
 * when iterating slots from Lua.
 */
public interface ScreenInspectProvider {
    
    /**
     * Snapshot the currently-open screen.
     *
     * <p>Implementations populate runtime class names ({@code dto.type},
     * {@code dto.menuClass}, {@code dto.slots[].container}) directly from
     * {@code Class#getName()}. {@link
     * com.debugbridge.core.server.BridgeServer} runs them through the mapping
     * resolver before serialization, so providers don't need to know about
     * Mojang vs intermediary names.
     *
     * @return populated DTO. When no screen is displayed, returns
     * {@code new ScreenInspectDto()} with {@code open=false}.
     * @throws Exception on query failure
     */
    ScreenInspectDto inspectCurrentScreen() throws Exception;
}
