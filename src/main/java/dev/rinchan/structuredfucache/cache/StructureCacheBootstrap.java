package dev.rinchan.structuredfucache.cache;

import com.mojang.datafixers.DataFixer;
import com.mojang.logging.LogUtils;
import dev.rinchan.structuredfucache.StructureDfuCache;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.SharedConstants;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.PathPackResources;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.resources.MultiPackResourceManager;
import net.minecraft.server.packs.resources.ResourceManager;
import net.neoforged.fml.loading.FMLLoader;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;

public final class StructureCacheBootstrap {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final long RESERVED_HEAP_BYTES = 2L * 1024L * 1024L * 1024L;
    private static final long BYTES_PER_CONVERSION_WORKER = 1024L * 1024L * 1024L;
    private static final int MAX_WORKERS = 4;
    private static final PackLocationInfo OVERLAY_INFO = new PackLocationInfo(
        StructureDfuCache.MOD_ID + "_generated",
        Component.literal("Structure DFU Cache generated overlay"),
        PackSource.BUILT_IN,
        Optional.empty()
    );

    private StructureCacheBootstrap() {
    }

    public static PreparedStructureResources prepare(ResourceManager original, DataFixer dataFixer) {
        return prepare(original, dataFixer, cacheRoot(), identity(), adaptiveWorkerThreads());
    }

    static PreparedStructureResources prepare(
        ResourceManager original,
        DataFixer dataFixer,
        Path cacheRoot,
        CacheIdentity identity,
        int workers
    ) {
        Path generation = null;
        try {
            CacheSnapshot snapshot = new StructureCacheBuilder(dataFixer, cacheRoot, workers).build(original, identity);
            CacheBuildStats stats = snapshot.stats();
            if (stats.totalResources() == 0 || stats.vanillaFallbacks() > 0
                || snapshot.preparedResources().size() != stats.totalResources()) {
                logReady(stats, workers, "vanilla resources");
                return new PreparedStructureResources(original, null);
            }

            generation = materializeOverlay(cacheRoot, snapshot);
            PackResources preparedPack = new PathPackResources(OVERLAY_INFO, generation);
            ResourceManager prepared = new MultiPackResourceManager(PackType.SERVER_DATA, List.of(preparedPack));
            logReady(stats, workers, generation.toString());
            return new PreparedStructureResources(prepared, generation);
        } catch (RuntimeException exception) {
            if (generation != null) {
                deleteTree(generation);
            }
            LOGGER.error(
                "Structure DFU cache disabled for this resource generation; vanilla structure loading remains active: {}",
                rootMessage(exception)
            );
            return new PreparedStructureResources(original, null);
        }
    }

    public static void retireGeneration(Path generation) {
        if (generation != null) {
            deleteTree(generation);
        }
    }

    static int adaptiveWorkerThreads() {
        return workerThreadsFor(Runtime.getRuntime().maxMemory(), Runtime.getRuntime().availableProcessors());
    }

    static int workerThreadsFor(long maximumHeapBytes, int availableProcessors) {
        if (maximumHeapBytes < 1L || availableProcessors < 1) {
            throw new IllegalArgumentException("heap and processor counts must be positive");
        }
        int cpuBudget = Math.max(1, availableProcessors - 1);
        long conversionHeap = Math.max(0L, maximumHeapBytes - RESERVED_HEAP_BYTES);
        int heapBudget = (int)Math.max(1L, conversionHeap / BYTES_PER_CONVERSION_WORKER);
        return Math.min(MAX_WORKERS, Math.min(cpuBudget, heapBudget));
    }

    static Path materializeOverlay(Path cacheRoot, CacheSnapshot snapshot) {
        Path generations = cacheRoot.resolve("generations");
        Path temporary = generations.resolve(".tmp-" + UUID.randomUUID());
        Path completed = generations.resolve(UUID.randomUUID().toString());
        try {
            Files.createDirectories(temporary.resolve("data"));
            for (var entry : snapshot.preparedResources().entrySet()) {
                ResourceLocation location = entry.getKey();
                Path target = temporary.resolve("data")
                    .resolve(location.getNamespace())
                    .resolve(location.getPath())
                    .normalize();
                if (!target.startsWith(temporary)) {
                    throw new CacheBuildException("Generated resource escaped overlay root: " + location);
                }
                Files.createDirectories(target.getParent());
                linkOrCopy(entry.getValue(), target);
            }
            String metadata = "{\"pack\":{\"description\":\"Structure DFU Cache generated overlay\",\"pack_format\":"
                + SharedConstants.getCurrentVersion().getPackVersion(PackType.SERVER_DATA) + "}}\n";
            Files.writeString(temporary.resolve("pack.mcmeta"), metadata);
            try {
                Files.move(temporary, completed, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException exception) {
                Files.move(temporary, completed);
            }
            return completed;
        } catch (IOException | RuntimeException exception) {
            deleteTree(temporary);
            throw new CacheBuildException("Cannot materialize generated structure resource overlay", exception);
        }
    }

    private static void linkOrCopy(Path source, Path target) throws IOException {
        try {
            Files.createLink(target, source);
        } catch (IOException | UnsupportedOperationException exception) {
            Files.copy(source, target);
        }
    }

    private static CacheIdentity identity() {
        var version = FMLLoader.versionInfo();
        return new CacheIdentity(
            CacheIdentity.CURRENT_FORMAT,
            SharedConstants.getCurrentVersion().getDataVersion().getVersion(),
            version.mcVersion(),
            version.neoForgeVersion()
        );
    }

    private static Path cacheRoot() {
        return FMLPaths.GAMEDIR.get().resolve("cache").resolve(StructureDfuCache.MOD_ID);
    }

    private static void deleteTree(Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException exception) {
            LOGGER.warn("Unable to remove stale structure DFU overlay {}: {}", root, rootMessage(exception));
        }
    }

    private static void logReady(CacheBuildStats stats, int workers, String resourceLayer) {
        LOGGER.info(
            "Structure DFU cache prepared before gameplay: resources={}, converted={}, current={}, cacheHits={}, vanillaFallbacks={}, workers={}, elapsed={} ms, slowest={}, layer={}",
            stats.totalResources(),
            stats.converted(),
            stats.current(),
            stats.cacheHits(),
            stats.vanillaFallbacks(),
            workers,
            stats.elapsedMillis(),
            stats.slowestResource(),
            resourceLayer
        );
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return current.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }

    public record PreparedStructureResources(ResourceManager resourceManager, Path generationRoot) {
        public PreparedStructureResources {
            if (resourceManager == null) {
                throw new IllegalArgumentException("resourceManager must not be null");
            }
        }
    }
}
