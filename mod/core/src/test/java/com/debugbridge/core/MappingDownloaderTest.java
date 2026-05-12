package com.debugbridge.core;

import com.debugbridge.core.mapping.*;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests downloading and parsing real Mojang mappings.
 * These tests hit the network so they're tagged for manual runs.
 */
class MappingDownloaderTest {
    
    @Test
    void testDownloadAndParse119Mappings() throws Exception {
        MappingCache cache = new MappingCache();
        String proguardContent;
        
        if (cache.has("1.19")) {
            System.out.println("Using cached 1.19 mappings");
            proguardContent = cache.load("1.19");
        } else {
            System.out.println("Downloading 1.19 mappings from Mojang...");
            MappingDownloader downloader = new MappingDownloader();
            proguardContent = downloader.download("1.19");
            cache.save("1.19", proguardContent);
            System.out.println("Downloaded and cached " + proguardContent.length() + " bytes");
        }
        
        assertFalse(proguardContent.isEmpty());
        assertTrue(proguardContent.contains("net.minecraft"));
        
        // Parse it
        ParsedMappings mappings = ProGuardParser.parse(proguardContent);
        
        // Verify some well-known classes exist
        assertTrue(mappings.classes.containsKey("net.minecraft.client.Minecraft"),
                "Should contain Minecraft class");
        assertTrue(mappings.classes.containsKey("net.minecraft.world.entity.Entity"),
                "Should contain Entity class");
        
        System.out.println("Parsed " + mappings.classes.size() + " classes");
        
        // Check Minecraft class has expected members
        assertNotNull(mappings.fields.get("net.minecraft.client.Minecraft"),
                "Minecraft should have fields");
        assertNotNull(mappings.methods.get("net.minecraft.client.Minecraft"),
                "Minecraft should have methods");
        
        // Print some interesting mappings for verification
        String mcObf = mappings.classes.get("net.minecraft.client.Minecraft");
        System.out.println("net.minecraft.client.Minecraft -> " + mcObf);
        
        var mcFields = mappings.fields.get("net.minecraft.client.Minecraft");
        if (mcFields.containsKey("player")) {
            System.out.println("  player -> " + mcFields.get("player"));
        }
        if (mcFields.containsKey("level")) {
            System.out.println("  level -> " + mcFields.get("level"));
        }
        
        var mcMethods = mappings.methods.get("net.minecraft.client.Minecraft");
        for (var entry : mcMethods.entrySet()) {
            if (entry.getKey().startsWith("getInstance")) {
                System.out.println("  " + entry.getKey() + " -> " + entry.getValue());
            }
        }
        
        // Build a resolver and test it
        StandaloneMojangResolver resolver = new StandaloneMojangResolver("1.19", mappings);
        assertEquals(mcObf, resolver.resolveClass("net.minecraft.client.Minecraft"));
        assertNotEquals("player", resolver.resolveField("net.minecraft.client.Minecraft", "player"));
        
        System.out.println("\nSample Entity fields:");
        var entityFields = mappings.fields.get("net.minecraft.world.entity.Entity");
        if (entityFields != null) {
            entityFields.entrySet().stream().limit(10).forEach(e ->
                    System.out.println("  " + e.getKey() + " -> " + e.getValue()));
        }
        
        System.out.println("\nSample Entity methods:");
        var entityMethods = mappings.methods.get("net.minecraft.world.entity.Entity");
        if (entityMethods != null) {
            entityMethods.entrySet().stream().limit(10).forEach(e ->
                    System.out.println("  " + e.getKey() + " -> " + e.getValue()));
        }
        
        // Verify search works
        assertTrue(resolver.getAllClassNames().size() > 1000,
                "Should have many classes, got: " + resolver.getAllClassNames().size());
    }
}
