package com.debugbridge.core.script;

import com.debugbridge.core.mapping.MappingResolver;
import com.debugbridge.core.refs.ObjectRefStore;
import groovy.lang.GroovyShell;
import groovy.lang.MissingMethodException;
import groovy.lang.MissingPropertyException;
import groovy.transform.ThreadInterrupt;
import java.io.PrintWriter;
import java.io.Writer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.codehaus.groovy.control.CompilerConfiguration;
import org.codehaus.groovy.control.MultipleCompilationErrorsException;
import org.codehaus.groovy.control.customizers.ASTTransformationCustomizer;
import org.codehaus.groovy.control.customizers.SecureASTCustomizer;
import org.codehaus.groovy.runtime.MethodClosure;

/**
 * A persistent Groovy execution environment with a Java/Minecraft bridge.
 * State persists across calls — undeclared assignments ({@code x = ...}) land in
 * the shared binding and survive to the next call. A per-call timeout interrupts
 * runaway scripts (loops are instrumented via {@link ThreadInterrupt}).
 *
 * <p>This is the Groovy successor to the old LuaRuntime; the wire contract
 * (code in, {@code returnValue}/{@code output}/{@code error} out) is unchanged.
 */
public class ScriptRuntime {
    private final GroovyShell shell;
    private final GroovyBridge bridge;
    private final StringBuilder printBuffer = new StringBuilder();
    private long maxExecutionTimeMs = 10_000;

    private volatile Thread scriptThread;

    public ScriptRuntime(MappingResolver resolver, ThreadDispatcher dispatcher, ObjectRefStore refs) {
        this.bridge = new GroovyBridge(resolver, dispatcher, refs);

        ScriptBinding binding = new ScriptBinding(bridge);
        JavaHelpers helpers = new JavaHelpers(bridge);
        binding.setVariable("java", helpers);
        // Top-level `sync { ... }` sugar for the game-thread batching helper.
        binding.setVariable("sync", new MethodClosure(helpers, "sync"));
        // Capture println/print output instead of writing to stdout.
        binding.setVariable("out", new PrintWriter(captureWriter(), true));

        this.shell = new GroovyShell(getClass().getClassLoader(), binding, compilerConfig());
    }

    private Writer captureWriter() {
        return new Writer() {
            @Override
            public void write(char[] cbuf, int off, int len) {
                printBuffer.append(cbuf, off, len);
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        };
    }

    private CompilerConfiguration compilerConfig() {
        CompilerConfiguration cfg = new CompilerConfiguration();
        // Inject interrupt checks into loops/methods so timeouts can stop runaway scripts.
        cfg.addCompilationCustomizers(new ASTTransformationCustomizer(ThreadInterrupt.class));

        // Best-effort sandbox mirroring SecurityPolicy: block dangerous imports,
        // including inline fully-qualified references (indirect import check).
        SecureASTCustomizer sec = new SecureASTCustomizer();
        sec.setIndirectImportCheckEnabled(true);
        sec.setDisallowedImports(SecurityPolicy.BLOCKED_IMPORTS);
        sec.setDisallowedStarImports(SecurityPolicy.BLOCKED_STAR_IMPORTS);
        cfg.addCompilationCustomizers(sec);
        return cfg;
    }

    public GroovyBridge getBridge() {
        return bridge;
    }

    public void setMaxExecutionTimeMs(long ms) {
        this.maxExecutionTimeMs = ms;
    }

    /** Execute Groovy code with the runtime's default timeout. */
    public ExecutionResult execute(String code) {
        return execute(code, maxExecutionTimeMs);
    }

    /** Execute Groovy code with an explicit per-call timeout (snapshotted per call). */
    public ExecutionResult execute(String code, long timeoutMs) {
        final long effectiveTimeoutMs = timeoutMs > 0 ? timeoutMs : maxExecutionTimeMs;
        printBuffer.setLength(0);

        ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "groovy-exec");
            t.setDaemon(true);
            return t;
        });

        Future<ExecutionResult> future = executor.submit(() -> {
            scriptThread = Thread.currentThread();
            try {
                Object result = shell.evaluate(code);
                return new ExecutionResult(result, printBuffer.toString(), null);
            } catch (MultipleCompilationErrorsException e) {
                return new ExecutionResult(null, printBuffer.toString(), "Compilation error: " + e.getMessage());
            } catch (MissingPropertyException | MissingMethodException e) {
                return new ExecutionResult(null, printBuffer.toString(), e.getMessage());
            } catch (StackOverflowError e) {
                return new ExecutionResult(
                        null,
                        printBuffer.toString(),
                        "Stack overflow — script has infinite recursion or is too deeply nested");
            } catch (OutOfMemoryError e) {
                return new ExecutionResult(
                        null, printBuffer.toString(), "Out of memory — script allocated too much data");
            } catch (Throwable e) {
                if (isInterrupt(e)) {
                    return new ExecutionResult(null, printBuffer.toString(), timeoutMessage(effectiveTimeoutMs));
                }
                return new ExecutionResult(null, printBuffer.toString(), describe(e));
            } finally {
                scriptThread = null;
            }
        });

        try {
            return future.get(effectiveTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            Thread t = scriptThread;
            if (t != null) t.interrupt();
            return new ExecutionResult(null, printBuffer.toString(), timeoutMessage(effectiveTimeoutMs));
        } catch (Exception e) {
            return new ExecutionResult(null, printBuffer.toString(), describe(e));
        } finally {
            executor.shutdownNow();
        }
    }

    private static boolean isInterrupt(Throwable e) {
        for (Throwable c = e; c != null && c.getCause() != c; c = c.getCause()) {
            if (c instanceof InterruptedException) return true;
            String msg = c.getMessage();
            if (msg != null && msg.toLowerCase().contains("interrupt")) return true;
            if (c.getCause() == null) break;
        }
        return false;
    }

    private static String timeoutMessage(long ms) {
        return "Execution timed out after " + ms + "ms — script may have an infinite loop or infinite recursion";
    }

    private static String describe(Throwable e) {
        Throwable c = e;
        while (c.getCause() != null && c.getCause() != c) c = c.getCause();
        String msg = c.getMessage();
        return c.getClass().getSimpleName() + (msg != null ? ": " + msg : "");
    }

    /** Result of executing a script. {@code returnValue} is a plain Java/Groovy object (or wrapper). */
    public static class ExecutionResult {
        public final Object returnValue;
        public final String output;
        public final String error;

        public ExecutionResult(Object returnValue, String output, String error) {
            this.returnValue = returnValue;
            this.output = output;
            this.error = error;
        }

        public boolean isSuccess() {
            return error == null;
        }
    }
}
