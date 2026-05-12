package com.debugbridge.core;

import com.debugbridge.core.mapping.ParsedMappings;
import com.debugbridge.core.mapping.PassthroughResolver;
import com.debugbridge.core.mapping.ProGuardParser;
import com.debugbridge.core.mapping.StandaloneMojangResolver;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ProGuardParserTest {
    
    private static final String SAMPLE_MAPPINGS = """
            # This is a comment
            net.minecraft.client.Minecraft -> eev:
                net.minecraft.client.player.LocalPlayer player -> s
                int fps -> a
                void setScreen(net.minecraft.client.gui.screens.Screen) -> a
                net.minecraft.client.Minecraft getInstance() -> D
                45:67:void tick() -> b
            net.minecraft.client.player.LocalPlayer -> fdm:
                float health -> r
                java.lang.String getName() -> e
                void attack(net.minecraft.world.entity.Entity) -> c
                int getHealth() -> bF
            net.minecraft.world.entity.Entity -> bfh:
                double x -> aM
                double y -> aN
                double z -> aO
                void tick() -> l
                int getId() -> af
            """;
    
    @Test
    void testParseClasses() {
        ParsedMappings m = ProGuardParser.parse(SAMPLE_MAPPINGS);
        
        assertEquals("eev", m.classes.get("net.minecraft.client.Minecraft"));
        assertEquals("fdm", m.classes.get("net.minecraft.client.player.LocalPlayer"));
        assertEquals("bfh", m.classes.get("net.minecraft.world.entity.Entity"));
        
        // Reverse
        assertEquals("net.minecraft.client.Minecraft", m.classesReverse.get("eev"));
    }
    
    @Test
    void testParseFields() {
        ParsedMappings m = ProGuardParser.parse(SAMPLE_MAPPINGS);
        
        assertEquals("s", m.fields.get("net.minecraft.client.Minecraft").get("player"));
        assertEquals("a", m.fields.get("net.minecraft.client.Minecraft").get("fps"));
        assertEquals("r", m.fields.get("net.minecraft.client.player.LocalPlayer").get("health"));
        assertEquals("aM", m.fields.get("net.minecraft.world.entity.Entity").get("x"));
    }
    
    @Test
    void testParseMethods() {
        ParsedMappings m = ProGuardParser.parse(SAMPLE_MAPPINGS);
        
        // Method with params
        assertEquals("a", m.methods.get("net.minecraft.client.Minecraft")
                .get("setScreen(net.minecraft.client.gui.screens.Screen)"));
        
        // Static method no params
        assertEquals("D", m.methods.get("net.minecraft.client.Minecraft")
                .get("getInstance()"));
        
        // Method with line numbers stripped
        assertEquals("b", m.methods.get("net.minecraft.client.Minecraft")
                .get("tick()"));
        
        // Method on LocalPlayer
        assertEquals("bF", m.methods.get("net.minecraft.client.player.LocalPlayer")
                .get("getHealth()"));
    }
    
    @Test
    void testFindMethodOverloads() {
        ParsedMappings m = ProGuardParser.parse(SAMPLE_MAPPINGS);
        var overloads = m.findMethodOverloads("net.minecraft.client.Minecraft", "setScreen");
        assertEquals(1, overloads.size());
        assertEquals("a", overloads.get(0));
    }
    
    @Test
    void testStandaloneResolver() {
        ParsedMappings m = ProGuardParser.parse(SAMPLE_MAPPINGS);
        StandaloneMojangResolver resolver = new StandaloneMojangResolver("1.19", m);
        
        // Class resolution
        assertEquals("eev", resolver.resolveClass("net.minecraft.client.Minecraft"));
        assertEquals("fdm", resolver.resolveClass("net.minecraft.client.player.LocalPlayer"));
        assertEquals("java.util.List", resolver.resolveClass("java.util.List")); // not in mappings
        
        // Field resolution
        assertEquals("s", resolver.resolveField("net.minecraft.client.Minecraft", "player"));
        assertEquals("aM", resolver.resolveField("net.minecraft.world.entity.Entity", "x"));
        assertEquals("unknownField", resolver.resolveField("net.minecraft.client.Minecraft", "unknownField"));
        
        // Method resolution
        assertEquals("D", resolver.resolveMethod("net.minecraft.client.Minecraft", "getInstance", null));
        assertEquals("b", resolver.resolveMethod("net.minecraft.client.Minecraft", "tick", null));
        
        // Reverse
        assertEquals("net.minecraft.client.Minecraft", resolver.unresolveClass("eev"));
        assertEquals("net.minecraft.world.entity.Entity", resolver.unresolveClass("bfh"));
        assertEquals("java.lang.String", resolver.unresolveClass("java.lang.String"));
        
        // Metadata
        assertEquals("1.19", resolver.getVersion());
        assertTrue(resolver.isObfuscated());
        assertTrue(resolver.getAllClassNames().contains("net.minecraft.client.Minecraft"));
    }
    
    @Test
    void testPassthroughResolver() {
        PassthroughResolver resolver = new PassthroughResolver("26.1");
        assertEquals("any.class.Name", resolver.resolveClass("any.class.Name"));
        assertEquals("anyField", resolver.resolveField("any.Class", "anyField"));
        assertEquals("anyMethod", resolver.resolveMethod("any.Class", "anyMethod", null));
        assertEquals("any.class.Name", resolver.unresolveClass("any.class.Name"));
        assertEquals("26.1", resolver.getVersion());
        assertFalse(resolver.isObfuscated());
    }
}
