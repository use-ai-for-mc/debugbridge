package com.debugbridge.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.debugbridge.core.mapping.PassthroughResolver;
import com.debugbridge.core.refs.ObjectRefStore;
import com.debugbridge.core.script.DirectDispatcher;
import com.debugbridge.core.script.ScriptRuntime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class TimeoutTest {

    private static ScriptRuntime newRuntime() {
        return new ScriptRuntime(new PassthroughResolver("test"), new DirectDispatcher(), new ObjectRefStore());
    }

    @Test
    @Timeout(10)
    void testInfiniteLoopTimesOut() {
        ScriptRuntime runtime = newRuntime();
        runtime.setMaxExecutionTimeMs(2000); // 2 second timeout

        long start = System.currentTimeMillis();
        var result = runtime.execute("while (true) {}");
        long elapsed = System.currentTimeMillis() - start;

        System.out.println("Elapsed: " + elapsed + "ms");
        System.out.println("Success: " + result.isSuccess());
        System.out.println("Error: " + result.error);

        assertFalse(result.isSuccess());
        assertTrue(result.error.contains("timed out"), "Expected timeout error, got: " + result.error);
        assertTrue(elapsed < 5000, "Should have timed out within ~2s, took " + elapsed + "ms");
    }

    @Test
    @Timeout(10)
    void testPerCallTimeoutOverride() {
        ScriptRuntime runtime = newRuntime();
        // Configure a generous default; the per-call override must trump it.
        runtime.setMaxExecutionTimeMs(60_000);

        long start = System.currentTimeMillis();
        var result = runtime.execute("while (true) {}", 1500);
        long elapsed = System.currentTimeMillis() - start;

        assertFalse(result.isSuccess());
        assertTrue(result.error.contains("1500ms"), "Error should reflect the override, got: " + result.error);
        assertTrue(elapsed < 4000, "Should have timed out near 1.5s, took " + elapsed + "ms");
    }

    @Test
    @Timeout(10)
    void testPerCallTimeoutDefaultsWhenZero() {
        ScriptRuntime runtime = newRuntime();
        runtime.setMaxExecutionTimeMs(1000);

        // Passing 0 (or anything <= 0) means "use the runtime default".
        var result = runtime.execute("while (true) {}", 0);
        assertFalse(result.isSuccess());
        assertTrue(result.error.contains("1000ms"), "Should fall back to default timeout, got: " + result.error);
    }
}
