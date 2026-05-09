package com.debugbridge.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;

/**
 * Reads config from .minecraft/config/debugbridge.json (or a provided path).
 * Falls back to sensible defaults if the file doesn't exist.
 */
public class BridgeConfig {
    private static final Logger LOG = Logger.getLogger("DebugBridge");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public int port = 9876;
    public long timeoutMs = 5000;
    public int maxResults = 100;
    public long luaMaxExecutionTimeMs = 5000;

    /**
     * Whether the user has acknowledged this is a developer tool.
     * Must be true for the mod to activate.
     */
    public boolean developerModeAccepted = false;

    /**
     * Slash-command execution via the bridge. Off by default — the runtime
     * still runs whatever Lua a connected client sends, so flipping this on
     * only widens the surface for anyone authorized to drive the bridge.
     */
    public boolean runCommandEnabled = false;

    /**
     * Path to the config file, set when loaded.
     */
    private transient Path configFile;

    /**
     * Load config from a directory (e.g. .minecraft/config/).
     */
    public static BridgeConfig load(Path configDir) {
        Path file = configDir.resolve("debugbridge.json");
        BridgeConfig config = new BridgeConfig();
        config.configFile = file;

        if (!Files.exists(file)) {
            LOG.info("[DebugBridge] No config file at " + file + ", using defaults (port 9876)");
            return config;
        }
        try {
            String json = Files.readString(file);
            JsonObject obj = GSON.fromJson(json, JsonObject.class);
            if (obj.has("port")) config.port = obj.get("port").getAsInt();
            if (obj.has("timeout_ms")) config.timeoutMs = obj.get("timeout_ms").getAsLong();
            if (obj.has("max_results")) config.maxResults = obj.get("max_results").getAsInt();
            if (obj.has("developer_mode_accepted")) {
                config.developerModeAccepted = obj.get("developer_mode_accepted").getAsBoolean();
            }
            if (obj.has("run_command_enabled")) {
                config.runCommandEnabled = obj.get("run_command_enabled").getAsBoolean();
            }
            if (obj.has("lua")) {
                JsonObject lua = obj.getAsJsonObject("lua");
                if (lua.has("max_execution_time_ms"))
                    config.luaMaxExecutionTimeMs = lua.get("max_execution_time_ms").getAsLong();
            }
            LOG.info("[DebugBridge] Config loaded from " + file + " (port " + config.port + ")");
            return config;
        } catch (IOException e) {
            LOG.warning("[DebugBridge] Failed to read config: " + e.getMessage() + ", using defaults");
            return config;
        }
    }

    /**
     * Save the current config to the config file.
     */
    public void save() {
        if (configFile == null) {
            LOG.warning("[DebugBridge] Cannot save config: no file path set");
            return;
        }
        try {
            JsonObject obj = new JsonObject();
            obj.addProperty("port", port);
            obj.addProperty("timeout_ms", timeoutMs);
            obj.addProperty("max_results", maxResults);
            obj.addProperty("developer_mode_accepted", developerModeAccepted);
            obj.addProperty("run_command_enabled", runCommandEnabled);
            JsonObject lua = new JsonObject();
            lua.addProperty("max_execution_time_ms", luaMaxExecutionTimeMs);
            obj.add("lua", lua);

            Files.writeString(configFile, GSON.toJson(obj));
            LOG.info("[DebugBridge] Config saved to " + configFile);
        } catch (IOException e) {
            LOG.warning("[DebugBridge] Failed to save config: " + e.getMessage());
        }
    }
}
