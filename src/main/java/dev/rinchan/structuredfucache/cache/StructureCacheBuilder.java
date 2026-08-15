package dev.rinchan.structuredfucache.cache;

import com.mojang.datafixers.DataFixer;
import com.mojang.logging.LogUtils;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.Deflater;
import java.util.zip.GZIPOutputStream;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.datafix.DataFixTypes;
import org.slf4j.Logger;

public final class StructureCacheBuilder {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String STRUCTURE_PREFIX = "structure";
    private static final String STRUCTURE_SUFFIX = ".nbt";

    private final DataFixer dataFixer;
    private final Path cacheRoot;
    private final CachePolicy policy;
    private final int workerThreads;

    public StructureCacheBuilder(DataFixer dataFixer, Path cacheRoot, CachePolicy policy, int workerThreads) {
        this.dataFixer = dataFixer;
        this.cacheRoot = cacheRoot.toAbsolutePath().normalize();
        this.policy = policy;
        if (workerThreads < 1 || workerThreads > 16) {
            throw new IllegalArgumentException("workerThreads must be between 1 and 16");
        }
        this.workerThreads = workerThreads;
    }

    public CacheSnapshot build(ResourceManager resourceManager, int targetDataVersion) {
        long startedNanos = System.nanoTime();
        Map<ResourceLocation, Resource> listed = resourceManager.listResources(
            STRUCTURE_PREFIX,
            location -> location.getPath().endsWith(STRUCTURE_SUFFIX)
        );
        List<Map.Entry<ResourceLocation, Resource>> resources = listed.entrySet().stream()
            .sorted(Map.Entry.comparingByKey(Comparator.comparing(ResourceLocation::toString)))
            .toList();
        CacheIndex previous = readPreviousIndex(targetDataVersion).orElse(null);
        long deadlineNanos = policy.hasTimeout()
            ? startedNanos + policy.timeout().toNanos()
            : Long.MAX_VALUE;

        ExecutorService workers = Executors.newFixedThreadPool(workerThreads, daemonThreadFactory());
        CompletionService<ResourceResult> completion = new ExecutorCompletionService<>(workers);
        Semaphore conversionMemory = new Semaphore(workerThreads, true);
        List<Future<ResourceResult>> futures = new ArrayList<>(resources.size());
        for (Map.Entry<ResourceLocation, Resource> resource : resources) {
            futures.add(completion.submit(() -> process(resource, previous, targetDataVersion, deadlineNanos, conversionMemory)));
        }

        List<ResourceResult> results = new ArrayList<>(resources.size());
        boolean complete = false;
        try {
            for (int index = 0; index < resources.size(); index++) {
                Future<ResourceResult> future;
                if (policy.hasTimeout()) {
                    long remaining = deadlineNanos - System.nanoTime();
                    if (remaining <= 0L || (future = completion.poll(remaining, TimeUnit.NANOSECONDS)) == null) {
                        throw timeout(resources.size(), results);
                    }
                } else {
                    future = completion.take();
                }
                results.add(future.get());
                if (results.size() % 1000 == 0 || results.size() == resources.size()) {
                    LOGGER.info("Structure DFU cache progress: {}/{} resources", results.size(), resources.size());
                }
            }
            complete = true;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new CacheBuildException("Structure DFU cache build was interrupted", exception);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof CacheBuildException cacheBuildException) {
                throw cacheBuildException;
            }
            throw new CacheBuildException("Structure DFU cache build failed", cause);
        } finally {
            if (!complete) {
                futures.forEach(future -> future.cancel(true));
            }
            workers.shutdownNow();
        }

        return commit(results, resources.size(), targetDataVersion, startedNanos);
    }

    private ResourceResult process(
        Map.Entry<ResourceLocation, Resource> listedResource,
        CacheIndex previous,
        int targetDataVersion,
        long deadlineNanos,
        Semaphore conversionMemory
    ) {
        long started = System.nanoTime();
        ResourceLocation location = listedResource.getKey();
        checkDeadline(deadlineNanos, location);
        byte[] bytes;
        try {
            bytes = listedResource.getValue().open().readAllBytes();
        } catch (IOException exception) {
            throw new CacheBuildException("Cannot read resolved structure resource " + location, exception);
        }
        CacheKey key = CacheKey.of(bytes, targetDataVersion);
        CacheEntry previousEntry = previous == null ? null : previous.entries().get(location.toString());
        if (previousEntry != null && previousEntry.digest().equals(key.digestHex())) {
            if (!previousEntry.isConverted()) {
                return ResourceResult.current(location, key, elapsedSince(started), true);
            }
            Path previousBlob = resolveIndexedBlob(previousEntry.blobPath());
            if (Files.isRegularFile(previousBlob)) {
                return ResourceResult.converted(location, key, previousBlob, elapsedSince(started), true);
            }
        }

        Path blob = blobPath(key);
        if (Files.isRegularFile(blob)) {
            return ResourceResult.converted(location, key, blob, elapsedSince(started), true);
        }

        int conversionPermits = permitWeight(bytes.length, workerThreads);
        acquirePermits(conversionMemory, conversionPermits, deadlineNanos, location);
        try {
        CompoundTag original;
        try {
            original = NbtIo.readCompressed(new ByteArrayInputStream(bytes), NbtAccounter.unlimitedHeap());
        } catch (IOException | RuntimeException exception) {
            LOGGER.warn("Skipping unreadable non-template structure resource {}: {}", location, exception.toString());
            return ResourceResult.skipped(location, elapsedSince(started));
        }
        if (!isStructureTemplate(original)) {
            LOGGER.debug("Skipping non-template resource under structure path {}", location);
            return ResourceResult.skipped(location, elapsedSince(started));
        }

        int sourceDataVersion = NbtUtils.getDataVersion(original, 500);
        if (sourceDataVersion >= targetDataVersion) {
            return ResourceResult.current(location, key, elapsedSince(started), false);
        }

        checkDeadline(deadlineNanos, location);
        StructureFingerprint before = StructureFingerprint.capture(original);
        CompoundTag updated;
        try {
            updated = DataFixTypes.STRUCTURE.updateToCurrentVersion(dataFixer, original, sourceDataVersion);
        } catch (RuntimeException exception) {
            throw new CacheBuildException("Official DFU failed for structure " + location, exception);
        }
        NbtUtils.addCurrentDataVersion(updated);
        checkDeadline(deadlineNanos, location);
        StructureFingerprint after = StructureFingerprint.capture(updated);
        if (!before.equals(after)) {
            throw new CacheBuildException(
                "Official DFU changed structure geometry for " + location + ": " + before + " -> " + after
            );
        }
        int outputDataVersion = NbtUtils.getDataVersion(updated, -1);
        if (outputDataVersion != targetDataVersion) {
            throw new CacheBuildException(
                "Official DFU produced DataVersion " + outputDataVersion + " for " + location + ", expected " + targetDataVersion
            );
        }
        writeBlobAtomic(blob, updated);
        return ResourceResult.converted(location, key, blob, elapsedSince(started), false);
        } finally {
            conversionMemory.release(conversionPermits);
        }
    }

    private CacheSnapshot commit(List<ResourceResult> results, int total, int targetDataVersion, long startedNanos) {
        Map<String, CacheEntry> indexEntries = new HashMap<>();
        Map<ResourceLocation, Path> convertedResources = new HashMap<>();
        int converted = 0;
        int current = 0;
        int hits = 0;
        ResourceResult slowest = null;
        for (ResourceResult result : results) {
            if (slowest == null || result.elapsedNanos() > slowest.elapsedNanos()) {
                slowest = result;
            }
            if (result.skipped()) {
                continue;
            }
            if (result.cacheHit()) {
                hits++;
            } else if (result.blob() == null) {
                current++;
            } else {
                converted++;
            }
            String blobPath = result.blob() == null ? "" : cacheRoot.relativize(result.blob()).toString().replace('\\', '/');
            indexEntries.put(result.location().toString(), new CacheEntry(result.key().digestHex(), blobPath));
            if (result.blob() != null) {
                convertedResources.put(result.location(), result.blob());
            }
        }

        CacheIndex index = new CacheIndex(CacheIndex.CURRENT_FORMAT, targetDataVersion, indexEntries);
        try {
            CacheIndexStore.writeAtomic(cacheRoot.resolve("index.json"), index);
        } catch (IOException exception) {
            throw new CacheBuildException("Cannot commit complete structure DFU cache index", exception);
        }
        pruneUnreferencedBlobs(index);
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
        CacheBuildStats stats = new CacheBuildStats(
            total,
            converted,
            current,
            hits,
            elapsedMillis,
            slowest == null ? "" : slowest.location().toString()
        );
        return new CacheSnapshot(targetDataVersion, convertedResources, stats);
    }

    private void pruneUnreferencedBlobs(CacheIndex index) {
        Path blobRoot = cacheRoot.resolve("blobs").normalize();
        if (!Files.isDirectory(blobRoot)) {
            return;
        }
        var referenced = index.entries().values().stream()
            .map(CacheEntry::blobPath)
            .filter(path -> !path.isEmpty())
            .map(this::resolveIndexedBlob)
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
        long removed = 0L;
        try (var paths = Files.walk(blobRoot)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                Path normalized = path.normalize();
                if (!referenced.contains(normalized)) {
                    Files.deleteIfExists(normalized);
                    removed++;
                }
            }
        } catch (IOException exception) {
            LOGGER.warn("Unable to fully prune unreferenced structure DFU cache blobs: {}", exception.toString());
            return;
        }
        if (removed > 0L) {
            LOGGER.info("Pruned {} unreferenced structure DFU cache blobs", removed);
        }
    }

    private Optional<CacheIndex> readPreviousIndex(int targetDataVersion) {
        try {
            return CacheIndexStore.read(cacheRoot.resolve("index.json"))
                .filter(index -> index.targetDataVersion() == targetDataVersion);
        } catch (IOException | RuntimeException exception) {
            LOGGER.warn("Ignoring unreadable structure DFU cache index: {}", exception.toString());
            return Optional.empty();
        }
    }

    private Path blobPath(CacheKey key) {
        return cacheRoot.resolve("blobs").resolve(key.blobRelativePath()).normalize();
    }

    private Path resolveIndexedBlob(String blobPath) {
        Path resolved = cacheRoot.resolve(blobPath).normalize();
        if (!resolved.startsWith(cacheRoot)) {
            throw new CacheBuildException("Cache index escaped cache root: " + blobPath);
        }
        return resolved;
    }

    private static boolean isStructureTemplate(CompoundTag tag) {
        return tag.contains("size", Tag.TAG_LIST)
            && tag.contains("blocks", Tag.TAG_LIST)
            && tag.contains("entities", Tag.TAG_LIST)
            && (tag.contains("palette", Tag.TAG_LIST) || tag.contains("palettes", Tag.TAG_LIST));
    }

    private static void writeBlobAtomic(Path blob, CompoundTag tag) {
        try {
            Files.createDirectories(blob.getParent());
            Path temporary = blob.resolveSibling(blob.getFileName() + ".tmp-" + Thread.currentThread().threadId());
            Files.deleteIfExists(temporary);
            try {
                try (
                    OutputStream output = Files.newOutputStream(temporary, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                    BufferedOutputStream buffered = new BufferedOutputStream(output);
                    GZIPOutputStream gzip = new FastGzipOutputStream(buffered);
                    DataOutputStream data = new DataOutputStream(gzip)
                ) {
                    NbtIo.write(tag, data);
                }
                try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                    channel.force(true);
                }
                try {
                    Files.move(temporary, blob, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException exception) {
                    Files.move(temporary, blob);
                } catch (java.nio.file.FileAlreadyExistsException exception) {
                    // Another worker or reload produced the same content-addressed blob.
                }
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (IOException exception) {
            throw new CacheBuildException("Cannot write converted structure cache blob " + blob, exception);
        }
    }

    private static final class FastGzipOutputStream extends GZIPOutputStream {
        private FastGzipOutputStream(OutputStream output) throws IOException {
            super(output);
            this.def.setLevel(Deflater.BEST_SPEED);
        }
    }

    static int permitWeight(int compressedBytes, int availablePermits) {
        int requested;
        if (compressedBytes <= 64 * 1024) {
            requested = 1;
        } else if (compressedBytes <= 256 * 1024) {
            requested = 2;
        } else if (compressedBytes <= 1024 * 1024) {
            requested = 4;
        } else {
            requested = availablePermits;
        }
        return Math.min(requested, availablePermits);
    }

    private void acquirePermits(
        Semaphore conversionMemory,
        int permits,
        long deadlineNanos,
        ResourceLocation location
    ) {
        try {
            if (policy.hasTimeout()) {
                long remaining = deadlineNanos - System.nanoTime();
                if (remaining <= 0L || !conversionMemory.tryAcquire(permits, remaining, TimeUnit.NANOSECONDS)) {
                    throw new CacheBuildException("Structure DFU cache deadline reached while waiting for memory budget at " + location);
                }
            } else {
                conversionMemory.acquire(permits);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new CacheBuildException("Structure DFU cache build was interrupted while waiting for " + location, exception);
        }
    }

    private CacheBuildException timeout(int total, List<ResourceResult> completed) {
        ResourceResult slowest = completed.stream().max(Comparator.comparingLong(ResourceResult::elapsedNanos)).orElse(null);
        String slowestName = slowest == null ? "none" : slowest.location().toString();
        return new CacheBuildException(
            "Structure DFU cold cache exceeded " + policy.timeout().toSeconds() + " seconds after " + completed.size() + "/" + total
                + " resources; slowest completed resource=" + slowestName
        );
    }

    private static void checkDeadline(long deadlineNanos, ResourceLocation location) {
        if (Thread.currentThread().isInterrupted() || System.nanoTime() > deadlineNanos) {
            throw new CacheBuildException("Structure DFU cache deadline reached while processing " + location);
        }
    }

    private static long elapsedSince(long startedNanos) {
        return System.nanoTime() - startedNanos;
    }

    private static ThreadFactory daemonThreadFactory() {
        AtomicInteger sequence = new AtomicInteger();
        return task -> {
            Thread thread = new Thread(task, "structure-dfu-cache-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    private record ResourceResult(
        ResourceLocation location,
        CacheKey key,
        Path blob,
        long elapsedNanos,
        boolean cacheHit,
        boolean skipped
    ) {
        static ResourceResult converted(ResourceLocation location, CacheKey key, Path blob, long elapsedNanos, boolean cacheHit) {
            return new ResourceResult(location, key, blob, elapsedNanos, cacheHit, false);
        }

        static ResourceResult current(ResourceLocation location, CacheKey key, long elapsedNanos, boolean cacheHit) {
            return new ResourceResult(location, key, null, elapsedNanos, cacheHit, false);
        }

        static ResourceResult skipped(ResourceLocation location, long elapsedNanos) {
            return new ResourceResult(location, null, null, elapsedNanos, false, true);
        }
    }
}
