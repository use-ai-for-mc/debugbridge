package com.debugbridge.core.protocol.dto;

/**
 * Wire shape for one item stack in a container slot or entity equipment slot.
 *
 * <p>{@code itemId} and {@code count} are always present. {@code damage} +
 * {@code maxDamage} appear together for damageable items; {@code name} appears
 * only for items with a custom name. All other fields are omitted on the wire
 * via the omit-nulls Gson.
 */
public final class ItemStackDto {
    public String itemId;
    public int count;
    public Integer damage;
    public Integer maxDamage;
    public String name;
}
