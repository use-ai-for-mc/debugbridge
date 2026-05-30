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
 * Full integration test: starts the BridgeServer on a fixed port, connects a
 * WebSocket client, and exercises the protocol — including the Groovy
 * {@code execute} endpoint and the {@code java.*} bridge.
 */
class IntegrationTest {
    private static BridgeServer server;
    private static int port;
    private TestClient client;

    @BeforeAll
    static void startServer() throws Exception {
        port = 19876; // Use a non-default port to avoid conflicts
        PassthroughResolver resolver = new PassthroughResolver("test");
        DirectDispatcher dispatcher = new DirectDispatcher();
        server = new BridgeServer(port, resolver, dispatcher);
        server.start();
        Thread.sleep(500); // Wait for server to start
    }

    @AfterAll
    static void stopServer() throws Exception {
        if (server != null) server.stop();
    }

    @BeforeEach
    void connectClient() throws Exception {
        client = new TestClient(new URI("ws://127.0.0.1:" + port));
        assertTrue(client.connectBlocking(3, TimeUnit.SECONDS), "Failed to connect");
    }

    @AfterEach
    void disconnectClient() throws Exception {
        if (client != null) client.closeBlocking();
    }

    @Test
    void testStatus() throws Exception {
        JsonObject resp = sendRequest("status", new JsonObject());
        assertTrue(resp.get("success").getAsBoolean());
        JsonObject result = resp.get("result").getAsJsonObject();
        assertEquals("test", result.get("version").getAsString());
        assertEquals("passthrough", result.get("mappingStatus").getAsString());
        assertFalse(result.get("obfuscated").getAsBoolean());
    }

    @Test
    void testExecuteSimpleExpression() throws Exception {
        JsonObject resp = executeCode("return 1 + 2");
        assertTrue(resp.get("success").getAsBoolean(), "Error: " + resp.get("error"));
        JsonObject result = resp.get("result").getAsJsonObject();
        assertEquals("number", result.get("type").getAsString());
        assertEquals(3, result.get("value").getAsInt());
    }

    @Test
    void testExecuteWithPrintOutput() throws Exception {
        JsonObject resp = executeCode("println 'hello from groovy'");
        assertTrue(resp.get("success").getAsBoolean());
        assertTrue(resp.get("output").getAsString().contains("hello from groovy"));
    }

    @Test
    void testExecuteJavaBridge() throws Exception {
        JsonObject resp = executeCode("""
                def list = java.type("java.util.ArrayList").create()
                list.add("one")
                list.add("two")
                list.add("three")
                return list.size()
                """);
        assertTrue(resp.get("success").getAsBoolean(), "Error: " + resp.get("error"));
        assertEquals(3, resp.get("result").getAsJsonObject().get("value").getAsInt());
    }

    @Test
    void testExecuteReturnJavaObject() throws Exception {
        JsonObject resp = executeCode("return java.type(\"java.util.ArrayList\").create()");
        assertTrue(resp.get("success").getAsBoolean(), "Error: " + resp.get("error"));
        JsonObject result = resp.get("result").getAsJsonObject();
        assertEquals("object", result.get("type").getAsString());
        assertEquals("java.util.ArrayList", result.get("className").getAsString());
        assertTrue(result.get("ref").getAsString().startsWith("$ref_"));
    }

    @Test
    void testExecuteReturnTable() throws Exception {
        JsonObject resp = executeCode("return [name: 'test', value: 42]");
        assertTrue(resp.get("success").getAsBoolean(), "Error: " + resp.get("error"));
        assertEquals("table", resp.get("result").getAsJsonObject().get("type").getAsString());
    }

    @Test
    void testExecuteError() throws Exception {
        JsonObject resp = executeCode("throw new RuntimeException('test error')");
        assertFalse(resp.get("success").getAsBoolean());
        assertTrue(resp.get("error").getAsString().contains("test error"));
    }

    @Test
    void testPersistentState() throws Exception {
        JsonObject resp1 = executeCode("my_var = 42");
        assertTrue(resp1.get("success").getAsBoolean());

        JsonObject resp2 = executeCode("return my_var");
        assertTrue(resp2.get("success").getAsBoolean());
        assertEquals(42, resp2.get("result").getAsJsonObject().get("value").getAsInt());
    }

    @Test
    void testReflectionDescribe() throws Exception {
        JsonObject resp = executeCode("""
                def list = java.type("java.util.ArrayList").create()
                return java.describe(list)['class']
                """);
        assertTrue(resp.get("success").getAsBoolean(), "Error: " + resp.get("error"));
        assertEquals(
                "java.util.ArrayList",
                resp.get("result").getAsJsonObject().get("value").getAsString());
    }

    @Test
    void testReflectionMethods() throws Exception {
        JsonObject resp = executeCode("""
                def list = java.type("java.util.ArrayList").create()
                return java.methods(list, "add").size()
                """);
        assertTrue(resp.get("success").getAsBoolean(), "Error: " + resp.get("error"));
        assertTrue(resp.get("result").getAsJsonObject().get("value").getAsInt() > 0);
    }

    @Test
    void testReflectionSupers() throws Exception {
        JsonObject resp = executeCode("""
                def list = java.type("java.util.ArrayList").create()
                return java.supers(list).hierarchy
                """);
        assertTrue(resp.get("success").getAsBoolean(), "Error: " + resp.get("error"));
    }

    @Test
    void testReflectionFields() throws Exception {
        JsonObject resp = executeCode("""
                def list = java.type("java.util.ArrayList").create()
                return java.fields(list)
                """);
        assertTrue(resp.get("success").getAsBoolean(), "Error: " + resp.get("error"));
    }

    @Test
    void testComplexReflectionWorkflow() throws Exception {
        // Simulates what an agent would do to explore an unknown object.
        JsonObject resp = executeCode("""
                def map = java.type("java.util.HashMap").create()
                map.put("key1", "value1")
                map.put("key2", "value2")
                def getMethods = java.methods(map, "get")
                def val = map.get("key1")
                def desc = java.describe(map)
                return [value: val, classInfo: desc['class'], size: map.size()]
                """);
        assertTrue(resp.get("success").getAsBoolean(), "Error: " + resp.get("error"));
    }

    @Test
    void testSecurityBlock() throws Exception {
        JsonObject resp = executeCode("java.type('java.lang.Runtime')");
        assertFalse(resp.get("success").getAsBoolean());
        assertTrue(resp.get("error").getAsString().contains("blocked"));
    }

    @Test
    void testListOverWebSocket() throws Exception {
        JsonObject resp = executeCode("""
                def list = java.type("java.util.ArrayList").create()
                list.add("alpha")
                list.add("beta")
                list.add("gamma")
                return java.list(list).join(",")
                """);
        assertTrue(resp.get("success").getAsBoolean(), "Error: " + resp.get("error"));
        assertEquals(
                "alpha,beta,gamma",
                resp.get("result").getAsJsonObject().get("value").getAsString());
    }

    @Test
    void testSnapshotWithoutProvider() throws Exception {
        JsonObject resp = sendRequest("snapshot", new JsonObject());
        assertFalse(resp.get("success").getAsBoolean());
        assertTrue(resp.get("error").getAsString().contains("provider"));
    }

    // ==================== Helpers ====================

    private JsonObject executeCode(String code) throws Exception {
        JsonObject payload = new JsonObject();
        payload.addProperty("code", code);
        return sendRequest("execute", payload);
    }

    private JsonObject sendRequest(String type, JsonObject payload) throws Exception {
        JsonObject req = new JsonObject();
        req.addProperty("id", "test_" + System.nanoTime());
        req.addProperty("type", type);
        req.add("payload", payload);

        client.send(new Gson().toJson(req));
        String response = client.responses.poll(5, TimeUnit.SECONDS);
        assertNotNull(response, "No response received within 5s");
        return JsonParser.parseString(response).getAsJsonObject();
    }

    private static class TestClient extends WebSocketClient {
        final LinkedBlockingQueue<String> responses = new LinkedBlockingQueue<>();

        TestClient(URI uri) {
            super(uri);
        }

        @Override
        public void onOpen(ServerHandshake handshake) {}

        @Override
        public void onMessage(String message) {
            responses.offer(message);
        }

        @Override
        public void onClose(int code, String reason, boolean remote) {}

        @Override
        public void onError(Exception ex) {
            ex.printStackTrace();
        }
    }
}
