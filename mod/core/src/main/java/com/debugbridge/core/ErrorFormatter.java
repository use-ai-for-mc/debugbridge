package com.debugbridge.core;

/**
 * Centralized helper for turning Java exceptions into concise, user-facing
 * messages without leaking implementation details.
 */
public final class ErrorFormatter {
    private ErrorFormatter() {}

    /**
     * Returns a short, human-readable message from {@code throwable}.
     * <ul>
     *   <li>Common exception types are mapped to stable, user-facing labels.</li>
     *   <li>Underlying details come from the root-cause message when present.</li>
     *   <li>Class names are omitted unless the exception has no message.</li>
     * </ul>
     */
    public static String format(Throwable throwable) {
        if (throwable == null) return "unknown error";

        Throwable cause = rootCause(throwable);
        String message = cause.getMessage();
        String typeLabel = friendlyLabel(cause);
        if (message == null || message.isBlank()) {
            return typeLabel;
        }
        return typeLabel + ": " + message;
    }

    /**
     * Same as {@link #format(Throwable)} but prepends a caller-provided context.
     */
    public static String withContext(String context, Throwable throwable) {
        String detail = format(throwable);
        if (context == null || context.isBlank()) return detail;
        return context + ": " + detail;
    }

    private static Throwable rootCause(Throwable throwable) {
        Throwable cause = throwable;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause;
    }

    private static String friendlyLabel(Throwable throwable) {
        if (throwable instanceof NullPointerException) return "null reference";
        if (throwable instanceof IllegalArgumentException) return "invalid argument";
        if (throwable instanceof IllegalStateException) return "invalid state";
        if (throwable instanceof IndexOutOfBoundsException) return "index out of bounds";
        if (throwable instanceof UnsupportedOperationException) return "unsupported operation";
        if (throwable instanceof SecurityException) return "security exception";
        if (throwable instanceof RuntimeException) return "runtime error";
        return "error";
    }
}
