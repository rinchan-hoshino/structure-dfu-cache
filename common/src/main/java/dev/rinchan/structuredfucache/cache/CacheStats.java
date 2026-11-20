package dev.rinchan.structuredfucache.cache;

import dev.rinchan.structuredfucache.config.StructureDfuCacheConfig;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Tracks runtime cache preparation metrics and inspects on-disk manifest.v2 + overlay/ stats
 * lazily for the native Config Screen dashboard and OP /structuredfucache command.
 */
public final class CacheStats {
    public enum Mode {
        IDLE,
        FAST_PATH,
        BUILT,
        DISABLED,
        FALLBACK
    }

    public record RuntimeRecord(
        Mode mode,
        long lastPrepareMs,
        int lastConverted,
        int lastPassthrough,
        int lastCacheHits,
        int lastSkippedBad,
        String fingerprint
    ) {
        static final RuntimeRecord INITIAL = new RuntimeRecord(Mode.IDLE, -1L, 0, 0, 0, 0, "");
    }

    public record Snapshot(
        Mode mode,
        long lastPrepareMs,
        int convertedCount,
        int passthroughCount,
        int cacheHits,
        int skippedBad,
        long overlayBytes,
        int overlayFiles,
        String fingerprint
    ) {
    }

    public record ClearResult(boolean cleared, boolean locked, int removedFiles, long freedBytes) {
    }

    private static volatile RuntimeRecord lastRun = RuntimeRecord.INITIAL;

    private CacheStats() {
    }

    public static void recordFastPath(long elapsedMs, String fingerprint) {
        lastRun = new RuntimeRecord(Mode.FAST_PATH, elapsedMs, 0, 0, 0, 0, safe(fingerprint));
    }

    public static void recordBuild(StructureCacheBuilder.BuildSummary summary, String fingerprint) {
        lastRun = new RuntimeRecord(
            Mode.BUILT,
            summary.elapsedMs(),
            summary.converted(),
            summary.passthrough(),
            summary.cacheHits(),
            summary.skippedBad(),
            safe(fingerprint)
        );
    }

    public static void recordDisabled() {
        lastRun = new RuntimeRecord(Mode.DISABLED, 0L, 0, 0, 0, 0, "");
    }

    public static void recordFallback() {
        lastRun = new RuntimeRecord(Mode.FALLBACK, -1L, 0, 0, 0, 0, "");
    }

    public static void resetRuntime() {
        lastRun = RuntimeRecord.INITIAL;
    }

    public static Snapshot inspect(Path cacheRoot) {
        RuntimeRecord runtime = lastRun;
        Mode effectiveMode = !StructureDfuCacheConfig.get().enabled() && runtime.mode() == Mode.IDLE
            ? Mode.DISABLED
            : runtime.mode();

        int converted = 0;
        int passthrough = 0;
        String fingerprint = runtime.fingerprint();

        Path manifestFile = cacheRoot.resolve(CacheManifest.FILE_NAME);
        try {
            CacheManifest.DiskCounts counts = CacheManifest.inspectCounts(manifestFile);
            converted = counts.converted();
            passthrough = counts.passthrough();
            if (fingerprint.isEmpty() && counts.header().isPresent()) {
                fingerprint = counts.header().get().packFingerprint();
            }
        } catch (IOException ignored) {
        }

        long overlayBytes = 0L;
        int overlayFiles = 0;
        Path overlayDir = cacheRoot.resolve("overlay");
        if (Files.isDirectory(overlayDir)) {
            try (Stream<Path> walk = Files.walk(overlayDir)) {
                for (Path path : walk.toList()) {
                    if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                        overlayFiles++;
                        try {
                            overlayBytes += Files.size(path);
                        } catch (IOException ignored) {
                        }
                    }
                }
            } catch (IOException | UncheckedIOException ignored) {
            }
        }

        return new Snapshot(
            effectiveMode,
            runtime.lastPrepareMs(),
            converted,
            passthrough,
            runtime.lastCacheHits(),
            runtime.lastSkippedBad(),
            overlayBytes,
            overlayFiles,
            shortFingerprint(fingerprint)
        );
    }

    public static ClearResult clearCache(Path cacheRoot) {
        if (cacheRoot == null || !Files.exists(cacheRoot)) {
            resetRuntime();
            return new ClearResult(true, false, 0, 0L);
        }
        try {
            Files.createDirectories(cacheRoot);
            try (var channel = FileChannel.open(
                     cacheRoot.resolve("prepare.lock"),
                     StandardOpenOption.CREATE,
                     StandardOpenOption.WRITE);
                 var lock = channel.tryLock()) {
                if (lock == null) {
                    return new ClearResult(false, true, 0, 0L);
                }
                int[] counters = new int[1];
                long[] bytes = new long[1];
                deleteTreeCounted(cacheRoot.resolve("overlay"), counters, bytes);
                deleteFileCounted(cacheRoot.resolve(CacheManifest.FILE_NAME), counters, bytes);
                deleteTreeCounted(cacheRoot.resolve("blobs"), counters, bytes);
                deleteTreeCounted(cacheRoot.resolve("sources"), counters, bytes);
                deleteTreeCounted(cacheRoot.resolve("generations"), counters, bytes);
                deleteFileCounted(cacheRoot.resolve("index.json"), counters, bytes);
                resetRuntime();
                return new ClearResult(true, false, counters[0], bytes[0]);
            }
        } catch (IOException | RuntimeException exception) {
            return new ClearResult(false, false, 0, 0L);
        }
    }

    public static String formatBytes(long bytes) {
        if (bytes < 1024L) {
            return bytes + " B";
        }
        if (bytes < 1024L * 1024L) {
            return String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0);
        }
        return String.format(Locale.ROOT, "%.2f MB", bytes / (1024.0 * 1024.0));
    }

    private static void deleteTreeCounted(Path root, int[] filesRemoved, long[] bytesFreed) {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                    try {
                        bytesFreed[0] += Files.size(path);
                        filesRemoved[0]++;
                    } catch (IOException ignored) {
                    }
                }
                Files.deleteIfExists(path);
            }
        } catch (IOException | UncheckedIOException ignored) {
        }
    }

    private static void deleteFileCounted(Path file, int[] filesRemoved, long[] bytesFreed) {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try {
            bytesFreed[0] += Files.size(file);
            filesRemoved[0]++;
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static String shortFingerprint(String fingerprint) {
        if (fingerprint == null || fingerprint.isEmpty()) {
            return "-";
        }
        return fingerprint.length() <= 12 ? fingerprint : fingerprint.substring(0, 12);
    }
}
