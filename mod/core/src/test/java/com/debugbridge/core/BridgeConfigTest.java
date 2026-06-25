package com.debugbridge.core;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BridgeConfigTest {
    @TempDir
    Path tempDir;

    @Test
    void loadReadsSnakeCaseKeysAndScriptBlock() throws Exception {
        Files.writeString(tempDir.resolve("debugbridge.json"), """
                {
                  "port": 9877,
                  "timeout_ms": 6000,
                  "max_results": 25,
                  "developer_mode_accepted": true,
                  "run_command_enabled": true,
                  "session_control_enabled": true,
                  "web_ui_enabled": false,
                  "script": {
                    "max_execution_time_ms": 7000
                  }
                }
                """);

        BridgeConfig config = BridgeConfig.load(tempDir);

        assertEquals(9877, config.port);
        assertEquals(6000, config.timeoutMs);
        assertEquals(25, config.maxResults);
        assertTrue(config.developerModeAccepted);
        assertTrue(config.runCommandEnabled);
        assertTrue(config.sessionControlEnabled);
        assertFalse(config.webUiEnabled);
        assertEquals(7000, config.scriptMaxExecutionTimeMs);
    }

    @Test
    void loadAcceptsLegacyLuaScriptBlock() throws Exception {
        Files.writeString(tempDir.resolve("debugbridge.json"), """
                {
                  "developer_mode_accepted": true,
                  "lua": {
                    "max_execution_time_ms": 8000
                  }
                }
                """);

        BridgeConfig config = BridgeConfig.load(tempDir);

        assertEquals(8000, config.scriptMaxExecutionTimeMs);
    }

    @Test
    void loadFallsBackForInvalidValues() throws Exception {
        Files.writeString(tempDir.resolve("debugbridge.json"), """
                {
                  "port": 9999,
                  "timeout_ms": 0,
                  "max_results": -5,
                  "script": {
                    "max_execution_time_ms": -1
                  }
                }
                """);

        BridgeConfig config = BridgeConfig.load(tempDir);

        assertEquals(9876, config.port);
        assertEquals(5000, config.timeoutMs);
        assertEquals(100, config.maxResults);
        assertEquals(5000, config.scriptMaxExecutionTimeMs);
    }

    @Test
    void loadFallsBackForMalformedJson() throws Exception {
        Files.writeString(tempDir.resolve("debugbridge.json"), "{ nope");

        BridgeConfig config = BridgeConfig.load(tempDir);

        assertEquals(9876, config.port);
        assertFalse(config.developerModeAccepted);
    }

    @Test
    void saveWritesCanonicalKeysAndScriptBlock() throws Exception {
        BridgeConfig config = BridgeConfig.load(tempDir);
        config.port = 9878;
        config.timeoutMs = 6500;
        config.maxResults = 42;
        config.developerModeAccepted = true;
        config.runCommandEnabled = true;
        config.sessionControlEnabled = true;
        config.webUiEnabled = false;
        config.scriptMaxExecutionTimeMs = 9000;

        config.save();

        JsonObject saved = JsonParser.parseString(Files.readString(tempDir.resolve("debugbridge.json")))
                .getAsJsonObject();
        assertEquals(9878, saved.get("port").getAsInt());
        assertEquals(6500, saved.get("timeout_ms").getAsLong());
        assertEquals(42, saved.get("max_results").getAsInt());
        assertTrue(saved.get("developer_mode_accepted").getAsBoolean());
        assertTrue(saved.get("run_command_enabled").getAsBoolean());
        assertTrue(saved.get("session_control_enabled").getAsBoolean());
        assertFalse(saved.get("web_ui_enabled").getAsBoolean());
        assertEquals(
                9000,
                saved.getAsJsonObject("script").get("max_execution_time_ms").getAsLong());
        assertFalse(saved.has("lua"));
        assertFalse(saved.has("scriptMaxExecutionTimeMs"));
    }
}
