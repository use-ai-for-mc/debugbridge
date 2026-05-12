package com.debugbridge.core.block;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks which block positions should appear highlighted on the client.
 * Populated by BridgeServer requests and read by version-specific render
 * hooks: 1.19 uses a LevelRenderer mixin to draw line boxes; 1.21.11 hands
 * positions to the vanilla GameTestBlockHighlightRenderer each tick.
 */
public final class ClientBlockGlowManager {
    private static final Set<Pos> GLOWING =
            Collections.newSetFromMap(new ConcurrentHashMap<>());
    
    private ClientBlockGlowManager() {
    }
    
    public static void setGlow(int x, int y, int z, boolean glow) {
        Pos key = new Pos(x, y, z);
        if (glow) GLOWING.add(key);
        else GLOWING.remove(key);
    }
    
    public static boolean isGlowing(int x, int y, int z) {
        return GLOWING.contains(new Pos(x, y, z));
    }
    
    public static Set<Pos> snapshot() {
        return Set.copyOf(GLOWING);
    }
    
    public static void clear() {
        GLOWING.clear();
    }
    
    public record Pos(int x, int y, int z) {
    }
}
