package com.debugbridge.core.texture;

/**
 * Resolves the displayed texture for an inventory item by walking Minecraft's
 * baked item-model tree (handling damage-based overrides, etc.) and reading
 * the pixel data from the resolved sprite.
 * <p>
 * Each version-specific mod provides its own implementation because the model
 * resolution APIs differ between MC versions.
 */
public interface ItemTextureProvider {

    /**
     * Get the resolved texture for the item in the given inventory slot.
     *
     * @param slot inventory slot index (0-40)
     * @return texture result with base64-encoded PNG and metadata
     * @throws Exception if extraction fails
     */
    TextureResult getItemTexture(int slot) throws Exception;

    /**
     * Get the resolved texture for an item equipped by a nearby entity.
     * Honors the stack's components (damage, custom_model_data, item_model, etc.)
     * so resource-pack overrides fire the same as in-game.
     *
     * @param entityId network entity ID
     * @param slot     equipment slot name (MAINHAND, OFFHAND, HEAD, CHEST, LEGS, FEET)
     * @return texture result with base64-encoded PNG and metadata
     * @throws Exception if extraction fails
     */
    TextureResult getEntityItemTexture(int entityId, String slot) throws Exception;

    /**
     * Get the resolved texture for a default {@link net.minecraft.world.item.ItemStack}
     * of the given item registry key. Used for thumbnail caching where per-stack
     * variation (damage, NBT, components) doesn't matter — we just need the icon
     * for the item type.
     *
     * @param itemId registry key, e.g. {@code minecraft:iron_helmet}
     * @return texture result with base64-encoded PNG and metadata
     * @throws Exception if the item is unknown or extraction fails
     */
    TextureResult getItemTextureById(String itemId) throws Exception;

    /**
     * Result of a successful texture extraction.
     */
    record TextureResult(String base64Png, int width, int height, String spriteName) {
    }
}
