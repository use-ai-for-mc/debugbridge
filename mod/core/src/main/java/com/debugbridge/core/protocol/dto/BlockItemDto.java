package com.debugbridge.core.protocol.dto;

/**
 * One item in a block-entity container ({@code blockDetails.items[]}).
 *
 * <p>Note: deliberately distinct from {@link ItemStackDto}. Block-container
 * items carry a flat {@code slot} index, whereas screenInspect uses a wrapper
 * {@link SlotDto} around an {@link ItemStackDto}. {@code itemId} is the
 * canonical registry-key form (e.g. {@code "minecraft:diamond"}), matching
 * every other {@code itemId} on the wire and accepted directly by the
 * texture-fetch endpoints.
 */
public final class BlockItemDto {
    public int slot;
    public String itemId;
    public int count;
    public Integer damage;
    public Integer maxDamage;
    public String name;
}
