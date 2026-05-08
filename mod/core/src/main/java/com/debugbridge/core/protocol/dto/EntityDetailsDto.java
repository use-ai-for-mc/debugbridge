package com.debugbridge.core.protocol.dto;

import java.util.List;
import java.util.Map;

/**
 * Wire shape for the {@code entityDetails} endpoint.
 *
 * <p>Two emit shapes:
 * <ul>
 *   <li><b>Gone</b> — entity no longer exists. Construct via {@link #gone()};
 *       serializes to {@code {"gone": true}}.
 *   <li><b>Present</b> — populated by the provider. Required: {@code entityId},
 *       {@code type}, {@code x/y/z}, {@code distance}, plus the always-present
 *       {@code isOnFire} and {@code isSprinting} state flags. All other fields
 *       are optional and drop on the wire when null.
 * </ul>
 *
 * <p>{@code type}, {@code vehicle}, and each entry of {@code passengers} carry
 * runtime class names from the provider; the handler runs them through the
 * mapping resolver before serialization.
 */
public final class EntityDetailsDto {
    public Boolean gone;

    public Integer entityId;
    public String type;
    public String customName;
    public Double x;
    public Double y;
    public Double z;
    public Double distance;

    // ItemFrame / Display.ItemDisplay variants.
    public EntityFrameItemDto frameItem;
    public EntityFrameItemDto displayItem;
    public String displayText;
    public String displayBlock;

    // LivingEntity variants.
    public Double health;
    public Double maxHealth;
    public Integer armor;
    /** Slot name → item ({@code HEAD}, {@code MAINHAND}, …). */
    public Map<String, EntityEquipmentItemDto> equipment;

    // State flags — always emitted on a present DTO. Defaults are fine when
    // unset (provider sets them explicitly).
    public Boolean isOnFire;
    public Boolean isSprinting;

    // Vehicle / passengers carry runtime class names; handler maps each.
    public String vehicle;
    public List<String> passengers;

    public List<String> tags;

    // Player-specific.
    public Boolean isPlayer;
    public String playerName;

    public static EntityDetailsDto gone() {
        EntityDetailsDto d = new EntityDetailsDto();
        d.gone = true;
        return d;
    }
}
