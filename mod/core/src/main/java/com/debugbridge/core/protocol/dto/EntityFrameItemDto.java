package com.debugbridge.core.protocol.dto;

/**
 * The item displayed by an {@code ItemFrame} ({@code entityDetails.frameItem})
 * or a 1.21.11 {@code Display.ItemDisplay} ({@code entityDetails.displayItem}).
 *
 * <p>Carries {@code count} (which {@link EntityEquipmentItemDto} omits) because
 * frames and item-displays can in principle hold a stack, and the shape was
 * defined that way historically. {@code itemId} uses
 * {@code Item#getDescriptionId()} (e.g. {@code item.minecraft.diamond}) — the
 * same convention as block-container items, distinct from {@link
 * EntityPrimaryEquipmentDto}'s registry-key form.
 */
public final class EntityFrameItemDto {
    public String itemId;
    public int count;
    public Integer damage;
    public Integer maxDamage;
    public String name;
}
