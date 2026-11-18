package dev.rinchan.structuredfucache.cache;

import com.mojang.datafixers.DataFixer;
import com.mojang.logging.LogUtils;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
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

/**
 * Streaming single-layer cache builder: upgrades only structure templates older than
 * targetDataVersion into overlay/data/<ns>/<path>, recording passthrough and unparseable
 * entries as P in manifest.v2 with zero bytes written to disk.
 */
public final class StructureCacheBuilder {
    static final int MAX_COMPRESSED_RESOURCE_BYTES = 64 * 1024 * 1024;
    static final long MAX_DECODED_NBT_BYTES = 32L * 1024L * 1024L;

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int MISSING_DATA_VERSION = 500;

    private final DataFixer dataFixer;
    private final Path cacheRoot;
    private final Path overlayDataDir;
    private final int workers;

    public record BuildSummary(int total, int converted, int passthrough, int cacheHits, int skippedBad, long elapsedMs) {
    }

    public StructureCacheBuilder(DataFixer dataFixer, Path cacheRoot) {
        this(dataFixer, cacheRoot, adaptiveWorkers());
    }

    StructureCacheBuilder(DataFixer dataFixer, Path cacheRoot, int workers) {
        if (dataFixer == null || cacheRoot == null) {
            throw new IllegalArgumentException("dataFixer and cacheRoot must not be null");
        }
        if (workers < 1) {
            throw new IllegalArgumentException("workers must be positive");
        }
        this.dataFixer = dataFixer;
        this.cacheRoot = cacheRoot.toAbsolutePath().normalize();
        this.overlayDataDir = this.cacheRoot.resolve("overlay").resolve("data");
        this.workers = workers;
    }

    public BuildSummary build(ResourceManager resourceManager, CacheManifest.Header header) throws IOException {
        long startedNanos = System.nanoTime();
        Path manifestFile = cacheRoot.resolve(CacheManifest.FILE_NAME);
        Map<String, CacheManifest.Entry> previousEntries = readPreviousEntries(manifestFile, header.targetDataVersion());

        Map<ResourceLocation, Resource> listed = new TreeMap<>(
            resourceManager.listResources("structure", loc -> loc.getPath().endsWith(".nbt"))
        );
        Files.createDirectories(overlayDataDir);

        Map<String, CacheManifest.Entry> nextEntries = new TreeMap<>();
        Set<Path> retainedOverlayFiles = new HashSet<>();
        AtomicInteger converted = new AtomicInteger();
        AtomicInteger passthrough = new AtomicInteger();
        AtomicInteger cacheHits = new AtomicInteger();
        AtomicInteger skippedBad = new AtomicInteger();

        if (workers == 1 || listed.size() <= 1) {
            for (Map.Entry<ResourceLocation, Resource> item : listed.entrySet()) {
                ProcessOutcome outcome = processOne(
                    item.getKey(),
                    item.getValue(),
                    previousEntries,
                    header.targetDataVersion(),
                    converted,
                    passthrough,
                    cacheHits,
                    skippedBad
                );
                nextEntries.put(item.getKey().toString(), outcome.entry());
                if (outcome.overlayFile() != null) {
                    retainedOverlayFiles.add(outcome.overlayFile());
                }
            }
        } else {
            Map<String, CacheManifest.Entry> concurrentEntries = new ConcurrentHashMap<>(listed.size());
            Set<Path> concurrentFiles = ConcurrentHashMap.newKeySet();
            Semaphore inFlightBudget = new Semaphore(workers * 2);
            try (ExecutorService pool = Executors.newFixedThreadPool(
                workers, Thread.ofPlatform().daemon().name("structure-dfu-v2-", 1).factory())) {
                var futures = new java.util.ArrayList<Future<?>>(listed.size());
                for (Map.Entry<ResourceLocation, Resource> item : listed.entrySet()) {
                    inFlightBudget.acquire();
                    futures.add(pool.submit(() -> {
                        try {
                            ProcessOutcome outcome = processOne(
                                item.getKey(),
                                item.getValue(),
                                previousEntries,
                                header.targetDataVersion(),
                                converted,
                                passthrough,
                                cacheHits,
                                skippedBad
                            );
                            concurrentEntries.put(item.getKey().toString(), outcome.entry());
                            if (outcome.overlayFile() != null) {
                                concurrentFiles.add(outcome.overlayFile());
                            }
                        } finally {
                            inFlightBudget.release();
                        }
                    }));
                }
                for (Future<?> future : futures) {
                    future.get();
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IOException("Structure DFU cache build interrupted", exception);
            } catch (ExecutionException exception) {
                if (exception.getCause() instanceof Error error) {
                    throw error;
                }
                throw new IOException("Structure DFU cache worker failed", exception.getCause());
            }
            nextEntries.putAll(concurrentEntries);
            retainedOverlayFiles.addAll(concurrentFiles);
        }

        pruneOrphanOverlayFiles(retainedOverlayFiles);
        CacheManifest.writeAtomic(manifestFile, header, nextEntries);
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
        return new BuildSummary(
            listed.size(),
            converted.get(),
            passthrough.get(),
            cacheHits.get(),
            skippedBad.get(),
            elapsedMs
        );
    }

    private ProcessOutcome processOne(
        ResourceLocation location,
        Resource resource,
        Map<String, CacheManifest.Entry> previousEntries,
        int targetDataVersion,
        AtomicInteger converted,
        AtomicInteger passthrough,
        AtomicInteger cacheHits,
        AtomicInteger skippedBad
    ) {
        Path overlayTarget = resolveOverlayTarget(location);
        byte[] sourceBytes;
        try {
            sourceBytes = readBounded(resource, MAX_COMPRESSED_RESOURCE_BYTES);
        } catch (IOException | RuntimeException exception) {
            LOGGER.debug("Skipping unreadable structure {}: {}", location, rootMessage(exception));
            skippedBad.incrementAndGet();
            deleteQuietly(overlayTarget);
            return new ProcessOutcome(new CacheManifest.Entry("unreadable", CacheManifest.Status.P), null);
        }

        String sourceSha256 = sha256Hex(sourceBytes);
        CacheManifest.Entry previous = previousEntries.get(location.toString());
        if (previous != null && previous.sourceSha256().equals(sourceSha256)) {
            if (previous.status() == CacheManifest.Status.P) {
                deleteQuietly(overlayTarget);
                cacheHits.incrementAndGet();
                return new ProcessOutcome(previous, null);
            }
            if (previous.status() == CacheManifest.Status.C
                && Files.isRegularFile(overlayTarget, LinkOption.NOFOLLOW_LINKS)
                && safeSize(overlayTarget) > 0L) {
                cacheHits.incrementAndGet();
                return new ProcessOutcome(previous, overlayTarget);
            }
        }

        CompoundTag parsed;
        try {
            parsed = NbtIo.readCompressed(new ByteArrayInputStream(sourceBytes), NbtAccounter.create(MAX_DECODED_NBT_BYTES));
        } catch (IOException | RuntimeException exception) {
            LOGGER.debug("Skipping unparseable structure {}: {}", location, rootMessage(exception));
            skippedBad.incrementAndGet();
            deleteQuietly(overlayTarget);
            return new ProcessOutcome(new CacheManifest.Entry(sourceSha256, CacheManifest.Status.P), null);
        }
        // Release compressed input immediately after NBT decode.
        sourceBytes = null;

        int sourceVersion = NbtUtils.getDataVersion(parsed, MISSING_DATA_VERSION);
        if (!isStructureTemplate(parsed) || sourceVersion >= targetDataVersion) {
            passthrough.incrementAndGet();
            deleteQuietly(overlayTarget);
            return new ProcessOutcome(new CacheManifest.Entry(sourceSha256, CacheManifest.Status.P), null);
        }

        CompoundTag upgraded = DataFixTypes.STRUCTURE.update(dataFixer, parsed, sourceVersion, targetDataVersion);
        parsed = null;
        NbtUtils.addCurrentDataVersion(upgraded);
        try {
            writeGzipNbt(overlayTarget, upgraded);
        } catch (IOException exception) {
            LOGGER.debug("Failed to write upgraded structure {}: {}", location, rootMessage(exception));
            skippedBad.incrementAndGet();
            deleteQuietly(overlayTarget);
            return new ProcessOutcome(new CacheManifest.Entry(sourceSha256, CacheManifest.Status.P), null);
        }
        converted.incrementAndGet();
        return new ProcessOutcome(new CacheManifest.Entry(sourceSha256, CacheManifest.Status.C), overlayTarget);
    }

    private Path resolveOverlayTarget(ResourceLocation location) {
        Path target = overlayDataDir.resolve(location.getNamespace()).resolve(location.getPath()).normalize();
        if (!target.startsWith(overlayDataDir)) {
            throw new IllegalArgumentException("Structure path escaped overlay root: " + location);
        }
        return target;
    }

    private Map<String, CacheManifest.Entry> readPreviousEntries(Path manifestFile, int targetDataVersion) {
        try {
            return CacheManifest.readEntriesIfMatchingTarget(manifestFile, targetDataVersion);
        } catch (IOException | RuntimeException exception) {
            LOGGER.debug("Ignoring unreadable manifest.v2: {}", rootMessage(exception));
            return new HashMap<>();
        }
    }

    private void pruneOrphanOverlayFiles(Set<Path> retainedOverlayFiles) {
        if (!Files.isDirectory(overlayDataDir)) {
            return;
        }
        try (var walk = Files.walk(overlayDataDir)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                if (path.equals(overlayDataDir)) {
                    continue;
                }
                if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                    if (!retainedOverlayFiles.contains(path.normalize())) {
                        Files.deleteIfExists(path);
                    }
                } else if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                    try (var children = Files.list(path)) {
                        if (children.findAny().isEmpty()) {
                            Files.deleteIfExists(path);
                        }
                    }
                }
            }
        } catch (IOException | UncheckedIOException exception) {
            LOGGER.debug("Unable to prune orphan overlay files: {}", rootMessage(exception));
        }
    }

    static int adaptiveWorkers() {
        Runtime runtime = Runtime.getRuntime();
        long maxHeap = runtime.maxMemory();
        long usedHeap = runtime.totalMemory() - runtime.freeMemory();
        long headroom = maxHeap - usedHeap;
        return workerCountFor(maxHeap, headroom, runtime.availableProcessors());
    }

    static int workerCountFor(long maxHeapBytes, long headroomBytes, int cpus) {
        if (maxHeapBytes <= 6_800_000_000L || headroomBytes < 2_500_000_000L) {
            return 1;
        }
        return Math.min(4, Math.max(2, cpus - 1));
    }

    static byte[] readBounded(Resource resource, int maximumBytes) throws IOException {
        try (InputStream input = resource.open()) {
            byte[] bytes = input.readNBytes(maximumBytes);
            if (input.read() != -1) {
                throw new IOException("compressed structure exceeds " + maximumBytes + " bytes");
            }
            return bytes;
        }
    }

    private static boolean isStructureTemplate(CompoundTag tag) {
        return tag.contains("size", Tag.TAG_LIST)
            && tag.contains("blocks", Tag.TAG_LIST)
            && (tag.contains("palette", Tag.TAG_LIST) || tag.contains("palettes", Tag.TAG_LIST));
    }

    private static void writeGzipNbt(Path target, CompoundTag tag) throws IOException {
        Files.createDirectories(target.getParent());
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp-" + Thread.currentThread().threadId());
        try {
            try (
                OutputStream fileOut = Files.newOutputStream(tmp);
                BufferedOutputStream buffered = new BufferedOutputStream(fileOut);
                GZIPOutputStream gzip = new FastGzipOutputStream(buffered);
                DataOutputStream dataOut = new DataOutputStream(gzip)
            ) {
                NbtIo.write(tag, dataOut);
            }
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private static String sha256Hex(byte[] bytes) {
        MessageDigest digest = PackFingerprint.sha256Digest();
        return HexFormat.of().formatHex(digest.digest(bytes));
    }

    private static long safeSize(Path path) {
        try {
            return Files.size(path);
        } catch (IOException exception) {
            return -1L;
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
        }
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

    private record ProcessOutcome(CacheManifest.Entry entry, Path overlayFile) {
    }
}
