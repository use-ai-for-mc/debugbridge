package com.debugbridge.fabric1214;

import com.debugbridge.core.texture.ItemTextureProvider;

/**
 * Item rendering is intentionally deferred for the 1.21.4 port. The newer
 * GPU renderer API does not exist in this version and the bot/MCP control path
 * does not depend on item thumbnails.
 */
public final class Minecraft1214ItemTextureProvider implements ItemTextureProvider {
    private static UnsupportedOperationException unsupported() {
        return new UnsupportedOperationException("item textures are not supported by the 1.21.4 port");
    }

    @Override
    public TextureResult getItemTexture(int slot) {
        throw unsupported();
    }

    @Override
    public TextureResult getEntityItemTexture(int entityId, String slot) {
        throw unsupported();
    }

    @Override
    public TextureResult getItemTextureById(String itemId) {
        throw unsupported();
    }
}
