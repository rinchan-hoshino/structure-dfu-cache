package dev.rinchan.structuredfucache.cache;

import com.mojang.datafixers.DataFixer;
import com.mojang.logging.LogUtils;
import dev.rinchan.structuredfucache.StructureDfuCache;
import dev.rinchan.structuredfucache.CacheConfig;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.resources.Resource;
import java.util.UUID;
import dev.rinchan.rinlib.minecraft.StructureLifecycle;
import dev.rinchan.rinlib.minecraft.StructureResources;
import net.minecraft.server.packs.resources.MultiPackResourceManager;
import net.minecraft.server.packs.resources.ResourceManager;
import org.slf4j.Logger;

public final class StructureCacheBootstrap implements StructureLifecycle.Preparation {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final long RESERVED_HEAP_BYTES = 2L * 1024L * 1024L * 1024L;
    private static final int MAX_WORKERS = 4;
    private final Path cacheRoot;
    private final CacheIdentity identity;
    private final CacheConfig config;
    private volatile PreparedStructureResources current;
    private PreparedStructureResources previous;

    public StructureCacheBootstrap(Path cacheRoot, String loaderVersion) {
        this(cacheRoot, loaderVersion, CacheConfig.defaults());
    }

    public StructureCacheBootstrap(Path cacheRoot, String loaderVersion, CacheConfig config) {
        this.cacheRoot = cacheRoot;
        this.config = config;
        this.identity = new CacheIdentity(CacheIdentity.CURRENT_FORMAT, StructureResources.dataVersion(),
            StructureResources.minecraftVersion(), loaderVersion);
    }

    @Override
    public ResourceManager prepare(ResourceManager original, DataFixer dataFixer) {
        var runtime = Runtime.getRuntime();
        long availableHeap = runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory());
        var prepared = prepare(original, dataFixer, cacheRoot, identity,
            workerThreadsFor(availableHeap, runtime.availableProcessors(), config), config);
        if (previous != null) previous.close();
        previous = current;
        current = prepared;
        return prepared.resourceManager();
    }

    @Override
    public Component operatorNotice() {
        var prepared = current;
        return prepared == null ? null : prepared.operatorNotice();
    }

    @Override
    public void close() {
        if (previous != null) previous.close();
        if (current != null) current.close();
        previous = null;
        current = null;
    }

    static PreparedStructureResources prepare(
        ResourceManager original,
        DataFixer dataFixer,
        Path cacheRoot,
        CacheIdentity identity,
        int workers
    ) {
        return prepare(original, dataFixer, cacheRoot, identity, workers, CacheConfig.defaults());
    }

    static PreparedStructureResources prepare(ResourceManager original, DataFixer dataFixer,
        Path cacheRoot, CacheIdentity identity, int workers, CacheConfig config) {
        Path generation = null;
        try {
            Files.createDirectories(cacheRoot);
            // Keep index replacement, pruning and materialization in one exclusive maintenance window.
            try (var channel = FileChannel.open(cacheRoot.resolve("prepare.lock"),
                     StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 var lock = channel.tryLock()) {
                if (lock == null) {
                    throw new CacheBuildException("Cache is being prepared by another process");
                }
                var resolved = StructureResources.list(original);
                CacheSnapshot snapshot = new StructureCacheBuilder(dataFixer, cacheRoot, workers, config).build(resolved, identity);
                CacheBuildStats stats = snapshot.stats();
                if (stats.totalResources() == 0) {
                    logReady(stats, workers, 0, "vanilla resources");
                    return new PreparedStructureResources(original, null, null);
                }
                var delegated = new HashMap<String, Resource>();
                resolved.forEach((key, resource) -> {
                    if (!snapshot.preparedResources().containsKey(key)) delegated.put(key, resource);
                });
                generation = materializeOverlay(cacheRoot, snapshot, resolved, delegated);
                ResourceManager prepared = StructureResources.overlay(generation, StructureDfuCache.MOD_ID + "_generated", delegated);
                logReady(stats, workers, delegated.size(), generation.toString());
                return new PreparedStructureResources(prepared, generation, notice(stats, delegated.size()));
            }
        } catch (IOException | RuntimeException exception) {
            deleteTree(generation);
            LOGGER.error(
                "Structure DFU cache disabled for this resource generation; vanilla structure loading remains active: {}",
                rootMessage(exception)
            );
            return new PreparedStructureResources(original, null, Component.literal(
                "[Structure DFU Cache] Cache unavailable; all structures use vanilla loading. See server console: " + rootMessage(exception)));
        }
    }

    static int workerThreadsFor(long maximumHeapBytes, int availableProcessors) {
        return workerThreadsFor(maximumHeapBytes, availableProcessors, CacheConfig.defaults());
    }

    static int workerThreadsFor(long maximumHeapBytes, int availableProcessors, CacheConfig config) {
        if (maximumHeapBytes < 1L || availableProcessors < 1) {
            throw new IllegalArgumentException("heap and processor counts must be positive");
        }
        int cpuBudget = Math.max(1, availableProcessors - 1);
        long conversionHeap = Math.max(0L, maximumHeapBytes - RESERVED_HEAP_BYTES);
        long bytesPerWorker = Math.multiplyExact(config.maxDecodedNbtBytes(), 4L);
        int heapBudget = (int)Math.min(MAX_WORKERS, Math.max(1L, conversionHeap / bytesPerWorker));
        return Math.min(MAX_WORKERS, Math.min(cpuBudget, heapBudget));
    }

    static Path materializeOverlay(Path cacheRoot, CacheSnapshot snapshot) {
        return materializeOverlay(cacheRoot, snapshot, Map.of(), new HashMap<>());
    }

    static Path materializeOverlay(Path cacheRoot, CacheSnapshot snapshot,
        Map<String, Resource> resolved,
        Map<String, Resource> delegated) {
        Path generations = cacheRoot.resolve("generations");
        Path temporary = generations.resolve(".tmp-" + UUID.randomUUID());
        Path completed = generations.resolve(UUID.randomUUID().toString());
        try {
            Files.createDirectories(temporary.resolve("data"));
            for (var entry : snapshot.preparedResources().entrySet()) {
                Path target = StructureResources.resourcePath(temporary, entry.getKey());
                try {
                    Files.createDirectories(target.getParent());
                    linkOrCopy(entry.getValue(), target);
                } catch (IOException | RuntimeException exception) {
                    var resource = resolved.get(entry.getKey());
                    if (resource == null) throw exception;
                    try {
                        Files.deleteIfExists(target);
                    } catch (IOException cleanupFailure) {
                        exception.addSuppressed(cleanupFailure);
                    }
                    delegated.put(entry.getKey(), resource);
                    LOGGER.warn("Cannot materialize cached structure {}; this structure will use vanilla loading: {}",
                        entry.getKey(), rootMessage(exception));
                }
            }
            Files.writeString(temporary.resolve("pack.mcmeta"), StructureResources.packMetadata());
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

    private static void deleteTree(Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException | UncheckedIOException exception) {
            LOGGER.warn("Unable to remove stale structure DFU overlay {}: {}", root, rootMessage(exception));
        }
    }

    private static void logReady(CacheBuildStats stats, int workers, int fallbacks, String resourceLayer) {
        var event = stats.hasProblems() || fallbacks > 0 ? LOGGER.atWarn() : LOGGER.atInfo();
        event.log(
            "Structure DFU cache prepared before gameplay: resources={}, converted={}, passthrough={}, cacheHits={}, vanillaFallbacks={}, repaired={}, unoptimized={}, index={}, workers={}, elapsed={} ms, slowest={}, layer={}",
            stats.totalResources(), stats.converted(), stats.passthrough(), stats.cacheHits(), fallbacks,
            stats.repairedResources(), stats.unoptimizedResources(), stats.indexState().description(),
            workers, stats.elapsedMillis(), stats.slowestResource(), resourceLayer);
    }

    private static Component notice(CacheBuildStats stats, int fallbacks) {
        if (!stats.hasProblems() && fallbacks == 0) return null;
        String index = stats.indexState() == CacheBuildStats.IndexState.RECOVERED ? "A damaged cache index was rebuilt. " : "";
        return Component.literal("[Structure DFU Cache] " + index
            + "Cache repairs: " + stats.repairedResources() + "; vanilla fallback: " + fallbacks
            + "; unoptimized resources: " + stats.unoptimizedResources() + ". See server console for details.");
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return current.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }

    public record PreparedStructureResources(ResourceManager resourceManager, Path generationRoot, Component operatorNotice) implements AutoCloseable {
        public PreparedStructureResources {
            if (resourceManager == null) {
                throw new IllegalArgumentException("resourceManager must not be null");
            }
        }

        @Override public void close() {
            if (generationRoot != null) {
                ((MultiPackResourceManager) resourceManager).close();
                deleteTree(generationRoot);
            }
        }
    }
}
