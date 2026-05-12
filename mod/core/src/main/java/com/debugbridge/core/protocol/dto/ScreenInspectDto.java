package com.debugbridge.core.protocol.dto;

import com.google.gson.JsonElement;

import java.util.List;

/**
 * Wire shape for the {@code screenInspect} endpoint.
 *
 * <p>When no screen is open, only {@code open=false} is emitted. When a screen
 * is open, {@code type} and {@code title} are populated; {@code menuClass} and
 * {@code slots} appear only for {@code AbstractContainerScreen} subclasses.
 * {@code icons} is added by the handler when the request set
 * {@code includeIcons=true}.
 *
 * <p>{@code type}, {@code menuClass}, and {@code slots[].container} carry
 * runtime class names from the provider; the handler runs them through the
 * mapping resolver before serialization.
 */
public final class ScreenInspectDto {
    public boolean open;
    public String type;
    public String title;
    public String menuClass;
    public List<SlotDto> slots;
    /**
     * Pass-through JSON map keyed by itemId; populated by the handler when
     * {@code includeIcons=true}. Shape: {@code {itemId: {base64Png, width,
     * height, spriteName?}}}. Stays as raw JSON (rather than a typed DTO)
     * because the icons sub-shape is owned by {@link
     * com.debugbridge.core.texture.ItemTextureProvider}, not this provider.
     */
    public JsonElement icons;
}
