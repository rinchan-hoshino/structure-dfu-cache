package dev.rinchan.structuredfucache.cache;

import com.mojang.datafixers.DataFixer;
import com.mojang.logging.LogUtils;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
    static final long MAX_DECODED_NBT_BYTES = 256L * 1024L * 1024L;
    static final int MAX_COMPRESSED_RESOURCE_BYTES = 64 * 1024 * 1024;

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int MISSING_DATA_VERSION = 500;

    private final DataFixer dataFixer;
    private final Path cacheRoot;
    private final int workerThreads;
    private final ConcurrentHashMap<CacheKey, CompletableFuture<PreparationOutcome>> preparations = new ConcurrentHashMap<>();

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
        this.cacheRoot = cacheRoot;
        this.workerThreads = workerThreads;
    }

    public CacheSnapshot build(ResourceManager resourceManager, CacheIdentity identity) {
        preparations.clear();
        long startedNanos = System.nanoTime();
        Map<ResourceLocation, Resource> listed = new TreeMap<>(
            resourceManager.listResources("structure", location -> location.getPath().endsWith(".nbt"))
        );
        Map<String, CacheEntry> previousEntries = readPreviousIndex(identity).map(CacheIndex::entries).orElse(Map.of());
        List<ResourceResult> results = processResources(listed, previousEntries, identity);

        Map<String, CacheEntry> nextEntries = new TreeMap<>();
        Map<ResourceLocation, Path> preparedResources = new HashMap<>();
        int converted = 0;
        int current = 0;
        int cacheHits = 0;
        int vanillaFallbacks = 0;
        for (ResourceResult result : results) {
            if (result.kind() == ResultKind.VANILLA_FALLBACK) {
                vanillaFallbacks++;
                continue;
            }
            nextEntries.put(
                result.location().toString(),
                new CacheEntry(
                    result.key().digest(),
                    cacheRoot.relativize(result.preparedBlob()).toString().replace('\\', '/'),
                    result.kind() == ResultKind.CONVERTED
                )
            );
            preparedResources.put(result.location(), result.preparedBlob());
            if (result.cacheHit()) {
                cacheHits++;
            } else if (result.kind() == ResultKind.CONVERTED) {
                converted++;
            } else {
                current++;
            }
        }

        if (vanillaFallbacks == 0) {
            commitIndexAndPrune(new CacheIndex(identity, nextEntries));
        }
        ResourceResult slowest = results.stream().max(Comparator.comparingLong(ResourceResult::elapsedNanos)).orElse(null);
        CacheBuildStats stats = new CacheBuildStats(
            listed.size(),
            converted,
            current,
            cacheHits,
            vanillaFallbacks,
            TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos),
            slowest == null ? "none" : slowest.location().toString()
        );
        return new CacheSnapshot(identity, preparedResources, stats);
    }

    private List<ResourceResult> processResources(
        Map<ResourceLocation, Resource> listed,
        Map<String, CacheEntry> previousEntries,
        CacheIdentity identity
    ) {
        ExecutorService workers = Executors.newFixedThreadPool(workerThreads, daemonThreadFactory());
        CompletionService<ResourceResult> completion = new ExecutorCompletionService<>(workers);
        try {
            for (Map.Entry<ResourceLocation, Resource> resource : listed.entrySet()) {
                completion.submit(() -> processResource(resource.getKey(), resource.getValue(), previousEntries, identity));
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
            throw new CacheBuildException("Structure DFU cache worker failed unexpectedly", exception.getCause());
        } finally {
            workers.shutdownNow();
            try {
                if (!workers.awaitTermination(30L, TimeUnit.SECONDS)) {
                    LOGGER.error("Structure DFU cache workers did not terminate after cancellation");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private ResourceResult processResource(
        ResourceLocation location,
        Resource resource,
        Map<String, CacheEntry> previousEntries,
        CacheIdentity identity
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
        ResourceResult warm = warmResult(location, key, previousEntries.get(location.toString()), startedNanos);
        if (warm != null) {
            return warm;
        }

        CompletableFuture<PreparationOutcome> candidate = new CompletableFuture<>();
        CompletableFuture<PreparationOutcome> existing = preparations.putIfAbsent(key, candidate);
        if (existing != null) {
            try {
                return resultFromOutcome(location, key, existing.join(), startedNanos, true);
            } catch (CompletionException exception) {
                LOGGER.warn("Cannot prepare duplicate structure {}: {}", location, rootMessage(exception));
                return ResourceResult.vanillaFallback(location, elapsedSince(startedNanos));
            }
        }

        try {
            PreparationOutcome outcome = prepareBytes(sourceBytes, key, location);
            candidate.complete(outcome);
            return resultFromOutcome(location, key, outcome, startedNanos, false);
        } catch (RuntimeException exception) {
            candidate.completeExceptionally(exception);
            LOGGER.warn("Cannot prepare structure {}; the complete flattened overlay is disabled: {}", location, rootMessage(exception));
            return ResourceResult.vanillaFallback(location, elapsedSince(startedNanos));
        }
    }

    private ResourceResult warmResult(
        ResourceLocation location,
        CacheKey key,
        CacheEntry previous,
        long startedNanos
    ) {
        if (previous == null || !previous.digest().equals(key.digest())) {
            return null;
        }
        Path indexedBlob = resolveIndexedBlob(previous.blobPath());
        if (!Files.isRegularFile(indexedBlob)) {
            return null;
        }
        return ResourceResult.prepared(
            location,
            key,
            indexedBlob,
            elapsedSince(startedNanos),
            previous.converted() ? ResultKind.CONVERTED : ResultKind.PASSTHROUGH,
            true
        );
    }

    private PreparationOutcome prepareBytes(byte[] sourceBytes, CacheKey key, ResourceLocation location) {
        Path sourceBlob = cacheRoot.resolve(key.sourceBlobRelativePath()).normalize();
        ensureRawBlob(sourceBlob, sourceBytes);

        CompoundTag original;
        try {
            original = readCompressed(sourceBytes, MAX_DECODED_NBT_BYTES);
        } catch (IOException | RuntimeException exception) {
            LOGGER.warn("Structure {} is not optimizable NBT; preserving its exact vanilla bytes", location);
            return PreparationOutcome.passthrough(sourceBlob);
        }
        if (!isStructureTemplate(original)) {
            return PreparationOutcome.passthrough(sourceBlob);
        }

        int sourceVersion = NbtUtils.getDataVersion(original, MISSING_DATA_VERSION);
        if (sourceVersion >= key.identity().targetDataVersion()) {
            return PreparationOutcome.passthrough(sourceBlob);
        }

        try {
            CompoundTag converted = DataFixTypes.STRUCTURE.update(
                dataFixer,
                original,
                sourceVersion,
                key.identity().targetDataVersion()
            );
            NbtUtils.addCurrentDataVersion(converted);
            if (NbtUtils.getDataVersion(converted, MISSING_DATA_VERSION) != key.identity().targetDataVersion()) {
                throw new CacheBuildException("official DFU did not produce the requested DataVersion");
            }
            Path convertedBlob = cacheRoot.resolve(key.convertedBlobRelativePath()).normalize();
            if (!Files.isRegularFile(convertedBlob)) {
                writeTagAtomic(convertedBlob, converted);
            }
            return PreparationOutcome.converted(convertedBlob);
        } catch (RuntimeException exception) {
            LOGGER.warn("Official structure DFU failed for {}; preserving its exact vanilla bytes: {}", location, rootMessage(exception));
            return PreparationOutcome.passthrough(sourceBlob);
        }
    }

    private ResourceResult resultFromOutcome(
        ResourceLocation location,
        CacheKey key,
        PreparationOutcome outcome,
        long startedNanos,
        boolean shared
    ) {
        return ResourceResult.prepared(location, key, outcome.blob(), elapsedSince(startedNanos), outcome.kind(), shared);
    }

    private Optional<CacheIndex> readPreviousIndex(CacheIdentity identity) {
        try {
            return CacheIndexStore.read(cacheRoot.resolve("index.json")).filter(index -> index.identity().equals(identity));
        } catch (IOException | RuntimeException exception) {
            LOGGER.warn("Ignoring unreadable structure DFU cache index: {}", rootMessage(exception));
            return Optional.empty();
        }
    }

    private void commitIndexAndPrune(CacheIndex index) {
        try {
            Files.createDirectories(cacheRoot);
            CacheIndexStore.writeAtomic(cacheRoot.resolve("index.json"), index);
        } catch (IOException exception) {
            throw new CacheBuildException("Cannot commit structure DFU cache index", exception);
        }

        Set<Path> retained = new HashSet<>();
        for (CacheEntry entry : index.entries().values()) {
            retained.add(resolveIndexedBlob(entry.blobPath()));
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
        } catch (IOException exception) {
            LOGGER.warn("Unable to fully prune unreferenced structure DFU cache blobs: {}", rootMessage(exception));
        }
        if (removed > 0L) {
            LOGGER.info("Pruned {} unreferenced structure DFU cache blobs under {}", removed, root.getFileName());
        }
    }

    private Path resolveIndexedBlob(String blobPath) {
        Path resolved = cacheRoot.resolve(blobPath).normalize();
        if (!resolved.startsWith(cacheRoot)) {
            throw new CacheBuildException("Cache index escaped cache root: " + blobPath);
        }
        return resolved;
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
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[Math.min(64 * 1024, maximumBytes + 1)];
            int total = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                total = Math.addExact(total, read);
                if (total > maximumBytes) {
                    throw new IOException("compressed structure exceeds " + maximumBytes + " bytes");
                }
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        } catch (ArithmeticException exception) {
            throw new IOException("compressed structure size overflow", exception);
        }
    }

    static CompoundTag readCompressed(byte[] bytes, long maxHeapBytes) throws IOException {
        if (maxHeapBytes < 1L) {
            throw new IllegalArgumentException("maxHeapBytes must be positive");
        }
        return NbtIo.readCompressed(new ByteArrayInputStream(bytes), NbtAccounter.create(maxHeapBytes));
    }

    private static boolean isStructureTemplate(CompoundTag tag) {
        return tag.contains("size", Tag.TAG_LIST)
            && tag.contains("blocks", Tag.TAG_LIST)
            && (tag.contains("palette", Tag.TAG_LIST) || tag.contains("palettes", Tag.TAG_LIST));
    }

    private static void ensureRawBlob(Path blob, byte[] bytes) {
        if (Files.isRegularFile(blob)) {
            return;
        }
        writeBytesAtomic(blob, bytes);
    }

    private static void writeBytesAtomic(Path blob, byte[] bytes) {
        writeAtomic(blob, output -> output.write(bytes));
    }

    private static void writeTagAtomic(Path blob, CompoundTag tag) {
        writeAtomic(blob, output -> {
            try (
                BufferedOutputStream buffered = new BufferedOutputStream(output);
                GZIPOutputStream gzip = new FastGzipOutputStream(buffered);
                DataOutputStream data = new DataOutputStream(gzip)
            ) {
                NbtIo.write(tag, data);
            }
        });
    }

    private static void writeAtomic(Path blob, OutputWriter writer) {
        try {
            Files.createDirectories(blob.getParent());
            Path temporary = blob.resolveSibling(blob.getFileName() + ".tmp-" + Thread.currentThread().threadId());
            Files.deleteIfExists(temporary);
            try {
                try (OutputStream output = Files.newOutputStream(temporary, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                    writer.write(output);
                }
                try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                    channel.force(true);
                }
                try {
                    Files.move(temporary, blob, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException exception) {
                    Files.move(temporary, blob);
                } catch (java.nio.file.FileAlreadyExistsException exception) {
                    // An identical content-addressed write won the race.
                }
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (IOException exception) {
            throw new CacheBuildException("Cannot write structure cache blob " + blob, exception);
        }
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

    private static java.util.concurrent.ThreadFactory daemonThreadFactory() {
        AtomicInteger sequence = new AtomicInteger();
        return task -> {
            Thread thread = new Thread(task, "structure-dfu-cache-build-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    @FunctionalInterface
    private interface OutputWriter {
        void write(OutputStream output) throws IOException;
    }

    private static final class FastGzipOutputStream extends GZIPOutputStream {
        private FastGzipOutputStream(OutputStream output) throws IOException {
            super(output);
            this.def.setLevel(Deflater.BEST_SPEED);
        }
    }

    private enum ResultKind {
        CONVERTED,
        PASSTHROUGH,
        VANILLA_FALLBACK
    }

    private record PreparationOutcome(ResultKind kind, Path blob) {
        static PreparationOutcome converted(Path blob) {
            return new PreparationOutcome(ResultKind.CONVERTED, blob);
        }

        static PreparationOutcome passthrough(Path blob) {
            return new PreparationOutcome(ResultKind.PASSTHROUGH, blob);
        }
    }

    private record ResourceResult(
        ResourceLocation location,
        CacheKey key,
        Path preparedBlob,
        long elapsedNanos,
        ResultKind kind,
        boolean cacheHit
    ) {
        static ResourceResult prepared(
            ResourceLocation location,
            CacheKey key,
            Path preparedBlob,
            long elapsedNanos,
            ResultKind kind,
            boolean cacheHit
        ) {
            return new ResourceResult(location, key, preparedBlob, elapsedNanos, kind, cacheHit);
        }

        static ResourceResult vanillaFallback(ResourceLocation location, long elapsedNanos) {
            return new ResourceResult(location, null, null, elapsedNanos, ResultKind.VANILLA_FALLBACK, false);
        }
    }
}
