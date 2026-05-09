package com.debugbridge.fabric119;

import com.debugbridge.core.texture.ItemTextureProvider;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.item.ItemColors;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.Direction;
import net.minecraft.core.Registry;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.MapItem;
import net.minecraft.world.level.material.MaterialColor;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Resolves item textures in Minecraft 1.19 by walking the baked item model
 * and extracting pixels from the resolved sprite in the texture atlas.
 * <p>
 * This captures the correct texture for the item including damage-based model
 * overrides and resource pack textures. Does not render overlays like
 * enchantment glint or damage bars — those are drawn by the GUI layer.
 */
public class Minecraft119ItemTextureProvider implements ItemTextureProvider {
    private static final Logger LOG = Logger.getLogger("DebugBridge");
    // Cache of fetched skin PNGs keyed by CDN URL.
    private static final ConcurrentHashMap<String, NativeImage> SKIN_CACHE = new ConcurrentHashMap<>();
    private static final int MAP_SIZE = 128;
    private static final int[] BRIGHTNESS_MOD = {180, 220, 255, 135};
    // Reflection cache: TextureAtlasSprite → NativeImage[] field
    private static volatile Field spritePixelsField;
    // Reflection cache: Minecraft → ItemColors field (private, no accessor in 1.19).
    private static volatile ItemColors cachedItemColors;

    private static int mapPixelArgb(byte packedColor) {
        int colorId = (packedColor & 0xFF) >> 2;
        int shade = packedColor & 3;
        if (colorId == 0) return 0;
        MaterialColor color = MaterialColor.byId(colorId);
        if (color == null) return 0;
        int col = color.col;
        int modifier = BRIGHTNESS_MOD[shade];
        int r = ((col >> 16) & 255) * modifier / 255;
        int g = ((col >> 8) & 255) * modifier / 255;
        int b = (col & 255) * modifier / 255;
        return (0xFF << 24) | (r << 16) | (g << 8) | b;
    }

    /**
     * Convert NativeImage's little-endian ABGR packing to standard ARGB.
     */
    private static int nativeToArgb(int abgr) {
        int a = (abgr >>> 24) & 0xFF;
        int b = (abgr >> 16) & 0xFF;
        int g = (abgr >> 8) & 0xFF;
        int r = abgr & 0xFF;
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    /**
     * Standard "source over" alpha compositing — top pixel drawn over bottom.
     */
    private static int alphaOver(int topArgb, int bottomArgb) {
        int ta = (topArgb >>> 24) & 0xFF;
        if (ta == 0) return bottomArgb;
        if (ta == 255) return topArgb;
        int ba = (bottomArgb >>> 24) & 0xFF;
        int tr = (topArgb >> 16) & 0xFF;
        int tg = (topArgb >> 8) & 0xFF;
        int tb = topArgb & 0xFF;
        int br = (bottomArgb >> 16) & 0xFF;
        int bg = (bottomArgb >> 8) & 0xFF;
        int bb = bottomArgb & 0xFF;
        int invTa = 255 - ta;
        int outA = ta + ba * invTa / 255;
        if (outA == 0) return 0;
        int outR = (tr * ta + br * ba * invTa / 255) / outA;
        int outG = (tg * ta + bg * ba * invTa / 255) / outA;
        int outB = (tb * ta + bb * ba * invTa / 255) / outA;
        return (outA << 24) | (outR << 16) | (outG << 8) | outB;
    }

    /**
     * Look up {@link ItemColors} on the {@link Minecraft} instance via reflection.
     * In 1.19 the field is private with no accessor, so we can't call a getter.
     * Returns null on failure; callers should skip tint in that case.
     */
    private static ItemColors resolveItemColors(Minecraft mc) {
        ItemColors cached = cachedItemColors;
        if (cached != null) return cached;
        try {
            Class<?> c = Minecraft.class;
            while (c != null && c != Object.class) {
                for (Field f : c.getDeclaredFields()) {
                    if (f.getType() == ItemColors.class) {
                        f.setAccessible(true);
                        ItemColors ic = (ItemColors) f.get(mc);
                        if (ic != null) {
                            cachedItemColors = ic;
                            return ic;
                        }
                    }
                }
                c = c.getSuperclass();
            }
        } catch (Exception e) {
            LOG.warning("[DebugBridge] Failed to resolve ItemColors via reflection: " + e.getMessage());
        }
        return null;
    }

    // ---- Filled-map rendering (bypasses the baked model pipeline) ----

    private static Field findNativeImageArrayField(Class<?> cls) {
        Class<?> c = cls;
        while (c != null && c != Object.class) {
            for (Field f : c.getDeclaredFields()) {
                if (f.getType() == NativeImage[].class) {
                    return f;
                }
            }
            c = c.getSuperclass();
        }
        return null;
    }

    @Override
    public TextureResult getItemTexture(int slot) throws Exception {
        Minecraft mc = Minecraft.getInstance();
        return renderStack(() -> {
            if (mc.player == null) throw new Exception("Player not available");
            ItemStack stack = mc.player.getInventory().getItem(slot);
            if (stack.isEmpty()) throw new Exception("Slot " + slot + " is empty");
            return stack;
        });
    }

    @Override
    public TextureResult getItemTextureById(String itemId) throws Exception {
        return renderStack(() -> {
            ResourceLocation key;
            try {
                key = new ResourceLocation(itemId);
            } catch (Exception e) {
                throw new Exception("Invalid item id: " + itemId);
            }
            if (!Registry.ITEM.containsKey(key))
                throw new Exception("Unknown item: " + itemId);
            Item item = Registry.ITEM.get(key);
            return new ItemStack(item);
        });
    }

    @Override
    public TextureResult getEntityItemTexture(int entityId, String slotName) throws Exception {
        Minecraft mc = Minecraft.getInstance();
        return renderStack(() -> {
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
            if ("FRAME".equals(slotName) && target instanceof ItemFrame frame) {
                stack = frame.getItem();
            } else if (target instanceof LivingEntity living) {
                EquipmentSlot slot;
                try {
                    slot = EquipmentSlot.valueOf(slotName);
                } catch (IllegalArgumentException e) {
                    throw new Exception("Unknown slot " + slotName);
                }
                stack = living.getItemBySlot(slot);
            } else {
                throw new Exception("Entity " + entityId + " has no equipment");
            }

            if (stack.isEmpty())
                throw new Exception("Slot " + slotName + " is empty on entity " + entityId);
            return stack;
        });
    }

    // ---- Custom-head face from profile NBT ----

    private TextureResult renderStack(StackSupplier supplier) throws Exception {
        Minecraft mc = Minecraft.getInstance();

        // Phase 1 (render thread): resolve the ItemStack + try the filled-map
        // fast path. Stack resolution must touch level/player state.
        CompletableFuture<StackOrResult> phase1 = new CompletableFuture<>();
        mc.execute(() -> {
            try {
                ItemStack stack = supplier.get();
                TextureResult mapResult = tryRenderFilledMap(mc, stack);
                if (mapResult != null) {
                    phase1.complete(new StackOrResult(null, mapResult));
                    return;
                }
                phase1.complete(new StackOrResult(stack, null));
            } catch (Exception e) {
                phase1.completeExceptionally(e);
            }
        });
        StackOrResult p1 = phase1.get(10, TimeUnit.SECONDS);
        if (p1.result != null) return p1.result;
        ItemStack stack = p1.stack;

        // Phase 2 (calling/WebSocket thread): custom-head face from NBT profile.
        // May block on HTTP, so it MUST NOT run on the render thread.
        TextureResult headResult = tryRenderHeadFromProfile(stack);
        if (headResult != null) return headResult;

        // Phase 3 (render thread): walk the baked model and composite quads.
        CompletableFuture<TextureResult> phase3 = new CompletableFuture<>();
        mc.execute(() -> {
            try {
                BakedModel model = mc.getItemRenderer().getModel(stack, mc.level, mc.player, 0);
                phase3.complete(renderFromBakedModel(stack, model, mc));
            } catch (Exception e) {
                phase3.completeExceptionally(e);
            }
        });
        return phase3.get(10, TimeUnit.SECONDS);
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

    // ---- Baked-model rendering with tint compositing ----

    /**
     * For a player head whose NBT carries a texture profile, decode the profile,
     * fetch the skin PNG from the Mojang CDN, and composite the face + hat
     * overlay into a 16×16 icon. Returns null for anything that isn't a
     * profile-backed head, or if fetch/decoding fails.
     * <p>
     * Runs on the caller thread — blocking HTTP is safe here.
     */
    private TextureResult tryRenderHeadFromProfile(ItemStack stack) {
        if (!stack.is(Items.PLAYER_HEAD)) return null;

        try {
            CompoundTag tag = stack.getTag();
            if (tag == null || !tag.contains("SkullOwner", Tag.TAG_COMPOUND)) return null;
            CompoundTag owner = tag.getCompound("SkullOwner");
            if (!owner.contains("Properties", Tag.TAG_COMPOUND)) return null;
            CompoundTag props = owner.getCompound("Properties");
            if (!props.contains("textures", Tag.TAG_LIST)) return null;
            ListTag textures = props.getList("textures", Tag.TAG_COMPOUND);
            if (textures.isEmpty()) return null;
            String value = textures.getCompound(0).getString("Value");
            if (value == null || value.isEmpty()) return null;

            byte[] decoded = Base64.getDecoder().decode(value);
            JsonObject root = JsonParser.parseString(new String(decoded, StandardCharsets.UTF_8))
                    .getAsJsonObject();
            JsonObject texturesObj = root.getAsJsonObject("textures");
            if (texturesObj == null) return null;
            JsonObject skin = texturesObj.getAsJsonObject("SKIN");
            if (skin == null || !skin.has("url")) return null;
            String url = skin.get("url").getAsString();
            if (url == null || url.isEmpty()) return null;

            NativeImage skinImg = fetchSkin(url);
            if (skinImg == null) return null;

            int sw = skinImg.getWidth();
            int sh = skinImg.getHeight();
            if (sw < 16 || sh < 16) return null;

            boolean hasHat = sw >= 48 && sh >= 16;

            // Face lives at (8,8)-(16,16); hat overlay at (40,8)-(48,16).
            // Upscale 2× into a 16×16 output.
            BufferedImage out = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < 8; y++) {
                for (int x = 0; x < 8; x++) {
                    int face = nativeToArgb(skinImg.getPixelRGBA(8 + x, 8 + y));
                    int argb = face;
                    if (hasHat) {
                        int hat = nativeToArgb(skinImg.getPixelRGBA(40 + x, 8 + y));
                        argb = alphaOver(hat, face);
                    }
                    int ox = x * 2;
                    int oy = y * 2;
                    out.setRGB(ox, oy, argb);
                    out.setRGB(ox + 1, oy, argb);
                    out.setRGB(ox, oy + 1, argb);
                    out.setRGB(ox + 1, oy + 1, argb);
                }
            }

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(out, "png", baos);
            String base64 = Base64.getEncoder().encodeToString(baos.toByteArray());

            int lastSlash = url.lastIndexOf('/');
            String hash = (lastSlash >= 0 && lastSlash < url.length() - 1)
                    ? url.substring(lastSlash + 1)
                    : url;
            String shortHash = hash.length() > 16 ? hash.substring(0, 16) : hash;

            return new TextureResult(base64, 16, 16, "head[" + shortHash + "]");
        } catch (Exception e) {
            LOG.warning("[DebugBridge] Head profile render failed: " + e.getMessage());
            return null;
        }
    }

    // ---- Pixel helpers ----

    private NativeImage fetchSkin(String url) throws Exception {
        NativeImage cached = SKIN_CACHE.get(url);
        if (cached != null) return cached;
        try (InputStream in = new URL(url).openStream()) {
            NativeImage img = NativeImage.read(in);
            NativeImage prev = SKIN_CACHE.putIfAbsent(url, img);
            if (prev != null) {
                img.close();
                return prev;
            }
            return img;
        }
    }

    /**
     * Composite a baked item model's sprites onto a single canvas, applying
     * per-quad tint via {@link ItemColors}. Needed because dyed leather armor,
     * potions, tipped arrows, spawn eggs, and firework stars encode their color
     * as a tint that's applied at render time — the sprites alone are grayscale
     * or uncolored.
     * <p>
     * Multi-layer items (e.g. leather armor's body + stitching) are handled by
     * compositing each quad's sprite in order over the canvas.
     */
    private TextureResult renderFromBakedModel(ItemStack stack, BakedModel model, Minecraft mc) throws Exception {
        List<BakedQuad> quads = model.getQuads(null, Direction.SOUTH, null);
        if (quads.isEmpty()) {
            quads = model.getQuads(null, null, null);
        }

        TextureAtlasSprite[] sprites;
        int[] tintIndices;
        if (quads.isEmpty()) {
            TextureAtlasSprite particle = model.getParticleIcon();
            if (particle == null) throw new Exception("No sprite found for item");
            sprites = new TextureAtlasSprite[]{particle};
            tintIndices = new int[]{-1};
        } else {
            sprites = new TextureAtlasSprite[quads.size()];
            tintIndices = new int[quads.size()];
            for (int i = 0; i < quads.size(); i++) {
                BakedQuad q = quads.get(i);
                sprites[i] = q.getSprite();
                tintIndices[i] = q.getTintIndex();
            }
        }

        NativeImage[] imgs = new NativeImage[sprites.length];
        int maxW = 0, maxH = 0;
        for (int i = 0; i < sprites.length; i++) {
            NativeImage img = getSpriteMainImage(sprites[i]);
            if (img == null) continue;
            imgs[i] = img;
            if (img.getWidth() > maxW) maxW = img.getWidth();
            if (img.getHeight() > maxH) maxH = img.getHeight();
        }
        if (maxW == 0 || maxH == 0) {
            throw new Exception("Sprite has no pixel data");
        }

        BufferedImage canvas = new BufferedImage(maxW, maxH, BufferedImage.TYPE_INT_ARGB);
        ItemColors itemColors = resolveItemColors(mc);

        for (int i = 0; i < sprites.length; i++) {
            NativeImage img = imgs[i];
            if (img == null) continue;
            int tintIndex = tintIndices[i];
            boolean applyTint = tintIndex >= 0 && itemColors != null;
            int tint = applyTint ? itemColors.getColor(stack, tintIndex) : 0xFFFFFFFF;
            int tr = (tint >> 16) & 0xFF;
            int tg = (tint >> 8) & 0xFF;
            int tb = tint & 0xFF;

            int w = img.getWidth();
            int h = img.getHeight();
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int argb = nativeToArgb(img.getPixelRGBA(x, y));
                    if (applyTint) {
                        int a = (argb >> 24) & 0xFF;
                        int r = ((argb >> 16) & 0xFF) * tr / 255;
                        int g = ((argb >> 8) & 0xFF) * tg / 255;
                        int b = (argb & 0xFF) * tb / 255;
                        argb = (a << 24) | (r << 16) | (g << 8) | b;
                    }
                    canvas.setRGB(x, y, alphaOver(argb, canvas.getRGB(x, y)));
                }
            }
        }

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(canvas, "png", baos);
        String base64 = Base64.getEncoder().encodeToString(baos.toByteArray());
        return new TextureResult(base64, maxW, maxH, getSpriteName(sprites[0]));
    }

    /**
     * Extract the full-resolution NativeImage from a TextureAtlasSprite using
     * reflection. In 1.19 the pixel data lives in a NativeImage[] field
     * (mipmap pyramid) — we take level 0.
     */
    private NativeImage getSpriteMainImage(TextureAtlasSprite sprite) throws Exception {
        if (spritePixelsField == null) {
            synchronized (Minecraft119ItemTextureProvider.class) {
                if (spritePixelsField == null) {
                    // Search the sprite's contents() object since 1.19.3+ moved pixel
                    // storage to SpriteContents. If that doesn't exist, fall back to
                    // searching the sprite itself.
                    Object contents = null;
                    try {
                        contents = sprite.getClass().getMethod("contents").invoke(sprite);
                    } catch (NoSuchMethodException ignored) {
                        // Pre-1.19.3: no SpriteContents, pixels live on sprite itself
                    }

                    Class<?> searchClass = (contents != null) ? contents.getClass() : sprite.getClass();
                    Field found = findNativeImageArrayField(searchClass);
                    if (found == null && contents != null) {
                        // Try sprite class as a backup
                        found = findNativeImageArrayField(sprite.getClass());
                    }
                    if (found == null) {
                        throw new Exception("Cannot locate NativeImage[] field on sprite");
                    }
                    found.setAccessible(true);
                    spritePixelsField = found;
                    LOG.info("[DebugBridge] Item texture provider reflection initialized: " + found);
                }
            }
        }

        // Resolve which object holds the field (contents vs sprite itself)
        Object target;
        if (spritePixelsField.getDeclaringClass().isInstance(sprite)) {
            target = sprite;
        } else {
            // Must be on contents()
            target = sprite.getClass().getMethod("contents").invoke(sprite);
        }

        NativeImage[] mipmaps = (NativeImage[]) spritePixelsField.get(target);
        if (mipmaps == null || mipmaps.length == 0) return null;
        return mipmaps[0];
    }

    private String getSpriteName(TextureAtlasSprite sprite) {
        // 1.19.3+: sprite.contents().name()
        try {
            Object contents = sprite.getClass().getMethod("contents").invoke(sprite);
            if (contents != null) {
                Object name = contents.getClass().getMethod("name").invoke(contents);
                if (name != null) return name.toString();
            }
        } catch (Exception ignored) {
        }
        // 1.19.0-1.19.2: sprite.getName()
        try {
            Object name = sprite.getClass().getMethod("getName").invoke(sprite);
            if (name != null) return name.toString();
        } catch (Exception ignored) {
        }
        return "";
    }

    @FunctionalInterface
    private interface StackSupplier {
        ItemStack get() throws Exception;
    }

    private record StackOrResult(ItemStack stack, TextureResult result) {
    }
}
