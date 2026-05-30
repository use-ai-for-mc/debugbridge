package com.debugbridge.core.script;

import java.util.List;
import java.util.Set;

/**
 * Controls which Java classes can be reached from scripts.
 * <p>
 * File I/O ({@code java.io.*}, {@code java.nio.file.*}) and {@code java.lang.System}
 * are intentionally allowed so scripts can read/write scratch files and read the
 * clock. Shell-out ({@code Runtime}, {@code ProcessBuilder}) and network classes
 * stay blocked.
 * <p>
 * This is the same best-effort posture the Lua runtime had: the gate is the
 * {@code java.type(...)} import path. Groovy can additionally name JDK classes
 * inline (e.g. {@code java.lang.Runtime} is auto-imported), so {@link #BLOCKED_PREFIXES}
 * is also fed to a Groovy {@code SecureASTCustomizer} import blacklist in
 * {@link ScriptRuntime}. Determined scripts running on localhost can still find
 * gaps — this is a dev tool, not a security boundary.
 */
public class SecurityPolicy {
    private static final Set<String> BLOCKED_PREFIXES = Set.of(
            "java.lang.Runtime",
            "java.lang.ProcessBuilder",
            "java.net.",
            "java.security.",
            "javax.net.",
            "sun.",
            "com.sun.",
            "jdk.");

    /** Exact class names to feed a Groovy import blacklist. */
    public static final List<String> BLOCKED_IMPORTS = List.of("java.lang.Runtime", "java.lang.ProcessBuilder");

    /** Package roots to feed a Groovy star-import blacklist (also caught inline via the indirect-import check). */
    public static final List<String> BLOCKED_STAR_IMPORTS =
            List.of("java.net", "java.security", "javax.net", "sun", "com.sun", "jdk");

    /** Check if a class is safe to access from a script. */
    public static boolean isAllowed(String className) {
        for (String prefix : BLOCKED_PREFIXES) {
            if (className.startsWith(prefix)) {
                return false;
            }
        }
        return true;
    }
}
