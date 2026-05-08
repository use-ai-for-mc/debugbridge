package com.debugbridge.core;

import com.debugbridge.core.chat.ChatHistoryProvider;
import com.debugbridge.core.entity.LookedAtEntityProvider;
import com.debugbridge.core.lua.DirectDispatcher;
import com.debugbridge.core.mapping.MappingResolver;
import com.debugbridge.core.mapping.PassthroughResolver;
import com.debugbridge.core.protocol.dto.ChatMessageDto;
import com.debugbridge.core.protocol.dto.ItemStackDto;
import com.debugbridge.core.protocol.dto.ScreenInspectDto;
import com.debugbridge.core.protocol.dto.SlotDto;
import com.debugbridge.core.screen.ScreenInspectProvider;
import com.debugbridge.core.server.BridgeServer;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.junit.jupiter.api.*;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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
    /** Chat messages the stub will return; tests mutate per case. */
    private static volatile List<ChatMessageDto> stubChatMessages = List.of();
    /** ScreenInspect DTO the stub will return; tests mutate per case. */
    private static volatile ScreenInspectDto stubScreenInspect = closedScreenDto();

    private TestClient client;

    @BeforeAll
    static void startServer() throws Exception {
        // Resolver that maps `net.minecraft.class_1277` (a real intermediary
        // name from the audit fixtures) to its Mojang counterpart so the
        // contract test can verify the handler applies `unresolveClass` to
        // {@code slots[].container}. Other names pass through unchanged.
        MappingResolver resolver = new PassthroughResolver("test") {
            @Override public String unresolveClass(String name) {
                if ("net.minecraft.class_1277".equals(name)) {
                    return "net.minecraft.world.SimpleContainer";
                }
                return name;
            }
        };
        server = new BridgeServer(PORT, resolver, new DirectDispatcher());
        // Stub providers — return whatever the test fixtured.
        server.setLookedAtEntityProvider((LookedAtEntityProvider) range -> stubLookedAtId);
        server.setChatHistoryProvider((ChatHistoryProvider)
            (limit, r, includeJson) -> stubChatMessages);
        server.setScreenInspectProvider((ScreenInspectProvider) () -> stubScreenInspect);
        server.start();
        Thread.sleep(500);
    }

    private static ScreenInspectDto closedScreenDto() {
        ScreenInspectDto d = new ScreenInspectDto();
        d.open = false;
        return d;
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
        stubChatMessages = List.of();
        stubScreenInspect = closedScreenDto();
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

    // ==================== chatHistory ====================

    @Test
    void testChatHistoryEmitsEmptyShapeWhenNoMessages() throws Exception {
        stubChatMessages = List.of();
        JsonObject result = call("chatHistory").getAsJsonObject("result");
        assertEquals(Set.of("messages", "count"), result.keySet());
        assertEquals(0, result.get("count").getAsInt());
        assertEquals(0, result.getAsJsonArray("messages").size());
    }

    @Test
    void testChatHistoryEmitsPlainAndAddedTimeOmittingNulls() throws Exception {
        ChatMessageDto m1 = new ChatMessageDto();
        m1.plain = "hello";
        m1.addedTime = 1000;
        ChatMessageDto m2 = new ChatMessageDto();
        m2.plain = "world";  // no addedTime — must be omitted on the wire
        stubChatMessages = List.of(m1, m2);

        JsonObject result = call("chatHistory").getAsJsonObject("result");
        JsonArray messages = result.getAsJsonArray("messages");
        assertEquals(2, result.get("count").getAsInt());
        assertEquals(2, messages.size());

        JsonObject first = messages.get(0).getAsJsonObject();
        assertEquals(Set.of("plain", "addedTime"), first.keySet(),
            "First message: plain + addedTime, no json. Got: " + first);
        assertEquals("hello", first.get("plain").getAsString());
        assertEquals(1000, first.get("addedTime").getAsInt());

        JsonObject second = messages.get(1).getAsJsonObject();
        assertEquals(Set.of("plain"), second.keySet(),
            "Second message: addedTime is null, must be omitted (not emitted as null). Got: " + second);
    }

    @Test
    void testChatHistoryIncludesJsonFieldWhenStubProvidesIt() throws Exception {
        ChatMessageDto m = new ChatMessageDto();
        m.plain = "styled";
        m.addedTime = 500;
        // Arbitrary JSON shape — stand-in for ComponentSerialization output.
        JsonObject styled = new JsonObject();
        styled.addProperty("text", "styled");
        styled.addProperty("color", "red");
        m.json = styled;
        stubChatMessages = List.of(m);

        JsonObject result = call("chatHistory").getAsJsonObject("result");
        JsonObject only = result.getAsJsonArray("messages").get(0).getAsJsonObject();
        assertEquals(Set.of("plain", "addedTime", "json"), only.keySet());
        // The pass-through JSON round-trips byte-for-byte.
        assertEquals(styled, only.getAsJsonObject("json"));
    }

    // ==================== screenInspect ====================

    @Test
    void testScreenInspectClosedEmitsOnlyOpenFalse() throws Exception {
        // Default stub is a closed-screen DTO; reset by @BeforeEach.
        JsonObject result = call("screenInspect").getAsJsonObject("result");
        assertEquals(Set.of("open"), result.keySet(),
            "Closed screen must emit only `open: false`. Got: " + result);
        assertFalse(result.get("open").getAsBoolean());
    }

    @Test
    void testScreenInspectOpenContainerEmitsTypeMenuAndSlots() throws Exception {
        ScreenInspectDto dto = new ScreenInspectDto();
        dto.open = true;
        dto.type = "net.minecraft.client.gui.screens.inventory.ContainerScreen";
        dto.title = "Test Chest";
        dto.menuClass = "net.minecraft.world.inventory.ChestMenu";

        SlotDto empty = new SlotDto();
        empty.idx = 0;
        // Use the intermediary that the test resolver knows how to map. The
        // assertion below pins that the handler applies the resolver — the
        // wire must NOT contain `class_1277`.
        empty.container = "net.minecraft.class_1277";

        SlotDto withItem = new SlotDto();
        withItem.idx = 1;
        withItem.container = "net.minecraft.class_1277";
        withItem.item = new ItemStackDto();
        withItem.item.itemId = "minecraft:diamond";
        withItem.item.count = 3;

        dto.slots = List.of(empty, withItem);
        stubScreenInspect = dto;

        JsonObject result = call("screenInspect").getAsJsonObject("result");
        assertTrue(result.get("open").getAsBoolean());
        assertEquals("Test Chest", result.get("title").getAsString());
        assertEquals(2, result.getAsJsonArray("slots").size());

        JsonObject slot0 = result.getAsJsonArray("slots").get(0).getAsJsonObject();
        assertEquals(Set.of("idx", "container"), slot0.keySet(),
            "Empty slot has only idx + container, no item. Got: " + slot0);

        JsonObject slot1 = result.getAsJsonArray("slots").get(1).getAsJsonObject();
        assertEquals(Set.of("idx", "container", "item"), slot1.keySet());
        assertEquals(3, slot1.getAsJsonObject("item").get("count").getAsInt());
    }

    @Test
    void testScreenInspectAppliesResolverToSlotContainerThemeOneFix() throws Exception {
        // Pins the Theme 1 fix from the dream review queue: provider emits
        // raw runtime class name in slots[].container; handler must apply
        // unresolveClass before the value reaches the wire.
        ScreenInspectDto dto = new ScreenInspectDto();
        dto.open = true;
        dto.type = "net.minecraft.class_1277";  // resolver maps this
        dto.menuClass = "net.minecraft.class_1277";  // resolver maps this
        SlotDto slot = new SlotDto();
        slot.idx = 0;
        slot.container = "net.minecraft.class_1277";  // <-- the bug location
        dto.slots = List.of(slot);
        stubScreenInspect = dto;

        JsonObject result = call("screenInspect").getAsJsonObject("result");
        // All three runtime class-name fields must be remapped to the Mojang name.
        assertEquals("net.minecraft.world.SimpleContainer", result.get("type").getAsString());
        assertEquals("net.minecraft.world.SimpleContainer", result.get("menuClass").getAsString());
        assertEquals("net.minecraft.world.SimpleContainer",
            result.getAsJsonArray("slots").get(0).getAsJsonObject().get("container").getAsString(),
            "Theme 1 regression: slots[].container must be Mojang-mapped on the wire");
    }

    @Test
    void testScreenInspectOmitsItemFieldsWhenAbsent() throws Exception {
        // ItemStack with no damage and no custom name should produce a wire
        // shape with only itemId + count — damage/maxDamage/name omitted.
        ScreenInspectDto dto = new ScreenInspectDto();
        dto.open = true;
        dto.type = "x";
        SlotDto slot = new SlotDto();
        slot.idx = 0;
        slot.container = "y";
        slot.item = new ItemStackDto();
        slot.item.itemId = "minecraft:stone";
        slot.item.count = 64;
        dto.slots = List.of(slot);
        stubScreenInspect = dto;

        JsonObject result = call("screenInspect").getAsJsonObject("result");
        JsonObject item = result.getAsJsonArray("slots").get(0).getAsJsonObject()
            .getAsJsonObject("item");
        assertEquals(Set.of("itemId", "count"), item.keySet(),
            "Optional damage/name fields must be omitted when null. Got: " + item);
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
