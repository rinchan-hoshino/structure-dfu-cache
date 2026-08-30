package dev.rinchan.structuredfucache.cache;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.resources.IoSupplier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.datafix.DataFixers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StructureCacheBuilderTest {
    private static final int TARGET_DATA_VERSION = 3955;
    private static final CacheIdentity IDENTITY = new CacheIdentity(
        CacheIdentity.CURRENT_FORMAT,
        TARGET_DATA_VERSION,
        "1.21.1",
        "21.1.248"
    );
    private static final ResourceLocation LOCATION = ResourceLocation.fromNamespaceAndPath("example", "structure/old.nbt");
    private static final ResourceLocation DUPLICATE_LOCATION = ResourceLocation.fromNamespaceAndPath(
        "example",
        "structure/duplicate.nbt"
    );

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
            CompoundTag actual = net.minecraft.nbt.NbtIo.readCompressed(input, net.minecraft.nbt.NbtAccounter.unlimitedHeap());
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
    void tamperedWarmBlobIsRejectedAndRebuilt() throws Exception {
        byte[] original = compressedStructure("integrity");
        StructureCacheBuilder builder = builder();
        CacheSnapshot cold = builder.build(manager(original), IDENTITY);
        Path blob = cold.preparedResources().get(LOCATION);
        Files.write(blob, new byte[] {1, 2, 3});

        CacheSnapshot rebuilt = builder.build(manager(original), IDENTITY);

        assertEquals(0, rebuilt.stats().cacheHits());
        assertEquals(1, rebuilt.stats().converted());
        try (var input = Files.newInputStream(rebuilt.preparedResources().get(LOCATION))) {
            CompoundTag actual = net.minecraft.nbt.NbtIo.readCompressed(input, net.minecraft.nbt.NbtAccounter.unlimitedHeap());
            assertEquals("integrity", actual.getString("author"));
            assertEquals(TARGET_DATA_VERSION, actual.getInt("DataVersion"));
        }
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
        CacheSnapshot snapshot = builder().build(
            manager(Map.of(LOCATION, duplicate, DUPLICATE_LOCATION, duplicate)),
            IDENTITY
        );

        assertEquals(1, snapshot.stats().converted());
        assertEquals(1, snapshot.stats().cacheHits());
        assertEquals(snapshot.preparedResources().get(LOCATION), snapshot.preparedResources().get(DUPLICATE_LOCATION));
    }

    @Test
    void oneUnreadableResourcePreventsPartialIndexPublication() {
        ResourceLocation brokenLocation = ResourceLocation.fromNamespaceAndPath("example", "structure/broken.nbt");
        Map<ResourceLocation, Resource> resources = Map.of(
            LOCATION,
            resource(() -> new ByteArrayInputStream(compressedStructure("readable"))),
            brokenLocation,
            resource(() -> {
                throw new IOException("negative fixture: resource stream cannot be opened");
            })
        );

        CacheSnapshot snapshot = builder().build(managerFromResources(resources), IDENTITY);

        assertEquals(1, snapshot.stats().vanillaFallbacks());
        assertEquals(1, snapshot.preparedResources().size());
        assertTrue(snapshot.preparedResources().containsKey(LOCATION));
        assertFalse(Files.exists(temporaryDirectory.resolve("index.json")));
    }

    @Test
    void malformedResourceIsPreservedByteForByteInFlattenedPack() throws Exception {
        byte[] malformed = new byte[] {1, 2, 3};
        CacheSnapshot snapshot = builder().build(manager(malformed), IDENTITY);

        assertEquals(1, snapshot.stats().current());
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

    private StructureCacheBuilder builder() {
        return new StructureCacheBuilder(DataFixers.getDataFixer(), temporaryDirectory, 2);
    }

    private static byte[] compressedStructure(String marker) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("DataVersion", 1000);
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

    private static ResourceManager manager(byte[] bytes) {
        return manager(Map.of(LOCATION, bytes));
    }

    private static ResourceManager manager(Map<ResourceLocation, byte[]> resources) {
        Map<ResourceLocation, Resource> resolved = resources.entrySet().stream().collect(
            java.util.stream.Collectors.toUnmodifiableMap(
                Map.Entry::getKey,
                entry -> resource(() -> new ByteArrayInputStream(entry.getValue()))
            )
        );
        return managerFromResources(resolved);
    }

    private static ResourceManager managerFromResources(Map<ResourceLocation, Resource> resolved) {
        return new ResourceManager() {
            @Override
            public Set<String> getNamespaces() {
                return Set.of("example");
            }

            @Override
            public Optional<Resource> getResource(ResourceLocation location) {
                return Optional.ofNullable(resolved.get(location));
            }

            @Override
            public List<Resource> getResourceStack(ResourceLocation location) {
                Resource found = resolved.get(location);
                return found == null ? List.of() : List.of(found);
            }

            @Override
            public Map<ResourceLocation, Resource> listResources(String path, java.util.function.Predicate<ResourceLocation> filter) {
                if (!path.equals("structure")) {
                    return Map.of();
                }
                return resolved.entrySet().stream()
                    .filter(entry -> filter.test(entry.getKey()))
                    .collect(java.util.stream.Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
            }

            @Override
            public Map<ResourceLocation, List<Resource>> listResourceStacks(
                String path,
                java.util.function.Predicate<ResourceLocation> filter
            ) {
                return listResources(path, filter).entrySet().stream().collect(
                    java.util.stream.Collectors.toUnmodifiableMap(Map.Entry::getKey, entry -> List.of(entry.getValue()))
                );
            }

            @Override
            public Stream<PackResources> listPacks() {
                return Stream.of(packResources());
            }
        };
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
