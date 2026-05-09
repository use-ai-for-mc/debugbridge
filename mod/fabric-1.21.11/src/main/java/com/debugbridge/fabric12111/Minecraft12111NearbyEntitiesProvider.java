package com.debugbridge.fabric12111;

import com.debugbridge.core.entity.NearbyEntitiesProvider;
import com.debugbridge.core.protocol.dto.EntityDetailsDto;
import com.debugbridge.core.protocol.dto.EntityEquipmentItemDto;
import com.debugbridge.core.protocol.dto.EntityFrameItemDto;
import com.debugbridge.core.protocol.dto.EntityPrimaryEquipmentDto;
import com.debugbridge.core.protocol.dto.EntitySummaryDto;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Native nearby-entities query for Minecraft 1.21.11.
 * Runs entity iteration directly in Java, avoiding the overhead of
 * per-entity Lua-to-Java bridge calls.
 */
public class Minecraft12111NearbyEntitiesProvider implements NearbyEntitiesProvider {

    @Override
    public List<EntitySummaryDto> getNearbyEntities(double range, int limit) throws Exception {
        Minecraft mc = Minecraft.getInstance();
        CompletableFuture<List<EntitySummaryDto>> future = new CompletableFuture<>();

        mc.execute(() -> {
            try {
                if (mc.player == null || mc.level == null) {
                    future.complete(Collections.emptyList());
                    return;
                }

                double px = mc.player.getX();
                double py = mc.player.getY();
                double pz = mc.player.getZ();
                double rangeSq = range * range;

                List<EntityEntry> entries = new ArrayList<>();
                for (Entity entity : mc.level.entitiesForRendering()) {
                    if (entity == mc.player) continue;
                    double dx = entity.getX() - px;
                    double dy = entity.getY() - py;
                    double dz = entity.getZ() - pz;
                    double distSq = dx * dx + dy * dy + dz * dz;
                    if (distSq <= rangeSq) {
                        entries.add(new EntityEntry(entity, Math.sqrt(distSq)));
                    }
                }

                entries.sort(Comparator.comparingDouble(e -> e.distance));

                List<EntitySummaryDto> out = new ArrayList<>(Math.min(limit, entries.size()));
                int count = 0;
                for (EntityEntry entry : entries) {
                    if (count >= limit) break;
                    Entity entity = entry.entity;

                    EntitySummaryDto dto = new EntitySummaryDto();
                    dto.id = entity.getId();
                    dto.type = entity.getClass().getName();
                    dto.distance = Math.round(entry.distance * 10.0) / 10.0;
                    dto.x = Math.round(entity.getX() * 10.0) / 10.0;
                    dto.y = Math.round(entity.getY() * 10.0) / 10.0;
                    dto.z = Math.round(entity.getZ() * 10.0) / 10.0;

                    var customName = entity.getCustomName();
                    if (customName != null) dto.customName = customName.getString();

                    var typeKey = entity.getType().getDescriptionId();
                    if (typeKey != null) dto.typeId = typeKey;

                    if (entity instanceof LivingEntity living) {
                        dto.primaryEquipment = pickPrimaryEquipment(living);
                    } else if (entity instanceof ItemFrame frame) {
                        dto.primaryEquipment = buildPrimary("FRAME", frame.getItem());
                    } else if (entity instanceof Display.ItemDisplay itemDisplay) {
                        var renderState = itemDisplay.itemRenderState();
                        if (renderState != null && renderState.itemStack() != null) {
                            dto.primaryEquipment = buildPrimary("DISPLAY", renderState.itemStack());
                        }
                    }

                    out.add(dto);
                    count++;
                }

                future.complete(out);
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        });

        return future.get(5, TimeUnit.SECONDS);
    }

    @Override
    public EntityDetailsDto getEntityDetails(int entityId) throws Exception {
        Minecraft mc = Minecraft.getInstance();
        CompletableFuture<EntityDetailsDto> future = new CompletableFuture<>();

        mc.execute(() -> {
            try {
                if (mc.player == null || mc.level == null) {
                    future.complete(null);
                    return;
                }

                Entity target = null;
                for (Entity entity : mc.level.entitiesForRendering()) {
                    if (entity.getId() == entityId) { target = entity; break; }
                }
                if (target == null) {
                    future.complete(null);
                    return;
                }

                EntityDetailsDto dto = new EntityDetailsDto();
                dto.entityId = target.getId();
                dto.type = target.getClass().getName();
                var customName = target.getCustomName();
                if (customName != null) dto.customName = customName.getString();
                dto.x = target.getX();
                dto.y = target.getY();
                dto.z = target.getZ();
                dto.distance = Math.round(target.distanceTo(mc.player) * 10.0) / 10.0;

                if (target instanceof ItemFrame frame) {
                    ItemStack framed = frame.getItem();
                    if (framed != null && !framed.isEmpty()) {
                        dto.frameItem = buildFrameItem(framed);
                    }
                }

                if (target instanceof LivingEntity living) {
                    dto.health = (double) Math.round(living.getHealth() * 10.0) / 10.0;
                    dto.maxHealth = (double) Math.round(living.getMaxHealth() * 10.0) / 10.0;
                    dto.armor = living.getArmorValue();

                    Map<String, EntityEquipmentItemDto> equipment = new LinkedHashMap<>();
                    addEquipment(equipment, "MAINHAND", living, EquipmentSlot.MAINHAND);
                    addEquipment(equipment, "OFFHAND", living, EquipmentSlot.OFFHAND);
                    addEquipment(equipment, "HEAD", living, EquipmentSlot.HEAD);
                    addEquipment(equipment, "CHEST", living, EquipmentSlot.CHEST);
                    addEquipment(equipment, "LEGS", living, EquipmentSlot.LEGS);
                    addEquipment(equipment, "FEET", living, EquipmentSlot.FEET);
                    if (!equipment.isEmpty()) dto.equipment = equipment;
                }

                extractDisplayData(dto, target);

                dto.isOnFire = target.isOnFire();
                dto.isSprinting = target.isSprinting();

                Entity vehicle = target.getVehicle();
                if (vehicle != null) dto.vehicle = vehicle.getClass().getName();

                if (!target.getPassengers().isEmpty()) {
                    List<String> passengers = new ArrayList<>();
                    for (Entity p : target.getPassengers()) passengers.add(p.getClass().getName());
                    dto.passengers = passengers;
                }

                if (!target.getTags().isEmpty()) {
                    dto.tags = new ArrayList<>(target.getTags());
                }

                if (target instanceof Player player) {
                    dto.isPlayer = true;
                    dto.playerName = player.getGameProfile().name();
                }

                future.complete(dto);
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        });

        return future.get(5, TimeUnit.SECONDS);
    }

    private void extractDisplayData(EntityDetailsDto dto, Entity target) {
        try {
            if (target instanceof Display.ItemDisplay itemDisplay) {
                var renderState = itemDisplay.itemRenderState();
                if (renderState != null) {
                    ItemStack stack = renderState.itemStack();
                    if (stack != null && !stack.isEmpty()) {
                        EntityFrameItemDto item = new EntityFrameItemDto();
                        item.itemId = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
                        item.count = stack.getCount();
                        var hoverName = stack.getHoverName();
                        if (hoverName != null) item.name = hoverName.getString();
                        // Display item shape historically omitted damage/maxDamage
                        // even when applicable; preserved here.
                        dto.displayItem = item;
                    }
                }
            } else if (target instanceof Display.TextDisplay textDisplay) {
                var renderState = textDisplay.textRenderState();
                if (renderState != null && renderState.text() != null) {
                    dto.displayText = renderState.text().getString();
                }
            } else if (target instanceof Display.BlockDisplay blockDisplay) {
                var renderState = blockDisplay.blockRenderState();
                if (renderState != null && renderState.blockState() != null) {
                    dto.displayBlock = renderState.blockState().getBlock().getDescriptionId();
                }
            }
        } catch (Exception ignored) {
            // Render states may not be populated yet; ignore silently.
        }
    }

    private EntityFrameItemDto buildFrameItem(ItemStack stack) {
        EntityFrameItemDto item = new EntityFrameItemDto();
        item.itemId = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        item.count = stack.getCount();
        if (stack.getMaxDamage() > 0) {
            item.damage = stack.getDamageValue();
            item.maxDamage = stack.getMaxDamage();
        }
        var hoverName = stack.getHoverName();
        if (hoverName != null) item.name = hoverName.getString();
        return item;
    }

    private void addEquipment(Map<String, EntityEquipmentItemDto> equipment,
                              String slotName, LivingEntity living, EquipmentSlot slot) {
        ItemStack stack = living.getItemBySlot(slot);
        if (stack != null && !stack.isEmpty()) {
            EntityEquipmentItemDto item = new EntityEquipmentItemDto();
            item.itemId = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
            if (stack.getMaxDamage() > 0) {
                item.damage = stack.getDamageValue();
                item.maxDamage = stack.getMaxDamage();
            }
            if (stack.has(DataComponents.CUSTOM_NAME)) {
                item.name = stack.getHoverName().getString();
            }
            equipment.put(slotName, item);
        }
    }

    private static final EquipmentSlot[] PRIMARY_SLOT_ORDER = {
        EquipmentSlot.HEAD,
        EquipmentSlot.MAINHAND,
        EquipmentSlot.OFFHAND,
        EquipmentSlot.CHEST,
        EquipmentSlot.LEGS,
        EquipmentSlot.FEET,
    };

    private EntityPrimaryEquipmentDto pickPrimaryEquipment(LivingEntity living) {
        for (EquipmentSlot slot : PRIMARY_SLOT_ORDER) {
            EntityPrimaryEquipmentDto dto = buildPrimary(slot.name(), living.getItemBySlot(slot));
            if (dto != null) return dto;
        }
        return null;
    }

    private EntityPrimaryEquipmentDto buildPrimary(String slot, ItemStack stack) {
        if (stack == null || stack.isEmpty()) return null;
        var key = BuiltInRegistries.ITEM.getKey(stack.getItem());
        EntityPrimaryEquipmentDto dto = new EntityPrimaryEquipmentDto();
        dto.slot = slot;
        dto.itemId = key.toString();
        return dto;
    }

    private record EntityEntry(Entity entity, double distance) {}
}
