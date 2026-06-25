package com.debugbridge.core.recording;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RecordingProviderTest {
    @TempDir
    Path tempDir;

    private final FrameCapturer capturer = (downscale, sink) -> sink.onPixels(new int[] {0xFF336699}, 1, 1);

    @Test
    void tempStorageIsDefaultScratchOutputWithExpiry() throws Exception {
        Path persistentRoot = tempDir.resolve("persistent");
        Path tempRoot = tempDir.resolve("temp");
        RecordingProvider provider = new RecordingProvider(capturer, persistentRoot, tempRoot);
        RecordingRequest req = new RecordingRequest(
                "temp-rec",
                RecordingRequest.Storage.TEMP,
                24,
                1,
                RecordingRequest.INTERVAL_EVERY_FRAME,
                RecordingRequest.OutputMode.GRID,
                1,
                1,
                0.75f);

        Instant before = Instant.now();
        RecordingResult result = runRecording(provider, req);

        assertEquals(RecordingRequest.Storage.TEMP, result.storage);
        assertTrue(Path.of(result.directory).startsWith(tempRoot.toAbsolutePath()));
        assertFalse(Path.of(result.directory).startsWith(persistentRoot.toAbsolutePath()));
        assertNotNull(result.expiresAt);
        assertTrue(result.expiresAt.isAfter(before.plusSeconds(23 * 3600L)));
        assertTrue(Files.exists(Path.of(((RecordingResult.Grid) result).path)));
    }

    @Test
    void persistentStorageUsesGameRecordingRootWithoutExpiry() throws Exception {
        Path persistentRoot = tempDir.resolve("persistent");
        Path tempRoot = tempDir.resolve("temp");
        RecordingProvider provider = new RecordingProvider(capturer, persistentRoot, tempRoot);
        RecordingRequest req = new RecordingRequest(
                "keep-rec",
                RecordingRequest.Storage.PERSISTENT,
                24,
                1,
                RecordingRequest.INTERVAL_EVERY_FRAME,
                RecordingRequest.OutputMode.GRID,
                1,
                1,
                0.75f);

        RecordingResult result = runRecording(provider, req);

        assertEquals(RecordingRequest.Storage.PERSISTENT, result.storage);
        assertTrue(Path.of(result.directory).startsWith(persistentRoot.toAbsolutePath()));
        assertNull(result.expiresAt);
        assertTrue(Files.exists(Path.of(((RecordingResult.Grid) result).path)));
    }

    private static RecordingResult runRecording(RecordingProvider provider, RecordingRequest req) throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<RecordingResult> future = executor.submit(() -> provider.record(req));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!future.isDone() && System.nanoTime() < deadline) {
                provider.onRenderFrame();
                Thread.sleep(5);
            }
            return future.get(5, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }
    }
}
