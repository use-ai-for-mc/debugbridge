package com.debugbridge.core;

import static org.junit.jupiter.api.Assertions.*;

import com.debugbridge.core.mapping.PassthroughResolver;
import com.debugbridge.core.script.DirectDispatcher;
import com.debugbridge.core.server.BridgeServer;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.junit.jupiter.api.*;

/**
 * Verifies that all error scenarios return useful JSON errors to the MCP caller
 * without crashing the game/server. Exercised through the Groovy {@code execute}
 * endpoint over a live WebSocket.
 */
class ErrorHandlingTest {
    private static final int PORT = 19877;
    private static BridgeServer server;
    private TestClient client;

    @BeforeAll
    static void startServer() throws Exception {
        server = new BridgeServer(PORT, new PassthroughResolver("test"), new DirectDispatcher());
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

    // ==================== Groovy compile / runtime errors ====================

    @Test
    void testSyntaxError() throws Exception {
        JsonObject resp = execute("return 1 +");
        assertFalse(resp.get("success").getAsBoolean());
        assertFalse(resp.get("error").getAsString().isEmpty());
        System.out.println("Syntax error: " + resp.get("error").getAsString());
    }

    @Test
    void testUnterminatedString() throws Exception {
        JsonObject resp = execute("def x = 'unterminated");
        assertFalse(resp.get("success").getAsBoolean());
        System.out.println("Unterminated string: " + resp.get("error").getAsString());
    }

    @Test
    void testRuntimeError() throws Exception {
        JsonObject resp = execute("throw new RuntimeException('something went wrong')");
        assertFalse(resp.get("success").getAsBoolean());
        assertTrue(resp.get("error").getAsString().contains("something went wrong"));
    }

    @Test
    void testNullIndexing() throws Exception {
        JsonObject resp = execute("def x = null\nreturn x.foo");
        assertFalse(resp.get("success").getAsBoolean());
        System.out.println("Null access: " + resp.get("error").getAsString());
    }

    @Test
    void testInfiniteLoopTimesOut() throws Exception {
        server.getScriptRuntime().setMaxExecutionTimeMs(3000);
        try {
            long start = System.currentTimeMillis();
            JsonObject resp = execute("while (true) {}");
            long elapsed = System.currentTimeMillis() - start;
            assertFalse(resp.get("success").getAsBoolean());
            String error = resp.get("error").getAsString();
            System.out.println("Infinite loop (took " + elapsed + "ms): " + error);
            assertTrue(
                    error.contains("timed out") || error.contains("interrupted") || error.contains("Timeout"),
                    "Should mention timeout: " + error);
        } finally {
            server.getScriptRuntime().setMaxExecutionTimeMs(10000);
        }
    }

    // ==================== Java bridge errors ====================

    @Test
    void testClassNotFound() throws Exception {
        JsonObject resp = execute("java.type('com.nonexistent.FooBar')");
        assertFalse(resp.get("success").getAsBoolean());
        String error = resp.get("error").getAsString();
        assertTrue(error.contains("not found") || error.contains("FooBar"), "Should mention missing class: " + error);
    }

    @Test
    void testMethodNotFound() throws Exception {
        JsonObject resp = execute("""
                def list = java.type("java.util.ArrayList").create()
                list.nonExistentMethod()
                """);
        assertFalse(resp.get("success").getAsBoolean());
        String error = resp.get("error").getAsString();
        assertTrue(error.contains("nonExistentMethod"), "Should mention method name: " + error);
    }

    @Test
    void testMethodWrongArgCount() throws Exception {
        JsonObject resp = execute("""
                def list = java.type("java.util.ArrayList").create()
                list.add("a", "b", "c", "d", "e")
                """);
        assertFalse(resp.get("success").getAsBoolean());
        System.out.println("Wrong arg count: " + resp.get("error").getAsString());
    }

    @Test
    void testSecurityBlocked() throws Exception {
        JsonObject resp = execute("java.type('java.lang.Runtime')");
        assertFalse(resp.get("success").getAsBoolean());
        String error = resp.get("error").getAsString();
        assertTrue(error.contains("blocked") || error.contains("security"), "Should mention security: " + error);
    }

    @Test
    void testSecurityBlockedProcessBuilder() throws Exception {
        JsonObject resp = execute("java.type('java.lang.ProcessBuilder')");
        assertFalse(resp.get("success").getAsBoolean());
    }

    @Test
    void testNativeRuntimeBlockedBySandbox() throws Exception {
        // Groovy auto-imports java.lang.*, so `Runtime` is reachable inline; the
        // SecureASTCustomizer import blacklist must reject it at compile time.
        JsonObject resp = execute("Runtime.getRuntime()");
        assertFalse(resp.get("success").getAsBoolean());
        System.out.println("Native Runtime block: " + resp.get("error").getAsString());
    }

    @Test
    void testFileIOAllowed() throws Exception {
        JsonObject resp = execute("return java.type('java.io.File') != null");
        assertTrue(resp.get("success").getAsBoolean(), "java.io.File should be importable: " + resp.get("error"));
    }

    @Test
    void testSystemClassAllowed() throws Exception {
        JsonObject resp = execute("return java.type('java.lang.System').currentTimeMillis()");
        assertTrue(resp.get("success").getAsBoolean(), "java.lang.System should be importable: " + resp.get("error"));
    }

    @Test
    void testFileIoRoundTripAllowed() throws Exception {
        // java.io / java.nio are intentionally allowed so scripts can write scratch files.
        java.nio.file.Path tmp = java.nio.file.Files.createTempFile("debugbridge-iotest", ".txt");
        String path = tmp.toAbsolutePath().toString().replace("\\", "\\\\");
        try {
            JsonObject resp =
                    execute("def f = new File('" + path + "')\n" + "f.text = 'hello from groovy'\n" + "return f.text");
            assertTrue(resp.get("success").getAsBoolean(), "file round-trip should succeed: " + resp.get("error"));
            assertEquals(
                    "hello from groovy",
                    resp.get("result").getAsJsonObject().get("value").getAsString());
        } finally {
            java.nio.file.Files.deleteIfExists(tmp);
        }
    }

    @Test
    void testListOnNonCollection() throws Exception {
        JsonObject resp = execute("""
                def map = java.type("java.util.HashMap").create()
                java.list(map)
                """);
        assertFalse(resp.get("success").getAsBoolean());
        String error = resp.get("error").getAsString();
        assertTrue(error.contains("Collection") || error.contains("array"), "Should mention Collection: " + error);
    }

    // ==================== Edge cases ====================

    @Test
    void testEmptyScript() throws Exception {
        JsonObject resp = execute("");
        assertTrue(resp.get("success").getAsBoolean());
    }

    @Test
    void testJustComments() throws Exception {
        JsonObject resp = execute("// just a comment\n// another comment");
        assertTrue(resp.get("success").getAsBoolean());
    }

    @Test
    void testMultipleErrorsInSequence() throws Exception {
        for (int i = 0; i < 5; i++) {
            JsonObject resp = execute("throw new RuntimeException('error " + i + "')");
            assertFalse(resp.get("success").getAsBoolean());
        }
        JsonObject resp = execute("return 'still alive'");
        assertTrue(resp.get("success").getAsBoolean());
        assertEquals(
                "still alive", resp.get("result").getAsJsonObject().get("value").getAsString());
    }

    @Test
    void testErrorPreservesState() throws Exception {
        execute("survivor = 'I made it'");
        execute("throw new RuntimeException('kaboom')");
        JsonObject resp = execute("return survivor");
        assertTrue(resp.get("success").getAsBoolean());
        assertEquals(
                "I made it", resp.get("result").getAsJsonObject().get("value").getAsString());
    }

    @Test
    void testJavaExceptionInMethod() throws Exception {
        JsonObject resp = execute("""
                def list = java.type("java.util.ArrayList").create()
                return list.get(0)
                """);
        assertFalse(resp.get("success").getAsBoolean());
        String error = resp.get("error").getAsString();
        assertTrue(
                error.contains("IndexOutOfBounds") || error.contains("threw") || error.contains("range"),
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
        public void onOpen(ServerHandshake h) {}

        @Override
        public void onMessage(String msg) {
            responses.offer(msg);
        }

        @Override
        public void onClose(int c, String r, boolean rem) {}

        @Override
        public void onError(Exception e) {
            e.printStackTrace();
        }
    }
}
