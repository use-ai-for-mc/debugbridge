package com.debugbridge.core.mapping;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Parses Mojang's ProGuard mapping files.
 * <p>
 * Format:
 * net.minecraft.client.Minecraft -> eev:
 * net.minecraft.client.player.LocalPlayer player -> s
 * void setScreen(net.minecraft.client.gui.screens.Screen) -> a
 * 45:67:void tick() -> b
 */
public class ProGuardParser {

    public static ParsedMappings parse(String proguardContent) {
        Map<String, String> classes = new LinkedHashMap<>();
        Map<String, String> classesReverse = new HashMap<>();
        Map<String, Map<String, String>> fields = new HashMap<>();
        Map<String, Map<String, String>> methods = new HashMap<>();
        // Store type info for Fabric resolver compatibility
        Map<String, Map<String, String>> fieldTypes = new HashMap<>();
        Map<String, Map<String, String>> methodDescriptors = new HashMap<>();

        String currentMojangClass = null;

        for (String line : proguardContent.split("\n")) {
            line = line.stripTrailing();
            if (line.startsWith("#") || line.isEmpty()) continue;

            if (!line.startsWith(" ")) {
                // Class line: "net.minecraft.Foo -> abc:"
                int arrowIdx = line.indexOf(" -> ");
                if (arrowIdx < 0) continue;
                String mojang = line.substring(0, arrowIdx).trim();
                String obf = line.substring(arrowIdx + 4).replace(":", "").trim();
                classes.put(mojang, obf);
                classesReverse.put(obf, mojang);
                currentMojangClass = mojang;
                fields.putIfAbsent(mojang, new LinkedHashMap<>());
                methods.putIfAbsent(mojang, new LinkedHashMap<>());
                fieldTypes.putIfAbsent(mojang, new HashMap<>());
                methodDescriptors.putIfAbsent(mojang, new HashMap<>());
            } else if (currentMojangClass != null) {
                // Member line
                String trimmed = line.trim();

                // Strip line number ranges (e.g., "45:67:" or "1:2:3:4:")
                trimmed = trimmed.replaceFirst("^(\\d+:\\d+:)+", "");

                int arrowIdx = trimmed.indexOf(" -> ");
                if (arrowIdx < 0) continue;
                String obf = trimmed.substring(arrowIdx + 4).trim();
                String left = trimmed.substring(0, arrowIdx).trim();

                if (left.contains("(")) {
                    // Method: "returnType name(params) -> obf"
                    int spaceIdx = left.indexOf(' ');
                    String returnType = left.substring(0, spaceIdx);
                    String rest = left.substring(spaceIdx + 1);
                    int parenOpen = rest.indexOf('(');
                    String name = rest.substring(0, parenOpen);
                    String params = rest.substring(parenOpen + 1, rest.length() - 1);

                    // Key: "name(paramTypes)" for disambiguation
                    String key = name + "(" + params + ")";
                    methods.get(currentMojangClass).put(key, obf);
                    methodDescriptors.computeIfAbsent(currentMojangClass, k -> new HashMap<>())
                            .put(key, "(" + params + ")" + returnType);
                } else {
                    // Field: "type name -> obf"
                    String[] parts = left.split("\\s+");
                    if (parts.length >= 2) {
                        String type = parts[0];
                        String name = parts[1];
                        fields.get(currentMojangClass).put(name, obf);
                        fieldTypes.computeIfAbsent(currentMojangClass, k -> new HashMap<>())
                                .put(name, type);
                    }
                }
            }
        }

        return new ParsedMappings(classes, classesReverse, fields, methods, fieldTypes, methodDescriptors);
    }
}
