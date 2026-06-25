package com.debugbridge.core.lifecycle;

import static org.junit.jupiter.api.Assertions.*;

import com.debugbridge.core.BridgeConfig;
import com.debugbridge.core.block.NearbyBlocksProvider;
import com.debugbridge.core.chat.ChatHistoryProvider;
import com.debugbridge.core.command.CommandProvider;
import com.debugbridge.core.entity.LookedAtEntityProvider;
import com.debugbridge.core.entity.NearbyEntitiesProvider;
import com.debugbridge.core.mapping.FabricNamespaceLookup;
import com.debugbridge.core.mapping.MappingResolver;
import com.debugbridge.core.protocol.dto.SnapshotDto;
import com.debugbridge.core.screen.ScreenInspectProvider;
import com.debugbridge.core.screenshot.ScreenshotProvider;
import com.debugbridge.core.script.ThreadDispatcher;
import com.debugbridge.core.server.BridgeServer;
import com.debugbridge.core.snapshot.GameStateProvider;
import com.debugbridge.core.texture.ItemTextureProvider;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the kernel lifecycle in {@link AbstractDebugBridgeMod}.
 * Lives in the same package as the class under test so it can read/write the
 * {@code protected} state fields ({@code config}, {@code server},
 * {@code needsWarning}, {@code startupError/Info}) and call
 * {@code protected} methods ({@code handleTick}).
 *
 * <p>The {@link StubMod} subclass overrides {@link AbstractDebugBridgeMod#tryStartOnPort}
 * so the port-probe loop and tick routing can be tested without binding real
 * sockets or touching Minecraft. Each test pre-populates the stub's behavior,
 * drives the kernel, and asserts the captured kernel-side effects.
 *
 * <p>Coverage:
 * <ul>
 *   <li>Port-probe loop with first port unavailable, fallthrough to next.
 *   <li>Port-range wraparound when preferred port is past the start.
 *   <li>All ports exhausted → {@code startupError} populated.
 *   <li>Preferred port out-of-range clamps before probing.
 *   <li>{@code startupInfo} only fires when chosen port differs from preferred.
 *   <li>Tick routing: error/info displayed when player ready, retained when not.
 *   <li>{@code onPostTick} fires every tick.
 *   <li>Warning-screen flow: shown once when ready, accept → server starts,
 *       decline → mod stays disabled.
 *   <li>Double-startup is idempotent (the {@code serverStarted} guard).
 * </ul>
 */
class AbstractDebugBridgeModTest {

    /**
     * Test double for {@link AbstractDebugBridgeMod}. Overrides
     * {@link #tryStartOnPort} so we never bind a real socket; ports in
     * {@link #availablePorts} are reported as bind-able and successful starts
     * record the chosen port + create a placeholder {@link BridgeServer}
     * (constructed but never started — its constructor doesn't bind).
     *
     * <p>Hook calls are captured into per-field counters/lists so each test
     * can assert which kernel branches fired.
     */
    static final class StubMod extends AbstractDebugBridgeMod {
        // --- Inputs the test pre-populates ---
        final Set<Integer> availablePorts = new HashSet<>();
        final BridgeConfig stubConfig = new BridgeConfig();
        boolean canShowWarning = true;
        boolean playerReady = true;

        // --- Captures the test asserts on ---
        final List<Integer> portsTried = new ArrayList<>();
        final List<String> errorsDisplayed = new ArrayList<>();
        final List<String> infosDisplayed = new ArrayList<>();
        int onPostTickCalls = 0;
        int webUiStartedForBridgePort = -1;
        Consumer<Boolean> capturedWarningCallback;
        int warningScreensOpened = 0;

        StubMod() {
            stubConfig.port = 9876;
        }

        @Override
        protected boolean tryStartOnPort(
                int port,
                MappingResolver resolver,
                ThreadDispatcher dispatcher,
                GameStateProvider stateProvider,
                ScreenshotProvider screenshotProvider) {
            portsTried.add(port);
            if (!availablePorts.contains(port)) return false;
            // BridgeServer's constructor doesn't actually bind; binding only
            // happens on .start(). So we can construct it safely for the
            // kernel's setProvider(...) calls without opening a real socket.
            this.server = new BridgeServer(port, resolver, dispatcher, stateProvider, screenshotProvider);
            return true;
        }

        int boundPort() {
            return server == null ? -1 : server.getPort();
        }

        @Override
        protected void startWebUi(int bridgePort) {
            // Record the kernel's intent without opening a real HTTP socket.
            webUiStartedForBridgePort = bridgePort;
        }

        @Override
        protected String mcVersion() {
            return "test";
        }

        @Override
        protected Path configDir() {
            return Path.of("/nonexistent-for-test");
        }

        @Override
        protected Path gameDir() {
            return Path.of("/nonexistent-for-test");
        }

        @Override
        protected FabricNamespaceLookup createNamespaceLookup() {
            // null → kernel falls back to PassthroughResolver, which is what
            // we want for tests (no mapping download).
            return null;
        }

        @Override
        protected void submitToGameThread(Runnable task) {
            // Run synchronously on the test thread — the kernel's dispatcher
            // wrapper only times-out if the future doesn't complete.
            task.run();
        }

        @Override
        protected GameStateProvider createStateProvider() {
            return SnapshotDto::new;
        }

        @Override
        protected ScreenshotProvider createScreenshotProvider() {
            return (downscale, quality, timeout) -> new ScreenshotProvider.Capture("/tmp/x.jpg", 0, 0, 0);
        }

        @Override
        protected ItemTextureProvider createTextureProvider() {
            return new ItemTextureProvider() {
                @Override
                public TextureResult getItemTexture(int slot) {
                    return null;
                }

                @Override
                public TextureResult getEntityItemTexture(int eid, String s) {
                    return null;
                }

                @Override
                public TextureResult getItemTextureById(String id) {
                    return null;
                }
            };
        }

        @Override
        protected NearbyEntitiesProvider createEntitiesProvider() {
            return new NearbyEntitiesProvider() {
                @Override
                public List<com.debugbridge.core.protocol.dto.EntitySummaryDto> getNearbyEntities(double r, int l) {
                    return List.of();
                }

                @Override
                public com.debugbridge.core.protocol.dto.EntityDetailsDto getEntityDetails(int id) {
                    return null;
                }
            };
        }

        @Override
        protected NearbyBlocksProvider createBlocksProvider() {
            return new NearbyBlocksProvider() {
                @Override
                public List<com.debugbridge.core.protocol.dto.BlockSummaryDto> getNearbyBlocks(double r, int l) {
                    return List.of();
                }

                @Override
                public com.debugbridge.core.protocol.dto.BlockDetailsDto getBlockDetails(int x, int y, int z) {
                    return null;
                }
            };
        }

        @Override
        protected LookedAtEntityProvider createLookedAtEntityProvider() {
            return range -> null;
        }

        @Override
        protected ChatHistoryProvider createChatHistoryProvider() {
            return (limit, resolver, includeJson) -> List.of();
        }

        @Override
        protected ScreenInspectProvider createScreenInspectProvider() {
            return () -> new com.debugbridge.core.protocol.dto.ScreenInspectDto();
        }

        @Override
        protected CommandProvider createCommandProvider() {
            return command -> {};
        }

        @Override
        protected com.debugbridge.core.session.SessionControlProvider createSessionControlProvider() {
            return null;
        }

        @Override
        protected boolean displayPlayerError(String m) {
            if (!playerReady) return false;
            errorsDisplayed.add(m);
            return true;
        }

        @Override
        protected boolean displayPlayerInfo(String m) {
            if (!playerReady) return false;
            infosDisplayed.add(m);
            return true;
        }

        @Override
        protected boolean canShowWarningScreen() {
            return canShowWarning;
        }

        @Override
        protected void showWarningScreen(Consumer<Boolean> onResult) {
            warningScreensOpened++;
            capturedWarningCallback = onResult;
        }

        @Override
        protected void onPostTick() {
            onPostTickCalls++;
        }
    }

    /**
     * Drives the "developer mode already accepted" path of {@link
     * AbstractDebugBridgeMod#initialize} without going through {@code
     * BridgeConfig.load}, which would need a real filesystem. Sets {@code
     * config} directly and immediately invokes the same {@code startServer}
     * the kernel would.
     */
    private static void runStartServer(StubMod mod) {
        mod.config = mod.stubConfig;
        // Drive the same code path initialize() takes when developerModeAccepted=true.
        // Faking the warning-screen accept path is the cleanest way to reach
        // startServer() from outside the package without touching real config files.
        mod.needsWarning = true;
        mod.handleTick();
        assertNotNull(
                mod.capturedWarningCallback, "warning screen should have been opened — kernel pre-conditions broken");
        mod.capturedWarningCallback.accept(true);
    }

    @Test
    void portProbeFallsThroughWhenFirstPortUnavailable() {
        StubMod mod = new StubMod();
        mod.stubConfig.port = 9876;
        mod.availablePorts.add(9878); // first two unavailable

        runStartServer(mod);

        assertEquals(List.of(9876, 9877, 9878), mod.portsTried, "kernel should probe in order until success");
        assertEquals(9878, mod.boundPort(), "server should bind on the first available port");
        assertEquals(9878, mod.webUiStartedForBridgePort, "web UI should be started for the port actually bound");
    }

    @Test
    void portWraparoundWhenPreferredPortPastFreePorts() {
        StubMod mod = new StubMod();
        mod.stubConfig.port = 9880;
        mod.availablePorts.add(9876); // only the first port in range is free

        runStartServer(mod);

        // First pass: 9880..9886 (all unavailable), then wraparound: 9876..9879.
        // Wraparound finds 9876 and stops.
        List<Integer> expected = List.of(9880, 9881, 9882, 9883, 9884, 9885, 9886, 9876);
        assertEquals(expected, mod.portsTried, "wraparound should explore preferred-onward then start-onward");
        assertEquals(9876, mod.boundPort());
    }

    @Test
    void portRangeExhaustedSetsStartupError() {
        StubMod mod = new StubMod();
        // availablePorts is empty — none of 9876..9886 succeed.

        runStartServer(mod);

        assertEquals(11, mod.portsTried.size(), "all 11 ports in range should have been probed");
        assertEquals(-1, mod.boundPort(), "no server should be bound");
        assertNotNull(mod.startupError, "startupError should be set when all ports fail");
        assertTrue(
                mod.startupError.contains("9876-9886"),
                "error should mention the configured port range; got: " + mod.startupError);
    }

    @Test
    void preferredPortClampedIntoRangeBeforeProbing() {
        StubMod mod = new StubMod();
        mod.stubConfig.port = 1; // way below the legal range
        mod.availablePorts.add(9876);

        runStartServer(mod);

        assertEquals(9876, mod.portsTried.get(0), "preferred port below the range should clamp to PORT_RANGE_START");
        assertEquals(9876, mod.boundPort());
    }

    @Test
    void infoFiresOnNextTickWhenChosenPortDiffersFromPreferred() {
        StubMod mod = new StubMod();
        mod.stubConfig.port = 9876;
        mod.availablePorts.add(9878); // preferred unavailable, falls through

        runStartServer(mod);

        // The kernel sets startupInfo at startup time; routing fires on the next tick.
        assertNotNull(mod.startupInfo, "startupInfo should be queued when chosen port != preferred");
        mod.handleTick();
        assertEquals(1, mod.infosDisplayed.size(), "queued info should fire on the next tick");
    }

    @Test
    void noStartupInfoWhenChosenPortMatchesPreferred() {
        StubMod mod = new StubMod();
        mod.stubConfig.port = 9876;
        mod.availablePorts.add(9876);

        runStartServer(mod);

        assertNull(mod.startupInfo, "startupInfo should stay null when preferred port was successfully bound");
    }

    @Test
    void tickRoutesErrorMessageOnceWhenPlayerIsReady() {
        StubMod mod = new StubMod();
        mod.startupError = "boom";

        mod.handleTick();

        assertEquals(List.of("boom"), mod.errorsDisplayed, "error message should be displayed once");
        assertNull(mod.startupError, "after display, error field should be cleared");

        mod.handleTick();
        assertEquals(1, mod.errorsDisplayed.size(), "cleared error field should not re-display on subsequent tick");
    }

    @Test
    void tickRetainsErrorMessageWhenPlayerIsNotReady() {
        StubMod mod = new StubMod();
        mod.playerReady = false;
        mod.startupError = "queued";

        mod.handleTick();

        assertEquals(0, mod.errorsDisplayed.size(), "no message should be sent when player is null");
        assertEquals("queued", mod.startupError, "error should remain queued for the next tick");

        mod.playerReady = true;
        mod.handleTick();
        assertEquals(List.of("queued"), mod.errorsDisplayed);
        assertNull(mod.startupError);
    }

    @Test
    void tickRoutesInfoMessageWhenPlayerReady() {
        StubMod mod = new StubMod();
        mod.startupInfo = "hello";

        mod.handleTick();

        assertEquals(List.of("hello"), mod.infosDisplayed);
        assertNull(mod.startupInfo);
    }

    @Test
    void onPostTickFiresEveryTick() {
        StubMod mod = new StubMod();

        mod.handleTick();
        mod.handleTick();
        mod.handleTick();

        assertEquals(3, mod.onPostTickCalls, "post-tick hook should fire on every tick regardless of warning state");
    }

    @Test
    void warningScreenOpensOnceWhenReady() {
        StubMod mod = new StubMod();
        mod.needsWarning = true;
        mod.canShowWarning = true;

        mod.handleTick();
        mod.handleTick();
        mod.handleTick();

        assertEquals(
                1, mod.warningScreensOpened, "warning screen should be opened exactly once even across multiple ticks");
    }

    @Test
    void warningScreenWaitsUntilCanShowReturnsTrue() {
        StubMod mod = new StubMod();
        mod.needsWarning = true;
        mod.canShowWarning = false;

        mod.handleTick();
        mod.handleTick();
        assertEquals(0, mod.warningScreensOpened);

        mod.canShowWarning = true;
        mod.handleTick();
        assertEquals(1, mod.warningScreensOpened);
    }

    @Test
    void warningScreenAcceptedStartsServer() {
        StubMod mod = new StubMod();
        mod.config = mod.stubConfig;
        mod.stubConfig.port = 9876;
        mod.availablePorts.add(9876);
        mod.needsWarning = true;

        mod.handleTick();
        assertNotNull(mod.capturedWarningCallback);

        mod.capturedWarningCallback.accept(true);

        assertEquals(9876, mod.boundPort(), "accepted warning should start the server");
        assertFalse(mod.needsWarning, "needsWarning flag should clear after callback");
    }

    @Test
    void warningScreenDeclinedLeavesServerStopped() {
        StubMod mod = new StubMod();
        mod.config = mod.stubConfig;
        mod.needsWarning = true;

        mod.handleTick();
        mod.capturedWarningCallback.accept(false);

        assertEquals(-1, mod.boundPort(), "declined warning should NOT start the server");
        assertFalse(mod.needsWarning);
        assertTrue(mod.portsTried.isEmpty(), "no port probing should have happened");
    }

    @Test
    void doubleStartupIsIdempotent() {
        StubMod mod = new StubMod();
        mod.config = mod.stubConfig;
        mod.stubConfig.port = 9876;
        mod.availablePorts.add(9876);
        mod.needsWarning = true;

        mod.handleTick();
        mod.capturedWarningCallback.accept(true);
        int firstPortsTriedCount = mod.portsTried.size();

        // Simulate a second accept (e.g. spurious second screen close). The
        // serverStarted AtomicBoolean should short-circuit the second start.
        mod.capturedWarningCallback.accept(true);

        assertEquals(firstPortsTriedCount, mod.portsTried.size(), "second startup attempt should be a no-op");
    }
}
