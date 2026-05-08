package com.debugbridge.core.lua;

import java.util.Set;

/**
 * Controls which Java classes can be accessed from Lua scripts.
 */
public class SecurityPolicy {
    // File I/O (java.io.*, java.nio.file.*) and java.lang.System are intentionally
    // allowed so Lua scripts can read/write scratch files and read the clock.
    // Shell-out (Runtime, ProcessBuilder) and network classes stay blocked.
    private static final Set<String> BLOCKED_PREFIXES = Set.of(
        "java.lang.Runtime",
        "java.lang.ProcessBuilder",
        "java.net.",
        "java.security.",
        "javax.net.",
        "sun.",
        "com.sun.",
        "jdk."
    );

    /**
     * Check if a class is safe to access from Lua.
     */
    public static boolean isAllowed(String className) {
        for (String prefix : BLOCKED_PREFIXES) {
            if (className.startsWith(prefix)) {
                return false;
            }
        }
        return true;
    }
}
