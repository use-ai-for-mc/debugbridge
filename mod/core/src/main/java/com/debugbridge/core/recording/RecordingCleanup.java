package com.debugbridge.core.recording;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.time.Instant;
import java.util.logging.Level;
import java.util.logging.Logger;

final class RecordingCleanup {
    private static final Logger LOG = Logger.getLogger("DebugBridge");

    private RecordingCleanup() {}

    static int deleteExpiredTempRecordings(Path baseDir, Duration maxAge, Path activeDir) {
        if (baseDir == null || maxAge == null || maxAge.isNegative() || maxAge.isZero()) {
            return 0;
        }
        if (!Files.isDirectory(baseDir, LinkOption.NOFOLLOW_LINKS)) {
            return 0;
        }

        int deleted = 0;
        Instant cutoff = Instant.now().minus(maxAge);
        Path normalizedActive =
                activeDir == null ? null : activeDir.toAbsolutePath().normalize();

        try (DirectoryStream<Path> children = Files.newDirectoryStream(baseDir)) {
            for (Path child : children) {
                if (!Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(child)) {
                    continue;
                }
                if (normalizedActive != null
                        && normalizedActive.equals(child.toAbsolutePath().normalize())) {
                    continue;
                }
                if (!looksLikeRecordingDir(child)) {
                    continue;
                }
                Instant newest = newestModifiedTime(child);
                if (newest != null && newest.isBefore(cutoff)) {
                    deleteTree(child);
                    deleted++;
                }
            }
        } catch (IOException e) {
            LOG.log(Level.WARNING, "[DebugBridge] Failed to scan temp recordings under " + baseDir, e);
        }
        if (deleted > 0) {
            LOG.info("[DebugBridge] Deleted " + deleted + " expired temp recording(s) under " + baseDir);
        }
        return deleted;
    }

    private static boolean looksLikeRecordingDir(Path dir) {
        try (DirectoryStream<Path> files = Files.newDirectoryStream(dir)) {
            for (Path file : files) {
                if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                String name = file.getFileName().toString();
                if ("recording.jpg".equals(name) || name.matches("frame-\\d{4}\\.jpg")) {
                    return true;
                }
            }
        } catch (IOException e) {
            LOG.log(Level.FINE, "[DebugBridge] Failed to inspect recording dir " + dir, e);
        }
        return false;
    }

    private static Instant newestModifiedTime(Path dir) {
        try {
            Instant newest =
                    Files.getLastModifiedTime(dir, LinkOption.NOFOLLOW_LINKS).toInstant();
            try (DirectoryStream<Path> files = Files.newDirectoryStream(dir)) {
                for (Path file : files) {
                    if (Files.isSymbolicLink(file)) {
                        continue;
                    }
                    Instant modified = Files.getLastModifiedTime(file, LinkOption.NOFOLLOW_LINKS)
                            .toInstant();
                    if (modified.isAfter(newest)) {
                        newest = modified;
                    }
                }
            }
            return newest;
        } catch (IOException e) {
            LOG.log(Level.FINE, "[DebugBridge] Failed to stat recording dir " + dir, e);
            return null;
        }
    }

    private static void deleteTree(Path dir) {
        try {
            Files.walkFileTree(dir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.deleteIfExists(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path d, IOException exc) throws IOException {
                    if (exc != null) throw exc;
                    Files.deleteIfExists(d);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            LOG.log(Level.WARNING, "[DebugBridge] Failed to delete expired temp recording " + dir, e);
        }
    }
}
