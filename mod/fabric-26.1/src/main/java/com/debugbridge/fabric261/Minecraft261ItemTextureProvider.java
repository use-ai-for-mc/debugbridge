package com.debugbridge.fabric261;

import com.debugbridge.core.texture.ItemTextureProvider;
import com.debugbridge.fabric261.mixin.FeatureRenderDispatcherAccessor;
import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.textures.TextureFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.imageio.ImageIO;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.Projection;
import net.minecraft.client.renderer.ProjectionMatrixBuffer;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.item.TrackingItemStackRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.item.*;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Renders items to an offscreen GPU texture through Minecraft's renderer and
 * reads the result back through the backend-neutral GPU abstraction.
 */
public class Minecraft261ItemTextureProvider implements ItemTextureProvider {
    private static final Logger LOGGER = LoggerFactory.getLogger(Minecraft261ItemTextureProvider.class);
    private static final int TEXTURE_SIZE = 32;
    private static final int MAP_SIZE = 128;
    /**
     * Vanilla 26.1's {@code GuiItemAtlas.drawToSlot(...)} submits GUI item
     * models with the full-bright light value 15728880.
     */
    private static final int GUI_ITEM_LIGHT = LightCoordsUtil.FULL_BRIGHT;

    private static final String SPRITE_FILLED_MAP = "filled_map";
    private static final String SPRITE_RENDERED_PREFIX = "rendered26.1:";
    private static final String SPRITE_FALLBACK_PREFIX = "fallback:";
    private static final int RENDER_TIMEOUT_SECONDS = 10;
    private static final int CLEANUP_TIMEOUT_SECONDS = 1;

    private GpuTexture itemTexture;
    private GpuTextureView itemTextureView;
    private GpuTexture depthTexture;
    private GpuTextureView depthTextureView;
    private GpuBuffer readbackBuffer;
    private final PoseStack poseStack = new PoseStack();
    private final Projection projection = new Projection();
    private ProjectionMatrixBuffer projectionMatrixBuffer;
    private volatile boolean renderingItemIcon;
    private volatile boolean closed;

    private static int mapPixelArgb(byte packedColor) {
        return MapColor.getColorFromPackedId(packedColor & 0xFF);
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
            return new ItemStack(item);
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
        if (mc.isSameThread()) {
            return renderSlotOnClientThread(mc, supplier);
        }

        CompletableFuture<TextureResult> future = new CompletableFuture<>();

        mc.execute(() -> {
            if (future.isCancelled()) {
                return;
            }
            try {
                TextureResult result = renderSlotOnClientThread(mc, supplier);
                if (!future.isCancelled()) {
                    future.complete(result);
                }
            } catch (Exception e) {
                if (!future.isCancelled()) {
                    future.completeExceptionally(e);
                }
            }
        });

        try {
            return future.get(RENDER_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            future.cancel(false);
            LOGGER.warn(
                    "Timed out after " + RENDER_TIMEOUT_SECONDS
                            + "s waiting for 26.1 item texture render on the Minecraft client thread",
                    e);
            throw e;
        }
    }

    private TextureResult renderSlotOnClientThread(Minecraft mc, StackSupplier supplier) throws Exception {
        if (closed) {
            throw new ProviderClosedException();
        }

        ItemStack stack = supplier.get();

        TextureResult mapResult = tryRenderFilledMap(mc, stack);
        if (mapResult != null) {
            if (closed) {
                throw new ProviderClosedException();
            }
            return mapResult;
        }

        try {
            return renderVanillaItem(mc, stack);
        } catch (Exception renderError) {
            if (renderError instanceof ProviderClosedException || closed) {
                throw new ProviderClosedException(renderError);
            }
            LOGGER.warn("Falling back to placeholder for 26.1 item icon " + itemId(stack), renderError);
            TextureResult fallback = renderFallbackPlaceholder(stack);
            if (closed) {
                throw new ProviderClosedException(renderError);
            }
            return fallback;
        }
    }

    /**
     * Render an ordinary item icon through the exact-26.1 GUI item pipeline.
     *
     * <p>The synchronized block only claims ownership of the reusable GPU
     * render target. It must stay small: actual rendering and GL cleanup run
     * outside the monitor. If {@link #close()} wins first, rendering fails as a
     * closed provider. If rendering wins first, {@code close()} marks the
     * provider closed and cleanup is deferred to this method's {@code finally}
     * block after the shared render target is no longer in use.</p>
     */
    private TextureResult renderVanillaItem(Minecraft mc, ItemStack stack) throws Exception {
        synchronized (this) {
            if (closed) {
                throw new ProviderClosedException();
            }
            if (renderingItemIcon) {
                throw new Exception("26.1 item icon renderer is already rendering");
            }
            renderingItemIcon = true;
        }
        try {
            return renderVanillaItemGuarded(mc, stack);
        } finally {
            boolean shouldCloseResources;
            synchronized (this) {
                renderingItemIcon = false;
                shouldCloseResources = closed;
            }
            if (shouldCloseResources) {
                closeItemTextureResourcesQuietly();
            }
        }
    }

    private TextureResult renderVanillaItemGuarded(Minecraft mc, ItemStack stack) throws Exception {
        TrackingItemStackRenderState renderState = new TrackingItemStackRenderState();
        mc.getItemModelResolver().updateForTopItem(renderState, stack, ItemDisplayContext.GUI, mc.level, mc.player, 0);
        if (renderState.isEmpty()) {
            throw new Exception("Item renderer produced an empty render state for " + itemId(stack));
        }

        FeatureRenderDispatcher featureDispatcher = mc.gameRenderer.getFeatureRenderDispatcher();
        SubmitNodeStorage submitNodeStorage = featureDispatcher.getSubmitNodeStorage();
        MultiBufferSource.BufferSource bufferSource =
                ((FeatureRenderDispatcherAccessor) featureDispatcher).debugbridge$getBufferSource();
        renderItemToTexture(mc, renderState, submitNodeStorage, featureDispatcher, bufferSource);
        TextureResult result = readItemTexture(stack);
        if (closed) {
            throw new ProviderClosedException();
        }
        return result;
    }

    private void renderItemToTexture(
            Minecraft mc,
            TrackingItemStackRenderState renderState,
            SubmitNodeStorage submitNodeStorage,
            FeatureRenderDispatcher featureDispatcher,
            MultiBufferSource.BufferSource bufferSource)
            throws ProviderClosedException {
        ensureItemTexture();

        CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
        encoder.clearColorAndDepthTextures(itemTexture, 0, depthTexture, 1.0);

        GpuTextureView previousColorOverride = RenderSystem.outputColorTextureOverride;
        GpuTextureView previousDepthOverride = RenderSystem.outputDepthTextureOverride;
        GpuBufferSlice previousShaderLights = RenderSystem.getShaderLights();
        boolean renderedFeatures = false;
        boolean endedBatch = false;
        boolean projectionBackedUp = false;
        poseStack.pushPose();
        try {
            RenderSystem.backupProjectionMatrix();
            projectionBackedUp = true;
            poseStack.translate(TEXTURE_SIZE / 2.0F, TEXTURE_SIZE / 2.0F, 0.0F);
            poseStack.scale(TEXTURE_SIZE, -TEXTURE_SIZE, TEXTURE_SIZE);
            RenderSystem.outputColorTextureOverride = itemTextureView;
            RenderSystem.outputDepthTextureOverride = depthTextureView;
            projection.setupOrtho(-1000.0F, 1000.0F, TEXTURE_SIZE, TEXTURE_SIZE, true);
            RenderSystem.setProjectionMatrix(projectionMatrixBuffer.getBuffer(projection), ProjectionType.ORTHOGRAPHIC);
            RenderSystem.enableScissorForRenderTypeDraws(0, 0, TEXTURE_SIZE, TEXTURE_SIZE);
            Lighting.Entry lighting =
                    renderState.usesBlockLight() ? Lighting.Entry.ITEMS_3D : Lighting.Entry.ITEMS_FLAT;
            mc.gameRenderer.getLighting().setupFor(lighting);
            renderState.submit(poseStack, submitNodeStorage, GUI_ITEM_LIGHT, OverlayTexture.NO_OVERLAY, 0);
            featureDispatcher.renderAllFeatures();
            renderedFeatures = true;
            bufferSource.endBatch();
            endedBatch = true;
        } finally {
            if (!endedBatch) {
                endBatchQuietly(bufferSource);
            }
            if (!renderedFeatures) {
                featureDispatcher.clearSubmitNodes();
            }
            RenderSystem.disableScissorForRenderTypeDraws();
            RenderSystem.outputColorTextureOverride = previousColorOverride;
            RenderSystem.outputDepthTextureOverride = previousDepthOverride;
            RenderSystem.setShaderLights(previousShaderLights);
            if (projectionBackedUp) {
                RenderSystem.restoreProjectionMatrix();
            }
            poseStack.popPose();
        }
    }

    private void endBatchQuietly(MultiBufferSource.BufferSource bufferSource) {
        try {
            bufferSource.endBatch();
        } catch (Exception e) {
            LOGGER.warn("Failed to drain 26.1 item icon buffer source after render failure", e);
        }
    }

    private void ensureItemTexture() throws ProviderClosedException {
        if (closed) {
            throw new ProviderClosedException();
        }
        if (itemTexture != null) {
            if (projectionMatrixBuffer == null) {
                projectionMatrixBuffer = new ProjectionMatrixBuffer("debugbridge-items");
            }
            return;
        }

        GpuDevice device = RenderSystem.getDevice();
        int colorUsage = GpuTexture.USAGE_COPY_DST
                | GpuTexture.USAGE_COPY_SRC
                | GpuTexture.USAGE_TEXTURE_BINDING
                | GpuTexture.USAGE_RENDER_ATTACHMENT;
        int depthUsage = GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_RENDER_ATTACHMENT;
        itemTexture = device.createTexture(
                "DebugBridge item icon", colorUsage, TextureFormat.RGBA8, TEXTURE_SIZE, TEXTURE_SIZE, 1, 1);
        itemTextureView = device.createTextureView(itemTexture);
        depthTexture = device.createTexture(
                "DebugBridge item icon depth", depthUsage, TextureFormat.DEPTH32, TEXTURE_SIZE, TEXTURE_SIZE, 1, 1);
        depthTextureView = device.createTextureView(depthTexture);
        projectionMatrixBuffer = new ProjectionMatrixBuffer("debugbridge-items");
    }

    private TextureResult readItemTexture(ItemStack stack) throws Exception {
        GpuBuffer readback = ensureReadbackBuffer();
        try {
            CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
            encoder.copyTextureToBuffer(itemTexture, readback, 0, () -> {}, 0, 0, 0, TEXTURE_SIZE, TEXTURE_SIZE);

            try (GpuBuffer.MappedView mapped = encoder.mapBuffer(readback, true, false)) {
                BufferedImage image = new BufferedImage(TEXTURE_SIZE, TEXTURE_SIZE, BufferedImage.TYPE_INT_ARGB);
                ByteBuffer pixels = mapped.data();
                for (int y = 0; y < TEXTURE_SIZE; y++) {
                    int srcY = TEXTURE_SIZE - 1 - y;
                    for (int x = 0; x < TEXTURE_SIZE; x++) {
                        int offset = (srcY * TEXTURE_SIZE + x) * 4;
                        image.setRGB(x, y, rgbaToStraightArgb(pixels, offset));
                    }
                }

                return encodeImage(image, SPRITE_RENDERED_PREFIX + itemId(stack));
            }
        } finally {
            RenderSystem.executePendingTasks();
        }
    }

    private GpuBuffer ensureReadbackBuffer() throws ProviderClosedException {
        if (closed) {
            throw new ProviderClosedException();
        }
        if (readbackBuffer == null || readbackBuffer.isClosed()) {
            int byteCount = TEXTURE_SIZE * TEXTURE_SIZE * 4;
            readbackBuffer = RenderSystem.getDevice()
                    .createBuffer(
                            () -> "DebugBridge item icon readback",
                            GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST,
                            byteCount);
        }
        return readbackBuffer;
    }

    private TextureResult tryRenderFilledMap(Minecraft mc, ItemStack stack) throws Exception {
        if (stack.getItem() != Items.FILLED_MAP) return null;
        if (mc.level == null) return null;

        MapId mapId = stack.get(DataComponents.MAP_ID);
        if (mapId == null) return null;

        MapItemSavedData mapData = mc.level.getMapData(mapId);
        if (mapData == null || mapData.colors == null || mapData.colors.length < MAP_SIZE * MAP_SIZE) {
            return null;
        }

        BufferedImage image = new BufferedImage(MAP_SIZE, MAP_SIZE, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < MAP_SIZE; y++) {
            for (int x = 0; x < MAP_SIZE; x++) {
                image.setRGB(x, y, mapPixelArgb(mapData.colors[x + y * MAP_SIZE]));
            }
        }

        return encodeImage(image, SPRITE_FILLED_MAP);
    }

    private TextureResult encodeImage(BufferedImage image, String spriteName) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(image, "png", baos);
        String base64 = Base64.getEncoder().encodeToString(baos.toByteArray());
        return new TextureResult(base64, image.getWidth(), image.getHeight(), spriteName);
    }

    private TextureResult renderFallbackPlaceholder(ItemStack stack) throws Exception {
        String itemId = itemId(stack);
        int rgb = 0x202020 | (itemId.hashCode() & 0xDFDFDF);

        BufferedImage image = new BufferedImage(TEXTURE_SIZE, TEXTURE_SIZE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(new Color(0, 0, 0, 0));
            graphics.fillRect(0, 0, TEXTURE_SIZE, TEXTURE_SIZE);
            graphics.setColor(new Color(rgb | 0xFF000000, true));
            graphics.fillRect(4, 4, TEXTURE_SIZE - 8, TEXTURE_SIZE - 8);
            graphics.setColor(new Color(0xFFFFFFFF, true));
            graphics.drawRect(4, 4, TEXTURE_SIZE - 9, TEXTURE_SIZE - 9);
        } finally {
            graphics.dispose();
        }

        return encodeImage(image, SPRITE_FALLBACK_PREFIX + itemId);
    }

    @Override
    public void close() {
        boolean shouldCloseResources;
        synchronized (this) {
            boolean hadResources = hasItemTextureResources();
            closed = true;
            shouldCloseResources = hadResources && !renderingItemIcon;
        }
        if (!shouldCloseResources) {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        if (mc.isSameThread()) {
            closeItemTextureResourcesQuietly();
            return;
        }

        CompletableFuture<Void> future = new CompletableFuture<>();
        try {
            mc.execute(() -> {
                try {
                    closeItemTextureResources();
                    future.complete(null);
                } catch (Exception e) {
                    future.completeExceptionally(e);
                }
            });
        } catch (Exception e) {
            LOGGER.warn("Failed to schedule 26.1 item texture GPU cleanup on the client thread", e);
            return;
        }
        try {
            future.get(CLEANUP_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            LOGGER.warn(
                    "Failed or timed out after " + CLEANUP_TIMEOUT_SECONDS
                            + "s closing 26.1 item texture GPU resources on the client thread",
                    e);
        }
    }

    private boolean hasItemTextureResources() {
        return itemTexture != null
                || itemTextureView != null
                || depthTexture != null
                || depthTextureView != null
                || readbackBuffer != null
                || projectionMatrixBuffer != null;
    }

    /**
     * Close provider-owned GPU resources outside synchronized lifecycle blocks.
     *
     * <p>The monitor is only used to decide whether cleanup is safe. Actual GL
     * handle disposal can touch driver state and should not run while holding
     * the provider lock.</p>
     */
    private void closeItemTextureResourcesQuietly() {
        closeItemTextureResources();
    }

    private void closeItemTextureResources() {
        GpuTextureView oldItemTextureView = itemTextureView;
        GpuTextureView oldDepthTextureView = depthTextureView;
        GpuTexture oldItemTexture = itemTexture;
        GpuTexture oldDepthTexture = depthTexture;
        GpuBuffer oldReadbackBuffer = readbackBuffer;
        ProjectionMatrixBuffer oldProjectionMatrixBuffer = projectionMatrixBuffer;

        itemTextureView = null;
        depthTextureView = null;
        itemTexture = null;
        depthTexture = null;
        readbackBuffer = null;
        projectionMatrixBuffer = null;

        closeTextureView("item texture view", oldItemTextureView);
        closeTextureView("depth texture view", oldDepthTextureView);
        closeTexture("item texture", oldItemTexture);
        closeTexture("depth texture", oldDepthTexture);
        closeGpuBuffer("readback buffer", oldReadbackBuffer);
        closeProjectionMatrixBuffer(oldProjectionMatrixBuffer);
    }

    private void closeTextureView(String label, GpuTextureView view) {
        if (view == null || view.isClosed()) return;
        try {
            view.close();
        } catch (Exception e) {
            LOGGER.warn("Failed to close 26.1 item icon " + label, e);
        }
    }

    private void closeTexture(String label, GpuTexture texture) {
        if (texture == null || texture.isClosed()) return;
        try {
            texture.close();
        } catch (Exception e) {
            LOGGER.warn("Failed to close 26.1 item icon " + label, e);
        }
    }

    private void closeGpuBuffer(String label, GpuBuffer buffer) {
        if (buffer == null || buffer.isClosed()) return;
        try {
            buffer.close();
        } catch (Exception e) {
            LOGGER.warn("Failed to close 26.1 item icon {}", label, e);
        }
    }

    private void closeProjectionMatrixBuffer(ProjectionMatrixBuffer buffer) {
        if (buffer == null) return;
        try {
            buffer.close();
        } catch (Exception e) {
            LOGGER.warn("Failed to close 26.1 item icon projection matrix buffer", e);
        }
    }

    private static int rgbaToStraightArgb(ByteBuffer pixels, int offset) {
        int r = pixels.get(offset) & 0xFF;
        int g = pixels.get(offset + 1) & 0xFF;
        int b = pixels.get(offset + 2) & 0xFF;
        int a = pixels.get(offset + 3) & 0xFF;
        if (a > 0 && a < 255) {
            r = Math.min(255, r * 255 / a);
            g = Math.min(255, g * 255 / a);
            b = Math.min(255, b * 255 / a);
        }
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private static String itemId(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    @FunctionalInterface
    private interface StackSupplier {
        ItemStack get() throws Exception;
    }

    private static final class ProviderClosedException extends Exception {
        private ProviderClosedException() {
            super("26.1 item texture provider is closed");
        }

        private ProviderClosedException(Throwable cause) {
            super("26.1 item texture provider is closed", cause);
        }
    }
}
