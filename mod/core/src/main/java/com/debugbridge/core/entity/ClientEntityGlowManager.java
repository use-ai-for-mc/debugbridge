package com.debugbridge.core.entity;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks which entity IDs should appear glowing on the client. Populated by
 * BridgeServer requests and read by version-specific EntityGlowMixin
 * implementations, which override Entity.isCurrentlyGlowing() to include
 * these IDs.
 */
public final class ClientEntityGlowManager {
    private static final Set<Integer> GLOWING =
            Collections.newSetFromMap(new ConcurrentHashMap<>());
    
    private ClientEntityGlowManager() {
    }
    
    public static void setGlow(int entityId, boolean glow) {
        if (glow) GLOWING.add(entityId);
        else GLOWING.remove(entityId);
    }
    
    public static boolean isGlowing(int entityId) {
        return GLOWING.contains(entityId);
    }
    
    public static void clear() {
        GLOWING.clear();
    }
}
