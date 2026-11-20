package dev.rinchan.structuredfucache.cache;

import com.mojang.datafixers.DataFixer;
import com.mojang.logging.LogUtils;
import dev.rinchan.structuredfucache.StructureDfuCache;
import dev.rinchan.structuredfucache.compat.McCompat;
import dev.rinchan.structuredfucache.config.StructureDfuCacheConfig;
import dev.rinchan.structuredfucache.platform.PlatformBridge;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import net.minecraft.server.packs.resources.ResourceManager;
import org.slf4j.Logger;

/**
 * Entry point for StructureTemplateManagerMixin.
 * Cleans legacy 1.x artifacts, checks manifest.v2 line-1 header for a sub-5ms fast-path mount,
 * or runs StructureCacheBuilder on miss/change.
 */
public final class StructureCacheBootstrap {
    private static final Logger LOGGER = LogUtils.getLogger();

    private StructureCacheBootstrap() {
    }

    public static ResourceManager prepare(ResourceManager original, DataFixer dataFixer) {
        ResourceManager base = McCompat.unwrapOverlay(original);
        StructureDfuCacheConfig config = StructureDfuCacheConfig.get();
        if (!config.enabled()) {
            CacheStats.recordDisabled();
            if (config.verboseLogging()) {
                LOGGER.info("Structure DFU cache disabled via config; using vanilla resources.");
            }
            return base;
        }
        PlatformBridge platform = StructureDfuCache.platform();
        Path gameDir = platform.gameDir();
        Path cacheRoot = StructureDfuCache.cacheRoot();
        CacheManifest.Header header = new CacheManifest.Header(
            McCompat.currentDataVersion(),
            platform.minecraftVersion(),
            platform.loaderId(),
            PackFingerprint.compute(base, gameDir)
        );
        return prepare(base, dataFixer, cacheRoot, header, config);
    }

    static ResourceManager prepare(
        ResourceManager original,
        DataFixer dataFixer,
        Path cacheRoot,
        CacheManifest.Header expectedHeader
    ) {
        return prepare(original, dataFixer, cacheRoot, expectedHeader, StructureDfuCacheConfig.defaults());
    }

    static ResourceManager prepare(
        ResourceManager original,
        DataFixer dataFixer,
        Path cacheRoot,
        CacheManifest.Header expectedHeader,
        StructureDfuCacheConfig config
    ) {
        ResourceManager base = McCompat.unwrapOverlay(original);
        if (config != null && !config.enabled()) {
            CacheStats.recordDisabled();
            return base;
        }
        long startedNanos = System.nanoTime();
        try {
            Files.createDirectories(cacheRoot);
            try (var channel = FileChannel.open(cacheRoot.resolve("prepare.lock"),
                     StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 var lock = channel.tryLock()) {
                if (lock == null) {
                    LOGGER.warn("Structure DFU cache lock is held by another process; using vanilla resources.");
                    CacheStats.recordFallback();
                    return base;
                }

                cleanupLegacyArtifacts(cacheRoot);

                Path manifestFile = cacheRoot.resolve(CacheManifest.FILE_NAME);
                Path overlayDataDir = cacheRoot.resolve("overlay").resolve("data");
                Optional<CacheManifest.Header> existing = CacheManifest.readHeaderOnly(manifestFile);
                if (existing.isPresent() && existing.get().equals(expectedHeader) && Files.isDirectory(overlayDataDir)) {
                    long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
                    CacheStats.recordFastPath(elapsedMs, expectedHeader.packFingerprint());
                    LOGGER.info(
                        "Structure DFU cache mounted in {} ms (fast-path, fingerprint={})",
                        elapsedMs,
                        shortFingerprint(expectedHeader.packFingerprint())
                    );
                    return McCompat.wrapOverlay(base, overlayDataDir);
                }

                int workers = StructureCacheBuilder.resolveWorkers(config != null ? config.maxWorkers() : 0);
                StructureCacheBuilder.BuildSummary summary =
                    new StructureCacheBuilder(dataFixer, cacheRoot, workers).build(base, expectedHeader);
                CacheStats.recordBuild(summary, expectedHeader.packFingerprint());
                if (config != null && config.verboseLogging()) {
                    LOGGER.info(
                        "Structure DFU cache built in {} ms (workers={}, total={}, converted={}, passthrough={}, cacheHits={}, skippedBad={}, fingerprint={})",
                        summary.elapsedMs(),
                        workers,
                        summary.total(),
                        summary.converted(),
                        summary.passthrough(),
                        summary.cacheHits(),
                        summary.skippedBad(),
                        shortFingerprint(expectedHeader.packFingerprint())
                    );
                } else {
                    LOGGER.info(
                        "Structure DFU cache built in {} ms (total={}, converted={}, passthrough={}, cacheHits={}, skippedBad={}, fingerprint={})",
                        summary.elapsedMs(),
                        summary.total(),
                        summary.converted(),
                        summary.passthrough(),
                        summary.cacheHits(),
                        summary.skippedBad(),
                        shortFingerprint(expectedHeader.packFingerprint())
                    );
                }
                return McCompat.wrapOverlay(base, overlayDataDir);
            }
        } catch (IOException | RuntimeException exception) {
            LOGGER.error("Structure DFU cache disabled; falling back to vanilla resources: {}", exception.getMessage());
            CacheStats.recordFallback();
            return base;
        }
    }

    private static void cleanupLegacyArtifacts(Path cacheRoot) {
        deleteTreeQuietly(cacheRoot.resolve("blobs"));
        deleteTreeQuietly(cacheRoot.resolve("sources"));
        deleteTreeQuietly(cacheRoot.resolve("generations"));
        try {
            Files.deleteIfExists(cacheRoot.resolve("index.json"));
        } catch (IOException ignored) {
        }
    }

    private static void deleteTreeQuietly(Path root) {
        if (!Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException | UncheckedIOException ignored) {
        }
    }

    private static String shortFingerprint(String fingerprint) {
        return fingerprint.length() <= 12 ? fingerprint : fingerprint.substring(0, 12);
    }
}
