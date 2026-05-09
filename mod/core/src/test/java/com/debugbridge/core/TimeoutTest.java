package com.debugbridge.core;

import com.debugbridge.core.lua.DirectDispatcher;
import com.debugbridge.core.lua.LuaRuntime;
import com.debugbridge.core.mapping.PassthroughResolver;
import com.debugbridge.core.refs.ObjectRefStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TimeoutTest {

    @Test
    @Timeout(10)
        // JUnit will kill the test if it takes > 10s
    void testInfiniteLoopTimesOut() {
        LuaRuntime runtime = new LuaRuntime(
                new PassthroughResolver("test"),
                new DirectDispatcher(),
                new ObjectRefStore()
        );
        runtime.setMaxExecutionTimeMs(2000); // 2 second timeout

        long start = System.currentTimeMillis();
        var result = runtime.execute("while true do end");
        long elapsed = System.currentTimeMillis() - start;

        System.out.println("Elapsed: " + elapsed + "ms");
        System.out.println("Success: " + result.isSuccess());
        System.out.println("Error: " + result.error);
        System.out.println("Output: " + result.output);

        assertFalse(result.isSuccess());
        assertTrue(result.error.contains("timed out"),
                "Expected timeout error, got: " + result.error);
        assertTrue(elapsed < 5000,
                "Should have timed out within ~2s, took " + elapsed + "ms");
    }

    @Test
    @Timeout(10)
    void testPerCallTimeoutOverride() {
        LuaRuntime runtime = new LuaRuntime(
            new PassthroughResolver("test"),
            new DirectDispatcher(),
            new ObjectRefStore()
        );
        // Configure a generous default; the per-call override must trump it.
        runtime.setMaxExecutionTimeMs(60_000);

        long start = System.currentTimeMillis();
        var result = runtime.execute("while true do end", 1500);
        long elapsed = System.currentTimeMillis() - start;

        assertFalse(result.isSuccess());
        assertTrue(result.error.contains("1500ms"),
            "Error should reflect the override, got: " + result.error);
        assertTrue(elapsed < 4000,
            "Should have timed out near 1.5s, took " + elapsed + "ms");
    }

    @Test
    @Timeout(10)
    void testPerCallTimeoutDefaultsWhenZero() {
        LuaRuntime runtime = new LuaRuntime(
            new PassthroughResolver("test"),
            new DirectDispatcher(),
            new ObjectRefStore()
        );
        runtime.setMaxExecutionTimeMs(1000);

        // Passing 0 (or anything <= 0) means "use the runtime default".
        var result = runtime.execute("while true do end", 0);
        assertFalse(result.isSuccess());
        assertTrue(result.error.contains("1000ms"),
            "Should fall back to default timeout, got: " + result.error);
    }
}
