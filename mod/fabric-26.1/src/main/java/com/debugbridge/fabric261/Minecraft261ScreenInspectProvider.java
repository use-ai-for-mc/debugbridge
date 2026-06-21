package com.debugbridge.fabric261;

import com.debugbridge.core.protocol.dto.ItemStackDto;
import com.debugbridge.core.protocol.dto.ScreenInspectDto;
import com.debugbridge.core.protocol.dto.SlotDto;
import com.debugbridge.core.screen.ScreenInspectProvider;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

public class Minecraft261ScreenInspectProvider implements ScreenInspectProvider {

    @Override
    public ScreenInspectDto inspectCurrentScreen() throws Exception {
        Minecraft mc = Minecraft.getInstance();
        if (mc.isSameThread()) {
            return collectCurrentScreen(mc);
        }

        CompletableFuture<ScreenInspectDto> future = new CompletableFuture<>();
        mc.execute(() -> {
            try {
                future.complete(collectCurrentScreen(mc));
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        });
        return future.get(2, TimeUnit.SECONDS);
    }

    private ScreenInspectDto collectCurrentScreen(Minecraft mc) {
        ScreenInspectDto dto = new ScreenInspectDto();
        Screen screen = mc.screen;
        if (screen == null) {
            dto.open = false;
            return dto;
        }
        dto.open = true;
        dto.type = screen.getClass().getName();
        dto.title = screen.getTitle().getString();

        if (screen instanceof AbstractContainerScreen<?> cs) {
            AbstractContainerMenu menu = cs.getMenu();
            dto.menuClass = menu.getClass().getName();
            int slotCount = menu.getItems().size();
            List<SlotDto> slots = new ArrayList<>(slotCount);
            for (int i = 0; i < slotCount; i++) {
                Slot slot = menu.getSlot(i);
                SlotDto s = new SlotDto();
                s.idx = i;
                s.container = slot.container.getClass().getName();
                ItemStack stack = slot.getItem();
                if (!stack.isEmpty()) {
                    ItemStackDto item = new ItemStackDto();
                    item.itemId = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
                    item.count = stack.getCount();
                    if (stack.isDamageableItem()) {
                        item.damage = stack.getDamageValue();
                        item.maxDamage = stack.getMaxDamage();
                    }
                    if (stack.has(DataComponents.CUSTOM_NAME)) {
                        item.name = stack.getHoverName().getString();
                    }
                    s.item = item;
                }
                slots.add(s);
            }
            dto.slots = slots;
        }
        return dto;
    }
}
