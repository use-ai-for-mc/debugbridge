package com.debugbridge.core.recording;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RecordingCleanupTest {
    @TempDir
    Path tempDir;

    @Test
    void deletesOnlyExpiredRecordingDirectories() throws Exception {
        Path expired = recordingDir("expired", Instant.now().minus(Duration.ofHours(3)));
        Path fresh = recordingDir("fresh", Instant.now());
        Path active = recordingDir("active", Instant.now().minus(Duration.ofHours(3)));
        Path foreign = tempDir.resolve("foreign");
        Files.createDirectories(foreign);
        Files.writeString(foreign.resolve("notes.txt"), "not a recording");
        setModified(foreign, Instant.now().minus(Duration.ofHours(3)));

        int deleted = RecordingCleanup.deleteExpiredTempRecordings(tempDir, Duration.ofHours(1), active);

        assertEquals(1, deleted);
        assertFalse(Files.exists(expired));
        assertTrue(Files.exists(fresh));
        assertTrue(Files.exists(active), "active recording must never be deleted");
        assertTrue(Files.exists(foreign), "non-recording directories are left alone");
    }

    private Path recordingDir(String name, Instant modified) throws Exception {
        Path dir = tempDir.resolve(name);
        Files.createDirectories(dir);
        Path jpg = dir.resolve("recording.jpg");
        Files.write(jpg, new byte[] {1, 2, 3});
        setModified(jpg, modified);
        setModified(dir, modified);
        return dir;
    }

    private static void setModified(Path path, Instant modified) throws Exception {
        Files.setLastModifiedTime(path, FileTime.from(modified));
    }
}
