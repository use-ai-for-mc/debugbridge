package com.debugbridge.core.mapping;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Local file cache for downloaded ProGuard mappings.
 * Stores in ~/.debugbridge/mappings/<version>.txt
 */
public class MappingCache {
    private final Path cacheDir;
    
    public MappingCache() {
        this(Path.of(System.getProperty("user.home"), ".debugbridge", "mappings"));
    }
    
    public MappingCache(Path cacheDir) {
        this.cacheDir = cacheDir;
        try {
            Files.createDirectories(cacheDir);
        } catch (IOException e) {
            throw new RuntimeException("Failed to create mapping cache directory: " + cacheDir, e);
        }
    }
    
    public boolean has(String version) {
        return Files.exists(cacheDir.resolve(version + ".txt"));
    }
    
    public String load(String version) throws IOException {
        return Files.readString(cacheDir.resolve(version + ".txt"));
    }
    
    public void save(String version, String content) throws IOException {
        Files.writeString(cacheDir.resolve(version + ".txt"), content);
    }
    
    public Path getCacheDir() {
        return cacheDir;
    }
}
