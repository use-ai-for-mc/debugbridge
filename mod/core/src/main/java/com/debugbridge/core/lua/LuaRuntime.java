package com.debugbridge.core.lua;

import com.debugbridge.core.mapping.MappingResolver;
import com.debugbridge.core.refs.ObjectRefStore;
import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.jse.JsePlatform;

import java.util.concurrent.*;

/**
 * Manages a persistent Lua execution environment with Java bridge capabilities.
 * State persists across calls — variables defined in one call are available in subsequent calls.
 * Includes a timeout mechanism to kill runaway scripts.
 */
public class LuaRuntime {
    private final Globals globals;
    private final JavaBridge bridge;
    private final StringBuilder printBuffer = new StringBuilder();
    private long maxExecutionTimeMs = 10_000;

    // Thread used for Lua execution — we interrupt it on timeout
    private volatile Thread luaThread;

    public LuaRuntime(MappingResolver resolver, ThreadDispatcher dispatcher, ObjectRefStore refs) {
        this.globals = JsePlatform.standardGlobals();
        this.bridge = new JavaBridge(resolver, dispatcher, refs);

        // Register the "java" global table
        globals.set("java", bridge.createJavaTable());

        // Override print() to capture output
        globals.set("print", new PrintFunction());

        // Install a Lua debug hook that checks Thread.interrupted() periodically
        installInterruptHook();

        // Security: remove dangerous globals.
        // `io` is kept so scripts can read/write scratch files via the standard
        // Lua API; Java-side shell-out is still blocked by SecurityPolicy.
        // `os` carries os.execute / os.exit / os.remove, so we drop it — scripts
        // that need the wall clock can call java.lang.System:currentTimeMillis().
        globals.set("os", LuaValue.NIL);
        globals.set("luajava", LuaValue.NIL);
        globals.set("require", LuaValue.NIL);
        globals.set("loadfile", LuaValue.NIL);
        globals.set("dofile", LuaValue.NIL);

        // Install Minecraft convenience globals: mc / player / level resolve lazily
        // through _G's __index, so they always reflect the current game state
        // (player and level change across world loads / respawns) without having
        // to re-call Minecraft.getInstance() by hand.
        installMinecraftGlobals();
    }

    /**
     * Install a __index metatable on the globals so bare references to
     * {@code mc}, {@code player}, and {@code level} resolve dynamically.
     * Runs once at startup; failures (e.g. missing class, bad mappings) are
     * swallowed so the rest of the Lua environment stays usable.
     */
    private void installMinecraftGlobals() {
        String bootstrap =
                """
                        do
                          local ok, Minecraft = pcall(java.import, 'net.minecraft.client.Minecraft')
                          if not ok then return end
                          local gmt = getmetatable(_G) or {}
                          local prev = gmt.__index
                          gmt.__index = function(t, k)
                            if k == 'mc' then return Minecraft:getInstance() end
                            if k == 'player' then return Minecraft:getInstance().player end
                            if k == 'level' then return Minecraft:getInstance().level end
                            if prev then
                              if type(prev) == 'function' then return prev(t, k) end
                              return prev[k]
                            end
                            return nil
                          end
                          setmetatable(_G, gmt)
                        end
                        """;
        try {
            globals.load(bootstrap, "=mc-globals").invoke();
        } catch (Exception e) {
            // Non-fatal: users can still use java.import() directly.
        }
    }

    private void installInterruptHook() {
        // Register a function that Lua's debug hook will call
        globals.set("__check_interrupt", new org.luaj.vm2.lib.ZeroArgFunction() {
            @Override
            public LuaValue call() {
                if (Thread.currentThread().isInterrupted()) {
                    throw new LuaError("Script interrupted: execution timed out");
                }
                return LuaValue.NONE;
            }
        });

        // Set a count-based debug hook: fires every 10000 VM instructions
        try {
            globals.load(
                    "debug.sethook(__check_interrupt, '', 10000)",
                    "=hook"
            ).invoke();
        } catch (Exception e) {
            // debug lib may not be available; timeout won't work but bridge still functions
        }
    }

    public JavaBridge getBridge() {
        return bridge;
    }

    public void setMaxExecutionTimeMs(long ms) {
        this.maxExecutionTimeMs = ms;
    }

    /**
     * Execute Lua code using the runtime's default timeout
     * ({@link #setMaxExecutionTimeMs}). The Lua state persists — variables
     * survive across calls.
     */
    public ExecutionResult execute(String luaCode) {
        return execute(luaCode, maxExecutionTimeMs);
    }

    /**
     * Execute Lua code with an explicit per-call timeout. Use this when a
     * caller knows their script needs more headroom than the default
     * (e.g. bulk reflection over many entities). The supplied timeout is
     * snapshotted, so concurrent callers don't see each other's overrides.
     */
    public ExecutionResult execute(String luaCode, long timeoutMs) {
        final long effectiveTimeoutMs = timeoutMs > 0 ? timeoutMs : maxExecutionTimeMs;
        printBuffer.setLength(0);

        ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "lua-exec");
            t.setDaemon(true);
            return t;
        });

        Future<ExecutionResult> future = executor.submit(() -> {
            luaThread = Thread.currentThread();
            try {
                LuaValue chunk = globals.load(luaCode, "=script");
                Varargs result = chunk.invoke();

                LuaValue returnValue = result.arg1();
                return new ExecutionResult(
                        returnValue.isnil() ? null : returnValue,
                        printBuffer.toString(),
                        null
                );
            } catch (LuaError e) {
                String msg = e.getMessage();
                if (msg != null && msg.contains("interrupted")) {
                    return new ExecutionResult(null, printBuffer.toString(),
                        "Execution timed out after " + effectiveTimeoutMs
                        + "ms — script may have an infinite loop or infinite recursion");
                }
                return new ExecutionResult(null, printBuffer.toString(), msg);
            } catch (StackOverflowError e) {
                return new ExecutionResult(null, printBuffer.toString(),
                        "Stack overflow — script has infinite recursion or is too deeply nested");
            } catch (OutOfMemoryError e) {
                return new ExecutionResult(null, printBuffer.toString(),
                        "Out of memory — script allocated too much data");
            } catch (Exception e) {
                return new ExecutionResult(null, printBuffer.toString(),
                        e.getClass().getSimpleName() + ": " + e.getMessage());
            } finally {
                luaThread = null;
            }
        });

        try {
            return future.get(effectiveTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            // Also interrupt the lua thread directly
            Thread lt = luaThread;
            if (lt != null) lt.interrupt();
            return new ExecutionResult(null, printBuffer.toString(),
                "Execution timed out after " + effectiveTimeoutMs
                + "ms — script may have an infinite loop or infinite recursion");
        } catch (Exception e) {
            return new ExecutionResult(null, printBuffer.toString(),
                    e.getClass().getSimpleName() + ": " + e.getMessage());
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * Result of executing Lua code.
     */
    public static class ExecutionResult {
        public final LuaValue returnValue;
        public final String output;
        public final String error;

        public ExecutionResult(LuaValue returnValue, String output, String error) {
            this.returnValue = returnValue;
            this.output = output;
            this.error = error;
        }

        public boolean isSuccess() {
            return error == null;
        }
    }

    /**
     * Custom print function that captures output instead of writing to stdout.
     */
    private class PrintFunction extends org.luaj.vm2.lib.VarArgFunction {
        @Override
        public Varargs invoke(Varargs args) {
            StringBuilder line = new StringBuilder();
            for (int i = 1; i <= args.narg(); i++) {
                if (i > 1) line.append("\t");
                line.append(args.arg(i).tojstring());
            }
            printBuffer.append(line).append("\n");
            return LuaValue.NONE;
        }
    }
}
