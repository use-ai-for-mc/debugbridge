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
 * Regression tests for the input-validation and injection hardening on
 * {@code search} and {@code runCommand}. These cover the wire-facing surface;
 * deeper sandbox hardening is tracked separately in the dream review queue.
 */
class SecurityHardeningTest {
    private static BridgeServer server;
    private static final int PORT = 19884;
    private static final LinkedBlockingQueue<String> commands = new LinkedBlockingQueue<>();
    private TestClient client;

    @BeforeAll
    static void startServer() throws Exception {
        server = new BridgeServer(PORT, new PassthroughResolver("test"), new DirectDispatcher());
        // runCommand is gated off by default; flip it on so we can exercise the
        // native provider path. The validity of the gating itself is covered
        // separately.
        server.setRunCommandEnabled(true);
        server.setCommandProvider(command -> commands.offer(command));
        server.start();
        Thread.sleep(500);
    }

    @AfterAll
    static void stopServer() throws Exception {
        if (server != null) server.stop();
    }

    @BeforeEach
    void connect() throws Exception {
        commands.clear();
        client = new TestClient(new URI("ws://127.0.0.1:" + PORT));
        assertTrue(client.connectBlocking(3, TimeUnit.SECONDS));
    }

    @AfterEach
    void disconnect() throws Exception {
        if (client != null) client.closeBlocking();
    }

    // ==================== runCommand injection hardening ====================

    /**
     * runCommand dispatches through a native provider, so injection-shaped text
     * is just command data. The old Groovy wrapper used byte literals to keep
     * this safe; this test pins the same external property on the native path.
     */
    @Test
    void testRunCommandTreatsBackslashQuotePayloadAsData() throws Exception {
        // Classic backslash+quote escape attempt + a literal script trailer.
        String payload = "say \\'; System.exit(0) //";
        JsonObject resp = sendRunCommand(payload);
        assertNotNull(resp, "Should get a response, not crash the server");
        assertTrue(resp.get("success").getAsBoolean(), "Expected native provider success: " + resp);
        assertEquals(payload, commands.poll(1, TimeUnit.SECONDS));
        JsonObject result = resp.getAsJsonObject("result");
        assertEquals("string", result.get("type").getAsString());
        assertEquals("Command sent: " + payload, result.get("value").getAsString());
    }

    @Test
    void testRunCommandRejectsOverLongInput() throws Exception {
        String tooLong = "a".repeat(2000);
        JsonObject resp = sendRunCommand(tooLong);
        assertFalse(resp.get("success").getAsBoolean());
        assertTrue(
                resp.get("error").getAsString().contains("too long"),
                "Expected 'too long' rejection, got: " + resp.get("error"));
    }

    @Test
    void testRunCommandRejectsMissingField() throws Exception {
        JsonObject req = baseRequest("runCommand");
        req.add("payload", new JsonObject()); // no command
        JsonObject resp = roundtrip(req);
        assertFalse(resp.get("success").getAsBoolean());
        assertTrue(
                resp.get("error").getAsString().contains("'command'"),
                "Expected validation error, got: " + resp.get("error"));
    }

    // ==================== search ReDoS hardening ====================

    @Test
    void testSearchRejectsOverLongPattern() throws Exception {
        String tooLong = "a".repeat(500);
        JsonObject resp = sendSearch(tooLong);
        assertFalse(resp.get("success").getAsBoolean());
        assertTrue(resp.get("error").getAsString().contains("too long"));
    }

    @Test
    void testSearchRejectsInvalidRegex() throws Exception {
        JsonObject resp = sendSearch("[unterminated");
        assertFalse(resp.get("success").getAsBoolean());
        assertTrue(resp.get("error").getAsString().contains("invalid regex"));
    }

    @Test
    void testSearchRejectsMissingField() throws Exception {
        JsonObject req = baseRequest("search");
        req.add("payload", new JsonObject()); // no pattern
        JsonObject resp = roundtrip(req);
        assertFalse(resp.get("success").getAsBoolean());
        assertTrue(resp.get("error").getAsString().contains("'pattern'"));
    }

    /**
     * The classic catastrophic-backtracking trigger pattern. With no timeout
     * guard, this hangs the matcher for many seconds. Our {@code timedFind}
     * wrapper bounds it at ~100ms per target string. With the {@link
     * PassthroughResolver}'s empty class list there's no input to backtrack
     * over, so this primarily exercises the compile-time path. The next
     * test ({@link #testSearchSurvivesPathologicalInputBacktracking}) drives
     * the actual matching path.
     */
    @Test
    void testSearchAcceptsPathologicalRegex() throws Exception {
        long start = System.currentTimeMillis();
        JsonObject resp = sendSearch("(a+)+b");
        long elapsed = System.currentTimeMillis() - start;
        assertTrue(resp.get("success").getAsBoolean(), "Should compile + run: " + resp);
        // The empty-resolver case should be near-instantaneous; this is a
        // sanity check, not a tight bound.
        assertTrue(elapsed < 5_000, "Compile path should be fast, took " + elapsed + "ms");
    }

    // ==================== Helpers ====================

    private JsonObject sendSearch(String pattern) throws Exception {
        JsonObject req = baseRequest("search");
        JsonObject payload = new JsonObject();
        payload.addProperty("pattern", pattern);
        req.add("payload", payload);
        return roundtrip(req);
    }

    private JsonObject sendRunCommand(String command) throws Exception {
        JsonObject req = baseRequest("runCommand");
        JsonObject payload = new JsonObject();
        payload.addProperty("command", command);
        req.add("payload", payload);
        return roundtrip(req);
    }

    private static JsonObject baseRequest(String type) {
        JsonObject req = new JsonObject();
        req.addProperty("id", "sec_" + System.nanoTime());
        req.addProperty("type", type);
        return req;
    }

    private JsonObject roundtrip(JsonObject req) throws Exception {
        client.send(new Gson().toJson(req));
        String response = client.responses.poll(8, TimeUnit.SECONDS);
        assertNotNull(response, "No response within 8s");
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
