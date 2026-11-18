package dev.rinchan.structuredfucache.cache;

import com.mojang.datafixers.DataFixer;
import com.mojang.logging.LogUtils;
import dev.rinchan.structuredfucache.StructureDfuCache;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import net.minecraft.SharedConstants;
import net.minecraft.server.packs.resources.ResourceManager;
import net.neoforged.fml.loading.FMLLoader;
import net.neoforged.fml.loading.FMLPaths;
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
        ResourceManager base = unwrap(original);
        Path gameDir = FMLPaths.GAMEDIR.get();
        Path cacheRoot = gameDir.resolve("cache").resolve(StructureDfuCache.MOD_ID);
        var version = FMLLoader.versionInfo();
        CacheManifest.Header header = new CacheManifest.Header(
            SharedConstants.getCurrentVersion().getDataVersion().getVersion(),
            version.mcVersion(),
            version.neoForgeVersion(),
            PackFingerprint.compute(base, gameDir)
        );
        return prepare(base, dataFixer, cacheRoot, header);
    }

    static ResourceManager prepare(
        ResourceManager original,
        DataFixer dataFixer,
        Path cacheRoot,
        CacheManifest.Header expectedHeader
    ) {
        ResourceManager base = unwrap(original);
        long startedNanos = System.nanoTime();
        try {
            Files.createDirectories(cacheRoot);
            try (var channel = FileChannel.open(cacheRoot.resolve("prepare.lock"),
                     StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 var lock = channel.tryLock()) {
                if (lock == null) {
                    LOGGER.warn("Structure DFU cache lock is held by another process; using vanilla resources.");
                    return base;
                }

                cleanupLegacyArtifacts(cacheRoot);

                Path manifestFile = cacheRoot.resolve(CacheManifest.FILE_NAME);
                Path overlayDataDir = cacheRoot.resolve("overlay").resolve("data");
                Optional<CacheManifest.Header> existing = CacheManifest.readHeaderOnly(manifestFile);
                if (existing.isPresent() && existing.get().equals(expectedHeader) && Files.isDirectory(overlayDataDir)) {
                    long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
                    LOGGER.info(
                        "Structure DFU cache mounted in {} ms (fast-path, fingerprint={})",
                        elapsedMs,
                        shortFingerprint(expectedHeader.packFingerprint())
                    );
                    return new StructureOverlayResourceManager(base, overlayDataDir);
                }

                StructureCacheBuilder.BuildSummary summary =
                    new StructureCacheBuilder(dataFixer, cacheRoot).build(base, expectedHeader);
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
                return new StructureOverlayResourceManager(base, overlayDataDir);
            }
        } catch (IOException | RuntimeException exception) {
            LOGGER.error("Structure DFU cache disabled; falling back to vanilla resources: {}", exception.getMessage());
            return base;
        }
    }

    private static ResourceManager unwrap(ResourceManager manager) {
        return manager instanceof StructureOverlayResourceManager overlay ? overlay.delegate() : manager;
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
