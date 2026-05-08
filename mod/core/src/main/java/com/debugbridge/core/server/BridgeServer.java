package com.debugbridge.core.server;

import com.debugbridge.core.lua.LuaRuntime;
import com.debugbridge.core.lua.ThreadDispatcher;
import com.debugbridge.core.mapping.MappingResolver;
import com.debugbridge.core.protocol.BridgeRequest;
import com.debugbridge.core.protocol.BridgeResponse;
import com.debugbridge.core.protocol.dto.BlockDetailsDto;
import com.debugbridge.core.protocol.dto.BlockSummaryDto;
import com.debugbridge.core.protocol.dto.ChatHistoryDto;
import com.debugbridge.core.protocol.dto.ChatMessageDto;
import com.debugbridge.core.protocol.dto.EntityDetailsDto;
import com.debugbridge.core.protocol.dto.EntitySummaryDto;
import com.debugbridge.core.protocol.dto.LookedAtEntityDto;
import com.debugbridge.core.protocol.dto.NearbyBlocksDto;
import com.debugbridge.core.protocol.dto.NearbyEntitiesDto;
import com.debugbridge.core.protocol.dto.ScreenInspectDto;
import com.debugbridge.core.protocol.dto.SlotDto;
import com.debugbridge.core.protocol.dto.StatusDto;
import com.debugbridge.core.refs.ObjectRefStore;
import com.debugbridge.core.entity.ClientEntityGlowManager;
import com.debugbridge.core.entity.LookedAtEntityProvider;
import com.debugbridge.core.block.ClientBlockGlowManager;
import com.debugbridge.core.block.NearbyBlocksProvider;
import com.debugbridge.core.chat.ChatHistoryProvider;
import com.debugbridge.core.screen.ScreenInspectProvider;
import com.debugbridge.core.entity.NearbyEntitiesProvider;
import com.debugbridge.core.screenshot.ScreenshotProvider;
import com.debugbridge.core.snapshot.GameStateProvider;
import com.debugbridge.core.texture.ItemTextureProvider;
import com.google.gson.*;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

import java.net.BindException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * WebSocket server that accepts Lua script execution requests and other commands.
 * Runs inside the Minecraft JVM. Accepts one client (the MCP server) at a time.
 */
public class BridgeServer extends WebSocketServer {
    private static final Logger LOG = Logger.getLogger("DebugBridge");
    /** Default serializer: nulls become {@code "field": null} on the wire.
     * Used for endpoints whose schema treats null as a meaningful value
     * (e.g. {@code lookedAtEntity.entityId}). */
    private static final Gson GSON = new GsonBuilder().serializeNulls().create();
    /** Omit-nulls serializer: null fields disappear from the JSON entirely.
     * Used for endpoints with conditional fields (e.g. {@code status} only
     * emits {@code gameDir}/{@code logsDir}/etc. when the host mod has set a
     * game directory). */
    private static final Gson GSON_OMIT_NULLS = new Gson();

    private final LuaRuntime lua;
    private final MappingResolver resolver;
    private final ObjectRefStore refs;
    private final ResultSerializer serializer;
    private final GameStateProvider stateProvider;
    private final ScreenshotProvider screenshotProvider;
    /**
     * Absolute path to the game run directory (the .minecraft / profile root),
     * or {@code null} if the embedder didn't provide one. When non-null, the
     * status endpoint exposes {@code gameDir} / {@code latestLog} / {@code debugLog}
     * so a connecting MCP client can read logs via its own file-read tools.
     */
    private volatile Path gameDir;

    /**
     * Item texture resolver. Set by the version-specific module.
     */
    private volatile ItemTextureProvider textureProvider;

    /**
     * Nearby entities query provider. Set by the version-specific module.
     */
    private volatile NearbyEntitiesProvider entitiesProvider;

    /**
     * Nearby block-entities query provider. Set by the version-specific module.
     */
    private volatile NearbyBlocksProvider blocksProvider;

    /**
     * Raycast-based "what is the player aiming at" provider. Set by the
     * version-specific module.
     */
    private volatile LookedAtEntityProvider lookedAtEntityProvider;

    /**
     * Recent client-side chat messages. Set by the version-specific module.
     */
    private volatile ChatHistoryProvider chatHistoryProvider;

    /**
     * Currently-open screen / container introspection. Set by the
     * version-specific module.
     */
    private volatile ScreenInspectProvider screenInspectProvider;

    /**
     * Callback for bind errors (e.g., port already in use). Called from the
     * WebSocket selector thread when the server fails to bind.
     */
    private volatile Consumer<Exception> bindErrorCallback;

    /**
     * Whether slash-command execution via the bridge is honored. When false
     * runCommand requests behave as if they don't exist.
     */
    private volatile boolean runCommandEnabled = false;

    public BridgeServer(int port, MappingResolver resolver, ThreadDispatcher dispatcher) {
        this(port, resolver, dispatcher, null, null);
    }

    public BridgeServer(int port, MappingResolver resolver, ThreadDispatcher dispatcher,
                        GameStateProvider stateProvider) {
        this(port, resolver, dispatcher, stateProvider, null);
    }

    public BridgeServer(int port, MappingResolver resolver, ThreadDispatcher dispatcher,
                        GameStateProvider stateProvider, ScreenshotProvider screenshotProvider) {
        super(new InetSocketAddress("127.0.0.1", port));
        this.resolver = resolver;
        this.refs = new ObjectRefStore();
        this.lua = new LuaRuntime(resolver, dispatcher, refs);
        this.serializer = new ResultSerializer(resolver, refs);
        this.stateProvider = stateProvider;
        this.screenshotProvider = screenshotProvider;
        setReuseAddr(true);
    }

    public LuaRuntime getLuaRuntime() { return lua; }

    /**
     * Tell the server where the game run directory is so it can surface log
     * paths in its status response. Call once, after construction, before
     * {@link #start()}.
     */
    public void setGameDir(Path gameDir) {
        this.gameDir = gameDir;
    }

    public void setRunCommandEnabled(boolean enabled) {
        this.runCommandEnabled = enabled;
    }

    public void setChatHistoryProvider(ChatHistoryProvider provider) {
        this.chatHistoryProvider = provider;
        LOG.info("[DebugBridge] Chat history provider registered: " + provider.getClass().getSimpleName());
    }

    public void setScreenInspectProvider(ScreenInspectProvider provider) {
        this.screenInspectProvider = provider;
        LOG.info("[DebugBridge] Screen inspect provider registered: " + provider.getClass().getSimpleName());
    }

    /**
     * Register the item texture provider. Called by the version-specific module
     * during initialization.
     */
    public void setTextureProvider(ItemTextureProvider provider) {
        this.textureProvider = provider;
        LOG.info("[DebugBridge] Texture provider registered: " + provider.getClass().getSimpleName());
    }

    /**
     * Register the nearby entities provider. Called by the version-specific module
     * during initialization.
     */
    public void setEntitiesProvider(NearbyEntitiesProvider provider) {
        this.entitiesProvider = provider;
        LOG.info("[DebugBridge] Entities provider registered: " + provider.getClass().getSimpleName());
    }

    /**
     * Register the nearby blocks provider. Called by the version-specific module
     * during initialization.
     */
    public void setBlocksProvider(NearbyBlocksProvider provider) {
        this.blocksProvider = provider;
        LOG.info("[DebugBridge] Blocks provider registered: " + provider.getClass().getSimpleName());
    }

    /**
     * Register the looked-at entity provider. Called by the version-specific
     * module during initialization.
     */
    public void setLookedAtEntityProvider(LookedAtEntityProvider provider) {
        this.lookedAtEntityProvider = provider;
        LOG.info("[DebugBridge] Looked-at entity provider registered: " + provider.getClass().getSimpleName());
    }

    /**
     * Set a callback to be notified if the server fails to bind to the port.
     * The callback is invoked from the WebSocket selector thread.
     */
    public void setBindErrorCallback(Consumer<Exception> callback) {
        this.bindErrorCallback = callback;
    }

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        LOG.info("[DebugBridge] Client connected: " + conn.getRemoteSocketAddress());
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        LOG.info("[DebugBridge] Client disconnected: " + reason);
        refs.clear();
    }

    @Override
    public void onMessage(WebSocket conn, String message) {
        try {
            BridgeRequest req = GSON.fromJson(message, BridgeRequest.class);
            BridgeResponse resp = handleRequest(req);
            conn.send(GSON.toJson(resp.toJson()));
        } catch (Exception e) {
            LOG.log(Level.WARNING, "[DebugBridge] Error handling message", e);
            try {
                BridgeResponse resp = BridgeResponse.error("unknown",
                    "Internal error: " + e.getMessage());
                conn.send(GSON.toJson(resp.toJson()));
            } catch (Exception e2) {
                // Connection may be dead
            }
        }
    }

    @Override
    public void onError(WebSocket conn, Exception ex) {
        LOG.log(Level.WARNING, "[DebugBridge] WebSocket error", ex);
        // conn == null means this is a server-level error (e.g., bind failure)
        if (conn == null && (ex instanceof BindException ||
                (ex.getCause() != null && ex.getCause() instanceof BindException))) {
            Consumer<Exception> callback = this.bindErrorCallback;
            if (callback != null) {
                callback.accept(ex);
            }
        }
    }

    @Override
    public void onStart() {
        LOG.info("[DebugBridge] Server started on port " + getPort());
    }

    private BridgeResponse handleRequest(BridgeRequest req) {
        try {
            return switch (req.type) {
                case "execute" -> handleExecute(req);
                case "search" -> handleSearch(req);
                case "snapshot" -> handleSnapshot(req);
                case "screenshot" -> handleScreenshot(req);
                case "runCommand" -> runCommandEnabled
                    ? handleRunCommand(req)
                    : BridgeResponse.error(req.id, "Unknown request type: runCommand");
                case "status" -> handleStatus(req);
                case "getItemTexture" -> handleGetItemTexture(req);
                case "getEntityItemTexture" -> handleGetEntityItemTexture(req);
                case "getItemTextureById" -> handleGetItemTextureById(req);
                case "nearbyEntities" -> handleNearbyEntities(req);
                case "entityDetails" -> handleEntityDetails(req);
                case "nearbyBlocks" -> handleNearbyBlocks(req);
                case "blockDetails" -> handleBlockDetails(req);
                case "lookedAtEntity" -> handleLookedAtEntity(req);
                case "chatHistory" -> handleChatHistory(req);
                case "screenInspect" -> handleScreenInspect(req);
                case "setEntityGlow" -> handleSetEntityGlow(req);
                case "setBlockGlow" -> handleSetBlockGlow(req);
                case "clearBlockGlow" -> handleClearBlockGlow(req);
                default -> BridgeResponse.error(req.id, "Unknown request type: " + req.type);
            };
        } catch (Exception e) {
            return BridgeResponse.error(req.id,
                e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    // Hard ceiling on per-request Lua timeouts. Even if a caller asks for
    // more, we cap here so a runaway script can't hang the bridge forever.
    private static final long MAX_EXECUTE_TIMEOUT_MS = 300_000L;

    private BridgeResponse handleExecute(BridgeRequest req) {
        String code = req.payload.get("code").getAsString();

        // Optional per-request override. Default (when absent or <= 0) is the
        // runtime's configured timeout (10s). Heavy reflection over many
        // entities can legitimately need more headroom — see CLAUDE.md.
        long timeoutMs = 0;
        if (req.payload.has("timeoutMs") && !req.payload.get("timeoutMs").isJsonNull()) {
            long requested = req.payload.get("timeoutMs").getAsLong();
            timeoutMs = Math.min(MAX_EXECUTE_TIMEOUT_MS, Math.max(0, requested));
        }

        LuaRuntime.ExecutionResult result = lua.execute(code, timeoutMs);

        if (!result.isSuccess()) {
            return BridgeResponse.error(req.id, result.error);
        }

        JsonElement serialized = null;
        if (result.returnValue != null) {
            serialized = serializer.serialize(result.returnValue);
        }
        return BridgeResponse.success(req.id, serialized, result.output);
    }

    private BridgeResponse handleSearch(BridgeRequest req) {
        if (req.payload == null || !req.payload.has("pattern")
                || !req.payload.get("pattern").isJsonPrimitive()) {
            return BridgeResponse.error(req.id, "search: 'pattern' must be a string");
        }
        String pattern = req.payload.get("pattern").getAsString();
        if (pattern.length() > MAX_PATTERN_LEN) {
            return BridgeResponse.error(req.id,
                "search: pattern too long (max " + MAX_PATTERN_LEN + " chars)");
        }
        String scope = req.payload.has("scope")
            ? req.payload.get("scope").getAsString() : "all";
        Pattern regex;
        try {
            regex = Pattern.compile(pattern, Pattern.CASE_INSENSITIVE);
        } catch (PatternSyntaxException e) {
            return BridgeResponse.error(req.id, "search: invalid regex — " + e.getDescription());
        }

        JsonArray results = new JsonArray();
        int limit = 100;

        if (scope.equals("class") || scope.equals("all")) {
            for (String mojangClass : resolver.getAllClassNames()) {
                if (timedFind(regex, mojangClass)) {
                    JsonObject entry = new JsonObject();
                    entry.addProperty("type", "class");
                    entry.addProperty("name", mojangClass);
                    results.add(entry);
                    if (results.size() >= limit) break;
                }
            }
        }

        if (results.size() < limit && (scope.equals("method") || scope.equals("all"))) {
            for (String className : resolver.getAllClassNames()) {
                for (String methodSig : resolver.getMethodSignatures(className)) {
                    if (timedFind(regex, methodSig)) {
                        JsonObject entry = new JsonObject();
                        entry.addProperty("type", "method");
                        entry.addProperty("owner", className);
                        entry.addProperty("name", methodSig);
                        results.add(entry);
                        if (results.size() >= limit) break;
                    }
                }
                if (results.size() >= limit) break;
            }
        }

        if (results.size() < limit && (scope.equals("field") || scope.equals("all"))) {
            for (String className : resolver.getAllClassNames()) {
                for (String fieldName : resolver.getFieldNames(className)) {
                    if (timedFind(regex, fieldName)) {
                        JsonObject entry = new JsonObject();
                        entry.addProperty("type", "field");
                        entry.addProperty("owner", className);
                        entry.addProperty("name", fieldName);
                        results.add(entry);
                        if (results.size() >= limit) break;
                    }
                }
                if (results.size() >= limit) break;
            }
        }

        return BridgeResponse.success(req.id, results, null);
    }

    /** Hard cap on `search` pattern length. Class/method names are tiny; very
     * long patterns are either pathological compilation work or an error. */
    private static final int MAX_PATTERN_LEN = 256;

    /** Per-string match deadline for `search`. Catastrophic backtracking on a
     * single string still has to read its way through the input via
     * {@link CharSequence#charAt}, so a deadline checked there bounds the
     * worst case at ~{@value} ms regardless of pattern shape. */
    private static final long REGEX_MATCH_TIMEOUT_MS = 100L;

    /** Run {@code regex.matcher(target).find()} with a per-call deadline that
     * defuses catastrophic-backtracking ReDoS. Returns {@code false} on timeout
     * (treats a timing-out pattern as no-match for that string and moves on
     * — better than hanging the websocket handler thread). */
    private static boolean timedFind(Pattern regex, String target) {
        try {
            long deadline = System.nanoTime() + REGEX_MATCH_TIMEOUT_MS * 1_000_000L;
            return regex.matcher(new TimeoutCharSequence(target, deadline)).find();
        } catch (RegexTimeoutException e) {
            return false;
        }
    }

    /** Throws a {@link RegexTimeoutException} from {@link #charAt(int)} once
     * the deadline passes. Java's regex engine reads input via {@code charAt},
     * so this gives the matcher a back-pressure signal even when it's looping
     * inside a backtracking blowup. */
    private static final class TimeoutCharSequence implements CharSequence {
        private final CharSequence inner;
        private final long deadlineNanos;
        TimeoutCharSequence(CharSequence inner, long deadlineNanos) {
            this.inner = inner;
            this.deadlineNanos = deadlineNanos;
        }
        @Override public int length() { return inner.length(); }
        @Override public char charAt(int index) {
            if (System.nanoTime() > deadlineNanos) {
                throw new RegexTimeoutException();
            }
            return inner.charAt(index);
        }
        @Override public CharSequence subSequence(int start, int end) {
            return new TimeoutCharSequence(inner.subSequence(start, end), deadlineNanos);
        }
        @Override public String toString() { return inner.toString(); }
    }

    private static final class RegexTimeoutException extends RuntimeException {
        RegexTimeoutException() { super(null, null, false, false); }
    }

    private BridgeResponse handleScreenshot(BridgeRequest req) {
        if (screenshotProvider == null) {
            return BridgeResponse.error(req.id,
                "No screenshot provider configured for this Minecraft version.");
        }
        int downscale = 2;
        float quality = 0.75f;
        long timeoutMs = 5000;
        if (req.payload != null) {
            if (req.payload.has("downscale")) {
                downscale = Math.max(1, req.payload.get("downscale").getAsInt());
            }
            if (req.payload.has("quality")) {
                quality = req.payload.get("quality").getAsFloat();
            }
            if (req.payload.has("timeoutMs")) {
                timeoutMs = Math.max(100, req.payload.get("timeoutMs").getAsLong());
            }
        }
        try {
            ScreenshotProvider.Capture cap = screenshotProvider.capture(downscale, quality, timeoutMs);
            JsonObject result = new JsonObject();
            result.addProperty("path", cap.path);
            result.addProperty("width", cap.width);
            result.addProperty("height", cap.height);
            result.addProperty("sizeBytes", cap.sizeBytes);
            result.addProperty("mimeType", "image/jpeg");
            return BridgeResponse.success(req.id, result, null);
        } catch (Exception e) {
            return BridgeResponse.error(req.id,
                "Screenshot failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private BridgeResponse handleSnapshot(BridgeRequest req) {
        if (stateProvider == null) {
            return BridgeResponse.error(req.id,
                "No game state provider configured. Use mc_execute with Lua instead.");
        }
        try {
            JsonObject snapshot = stateProvider.captureSnapshot();
            // Map intermediary class names on any nested entity-type fields.
            unresolveClassField(snapshot.has("player") && snapshot.get("player").isJsonObject()
                ? snapshot.getAsJsonObject("player").getAsJsonObject("vehicle") : null, "type");
            unresolveClassField(snapshot.getAsJsonObject("target"), "entityType");
            return BridgeResponse.success(req.id, snapshot, null);
        } catch (Exception e) {
            return BridgeResponse.error(req.id,
                "Snapshot failed: " + e.getMessage());
        }
    }

    private void unresolveClassField(JsonObject obj, String field) {
        if (obj == null || !obj.has(field)) return;
        String mapped = resolver.unresolveClass(obj.get(field).getAsString());
        if (mapped != null) obj.addProperty(field, mapped);
    }

    /** Apply {@link MappingResolver#unresolveClass} to a raw runtime class
     * name. Returns the original value when the resolver doesn't know the
     * mapping (preserves prior behavior where unmapped names pass through). */
    private String unresolveOrNull(String runtimeName) {
        if (runtimeName == null) return null;
        String mapped = resolver.unresolveClass(runtimeName);
        return mapped != null ? mapped : runtimeName;
    }

    /**
     * Render the texture for each unique {@code itemId} in {@code uniqueIds}
     * and return a JSON map {itemId: {base64Png, width, height, spriteName}}.
     * Items that fail to render are silently omitted. Empty input returns an
     * empty object. Callers attach this as a top-level {@code icons} field
     * on a response so the agent can see all item visuals in one shot.
     */
    private JsonObject renderIconsMap(java.util.Set<String> uniqueIds) {
        JsonObject icons = new JsonObject();
        if (textureProvider == null || uniqueIds.isEmpty()) return icons;
        for (String itemId : uniqueIds) {
            try {
                ItemTextureProvider.TextureResult tex = textureProvider.getItemTextureById(itemId);
                JsonObject entry = new JsonObject();
                entry.addProperty("base64Png", tex.base64Png());
                entry.addProperty("width", tex.width());
                entry.addProperty("height", tex.height());
                entry.addProperty("spriteName", tex.spriteName());
                icons.add(itemId, entry);
            } catch (Exception ignore) {
                // Skip items that fail to render (e.g., unknown id).
            }
        }
        return icons;
    }

    private BridgeResponse handleRunCommand(BridgeRequest req) {
        if (req.payload == null || !req.payload.has("command")
                || !req.payload.get("command").isJsonPrimitive()) {
            return BridgeResponse.error(req.id, "runCommand: 'command' must be a string");
        }
        String command = req.payload.get("command").getAsString();
        if (command.length() > MAX_COMMAND_LEN) {
            return BridgeResponse.error(req.id,
                "runCommand: command too long (max " + MAX_COMMAND_LEN + " chars)");
        }
        // SECURITY: encode the command as `string.char(b1, b2, ...)` so the
        // generated Lua source contains no user-controlled bytes. Earlier
        // single-quote escaping ('"' -> "\\'") was bypassable via backslash +
        // quote, newlines, and null bytes. This formulation is unconditionally
        // injection-proof: every byte of the user's command lands as a numeric
        // literal, and Lua reassembles the string at runtime.
        // This is a temporary measure — the long-term fix is a native
        // CommandProvider that bypasses Lua entirely (see review queue).
        byte[] cmdBytes = command.getBytes(StandardCharsets.UTF_8);
        StringBuilder bytes = new StringBuilder(cmdBytes.length * 4);
        for (int i = 0; i < cmdBytes.length; i++) {
            if (i > 0) bytes.append(',');
            bytes.append(cmdBytes[i] & 0xFF);
        }
        String luaCode =
            "local cmd = string.char(" + bytes + ")\n" +
            "local mc = java.import('net.minecraft.client.Minecraft'):getInstance()\n" +
            "mc.player:connection():sendCommand(cmd)\n" +
            "return 'Command sent: ' .. cmd";
        return handleExecute(new BridgeRequest(req.id, "execute",
            createPayload("code", luaCode)));
    }

    /** Hard cap on `runCommand` input length. */
    private static final int MAX_COMMAND_LEN = 1024;

    private BridgeResponse handleStatus(BridgeRequest req) {
        StatusDto dto = new StatusDto();
        dto.version = resolver.getVersion();
        dto.mappingStatus = resolver.isObfuscated() ? "mojang" : "passthrough";
        dto.obfuscated = resolver.isObfuscated();
        dto.refs = refs.size();

        // Expose the game dir and log paths so a connecting client can read the
        // log via its own file-read tools. We always expose the path we *would*
        // write to, even if the file doesn't exist yet (a world-load crash
        // might create it between the status call and the Read). Where we can
        // check existence cheaply, we do, and add an explicit "exists" flag.
        Path dir = this.gameDir;
        if (dir != null) {
            Path logsDir = dir.resolve("logs");
            Path latest = logsDir.resolve("latest.log");
            Path debug = logsDir.resolve("debug.log");
            dto.gameDir = dir.toAbsolutePath().toString();
            dto.logsDir = logsDir.toAbsolutePath().toString();
            dto.latestLog = latest.toAbsolutePath().toString();
            dto.latestLogExists = Files.exists(latest);
            dto.debugLog = debug.toAbsolutePath().toString();
            dto.debugLogExists = Files.exists(debug);
        }

        return BridgeResponse.success(req.id, GSON_OMIT_NULLS.toJsonTree(dto), null);
    }

    private JsonObject createPayload(String key, String value) {
        JsonObject payload = new JsonObject();
        payload.addProperty(key, value);
        return payload;
    }

    // ==================== Item Texture Handler ====================

    private BridgeResponse handleGetItemTexture(BridgeRequest req) {
        if (textureProvider == null) {
            return BridgeResponse.error(req.id,
                "No texture provider configured for this Minecraft version.");
        }

        int slot = req.payload.get("slot").getAsInt();
        try {
            ItemTextureProvider.TextureResult tex = textureProvider.getItemTexture(slot);
            JsonObject result = new JsonObject();
            result.addProperty("base64Png", tex.base64Png());
            result.addProperty("width", tex.width());
            result.addProperty("height", tex.height());
            result.addProperty("spriteName", tex.spriteName());
            return BridgeResponse.success(req.id, result, null);
        } catch (Exception e) {
            return BridgeResponse.error(req.id,
                "Texture extraction failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private BridgeResponse handleGetEntityItemTexture(BridgeRequest req) {
        if (textureProvider == null) {
            return BridgeResponse.error(req.id,
                "No texture provider configured for this Minecraft version.");
        }

        int entityId = req.payload.get("entityId").getAsInt();
        String slot = req.payload.get("slot").getAsString();
        try {
            ItemTextureProvider.TextureResult tex = textureProvider.getEntityItemTexture(entityId, slot);
            JsonObject result = new JsonObject();
            result.addProperty("base64Png", tex.base64Png());
            result.addProperty("width", tex.width());
            result.addProperty("height", tex.height());
            result.addProperty("spriteName", tex.spriteName());
            return BridgeResponse.success(req.id, result, null);
        } catch (Exception e) {
            return BridgeResponse.error(req.id,
                "Entity texture extraction failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private BridgeResponse handleGetItemTextureById(BridgeRequest req) {
        if (textureProvider == null) {
            return BridgeResponse.error(req.id,
                "No texture provider configured for this Minecraft version.");
        }

        String itemId = req.payload.get("itemId").getAsString();
        try {
            ItemTextureProvider.TextureResult tex = textureProvider.getItemTextureById(itemId);
            JsonObject result = new JsonObject();
            result.addProperty("base64Png", tex.base64Png());
            result.addProperty("width", tex.width());
            result.addProperty("height", tex.height());
            result.addProperty("spriteName", tex.spriteName());
            return BridgeResponse.success(req.id, result, null);
        } catch (Exception e) {
            return BridgeResponse.error(req.id,
                "Item texture extraction failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    // ==================== Nearby Entities Handlers ====================

    private BridgeResponse handleNearbyEntities(BridgeRequest req) {
        if (entitiesProvider == null) {
            return BridgeResponse.error(req.id,
                "No entities provider configured for this Minecraft version.");
        }

        double range = req.payload.has("range") ? req.payload.get("range").getAsDouble() : 64.0;
        int limit = req.payload.has("limit") ? req.payload.get("limit").getAsInt() : 100;
        boolean includeIcons = req.payload.has("includeIcons")
            && req.payload.get("includeIcons").getAsBoolean();
        try {
            List<EntitySummaryDto> entities = entitiesProvider.getNearbyEntities(range, limit);
            // Map runtime entity class names to Mojang names.
            for (EntitySummaryDto e : entities) {
                e.type = unresolveOrNull(e.type);
            }
            NearbyEntitiesDto dto = new NearbyEntitiesDto(entities);
            if (includeIcons) {
                java.util.Set<String> uniqueIds = new java.util.LinkedHashSet<>();
                for (EntitySummaryDto e : entities) {
                    if (e.primaryEquipment != null && e.primaryEquipment.itemId != null) {
                        uniqueIds.add(e.primaryEquipment.itemId);
                    }
                }
                dto.icons = renderIconsMap(uniqueIds);
            }
            return BridgeResponse.success(req.id, GSON_OMIT_NULLS.toJsonTree(dto), null);
        } catch (Exception e) {
            return BridgeResponse.error(req.id,
                "Nearby entities query failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private BridgeResponse handleEntityDetails(BridgeRequest req) {
        if (entitiesProvider == null) {
            return BridgeResponse.error(req.id,
                "No entities provider configured for this Minecraft version.");
        }

        int entityId = req.payload.get("entityId").getAsInt();
        try {
            EntityDetailsDto details = entitiesProvider.getEntityDetails(entityId);
            if (details == null) {
                return BridgeResponse.success(req.id,
                    GSON_OMIT_NULLS.toJsonTree(EntityDetailsDto.gone()), null);
            }
            // Apply class-name mapping uniformly: type, vehicle, and each passenger.
            details.type = unresolveOrNull(details.type);
            details.vehicle = unresolveOrNull(details.vehicle);
            if (details.passengers != null) {
                List<String> mapped = new java.util.ArrayList<>(details.passengers.size());
                for (String p : details.passengers) mapped.add(unresolveOrNull(p));
                details.passengers = mapped;
            }
            return BridgeResponse.success(req.id, GSON_OMIT_NULLS.toJsonTree(details), null);
        } catch (Exception e) {
            return BridgeResponse.error(req.id,
                "Entity details query failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    // ==================== Nearby Blocks Handlers ====================

    private BridgeResponse handleNearbyBlocks(BridgeRequest req) {
        if (blocksProvider == null) {
            return BridgeResponse.error(req.id,
                "No blocks provider configured for this Minecraft version.");
        }

        double range = req.payload.has("range") ? req.payload.get("range").getAsDouble() : 16.0;
        int limit = req.payload.has("limit") ? req.payload.get("limit").getAsInt() : 100;
        try {
            List<BlockSummaryDto> blocks = blocksProvider.getNearbyBlocks(range, limit);
            // Map each runtime BlockEntity class name to the Mojang name.
            for (BlockSummaryDto b : blocks) {
                b.type = unresolveOrNull(b.type);
            }
            return BridgeResponse.success(req.id,
                GSON_OMIT_NULLS.toJsonTree(new NearbyBlocksDto(blocks)), null);
        } catch (Exception e) {
            return BridgeResponse.error(req.id,
                "Nearby blocks query failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private BridgeResponse handleBlockDetails(BridgeRequest req) {
        if (blocksProvider == null) {
            return BridgeResponse.error(req.id,
                "No blocks provider configured for this Minecraft version.");
        }

        int x = req.payload.get("x").getAsInt();
        int y = req.payload.get("y").getAsInt();
        int z = req.payload.get("z").getAsInt();
        try {
            BlockDetailsDto details = blocksProvider.getBlockDetails(x, y, z);
            if (details == null) {
                // Provider signals "no block entity" via null; the wire shape
                // for that case is `{gone: true}`.
                return BridgeResponse.success(req.id,
                    GSON_OMIT_NULLS.toJsonTree(BlockDetailsDto.gone()), null);
            }
            details.type = unresolveOrNull(details.type);
            return BridgeResponse.success(req.id, GSON_OMIT_NULLS.toJsonTree(details), null);
        } catch (Exception e) {
            return BridgeResponse.error(req.id,
                "Block details query failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private BridgeResponse handleLookedAtEntity(BridgeRequest req) {
        if (lookedAtEntityProvider == null) {
            return BridgeResponse.error(req.id,
                "No looked-at entity provider configured for this Minecraft version.");
        }
        double range = (req.payload != null && req.payload.has("range"))
            ? req.payload.get("range").getAsDouble() : 64.0;
        try {
            LookedAtEntityDto dto =
                new LookedAtEntityDto(lookedAtEntityProvider.getLookedAtEntity(range));
            // GSON (serializeNulls=true) so absent-target emits `entityId: null`
            // explicitly — clients distinguish "no target" from a malformed
            // response by the key being present.
            return BridgeResponse.success(req.id, GSON.toJsonTree(dto), null);
        } catch (Exception e) {
            return BridgeResponse.error(req.id,
                "Looked-at entity query failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private BridgeResponse handleChatHistory(BridgeRequest req) {
        if (chatHistoryProvider == null) {
            return BridgeResponse.error(req.id,
                "No chat history provider configured for this Minecraft version.");
        }
        int limit = req.payload != null && req.payload.has("limit")
            ? req.payload.get("limit").getAsInt() : 50;
        boolean includeJson = req.payload != null && req.payload.has("includeJson")
            && req.payload.get("includeJson").getAsBoolean();
        try {
            List<ChatMessageDto> messages =
                chatHistoryProvider.getRecentMessages(limit, resolver, includeJson);
            ChatHistoryDto dto = new ChatHistoryDto(messages);
            // Omit-nulls so per-message optional fields (addedTime, json) drop
            // out when not populated, preserving the historical wire shape.
            return BridgeResponse.success(req.id, GSON_OMIT_NULLS.toJsonTree(dto), null);
        } catch (Exception e) {
            return BridgeResponse.error(req.id,
                "Chat history query failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private BridgeResponse handleScreenInspect(BridgeRequest req) {
        if (screenInspectProvider == null) {
            return BridgeResponse.error(req.id,
                "No screen inspect provider configured for this Minecraft version.");
        }
        boolean includeIcons = req.payload != null && req.payload.has("includeIcons")
            && req.payload.get("includeIcons").getAsBoolean();
        try {
            ScreenInspectDto dto = screenInspectProvider.inspectCurrentScreen();
            // Map runtime class names → Mojang names. Provider populates the
            // raw `getClass().getName()` strings; the kernel applies the
            // resolver here so each version impl stays free of mapping logic.
            // Closes review.md Theme 1 (`slots[].container` was previously not
            // mapped — both versions emitted `class_1277` / `class_1661`).
            dto.type = unresolveOrNull(dto.type);
            dto.menuClass = unresolveOrNull(dto.menuClass);
            if (dto.slots != null) {
                for (SlotDto slot : dto.slots) {
                    slot.container = unresolveOrNull(slot.container);
                }
            }

            if (includeIcons && dto.slots != null) {
                java.util.Set<String> uniqueIds = new java.util.LinkedHashSet<>();
                for (SlotDto slot : dto.slots) {
                    if (slot.item != null && slot.item.itemId != null) {
                        uniqueIds.add(slot.item.itemId);
                    }
                }
                dto.icons = renderIconsMap(uniqueIds);
            }
            return BridgeResponse.success(req.id, GSON_OMIT_NULLS.toJsonTree(dto), null);
        } catch (Exception e) {
            return BridgeResponse.error(req.id,
                "Screen inspect failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private BridgeResponse handleSetEntityGlow(BridgeRequest req) {
        int entityId = req.payload.get("entityId").getAsInt();
        boolean glow = req.payload.get("glow").getAsBoolean();
        ClientEntityGlowManager.setGlow(entityId, glow);
        JsonObject result = new JsonObject();
        result.addProperty("entityId", entityId);
        result.addProperty("glow", glow);
        return BridgeResponse.success(req.id, result, null);
    }

    private BridgeResponse handleSetBlockGlow(BridgeRequest req) {
        int x = req.payload.get("x").getAsInt();
        int y = req.payload.get("y").getAsInt();
        int z = req.payload.get("z").getAsInt();
        boolean glow = req.payload.get("glow").getAsBoolean();
        ClientBlockGlowManager.setGlow(x, y, z, glow);
        JsonObject result = new JsonObject();
        result.addProperty("x", x);
        result.addProperty("y", y);
        result.addProperty("z", z);
        result.addProperty("glow", glow);
        return BridgeResponse.success(req.id, result, null);
    }

    private BridgeResponse handleClearBlockGlow(BridgeRequest req) {
        ClientBlockGlowManager.clear();
        JsonObject result = new JsonObject();
        result.addProperty("cleared", true);
        return BridgeResponse.success(req.id, result, null);
    }

}
