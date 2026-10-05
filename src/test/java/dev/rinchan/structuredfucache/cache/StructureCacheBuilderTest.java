package dev.rinchan.structuredfucache.cache;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.datafixers.DataFixer;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import dev.rinchan.rinlib.minecraft.StructureResources;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.resources.IoSupplier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.util.datafix.DataFixers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StructureCacheBuilderTest {
    private static final int TARGET_DATA_VERSION = detectVersion();
    private static int detectVersion() {
        net.minecraft.SharedConstants.tryDetectVersion();
        return StructureResources.dataVersion();
    }
    private static final CacheIdentity IDENTITY = new CacheIdentity(
        CacheIdentity.CURRENT_FORMAT,
        TARGET_DATA_VERSION,
        "1.21.1",
        "21.1.248"
    );
    private static final String LOCATION = "example:" + StructureResources.directory() + "/old.nbt";
    private static final String DUPLICATE_LOCATION = "example:" + StructureResources.directory() + "/duplicate.nbt";

    @TempDir
    Path temporaryDirectory;

    @Test
    void coldWarmAndChangedResourceUseContentAddressedCache() throws Exception {
        byte[] original = compressedStructure("first");
        StructureCacheBuilder builder = builder();

        CacheSnapshot cold = builder.build(manager(original), IDENTITY);
        Path firstBlob = cold.preparedResources().get(LOCATION);
        assertTrue(Files.isRegularFile(firstBlob));
        assertEquals(1, cold.stats().converted());
        assertEquals(0, cold.stats().cacheHits());
        try (var input = Files.newInputStream(firstBlob)) {
            CompoundTag actual = StructureResources.readCompressed(input.readAllBytes(), StructureCacheBuilder.MAX_DECODED_NBT_BYTES);
            CompoundTag expected = net.minecraft.util.datafix.DataFixTypes.STRUCTURE.update(
                DataFixers.getDataFixer(),
                StructureCacheBuilder.readCompressed(original, StructureCacheBuilder.MAX_DECODED_NBT_BYTES),
                1000,
                TARGET_DATA_VERSION
            );
            net.minecraft.nbt.NbtUtils.addCurrentDataVersion(expected);
            assertEquals(expected, actual);
        }

        CacheSnapshot warm = builder.build(manager(original), IDENTITY);
        assertEquals(firstBlob, warm.preparedResources().get(LOCATION));
        assertEquals(1, warm.stats().cacheHits());
        assertEquals(0, warm.stats().converted());

        CacheSnapshot changed = builder.build(manager(compressedStructure("changed")), IDENTITY);
        Path changedBlob = changed.preparedResources().get(LOCATION);
        assertNotEquals(firstBlob, changedBlob);
        assertTrue(Files.isRegularFile(changedBlob));
        assertFalse(Files.exists(firstBlob));
        assertEquals(1, changed.stats().converted());
    }

    @Test
    void unreadableResourceDoesNotPreventCommittingHealthyEntries() throws Exception {
        var pack = packResources();
        var healthy = new Resource(pack, () -> new ByteArrayInputStream(compressedStructure("healthy")));
        var broken = new Resource(pack, () -> { throw new IOException("fixture unavailable"); });
        String bad = "example:" + StructureResources.directory() + "/broken.nbt";
        var snapshot = builder().build(Map.of(LOCATION, healthy, bad, broken), IDENTITY);
        assertEquals(1, snapshot.stats().converted());
        assertEquals(1, snapshot.stats().vanillaFallbacks());
        assertTrue(Files.isRegularFile(temporaryDirectory.resolve("index.json")));
        var index = CacheIndexStore.read(temporaryDirectory.resolve("index.json")).orElseThrow();
        assertEquals(Set.of(LOCATION), index.entries().keySet());
    }

    @Test
    void tamperedWarmBlobIsRejectedAndRebuilt() throws Exception {
        byte[] original = compressedStructure("integrity");
        StructureCacheBuilder builder = builder();
        CacheSnapshot cold = builder.build(manager(original), IDENTITY);
        Path blob = cold.preparedResources().get(LOCATION);
        Files.write(blob, new byte[] {1, 2, 3});

        CacheSnapshot rebuilt = builder.build(manager(original), IDENTITY);

        assertEquals(0, rebuilt.stats().cacheHits());
        assertEquals(1, rebuilt.stats().converted());
        assertEquals(1, rebuilt.stats().repairedResources());
        try (var input = Files.newInputStream(rebuilt.preparedResources().get(LOCATION))) {
            CompoundTag actual = StructureResources.readCompressed(input.readAllBytes(), StructureCacheBuilder.MAX_DECODED_NBT_BYTES);
            assertEquals(net.minecraft.nbt.StringTag.valueOf("integrity"), actual.get("author"));
            assertEquals(TARGET_DATA_VERSION, net.minecraft.nbt.NbtUtils.getDataVersion(actual, -1));
        }
    }

    @Test
    void missingIndexDoesNotTrustAnExistingConvertedBlob() throws Exception {
        byte[] original = compressedStructure("orphan");
        Path blob = builder().build(manager(original), IDENTITY).preparedResources().get(LOCATION);
        Files.write(blob, new byte[] {1, 2, 3});
        Files.delete(temporaryDirectory.resolve("index.json"));

        CacheSnapshot rebuilt = builder().build(manager(original), IDENTITY);

        assertEquals(0, rebuilt.stats().cacheHits());
        CompoundTag actual = StructureCacheBuilder.readCompressed(
            Files.readAllBytes(rebuilt.preparedResources().get(LOCATION)), 1024 * 1024);
        assertEquals(net.minecraft.nbt.StringTag.valueOf("orphan"), actual.get("author"));
        assertEquals(TARGET_DATA_VERSION, net.minecraft.nbt.NbtUtils.getDataVersion(actual, -1));
    }

    @Test
    void corruptIndexDoesNotTrustAnExistingPassthroughBlob() throws Exception {
        byte[] original = new byte[] {1, 2, 3};
        Path blob = builder().build(manager(original), IDENTITY).preparedResources().get(LOCATION);
        Files.write(blob, new byte[] {9, 8, 7});
        Files.writeString(temporaryDirectory.resolve("index.json"), "not-json");

        CacheSnapshot rebuilt = builder().build(manager(original), IDENTITY);

        assertEquals(0, rebuilt.stats().cacheHits());
        assertArrayEquals(original, Files.readAllBytes(rebuilt.preparedResources().get(LOCATION)));
        assertEquals(CacheBuildStats.IndexState.RECOVERED, rebuilt.stats().indexState());
    }

    @Test
    void changedRuntimeIdentityCannotReusePreviousBlob() {
        byte[] original = compressedStructure("identity");
        CacheSnapshot first = builder().build(manager(original), IDENTITY);
        CacheIdentity changed = new CacheIdentity(
            CacheIdentity.CURRENT_FORMAT,
            TARGET_DATA_VERSION,
            "1.21.1",
            "21.1.249"
        );

        CacheSnapshot rebuilt = builder().build(manager(original), changed);

        assertEquals(0, rebuilt.stats().cacheHits());
        assertEquals(1, rebuilt.stats().converted());
        assertNotEquals(first.preparedResources().get(LOCATION), rebuilt.preparedResources().get(LOCATION));
        assertEquals(CacheBuildStats.IndexState.VERSION_CHANGED, rebuilt.stats().indexState());
        assertFalse(rebuilt.stats().hasProblems());
    }

    @Test
    void corruptIndexIsIgnoredAndReplaced() throws Exception {
        Files.writeString(temporaryDirectory.resolve("index.json"), "not-json");

        CacheSnapshot rebuilt = builder().build(manager(compressedStructure("index")), IDENTITY);

        assertEquals(0, rebuilt.stats().cacheHits());
        assertEquals(1, rebuilt.stats().converted());
        assertTrue(CacheIndexStore.read(temporaryDirectory.resolve("index.json")).isPresent());
    }

    @Test
    void duplicateContentIsConvertedOnceAndSharedAcrossWorkers() {
        byte[] duplicate = compressedStructure("shared");
        AtomicInteger conversions = new AtomicInteger();
        StructureCacheBuilder builder = new StructureCacheBuilder(countingFixer(conversions, false), temporaryDirectory, 2);
        CacheSnapshot snapshot = builder.build(
            manager(Map.of(LOCATION, duplicate, DUPLICATE_LOCATION, duplicate)),
            IDENTITY
        );

        assertEquals(1, conversions.get());
        assertEquals(1, snapshot.stats().converted());
        assertEquals(1, snapshot.stats().cacheHits());
        assertFalse(Files.exists(temporaryDirectory.resolve("sources")));
        assertEquals(snapshot.preparedResources().get(LOCATION), snapshot.preparedResources().get(DUPLICATE_LOCATION));
    }

    @Test
    void failedConversionIsSharedAndPreservesSourceBytes() throws Exception {
        byte[] original = compressedStructure("failed-conversion");
        AtomicInteger conversions = new AtomicInteger();
        StructureCacheBuilder builder = new StructureCacheBuilder(countingFixer(conversions, true), temporaryDirectory, 2);
        CacheSnapshot snapshot = builder.build(
            manager(Map.of(LOCATION, original, DUPLICATE_LOCATION, original)), IDENTITY);

        assertEquals(1, conversions.get());
        assertEquals(0, snapshot.stats().converted());
        assertEquals(0, snapshot.stats().vanillaFallbacks());
        assertEquals(1, snapshot.stats().cacheHits());
        assertEquals(snapshot.preparedResources().get(LOCATION), snapshot.preparedResources().get(DUPLICATE_LOCATION));
        assertArrayEquals(original, Files.readAllBytes(snapshot.preparedResources().get(LOCATION)));
    }

    @Test
    void malformedResourceIsPreservedByteForByteInFlattenedPack() throws Exception {
        byte[] malformed = new byte[] {1, 2, 3};
        CacheSnapshot snapshot = builder().build(manager(malformed), IDENTITY);

        assertEquals(1, snapshot.stats().passthrough());
        assertEquals(0, snapshot.stats().vanillaFallbacks());
        assertArrayEquals(malformed, Files.readAllBytes(snapshot.preparedResources().get(LOCATION)));
        assertTrue(Files.isRegularFile(temporaryDirectory.resolve("index.json")));
    }

    @Test
    void boundedReaderClosesResourceStream() throws Exception {
        AtomicBoolean closed = new AtomicBoolean();
        InputStream input = new ByteArrayInputStream(new byte[] {1, 2, 3}) {
            @Override
            public void close() throws IOException {
                closed.set(true);
                super.close();
            }
        };

        assertArrayEquals(new byte[] {1, 2, 3}, StructureCacheBuilder.readBounded(resource(() -> input)));
        assertTrue(closed.get());
    }

    @Test
    void boundedReaderRejectsCompressedInputAboveLimit() {
        assertThrows(
            IOException.class,
            () -> StructureCacheBuilder.readBounded(new ByteArrayInputStream(new byte[] {1, 2, 3, 4}), 3)
        );
    }

    @Test
    void boundedReaderRejectsNbtThatExpandsBeyondItsMemoryBudget() throws Exception {
        CompoundTag oversized = new CompoundTag();
        oversized.putByteArray("payload", new byte[2 * 1024 * 1024]);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        net.minecraft.nbt.NbtIo.writeCompressed(oversized, output);

        assertThrows(RuntimeException.class, () -> StructureCacheBuilder.readCompressed(output.toByteArray(), 1024L));
    }

    @Test
    void currentAndFutureTemplatesRemainByteIdenticalWithoutInvokingDfu() throws Exception {
        AtomicInteger conversions = new AtomicInteger();
        StructureCacheBuilder builder = new StructureCacheBuilder(countingFixer(conversions, true), temporaryDirectory, 2);
        for (int version : new int[] {TARGET_DATA_VERSION, TARGET_DATA_VERSION + 1}) {
            byte[] bytes = compressedStructure("passthrough", version);
            CacheSnapshot snapshot = builder.build(manager(bytes), IDENTITY);
            assertEquals(1, snapshot.stats().passthrough());
            assertArrayEquals(bytes, Files.readAllBytes(snapshot.preparedResources().get(LOCATION)));
        }
        assertEquals(0, conversions.get());
    }

    @Test
    void fatalWorkerErrorsAreNotHiddenByVanillaFallback() {
        Map<String, Resource> resources = managerFromResources(Map.of(LOCATION, resource(() -> {
            throw new AssertionError("negative fixture: fatal worker failure");
        })));
        assertThrows(AssertionError.class, () -> builder().build(resources, IDENTITY));
        assertFalse(Files.exists(temporaryDirectory.resolve("index.json")));
    }

    @Test
    void interruptionWaitsForWorkersAndPreservesInterruptStatus() throws Exception {
        CountDownLatch reading = new CountDownLatch(1);
        CountDownLatch cancelled = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean closed = new AtomicBoolean();
        AtomicBoolean interrupted = new AtomicBoolean();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Map<String, Resource> resources = managerFromResources(Map.of(LOCATION, resource(() -> {
            reading.countDown();
            while (true) {
                try {
                    release.await();
                    break;
                } catch (InterruptedException exception) {
                    cancelled.countDown(); // Model a source that needs time to unwind after cancellation.
                }
            }
            return new ByteArrayInputStream(new byte[] {1, 2, 3}) {
                @Override
                public void close() {
                    closed.set(true);
                }
            };
        })));
        Thread caller = new Thread(() -> {
            try {
                builder().build(resources, IDENTITY);
            } catch (Throwable exception) {
                failure.set(exception);
            } finally {
                interrupted.set(Thread.currentThread().isInterrupted());
            }
        });
        caller.start();
        try {
            assertTrue(reading.await(10, TimeUnit.SECONDS));
            caller.interrupt();
            assertTrue(cancelled.await(10, TimeUnit.SECONDS));
            assertTrue(caller.isAlive(), "preparation must not return while its worker still reads resources");
        } finally {
            release.countDown();
            caller.join(10000);
        }
        assertFalse(caller.isAlive());
        assertTrue(closed.get());
        assertTrue(interrupted.get());
        assertTrue(failure.get() instanceof CacheBuildException);
        assertFalse(Files.exists(temporaryDirectory.resolve("index.json")));
    }

    @Test
    void indexCannotRedirectReadsOrDeletionOutsideTheCache() throws Exception {
        byte[] original = compressedStructure("redirect");
        builder().build(manager(original), IDENTITY);
        Path sentinel = temporaryDirectory.resolveSibling(temporaryDirectory.getFileName() + "-sentinel");
        try {
            Files.writeString(sentinel, "leave intact");
            CacheEntry redirected = new CacheEntry(CacheKey.sha256(original), "0".repeat(64), sentinel.toString(), true);
            CacheIndexStore.writeAtomic(temporaryDirectory.resolve("index.json"),
                new CacheIndex(IDENTITY, Map.of(LOCATION.toString(), redirected)));
            CacheSnapshot snapshot = builder().build(manager(original), IDENTITY);
            assertEquals(1, snapshot.stats().converted());
            assertEquals("leave intact", Files.readString(sentinel));
        } finally {
            Files.deleteIfExists(sentinel);
        }
    }

    @Test
    void cacheWriteFailureDoesNotBecomeAReportedSuccessfulPassthrough() throws Exception {
        Files.writeString(temporaryDirectory.resolve("blobs"), "not a directory");
        CacheSnapshot snapshot = builder().build(manager(compressedStructure("disk-failure")), IDENTITY);
        assertEquals(1, snapshot.stats().vanillaFallbacks());
        assertTrue(snapshot.preparedResources().isEmpty());
        assertTrue(CacheIndexStore.read(temporaryDirectory.resolve("index.json")).orElseThrow().entries().isEmpty());
    }

    @Test
    void failedConversionRetriesAfterTheFixerRecovers() throws Exception {
        byte[] bytes = compressedStructure("retry");
        var failed = new StructureCacheBuilder(countingFixer(new AtomicInteger(), true), temporaryDirectory, 1)
            .build(manager(bytes), IDENTITY);
        assertEquals(1, failed.stats().unoptimizedResources());
        var recovered = builder().build(manager(bytes), IDENTITY);
        assertEquals(1, recovered.stats().converted());
        assertEquals(0, recovered.stats().cacheHits());
        assertFalse(recovered.stats().hasProblems());
    }

    @Test
    void singleBlobWriteFailureKeepsOtherStructuresCached() throws Exception {
        byte[] good = compressedStructure("healthy");
        byte[] broken = compressedStructure("write-failure");
        Path obstruction = temporaryDirectory.resolve(CacheKey.of(broken, IDENTITY).convertedBlobRelativePath());
        Files.createDirectories(obstruction);
        var result = builder().build(manager(Map.of(LOCATION, good, DUPLICATE_LOCATION, broken)), IDENTITY);
        assertEquals(1, result.stats().converted());
        assertEquals(1, result.stats().vanillaFallbacks());
        assertEquals(Set.of(LOCATION), CacheIndexStore.read(temporaryDirectory.resolve("index.json")).orElseThrow().entries().keySet());
        assertEquals(Set.of(LOCATION), result.preparedResources().keySet());
    }

    private static DataFixer countingFixer(AtomicInteger conversions, boolean fail) {
        return (DataFixer)Proxy.newProxyInstance(
            DataFixer.class.getClassLoader(), new Class<?>[] {DataFixer.class},
            (proxy, method, arguments) -> {
                if (method.getName().equals("update")) {
                    conversions.incrementAndGet();
                    if (fail) {
                        throw new IllegalStateException("negative fixture: conversion failed");
                    }
                }
                return method.invoke(DataFixers.getDataFixer(), arguments);
            });
    }

    private StructureCacheBuilder builder() {
        return new StructureCacheBuilder(DataFixers.getDataFixer(), temporaryDirectory, 2);
    }

    private static byte[] compressedStructure(String marker) {
        return compressedStructure(marker, 1000);
    }

    private static byte[] compressedStructure(String marker, int version) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("DataVersion", version);
        tag.putString("author", marker);
        tag.put("size", ints(1, 1, 1));
        ListTag palette = new ListTag();
        CompoundTag stone = new CompoundTag();
        stone.putString("Name", "minecraft:stone");
        palette.add(stone);
        tag.put("palette", palette);
        ListTag blocks = new ListTag();
        CompoundTag block = new CompoundTag();
        block.putInt("state", 0);
        block.put("pos", ints(0, 0, 0));
        blocks.add(block);
        tag.put("blocks", blocks);
        tag.put("entities", new ListTag());
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            net.minecraft.nbt.NbtIo.writeCompressed(tag, output);
            return output.toByteArray();
        } catch (IOException exception) {
            throw new AssertionError(exception);
        }
    }

    private static ListTag ints(int... values) {
        ListTag list = new ListTag();
        for (int value : values) {
            list.add(IntTag.valueOf(value));
        }
        return list;
    }

    private static Map<String, Resource> manager(byte[] bytes) {
        return manager(Map.of(LOCATION, bytes));
    }

    private static Map<String, Resource> manager(Map<String, byte[]> resources) {
        Map<String, Resource> resolved = resources.entrySet().stream().collect(
            java.util.stream.Collectors.toUnmodifiableMap(
                Map.Entry::getKey,
                entry -> resource(() -> new ByteArrayInputStream(entry.getValue()))
            )
        );
        return managerFromResources(resolved);
    }

    private static Map<String, Resource> managerFromResources(Map<String, Resource> resolved) {
        return resolved;
    }

    private static Resource resource(IoSupplier<InputStream> supplier) {
        return new Resource(packResources(), supplier);
    }

    private static PackResources packResources() {
        return (PackResources)Proxy.newProxyInstance(
            StructureCacheBuilderTest.class.getClassLoader(),
            new Class<?>[] {PackResources.class},
            (proxy, method, arguments) -> {
                if (method.getName().equals("packId")) {
                    return "test";
                }
                Class<?> type = method.getReturnType();
                if (type == boolean.class) {
                    return false;
                }
                if (type == int.class) {
                    return 0;
                }
                if (type == Set.class) {
                    return Set.of();
                }
                if (type == List.class) {
                    return List.of();
                }
                if (type == Optional.class) {
                    return Optional.empty();
                }
                return null;
            }
        );
    }
}
