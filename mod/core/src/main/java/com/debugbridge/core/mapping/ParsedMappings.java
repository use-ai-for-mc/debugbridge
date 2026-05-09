package com.debugbridge.core.mapping;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Result of parsing a ProGuard mappings file.
 * All maps use Mojang names as keys.
 */
public class ParsedMappings {
    /**
     * Mojang class name -> obfuscated class name
     */
    public final Map<String, String> classes;
    /**
     * Obfuscated class name -> Mojang class name
     */
    public final Map<String, String> classesReverse;
    /**
     * Mojang class -> { mojang field name -> obfuscated field name }
     */
    public final Map<String, Map<String, String>> fields;
    /**
     * Mojang class -> { "methodName(paramTypes)" -> obfuscated method name }
     */
    public final Map<String, Map<String, String>> methods;
    /**
     * Mojang class -> { field name -> mojang type name }
     */
    public final Map<String, Map<String, String>> fieldTypes;
    /**
     * Mojang class -> { "methodName(paramTypes)" -> "(paramTypes)returnType" }
     */
    public final Map<String, Map<String, String>> methodDescriptors;

    public ParsedMappings(
            Map<String, String> classes,
            Map<String, String> classesReverse,
            Map<String, Map<String, String>> fields,
            Map<String, Map<String, String>> methods,
            Map<String, Map<String, String>> fieldTypes,
            Map<String, Map<String, String>> methodDescriptors) {
        this.classes = Collections.unmodifiableMap(classes);
        this.classesReverse = Collections.unmodifiableMap(classesReverse);
        this.fields = Collections.unmodifiableMap(fields);
        this.methods = Collections.unmodifiableMap(methods);
        this.fieldTypes = Collections.unmodifiableMap(fieldTypes);
        this.methodDescriptors = Collections.unmodifiableMap(methodDescriptors);
    }

    /**
     * Get the simple method name from a qualified key like "getName()" -> "getName"
     */
    public static String simpleMethodName(String key) {
        int paren = key.indexOf('(');
        return paren >= 0 ? key.substring(0, paren) : key;
    }

    /**
     * Find all method names (without descriptor) that match a simple name in a class.
     * Returns the obfuscated names for all overloads.
     */
    public List<String> findMethodOverloads(String mojangClass, String methodName) {
        Map<String, String> classMethods = methods.get(mojangClass);
        if (classMethods == null) return Collections.emptyList();

        List<String> results = new ArrayList<>();
        String prefix = methodName + "(";
        for (Map.Entry<String, String> entry : classMethods.entrySet()) {
            if (entry.getKey().startsWith(prefix)) {
                results.add(entry.getValue());
            }
        }
        return results;
    }
}
