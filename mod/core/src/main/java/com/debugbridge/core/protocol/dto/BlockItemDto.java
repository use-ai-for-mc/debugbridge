package com.debugbridge.core.protocol.dto;

/**
 * One item in a block-entity container ({@code blockDetails.items[]}).
 *
 * <p>Note: deliberately distinct from {@link ItemStackDto}. Block-container
 * items carry a flat {@code slot} index (whereas screenInspect uses a wrapper
 * {@link SlotDto} around an {@link ItemStackDto}), and the {@code itemId}
 * here is currently {@code Item#getDescriptionId()} (e.g.
 * {@code "item.minecraft.diamond"}) rather than the registry key
 * (e.g. {@code "minecraft:diamond"}) used by screenInspect. The
 * inconsistency is preserved by Phase 1 (schema-lock) and tracked separately
 * for resolution.
 */
public final class BlockItemDto {
    public int slot;
    public String itemId;
    public int count;
    public Integer damage;
    public Integer maxDamage;
    public String name;
}
