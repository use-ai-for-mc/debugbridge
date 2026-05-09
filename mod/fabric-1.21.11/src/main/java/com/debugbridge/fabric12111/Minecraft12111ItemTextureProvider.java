package com.debugbridge.fabric12111;

import com.debugbridge.core.texture.ItemTextureProvider;
import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.textures.TextureFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.render.GuiRenderer;
import net.minecraft.client.renderer.CachedOrthoProjectionMatrixBuffer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.client.renderer.item.TrackingItemStackRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.item.*;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Renders inventory items to an offscreen GPU texture using Minecraft's own
 * item rendering pipeline, then reads back the pixels as a PNG.
 * <p>
 * This produces the exact same visual as the in-game GUI inventory, including
 * 3D models, damage-based model overrides, and resource pack textures.
 * <p>
 * Uses reflection to access GuiRenderer internals (bufferSource,
 * submitNodeCollector, featureRenderDispatcher) by type, resilient
 * to intermediary name remapping.
 */
public class Minecraft12111ItemTextureProvider implements ItemTextureProvider {
    private static final Logger LOG = Logger.getLogger("DebugBridge");
    private static final int TEXTURE_SIZE = 32;
    private static final int MAP_SIZE = 128;
    private static final int[] BRIGHTNESS_MOD = {180, 220, 255, 135};
    // Reflection cache
    private static volatile boolean reflectionReady = false;
    private static Field gameRendererGuiRendererField;       // GameRenderer → GuiRenderer
    private static Field guiRendererBufferSourceField;       // GuiRenderer → MultiBufferSource.BufferSource
    private static Field guiRendererSubmitCollectorField;    // GuiRenderer → SubmitNodeCollector
    private static Field guiRendererFeatureDispatcherField;  // GuiRenderer → FeatureRenderDispatcher

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

    private static synchronized void initReflection() throws Exception {
        if (reflectionReady) return;

        for (Field f : net.minecraft.client.renderer.GameRenderer.class.getDeclaredFields()) {
            if (f.getType() == GuiRenderer.class && !Modifier.isStatic(f.getModifiers())) {
                gameRendererGuiRendererField = f;
                f.setAccessible(true);
                break;
            }
        }
        if (gameRendererGuiRendererField == null)
            throw new Exception("Cannot find GameRenderer.guiRenderer field");

        for (Field f : GuiRenderer.class.getDeclaredFields()) {
            if (f.getType() == MultiBufferSource.BufferSource.class
                    && !Modifier.isStatic(f.getModifiers())) {
                guiRendererBufferSourceField = f;
                f.setAccessible(true);
                break;
            }
        }
        if (guiRendererBufferSourceField == null)
            throw new Exception("Cannot find GuiRenderer.bufferSource field");

        for (Field f : GuiRenderer.class.getDeclaredFields()) {
            if (SubmitNodeCollector.class.isAssignableFrom(f.getType())
                    && !Modifier.isStatic(f.getModifiers())) {
                guiRendererSubmitCollectorField = f;
                f.setAccessible(true);
                break;
            }
        }
        if (guiRendererSubmitCollectorField == null)
            throw new Exception("Cannot find GuiRenderer.submitNodeCollector field");

        for (Field f : GuiRenderer.class.getDeclaredFields()) {
            if (f.getType() == FeatureRenderDispatcher.class
                    && !Modifier.isStatic(f.getModifiers())) {
                guiRendererFeatureDispatcherField = f;
                f.setAccessible(true);
                break;
            }
        }
        if (guiRendererFeatureDispatcherField == null)
            throw new Exception("Cannot find GuiRenderer.featureRenderDispatcher field");

        reflectionReady = true;
        LOG.info("[DebugBridge] Item texture provider reflection initialized (offscreen render)");
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

    // ---- Filled-map rendering (bypasses the GUI item pipeline) ----

    @Override
    public TextureResult getItemTextureById(String itemId) throws Exception {
        return renderSlot(() -> {
            Identifier key = Identifier.tryParse(itemId);
            if (key == null) throw new Exception("Invalid item id: " + itemId);
            if (!BuiltInRegistries.ITEM.containsKey(key))
                throw new Exception("Unknown item: " + itemId);
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
                case Display.ItemDisplay itemDisplay when "DISPLAY".equals(slotName) -> {
                    var renderState = itemDisplay.itemRenderState();
                    if (renderState == null) {
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

            if (stack.isEmpty())
                throw new Exception("Slot " + slotName + " is empty on entity " + entityId);
            return stack;
        });
    }

    /**
     * Runs the stack-producing supplier on the game thread, then renders
     * the resulting ItemStack offscreen.
     */
    private TextureResult renderSlot(StackSupplier supplier) throws Exception {
        Minecraft mc = Minecraft.getInstance();
        CompletableFuture<TextureResult> future = new CompletableFuture<>();

        mc.execute(() -> {
            GpuTexture colorTex = null;
            GpuTextureView colorView = null;
            GpuTexture depthTex = null;
            GpuTextureView depthView = null;
            CachedOrthoProjectionMatrixBuffer projBuf = null;

            try {
                initReflection();

                ItemStack stack = supplier.get();

                // Fast path: filled_map renders the actual map content (128x128
                // pixel grid via MapColor palette), not the inventory icon.
                TextureResult mapResult = tryRenderFilledMap(mc, stack);
                if (mapResult != null) {
                    future.complete(mapResult);
                    return;
                }

                // 1. Prepare item render state using the model resolver
                TrackingItemStackRenderState renderState = new TrackingItemStackRenderState();
                ItemModelResolver resolver = mc.getItemModelResolver();
                resolver.updateForTopItem(renderState, stack, ItemDisplayContext.GUI,
                        mc.level, null, 0);

                if (renderState.isEmpty()) {
                    future.completeExceptionally(new Exception("Empty render state for item"));
                    return;
                }

                // 2. Get GuiRenderer internals via reflection
                GuiRenderer guiRenderer = (GuiRenderer) gameRendererGuiRendererField.get(mc.gameRenderer);
                MultiBufferSource.BufferSource bufferSource =
                        (MultiBufferSource.BufferSource) guiRendererBufferSourceField.get(guiRenderer);
                SubmitNodeCollector collector =
                        (SubmitNodeCollector) guiRendererSubmitCollectorField.get(guiRenderer);
                FeatureRenderDispatcher features =
                        (FeatureRenderDispatcher) guiRendererFeatureDispatcherField.get(guiRenderer);

                // 3. Create offscreen GPU textures
                int size = TEXTURE_SIZE;
                var device = RenderSystem.getDevice();
                colorTex = device.createTexture(() -> "dbg_item_color", 14,
                        TextureFormat.RGBA8, size, size, 1, 1);
                colorView = device.createTextureView(colorTex);
                depthTex = device.createTexture(() -> "dbg_item_depth", 8,
                        TextureFormat.DEPTH32, size, size, 1, 1);
                depthView = device.createTextureView(depthTex);

                device.createCommandEncoder().clearColorAndDepthTextures(
                        colorTex, 0, depthTex, 1.0);

                // 4. Save and redirect render output
                GpuTextureView savedColor = RenderSystem.outputColorTextureOverride;
                GpuTextureView savedDepth = RenderSystem.outputDepthTextureOverride;
                RenderSystem.outputColorTextureOverride = colorView;
                RenderSystem.outputDepthTextureOverride = depthView;

                RenderSystem.backupProjectionMatrix();
                projBuf = new CachedOrthoProjectionMatrixBuffer(
                        "dbg_item", -1000.0F, 1000.0F, true);
                RenderSystem.setProjectionMatrix(
                        projBuf.getBuffer(size, size), ProjectionType.ORTHOGRAPHIC);

                // 5. Set lighting
                boolean flat = !renderState.usesBlockLight();
                mc.gameRenderer.getLighting().setupFor(
                        flat ? Lighting.Entry.ITEMS_FLAT : Lighting.Entry.ITEMS_3D);

                // 6. Render the item
                PoseStack poseStack = new PoseStack();
                poseStack.pushPose();
                poseStack.translate(size / 2.0f, size / 2.0f, 0.0f);
                poseStack.scale(size, -size, size);

                RenderSystem.enableScissorForRenderTypeDraws(0, 0, size, size);
                renderState.submit(poseStack, collector, 15728880,
                        OverlayTexture.NO_OVERLAY, 0);
                features.renderAllFeatures();
                bufferSource.endBatch();
                RenderSystem.disableScissorForRenderTypeDraws();
                poseStack.popPose();

                // 7. Restore render state
                RenderSystem.outputColorTextureOverride = savedColor;
                RenderSystem.outputDepthTextureOverride = savedDepth;
                RenderSystem.restoreProjectionMatrix();
                projBuf.close();
                projBuf = null;

                // 8. Read back pixels
                long bufSize = (long) size * size * colorTex.getFormat().pixelSize();
                GpuBuffer readBuf = device.createBuffer(() -> "dbg_item_read", 9, bufSize);
                CommandEncoder readEncoder = device.createCommandEncoder();

                final GpuTexture fColorTex = colorTex;
                final GpuTextureView fColorView = colorView;
                final GpuTexture fDepthTex = depthTex;
                final GpuTextureView fDepthView = depthView;

                colorTex = null;
                colorView = null;
                depthTex = null;
                depthView = null;

                device.createCommandEncoder().copyTextureToBuffer(
                        fColorTex, readBuf, 0L, () -> {
                            try (GpuBuffer.MappedView view =
                                         readEncoder.mapBuffer(readBuf, true, false)) {
                                BufferedImage img = new BufferedImage(
                                        size, size, BufferedImage.TYPE_INT_ARGB);

                                for (int y = 0; y < size; y++) {
                                    for (int x = 0; x < size; x++) {
                                        int abgr = view.data().getInt(
                                                (x + y * size) * 4);
                                        int a = (abgr >> 24) & 0xFF;
                                        int b = (abgr >> 16) & 0xFF;
                                        int g = (abgr >> 8) & 0xFF;
                                        int r = abgr & 0xFF;
                                        img.setRGB(x, size - y - 1,
                                                (a << 24) | (r << 16) | (g << 8) | b);
                                    }
                                }

                                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                                ImageIO.write(img, "png", baos);
                                String base64 = Base64.getEncoder()
                                        .encodeToString(baos.toByteArray());

                                future.complete(new TextureResult(
                                        base64, size, size, "rendered"));
                            } catch (Exception e) {
                                future.completeExceptionally(e);
                            } finally {
                                readBuf.close();
                                fColorTex.close();
                                fColorView.close();
                                fDepthTex.close();
                                fDepthView.close();
                            }
                        }, 0);

            } catch (Exception e) {
                if (projBuf != null) projBuf.close();
                if (colorView != null) colorView.close();
                if (colorTex != null) colorTex.close();
                if (depthView != null) depthView.close();
                if (depthTex != null) depthTex.close();
                future.completeExceptionally(e);
            }
        });

        return future.get(10, TimeUnit.SECONDS);
    }

    private TextureResult tryRenderFilledMap(Minecraft mc, ItemStack stack) throws Exception {
        if (!stack.is(Items.FILLED_MAP)) return null;
        if (mc.level == null) return null;

        MapItemSavedData mapData = MapItem.getSavedData(stack, mc.level);
        if (mapData == null || mapData.colors == null
                || mapData.colors.length < MAP_SIZE * MAP_SIZE) {
            return null;
        }

        BufferedImage img = new BufferedImage(MAP_SIZE, MAP_SIZE, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < MAP_SIZE; y++) {
            for (int x = 0; x < MAP_SIZE; x++) {
                img.setRGB(x, y, mapPixelArgb(mapData.colors[x + y * MAP_SIZE]));
            }
        }
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(img, "png", baos);
        String base64 = Base64.getEncoder().encodeToString(baos.toByteArray());
        return new TextureResult(base64, MAP_SIZE, MAP_SIZE, "filled_map");
    }

    // ---- One-time reflection setup ----

    @FunctionalInterface
    private interface StackSupplier {
        ItemStack get() throws Exception;
    }
}
