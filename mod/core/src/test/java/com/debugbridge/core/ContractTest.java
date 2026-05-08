package com.debugbridge.core;

import com.debugbridge.core.entity.LookedAtEntityProvider;
import com.debugbridge.core.lua.DirectDispatcher;
import com.debugbridge.core.mapping.PassthroughResolver;
import com.debugbridge.core.server.BridgeServer;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.junit.jupiter.api.*;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Wire-contract tests that pin the JSON shape produced by each endpoint.
 *
 * <p>This is the foundation of {@code MULTIVERSION_PLAN.md} Phase 1: every
 * version-specific provider must serialize to the same DTO, so a single test
 * here is enough to catch cross-version drift. Phase 1 starts with two
 * endpoints — {@code status} (no provider, exercises the omit-nulls path) and
 * {@code lookedAtEntity} (provider-driven, exercises the keep-nulls path).
 * Subsequent phases extend this file as more endpoints migrate to DTOs.
 *
 * <p>Tests use a stub {@link LookedAtEntityProvider} and the {@link
 * PassthroughResolver}, so no live Minecraft client is required.
 */
class ContractTest {
    private static BridgeServer server;
    private static final int PORT = 19885;
    /** Temp game-dir for the {@code status} positive test; null when not in use. */
    private static volatile Path tempGameDir;
    /** Looked-at entity id served by the stub; volatile because tests mutate it. */
    private static volatile Integer stubLookedAtId = null;

    private TestClient client;

    @BeforeAll
    static void startServer() throws Exception {
        server = new BridgeServer(PORT,
            new PassthroughResolver("test"),
            new DirectDispatcher());
        // Stub provider that returns whatever the test set in stubLookedAtId.
        server.setLookedAtEntityProvider((LookedAtEntityProvider) range -> stubLookedAtId);
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
        // Reset per-test state.
        server.setGameDir(null);
        tempGameDir = null;
        stubLookedAtId = null;
    }

    @AfterEach
    void disconnect() throws Exception {
        if (client != null) client.closeBlocking();
        if (tempGameDir != null) {
            // Best-effort cleanup of the temp dir tree.
            Files.walk(tempGameDir).sorted((a, b) -> b.compareTo(a))
                .forEach(p -> { try { Files.deleteIfExists(p); } catch (Exception ignore) {} });
        }
    }

    // ==================== status ====================

    @Test
    void testStatusOmitsLogPathsWhenNoGameDir() throws Exception {
        JsonObject result = call("status").getAsJsonObject("result");

        // Always-present fields.
        assertEquals(Set.of("version", "mappingStatus", "obfuscated", "refs"), result.keySet(),
            "status without a gameDir should emit exactly the always-present fields, "
            + "not null log paths. Got: " + result);
        assertEquals("test", result.get("version").getAsString());
        assertEquals("passthrough", result.get("mappingStatus").getAsString());
        assertFalse(result.get("obfuscated").getAsBoolean());
        assertTrue(result.get("refs").isJsonPrimitive() && result.get("refs").getAsJsonPrimitive().isNumber());
    }

    @Test
    void testStatusEmitsLogPathsWhenGameDirSet() throws Exception {
        Path dir = Files.createTempDirectory("debugbridge-contract-test");
        Files.createDirectories(dir.resolve("logs"));
        Files.writeString(dir.resolve("logs/latest.log"), "");
        tempGameDir = dir;
        server.setGameDir(dir);

        JsonObject result = call("status").getAsJsonObject("result");
        Set<String> expected = Set.of(
            "version", "mappingStatus", "obfuscated", "refs",
            "gameDir", "logsDir", "latestLog", "latestLogExists",
            "debugLog", "debugLogExists");
        assertEquals(expected, result.keySet(),
            "status with gameDir set should emit all 10 fields. Got: " + result);
        assertTrue(result.get("latestLogExists").getAsBoolean(), "latest.log was created");
        assertFalse(result.get("debugLogExists").getAsBoolean(), "debug.log was not created");
        assertTrue(result.get("gameDir").getAsString().endsWith(dir.getFileName().toString()));
    }

    // ==================== lookedAtEntity ====================

    @Test
    void testLookedAtEntityEmitsExplicitNullWhenNoTarget() throws Exception {
        stubLookedAtId = null;
        JsonObject result = call("lookedAtEntity").getAsJsonObject("result");

        // The key MUST be present and the value MUST be JsonNull. Clients
        // distinguish "no target in range" from "malformed response" by this
        // exact shape.
        assertTrue(result.has("entityId"),
            "entityId key must be present even when null. Got: " + result);
        assertTrue(result.get("entityId").isJsonNull(),
            "entityId must be JsonNull, not omitted, not 0. Got: " + result);
        assertEquals(1, result.size(), "result should contain only entityId. Got: " + result);
    }

    @Test
    void testLookedAtEntityEmitsIntWhenTargeting() throws Exception {
        stubLookedAtId = 12345;
        JsonObject result = call("lookedAtEntity").getAsJsonObject("result");
        assertEquals(12345, result.get("entityId").getAsInt());
        assertEquals(1, result.size());
    }

    // ==================== Helpers ====================

    private JsonObject call(String type) throws Exception {
        JsonObject req = new JsonObject();
        req.addProperty("id", "ct_" + System.nanoTime());
        req.addProperty("type", type);
        req.add("payload", new JsonObject());
        client.send(new Gson().toJson(req));
        String response = client.responses.poll(5, TimeUnit.SECONDS);
        assertNotNull(response, "No response within 5s");
        JsonElement el = JsonParser.parseString(response);
        JsonObject resp = el.getAsJsonObject();
        assertTrue(resp.get("success").getAsBoolean(), "Request failed: " + resp);
        return resp;
    }

    private static class TestClient extends WebSocketClient {
        final LinkedBlockingQueue<String> responses = new LinkedBlockingQueue<>();
        TestClient(URI uri) { super(uri); }
        @Override public void onOpen(ServerHandshake h) {}
        @Override public void onMessage(String msg) { responses.offer(msg); }
        @Override public void onClose(int c, String r, boolean rem) {}
        @Override public void onError(Exception e) { e.printStackTrace(); }
    }
}
