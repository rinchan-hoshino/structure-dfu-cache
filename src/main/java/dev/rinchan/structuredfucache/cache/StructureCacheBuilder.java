package dev.rinchan.structuredfucache.cache;

import com.mojang.datafixers.DataFixer;
import com.mojang.logging.LogUtils;
import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.zip.Deflater;
import java.util.zip.GZIPOutputStream;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.ListTag;
import dev.rinchan.rinlib.minecraft.StructureResources;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.util.datafix.DataFixTypes;
import org.slf4j.Logger;

public final class StructureCacheBuilder {
    static final long MAX_DECODED_NBT_BYTES = 256L * 1024L * 1024L;
    static final int MAX_COMPRESSED_RESOURCE_BYTES = 64 * 1024 * 1024;

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int MISSING_DATA_VERSION = 500;

    private final DataFixer dataFixer;
    private final Path cacheRoot;
    private final int workerThreads;

    public StructureCacheBuilder(DataFixer dataFixer, Path cacheRoot, int workerThreads) {
        if (dataFixer == null) {
            throw new IllegalArgumentException("dataFixer must not be null");
        }
        if (cacheRoot == null) {
            throw new IllegalArgumentException("cacheRoot must not be null");
        }
        if (workerThreads < 1) {
            throw new IllegalArgumentException("workerThreads must be positive");
        }
        this.dataFixer = dataFixer;
        this.cacheRoot = cacheRoot.toAbsolutePath().normalize();
        this.workerThreads = workerThreads;
    }

    public CacheSnapshot build(Map<String, Resource> resources, CacheIdentity identity) {
        long startedNanos = System.nanoTime();
        Map<String, Resource> listed = new TreeMap<>(resources);
        Map<String, CacheEntry> previousEntries = readPreviousIndex(identity).map(CacheIndex::entries).orElse(Map.of());
        List<ResourceResult> results = processResources(listed, previousEntries, identity);

        Map<String, CacheEntry> nextEntries = new TreeMap<>();
        Map<String, Path> preparedResources = new HashMap<>();
        int converted = 0;
        int passthrough = 0;
        int cacheHits = 0;
        int vanillaFallbacks = 0;
        for (ResourceResult result : results) {
            PreparedBlob blob = result.blob();
            if (blob == null) {
                vanillaFallbacks++;
                continue;
            }
            nextEntries.put(result.location().toString(), blob.entry());
            preparedResources.put(result.location(), blob.path());
            if (result.cacheHit()) {
                cacheHits++;
            } else if (blob.entry().converted()) {
                converted++;
            } else {
                passthrough++;
            }
        }

        if (vanillaFallbacks == 0) {
            commitIndexAndPrune(new CacheIndex(identity, nextEntries), Set.copyOf(preparedResources.values()));
        }
        ResourceResult slowest = results.stream().max(Comparator.comparingLong(ResourceResult::elapsedNanos)).orElse(null);
        CacheBuildStats stats = new CacheBuildStats(
            listed.size(),
            converted,
            passthrough,
            cacheHits,
            vanillaFallbacks,
            TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos),
            slowest == null ? "none" : slowest.location().toString()
        );
        return new CacheSnapshot(identity, preparedResources, stats);
    }

    private List<ResourceResult> processResources(
        Map<String, Resource> listed,
        Map<String, CacheEntry> previousEntries,
        CacheIdentity identity
    ) {
        var preparations = new ConcurrentHashMap<CacheKey, FutureTask<PreparedBlob>>();
        var sequence = new java.util.concurrent.atomic.AtomicInteger();
        ExecutorService workers = Executors.newFixedThreadPool(workerThreads, task -> {
            Thread thread = new Thread(task, "structure-dfu-cache-build-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
        try {
            CompletionService<ResourceResult> completion = new ExecutorCompletionService<>(workers);
            try {
                for (Map.Entry<String, Resource> resource : listed.entrySet()) {
                    completion.submit(() -> processResource(resource.getKey(), resource.getValue(), previousEntries, identity, preparations));
                }
                List<ResourceResult> results = new ArrayList<>(listed.size());
                for (int index = 0; index < listed.size(); index++) {
                    results.add(completion.take().get());
                }
                return results;
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new CacheBuildException("Structure DFU cache build was interrupted", exception);
            } catch (ExecutionException exception) {
                if (exception.getCause() instanceof Error error) {
                    throw error;
                }
                throw new CacheBuildException("Structure DFU cache worker failed unexpectedly", exception.getCause());
            }
        } finally {
            workers.shutdownNow();
            boolean interrupted = false;
            while (!workers.isTerminated()) {
                try {
                    workers.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
                } catch (InterruptedException exception) {
                    interrupted = true;
                }
            }
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    private ResourceResult processResource(
        String location,
        Resource resource,
        Map<String, CacheEntry> previousEntries,
        CacheIdentity identity,
        ConcurrentHashMap<CacheKey, FutureTask<PreparedBlob>> preparations
    ) {
        long startedNanos = System.nanoTime();
        byte[] sourceBytes;
        try {
            sourceBytes = readBounded(resource);
        } catch (IOException | RuntimeException exception) {
            LOGGER.warn("Cannot capture structure {}; the complete flattened overlay is disabled: {}", location, rootMessage(exception));
            return ResourceResult.vanillaFallback(location, elapsedSince(startedNanos));
        }

        CacheKey key = CacheKey.of(sourceBytes, identity);
        var candidate = new FutureTask<>(() -> prepareBytes(sourceBytes, key, previousEntries.get(location.toString()), location));
        var existing = preparations.putIfAbsent(key, candidate);
        var task = existing == null ? candidate : existing;
        task.run();
        try {
            PreparedBlob blob = task.get();
            return new ResourceResult(location, blob, elapsedSince(startedNanos), existing != null || blob.cacheHit());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new CacheBuildException("Structure preparation interrupted: " + location, exception);
        } catch (ExecutionException exception) {
            if (exception.getCause() instanceof Error error) {
                throw error;
            }
            LOGGER.warn("Cannot prepare structure {}; the complete flattened overlay is disabled: {}", location, rootMessage(exception));
            return ResourceResult.vanillaFallback(location, elapsedSince(startedNanos));
        }
    }

    private PreparedBlob prepareBytes(byte[] sourceBytes, CacheKey key, CacheEntry previous, String location) throws IOException {
        if (previous != null && previous.digest().equals(key.digest())) {
            String expectedPath = previous.converted() ? key.convertedBlobRelativePath() : key.sourceBlobRelativePath();
            Path blob = cacheRoot.resolve(expectedPath);
            if (previous.blobPath().equals(expectedPath) && Files.isRegularFile(blob, LinkOption.NOFOLLOW_LINKS)
                && previous.blobDigest().equals(CacheKey.sha256(blob))) {
                return new PreparedBlob(previous, blob, true);
            }
        }
        // No valid index means existing files are untrusted and must be replaced.
        CompoundTag converted = null;
        try {
            CompoundTag original = readCompressed(sourceBytes, MAX_DECODED_NBT_BYTES);
            int sourceVersion = NbtUtils.getDataVersion(original, MISSING_DATA_VERSION);
            if (isStructureTemplate(original) && sourceVersion < key.identity().targetDataVersion()) {
                converted = DataFixTypes.STRUCTURE.update(dataFixer, original, sourceVersion, key.identity().targetDataVersion());
                NbtUtils.addCurrentDataVersion(converted);
                if (NbtUtils.getDataVersion(converted, MISSING_DATA_VERSION) != key.identity().targetDataVersion()) {
                    throw new CacheBuildException("official DFU did not produce the requested DataVersion");
                }
            }
        } catch (IOException | RuntimeException exception) {
            converted = null;
            LOGGER.warn("Cannot optimize structure {}; preserving its exact vanilla bytes: {}", location, rootMessage(exception));
        }
        // Disk failure must disable this generation, not masquerade as a DFU failure.
        boolean upgraded = converted != null;
        String relativePath = upgraded ? key.convertedBlobRelativePath() : key.sourceBlobRelativePath();
        Path blob = cacheRoot.resolve(relativePath);
        String digest = upgraded ? writeTag(blob, converted) : CacheFiles.write(blob, output -> output.write(sourceBytes));
        return new PreparedBlob(new CacheEntry(key.digest(), digest, relativePath, upgraded), blob, false);
    }

    private Optional<CacheIndex> readPreviousIndex(CacheIdentity identity) {
        try {
            return CacheIndexStore.read(cacheRoot.resolve("index.json")).filter(index -> index.identity().equals(identity));
        } catch (IOException | RuntimeException exception) {
            LOGGER.warn("Ignoring unreadable structure DFU cache index: {}", rootMessage(exception));
            return Optional.empty();
        }
    }

    private void commitIndexAndPrune(CacheIndex index, Set<Path> retained) {
        try {
            CacheIndexStore.writeAtomic(cacheRoot.resolve("index.json"), index);
        } catch (IOException exception) {
            throw new CacheBuildException("Cannot commit structure DFU cache index", exception);
        }
        pruneTree(cacheRoot.resolve("blobs"), retained);
        pruneTree(cacheRoot.resolve("sources"), retained);
    }

    private void pruneTree(Path root, Set<Path> retained) {
        if (!Files.isDirectory(root)) {
            return;
        }
        long removed = 0L;
        try (var paths = Files.walk(root)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                if (!retained.contains(path.normalize()) && Files.deleteIfExists(path)) {
                    removed++;
                }
            }
        } catch (IOException | UncheckedIOException exception) {
            LOGGER.warn("Unable to fully prune unreferenced structure DFU cache blobs: {}", rootMessage(exception));
        }
        if (removed > 0L) {
            LOGGER.info("Pruned {} unreferenced structure DFU cache blobs under {}", removed, root.getFileName());
        }
    }

    static byte[] readBounded(Resource resource) throws IOException {
        try (InputStream input = resource.open()) {
            return readBounded(input, MAX_COMPRESSED_RESOURCE_BYTES);
        }
    }

    static byte[] readBounded(InputStream input, int maximumBytes) throws IOException {
        if (maximumBytes < 1) {
            throw new IllegalArgumentException("maximumBytes must be positive");
        }
        byte[] bytes = input.readNBytes(maximumBytes);
        if (input.read() != -1) {
            throw new IOException("compressed structure exceeds " + maximumBytes + " bytes");
        }
        return bytes;
    }

    static CompoundTag readCompressed(byte[] bytes, long maxHeapBytes) throws IOException {
        if (maxHeapBytes < 1L) {
            throw new IllegalArgumentException("maxHeapBytes must be positive");
        }
        return StructureResources.readCompressed(bytes, maxHeapBytes);
    }

    private static boolean isStructureTemplate(CompoundTag tag) {
        return tag.get("size") instanceof ListTag
            && tag.get("blocks") instanceof ListTag
            && (tag.get("palette") instanceof ListTag || tag.get("palettes") instanceof ListTag);
    }

    private static String writeTag(Path blob, CompoundTag tag) throws IOException {
        return CacheFiles.write(blob, output -> {
            try (
                BufferedOutputStream buffered = new BufferedOutputStream(output);
                GZIPOutputStream gzip = new FastGzipOutputStream(buffered);
                DataOutputStream data = new DataOutputStream(gzip)
            ) {
                NbtIo.write(tag, data);
            }
        });
    }

    private static long elapsedSince(long startedNanos) {
        return System.nanoTime() - startedNanos;
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return current.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }

    private static final class FastGzipOutputStream extends GZIPOutputStream {
        private FastGzipOutputStream(OutputStream output) throws IOException {
            super(output);
            this.def.setLevel(Deflater.BEST_SPEED);
        }
    }

    private record PreparedBlob(CacheEntry entry, Path path, boolean cacheHit) {
    }

    private record ResourceResult(String location, PreparedBlob blob, long elapsedNanos, boolean cacheHit) {
        static ResourceResult vanillaFallback(String location, long elapsedNanos) {
            return new ResourceResult(location, null, elapsedNanos, false);
        }
    }
}
