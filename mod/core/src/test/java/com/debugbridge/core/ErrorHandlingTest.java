package com.debugbridge.core;

import com.debugbridge.core.lua.DirectDispatcher;
import com.debugbridge.core.mapping.PassthroughResolver;
import com.debugbridge.core.server.BridgeServer;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.junit.jupiter.api.*;

import java.net.URI;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that all error scenarios return useful JSON errors to the MCP caller
 * without crashing the game/server.
 */
class ErrorHandlingTest {
    private static final int PORT = 19877;
    private static BridgeServer server;
    private TestClient client;

    @BeforeAll
    static void startServer() throws Exception {
        server = new BridgeServer(PORT,
                new PassthroughResolver("test"),
                new DirectDispatcher());
        server.start();
        Thread.sleep(500);
    }

    @AfterAll
    static void stopServer() throws Exception {
        if (server != null) server.stop();
    }

    @BeforeEach
    void connect() throws Exception {
        client = new TestClient(new URI("ws://127.0.0.1:" + PORT));
        assertTrue(client.connectBlocking(3, TimeUnit.SECONDS));
    }

    @AfterEach
    void disconnect() throws Exception {
        if (client != null) client.closeBlocking();
    }

    // ==================== Lua syntax errors ====================

    @Test
    void testSyntaxError() throws Exception {
        JsonObject resp = execute("this is not valid lua !!!");
        assertFalse(resp.get("success").getAsBoolean());
        String error = resp.get("error").getAsString();
        assertTrue(error.contains("script"), "Should mention script location: " + error);
        System.out.println("Syntax error: " + error);
    }

    @Test
    void testUnterminatedString() throws Exception {
        JsonObject resp = execute("local x = 'unterminated");
        assertFalse(resp.get("success").getAsBoolean());
        System.out.println("Unterminated string: " + resp.get("error").getAsString());
    }

    @Test
    void testRuntimeErrorInLua() throws Exception {
        JsonObject resp = execute("error('something went wrong')");
        assertFalse(resp.get("success").getAsBoolean());
        assertTrue(resp.get("error").getAsString().contains("something went wrong"));
        System.out.println("Runtime error: " + resp.get("error").getAsString());
    }

    @Test
    void testNilIndexing() throws Exception {
        JsonObject resp = execute("local x = nil\nreturn x.foo");
        assertFalse(resp.get("success").getAsBoolean());
        System.out.println("Nil index: " + resp.get("error").getAsString());
    }

    @Test
    void testInfiniteLoopTimesOut() throws Exception {
        // Configure a short timeout on the server's Lua runtime
        server.getLuaRuntime().setMaxExecutionTimeMs(3000);
        try {
            long start = System.currentTimeMillis();
            JsonObject resp = execute("while true do end");
            long elapsed = System.currentTimeMillis() - start;
            assertFalse(resp.get("success").getAsBoolean());
            String error = resp.get("error").getAsString();
            System.out.println("Infinite loop (took " + elapsed + "ms): " + error);
            assertTrue(error.contains("timed out") || error.contains("interrupted")
                            || error.contains("Timeout"),
                    "Should mention timeout: " + error);
        } finally {
            server.getLuaRuntime().setMaxExecutionTimeMs(10000);
        }
    }

    // ==================== Java bridge errors ====================

    @Test
    void testClassNotFound() throws Exception {
        JsonObject resp = execute("java.import('com.nonexistent.FooBar')");
        assertFalse(resp.get("success").getAsBoolean());
        String error = resp.get("error").getAsString();
        assertTrue(error.contains("not found") || error.contains("FooBar"),
                "Should mention missing class: " + error);
        System.out.println("Class not found: " + error);
    }

    @Test
    void testMethodNotFound() throws Exception {
        JsonObject resp = execute("""
                local ArrayList = java.import("java.util.ArrayList")
                local list = java.new(ArrayList)
                list:nonExistentMethod()
                """);
        assertFalse(resp.get("success").getAsBoolean());
        String error = resp.get("error").getAsString();
        assertTrue(error.contains("nonExistentMethod"),
                "Should mention method name: " + error);
        System.out.println("Method not found: " + error);
    }

    @Test
    void testMethodWrongArgCount() throws Exception {
        JsonObject resp = execute("""
                local ArrayList = java.import("java.util.ArrayList")
                local list = java.new(ArrayList)
                list:add("a", "b", "c", "d", "e")
                """);
        assertFalse(resp.get("success").getAsBoolean());
        String error = resp.get("error").getAsString();
        System.out.println("Wrong arg count: " + error);
    }

    @Test
    void testFieldNotFound() throws Exception {
        // Accessing a non-existent field returns a MethodCallWrapper (which is then not callable)
        // This is by design — we try field first, then assume it's a method
        JsonObject resp = execute("""
                local ArrayList = java.import("java.util.ArrayList")
                local list = java.new(ArrayList)
                return list.nonExistentField
                """);
        // This returns a method wrapper, not an error — but calling it would fail
        System.out.println("Non-existent field access result: success=" + resp.get("success").getAsBoolean());

        // Now try calling it
        JsonObject resp2 = execute("""
                local ArrayList = java.import("java.util.ArrayList")
                local list = java.new(ArrayList)
                list.nonExistentField()
                """);
        assertFalse(resp2.get("success").getAsBoolean());
        System.out.println("Calling non-existent as method: " + resp2.get("error").getAsString());
    }

    @Test
    void testNullObjectAccess() throws Exception {
        JsonObject resp = execute("""
                local ArrayList = java.import("java.util.ArrayList")
                local list = java.new(ArrayList)
                local item = list:get(0)
                """);
        assertFalse(resp.get("success").getAsBoolean());
        String error = resp.get("error").getAsString();
        System.out.println("Null/OOB access: " + error);
    }

    @Test
    void testSecurityBlocked() throws Exception {
        JsonObject resp = execute("java.import('java.lang.Runtime')");
        assertFalse(resp.get("success").getAsBoolean());
        String error = resp.get("error").getAsString();
        assertTrue(error.contains("blocked") || error.contains("security"),
                "Should mention security: " + error);
        System.out.println("Security block: " + error);
    }

    @Test
    void testSecurityBlockedProcessBuilder() throws Exception {
        JsonObject resp = execute("java.import('java.lang.ProcessBuilder')");
        assertFalse(resp.get("success").getAsBoolean());
        System.out.println("ProcessBuilder block: " + resp.get("error").getAsString());
    }

    @Test
    void testFileIOAllowed() throws Exception {
        // java.io.* / java.nio.file.* are intentionally allowed so scripts can
        // write scratch files; only shell-out classes (Runtime, ProcessBuilder)
        // and network classes stay blocked.
        JsonObject resp = execute("local F = java.import('java.io.File'); return F ~= nil");
        assertTrue(resp.get("success").getAsBoolean(),
            "java.io.File should be importable: " + resp.get("error"));
    }

    @Test
    void testSystemClassAllowed() throws Exception {
        // java.lang.System is allowed so scripts can read the wall clock
        // (System:currentTimeMillis(), nanoTime()).
        JsonObject resp = execute("return java.import('java.lang.System'):currentTimeMillis()");
        assertTrue(resp.get("success").getAsBoolean(),
            "java.lang.System should be importable: " + resp.get("error"));
    }

    @Test
    void testLuaIoLibraryAvailable() throws Exception {
        // Lua's built-in io library is intentionally kept available so scripts
        // can write scratch files via io.open(...). os is still nil because it
        // exposes os.execute / os.exit.
        java.nio.file.Path tmp = java.nio.file.Files.createTempFile("debugbridge-iotest", ".txt");
        String luaPath = tmp.toAbsolutePath().toString().replace("\\", "\\\\");
        try {
            JsonObject resp = execute(
                "local f = io.open('" + luaPath + "', 'w')\n" +
                "f:write('hello from lua')\n" +
                "f:close()\n" +
                "local g = io.open('" + luaPath + "', 'r')\n" +
                "local data = g:read('*a')\n" +
                "g:close()\n" +
                "return data\n"
            );
            assertTrue(resp.get("success").getAsBoolean(),
                "io.open round-trip should succeed: " + resp.get("error"));
            assertEquals("hello from lua",
                resp.get("result").getAsJsonObject().get("value").getAsString());
        } finally {
            java.nio.file.Files.deleteIfExists(tmp);
        }
    }

    @Test
    void testOsLibraryStillBlocked() throws Exception {
        // Sanity: os.* (esp. os.execute) is still nil — the relaxation only
        // covers file I/O, not shell-out.
        JsonObject resp = execute("return os == nil");
        assertTrue(resp.get("success").getAsBoolean(),
            "os comparison should evaluate: " + resp.get("error"));
        assertEquals(true,
            resp.get("result").getAsJsonObject().get("value").getAsBoolean());
    }

    // ==================== Type conversion errors ====================

    @Test
    void testBadCast() throws Exception {
        JsonObject resp = execute("""
                local ArrayList = java.import("java.util.ArrayList")
                local list = java.new(ArrayList)
                java.cast(list, "com.nonexistent.Type")
                """);
        assertFalse(resp.get("success").getAsBoolean());
        System.out.println("Bad cast: " + resp.get("error").getAsString());
    }

    @Test
    void testIterOnNonIterable() throws Exception {
        // Use a HashMap (which is not Iterable — its entrySet() is, but the map itself isn't)
        JsonObject resp = execute("""
                local HashMap = java.import("java.util.HashMap")
                local map = java.new(HashMap)
                for x in java.iter(map) do end
                """);
        assertFalse(resp.get("success").getAsBoolean());
        String error = resp.get("error").getAsString();
        assertTrue(error.contains("Iterable") || error.contains("iter"),
                "Should mention Iterable: " + error);
        System.out.println("Iter on non-iterable: " + error);
    }

    @Test
    void testArrayOnNonCollection() throws Exception {
        JsonObject resp = execute("""
                local Integer = java.import("java.lang.Integer")
                local num = Integer:valueOf(42)
                java.array(num)
                """);
        assertFalse(resp.get("success").getAsBoolean());
        System.out.println("Array on non-collection: " + resp.get("error").getAsString());
    }

    // ==================== Edge cases ====================

    @Test
    void testEmptyScript() throws Exception {
        JsonObject resp = execute("");
        assertTrue(resp.get("success").getAsBoolean());
        System.out.println("Empty script: success (no output)");
    }

    @Test
    void testJustComments() throws Exception {
        JsonObject resp = execute("-- just a comment\n-- another comment");
        assertTrue(resp.get("success").getAsBoolean());
    }

    @Test
    void testMultipleErrorsInSequence() throws Exception {
        // Verify the server stays alive after multiple errors
        for (int i = 0; i < 5; i++) {
            JsonObject resp = execute("error('error " + i + "')");
            assertFalse(resp.get("success").getAsBoolean());
        }
        // Should still work after errors
        JsonObject resp = execute("return 'still alive'");
        assertTrue(resp.get("success").getAsBoolean());
        assertEquals("still alive",
                resp.get("result").getAsJsonObject().get("value").getAsString());
    }

    @Test
    void testErrorPreservesState() throws Exception {
        // Set a variable, then error, then check variable survives
        execute("survivor = 'I made it'");
        execute("error('kaboom')"); // This errors
        JsonObject resp = execute("return survivor");
        assertTrue(resp.get("success").getAsBoolean());
        assertEquals("I made it",
                resp.get("result").getAsJsonObject().get("value").getAsString());
    }

    @Test
    void testJavaExceptionInMethod() throws Exception {
        // ArrayList.get(0) on empty list throws IndexOutOfBoundsException
        JsonObject resp = execute("""
                local ArrayList = java.import("java.util.ArrayList")
                local list = java.new(ArrayList)
                return list:get(0)
                """);
        assertFalse(resp.get("success").getAsBoolean());
        String error = resp.get("error").getAsString();
        System.out.println("Java exception: " + error);
        // Should contain the Java exception info, not just "invoke failed"
        assertTrue(error.contains("IndexOutOfBounds") || error.contains("threw") || error.contains("range"),
                "Should contain meaningful Java exception info: " + error);
    }

    // ==================== Helpers ====================

    private JsonObject execute(String code) throws Exception {
        JsonObject req = new JsonObject();
        req.addProperty("id", "err_" + System.nanoTime());
        req.addProperty("type", "execute");
        JsonObject payload = new JsonObject();
        payload.addProperty("code", code);
        req.add("payload", payload);

        client.send(new Gson().toJson(req));
        String response = client.responses.poll(5, TimeUnit.SECONDS);
        assertNotNull(response, "No response within 5s");
        return JsonParser.parseString(response).getAsJsonObject();
    }

    private static class TestClient extends WebSocketClient {
        final LinkedBlockingQueue<String> responses = new LinkedBlockingQueue<>();

        TestClient(URI uri) {
            super(uri);
        }

        @Override
        public void onOpen(ServerHandshake h) {
        }

        @Override
        public void onMessage(String msg) {
            responses.offer(msg);
        }

        @Override
        public void onClose(int c, String r, boolean rem) {
        }

        @Override
        public void onError(Exception e) {
            e.printStackTrace();
        }
    }
}
