package com.debugbridge.fabric262;

import com.debugbridge.core.texture.ItemTextureProvider;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.PoseStack;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.client.renderer.item.TrackingItemStackRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.item.*;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import org.joml.Vector4f;

/**
 * Renders items to an offscreen GPU texture through Minecraft's renderer and
 * reads the result back through the backend-neutral GPU abstraction.
 */
public class Minecraft262ItemTextureProvider implements ItemTextureProvider {
    private static final int TEXTURE_SIZE = 32;
    private static final int MAP_SIZE = 128;
    private static final int[] BRIGHTNESS_MOD = {180, 220, 255, 135};

    private static int mapPixelArgb(byte packedColor) {
        int colorId = (packedColor & 0xFF) >> 2;
        int shade = packedColor & 3;
        if (colorId == 0) return 0;
        MapColor color = MapColor.byId(colorId);
        if (color == null) return 0;
        int col = color.col;
        int modifier = BRIGHTNESS_MOD[shade];
        int r = ((col >> 16) & 255) * modifier / 255;
        int g = ((col >> 8) & 255) * modifier / 255;
        int b = (col & 255) * modifier / 255;
        return (0xFF << 24) | (r << 16) | (g << 8) | b;
    }

    @Override
    public TextureResult getItemTexture(int slot) throws Exception {
        Minecraft mc = Minecraft.getInstance();
        return renderSlot(() -> {
            if (mc.player == null) throw new Exception("Player not available");
            ItemStack stack = mc.player.getInventory().getItem(slot);
            if (stack.isEmpty()) throw new Exception("Slot " + slot + " is empty");
            return stack;
        });
    }

    @Override
    public TextureResult getItemTextureById(String itemId) throws Exception {
        return renderSlot(() -> {
            Identifier key = Identifier.tryParse(itemId);
            if (key == null) throw new Exception("Invalid item id: " + itemId);
            if (!BuiltInRegistries.ITEM.containsKey(key)) {
                throw new Exception("Unknown item: " + itemId);
            }
            Item item = BuiltInRegistries.ITEM.getValue(key);
            DataComponentMap components = DataComponentMap.builder()
                    .addAll(DataComponents.COMMON_ITEM_COMPONENTS)
                    .set(DataComponents.ITEM_NAME, Component.translatable(item.getDescriptionId()))
                    .set(DataComponents.ITEM_MODEL, key)
                    .build();
            return new ItemStack(Holder.direct(item, components));
        });
    }

    @Override
    public TextureResult getEntityItemTexture(int entityId, String slotName) throws Exception {
        Minecraft mc = Minecraft.getInstance();
        return renderSlot(() -> {
            if (mc.level == null) throw new Exception("Level not loaded");

            Entity target = null;
            for (Entity e : mc.level.entitiesForRendering()) {
                if (e.getId() == entityId) {
                    target = e;
                    break;
                }
            }
            if (target == null) throw new Exception("Entity " + entityId + " not found");

            ItemStack stack;
            switch (target) {
                case ItemFrame frame when "FRAME".equals(slotName) -> stack = frame.getItem();
                case Display.ItemDisplay itemDisplay
                when "DISPLAY".equals(slotName) -> {
                    var renderState = itemDisplay.itemRenderState();
                    if (renderState == null || renderState.itemStack() == null) {
                        throw new Exception("ItemDisplay render state not ready");
                    }
                    stack = renderState.itemStack();
                }
                case LivingEntity living -> {
                    EquipmentSlot slot;
                    try {
                        slot = EquipmentSlot.valueOf(slotName);
                    } catch (IllegalArgumentException e) {
                        throw new Exception("Unknown slot " + slotName);
                    }
                    stack = living.getItemBySlot(slot);
                }
                default -> throw new Exception("Entity " + entityId + " has no equipment");
            }

            if (stack.isEmpty()) {
                throw new Exception("Slot " + slotName + " is empty on entity " + entityId);
            }
            return stack;
        });
    }

    private TextureResult renderSlot(StackSupplier supplier) throws Exception {
        Minecraft mc = Minecraft.getInstance();
        CompletableFuture<TextureResult> future = new CompletableFuture<>();

        mc.execute(() -> {
            GpuTexture colorTex = null;
            GpuTextureView colorView = null;
            GpuTexture depthTex = null;
            GpuTextureView depthView = null;
            GpuBuffer readBuffer = null;
            ProjectionMatrixBuffer projectionBuffer = null;
            GpuTextureView savedColor = null;
            GpuTextureView savedDepth = null;
            boolean renderTargetsRedirected = false;
            boolean projectionBackedUp = false;
            boolean scissorEnabled = false;

            try {
                ItemStack stack = supplier.get();

                TextureResult mapResult = tryRenderFilledMap(mc, stack);
                if (mapResult != null) {
                    future.complete(mapResult);
                    return;
                }

                TrackingItemStackRenderState renderState = new TrackingItemStackRenderState();
                ItemModelResolver resolver = mc.getItemModelResolver();
                resolver.updateForTopItem(renderState, stack, ItemDisplayContext.GUI, mc.level, null, 0);

                if (renderState.isEmpty()) {
                    future.completeExceptionally(new Exception("Empty render state for item"));
                    return;
                }

                int size = TEXTURE_SIZE;
                var device = RenderSystem.getDevice();
                colorTex = device.createTexture(
                        () -> "dbg_item_color",
                        GpuTexture.USAGE_COPY_SRC
                                | GpuTexture.USAGE_COPY_DST
                                | GpuTexture.USAGE_TEXTURE_BINDING
                                | GpuTexture.USAGE_RENDER_ATTACHMENT,
                        GpuFormat.RGBA8_UNORM,
                        size,
                        size,
                        1,
                        1);
                colorView = device.createTextureView(colorTex);
                depthTex = device.createTexture(
                        () -> "dbg_item_depth",
                        GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_RENDER_ATTACHMENT,
                        GpuFormat.D32_FLOAT,
                        size,
                        size,
                        1,
                        1);
                depthView = device.createTextureView(depthTex);

                device.createCommandEncoder()
                        .clearColorAndDepthTextures(colorTex, new Vector4f(0.0f, 0.0f, 0.0f, 0.0f), depthTex, 0.0);

                savedColor = RenderSystem.outputColorTextureOverride;
                savedDepth = RenderSystem.outputDepthTextureOverride;
                RenderSystem.outputColorTextureOverride = colorView;
                RenderSystem.outputDepthTextureOverride = depthView;
                renderTargetsRedirected = true;

                RenderSystem.backupProjectionMatrix();
                projectionBackedUp = true;
                Projection projection = new Projection();
                projection.setupOrtho(-1000.0F, 1000.0F, size, size, true);
                projectionBuffer = new ProjectionMatrixBuffer("dbg_item");
                RenderSystem.setProjectionMatrix(projectionBuffer.getBuffer(projection), ProjectionType.ORTHOGRAPHIC);

                boolean flat = !renderState.usesBlockLight();
                mc.gameRenderer.lighting().setupFor(flat ? Lighting.Entry.ITEMS_FLAT : Lighting.Entry.ITEMS_3D);

                PoseStack poseStack = new PoseStack();
                poseStack.pushPose();
                poseStack.translate(size / 2.0f, size / 2.0f, 0.0f);
                poseStack.scale(size, -size, size);

                RenderSystem.enableScissorForRenderTypeDraws(0, 0, size, size);
                scissorEnabled = true;
                renderItemWithIsolatedSubmitState(mc, renderState, poseStack);
                RenderSystem.disableScissorForRenderTypeDraws();
                scissorEnabled = false;
                poseStack.popPose();

                RenderSystem.outputColorTextureOverride = savedColor;
                RenderSystem.outputDepthTextureOverride = savedDepth;
                renderTargetsRedirected = false;
                RenderSystem.restoreProjectionMatrix();
                projectionBackedUp = false;
                projectionBuffer.close();
                projectionBuffer = null;

                long bufferSize = (long) size * size * colorTex.getFormat().pixelSize();
                readBuffer = device.createBuffer(
                        () -> "dbg_item_read", GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST, bufferSize);

                final GpuBuffer finalReadBuffer = readBuffer;
                final GpuTexture finalColorTex = colorTex;
                final GpuTextureView finalColorView = colorView;
                final GpuTexture finalDepthTex = depthTex;
                final GpuTextureView finalDepthView = depthView;

                device.createCommandEncoder()
                        .copyTextureToBuffer(
                                finalColorTex,
                                finalReadBuffer,
                                0L,
                                () -> {
                                    try (GpuBufferSlice.MappedView view = finalReadBuffer.map(true, false)) {
                                        BufferedImage image =
                                                new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);

                                        for (int y = 0; y < size; y++) {
                                            for (int x = 0; x < size; x++) {
                                                int abgr = view.data().getInt((x + y * size) * 4);
                                                int a = (abgr >> 24) & 0xFF;
                                                int b = (abgr >> 16) & 0xFF;
                                                int g = (abgr >> 8) & 0xFF;
                                                int r = abgr & 0xFF;
                                                image.setRGB(x, size - y - 1, (a << 24) | (r << 16) | (g << 8) | b);
                                            }
                                        }

                                        ByteArrayOutputStream baos = new ByteArrayOutputStream();
                                        ImageIO.write(image, "png", baos);
                                        String base64 = Base64.getEncoder().encodeToString(baos.toByteArray());

                                        future.complete(new TextureResult(base64, size, size, "rendered"));
                                    } catch (Exception e) {
                                        future.completeExceptionally(e);
                                    } finally {
                                        finalReadBuffer.close();
                                        finalColorTex.close();
                                        finalColorView.close();
                                        finalDepthTex.close();
                                        finalDepthView.close();
                                    }
                                },
                                0);

                readBuffer = null;
                colorTex = null;
                colorView = null;
                depthTex = null;
                depthView = null;
            } catch (Exception e) {
                if (scissorEnabled) RenderSystem.disableScissorForRenderTypeDraws();
                if (renderTargetsRedirected) {
                    RenderSystem.outputColorTextureOverride = savedColor;
                    RenderSystem.outputDepthTextureOverride = savedDepth;
                }
                if (projectionBackedUp) RenderSystem.restoreProjectionMatrix();
                if (projectionBuffer != null) projectionBuffer.close();
                if (readBuffer != null) readBuffer.close();
                if (colorView != null) colorView.close();
                if (colorTex != null) colorTex.close();
                if (depthView != null) depthView.close();
                if (depthTex != null) depthTex.close();
                future.completeExceptionally(e);
            }
        });

        return future.get(10, TimeUnit.SECONDS);
    }

    private void renderItemWithIsolatedSubmitState(
            Minecraft mc, TrackingItemStackRenderState renderState, PoseStack poseStack) {
        SubmitNodeStorage collector = new SubmitNodeStorage();
        renderState.submit(poseStack, collector, 15728880, OverlayTexture.NO_OVERLAY, 0);
        mc.gameRenderer.featureRenderDispatcher().renderAllFeatures(collector);
    }

    private TextureResult tryRenderFilledMap(Minecraft mc, ItemStack stack) throws Exception {
        if (stack.getItem() != Items.FILLED_MAP) return null;
        if (mc.level == null) return null;

        MapItemSavedData mapData = MapItem.getSavedData(stack, mc.level);
        if (mapData == null || mapData.colors == null || mapData.colors.length < MAP_SIZE * MAP_SIZE) {
            return null;
        }

        BufferedImage image = new BufferedImage(MAP_SIZE, MAP_SIZE, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < MAP_SIZE; y++) {
            for (int x = 0; x < MAP_SIZE; x++) {
                image.setRGB(x, y, mapPixelArgb(mapData.colors[x + y * MAP_SIZE]));
            }
        }

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(image, "png", baos);
        String base64 = Base64.getEncoder().encodeToString(baos.toByteArray());
        return new TextureResult(base64, MAP_SIZE, MAP_SIZE, "filled_map");
    }

    @FunctionalInterface
    private interface StackSupplier {
        ItemStack get() throws Exception;
    }
}
