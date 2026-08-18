package dev.rinchan.structuredfucache.cache;

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
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.datafix.DataFixers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StructureCacheBuilderTest {
    private static final int TARGET_DATA_VERSION = 3955;
    private static final ResourceLocation LOCATION = ResourceLocation.fromNamespaceAndPath("example", "structure/old.nbt");
    private static final ResourceLocation DUPLICATE_LOCATION = ResourceLocation.fromNamespaceAndPath("example", "structure/duplicate.nbt");

    @TempDir
    Path temporaryDirectory;

    @Test
    void coldWarmAndChangedResourceUseContentAddressedCache() throws Exception {
        byte[] original = compressedStructure("first");
        StructureCacheBuilder builder = builder(new CachePolicy(Duration.ofMinutes(5)));

        CacheSnapshot cold = builder.build(manager(original), TARGET_DATA_VERSION);
        Path firstBlob = cold.convertedResources().get(LOCATION);
        assertTrue(Files.isRegularFile(firstBlob));
        assertEquals(1, cold.stats().converted());
        assertEquals(0, cold.stats().cacheHits());
        try (var input = Files.newInputStream(firstBlob)) {
            assertEquals(
                TARGET_DATA_VERSION,
                net.minecraft.nbt.NbtIo.readCompressed(input, net.minecraft.nbt.NbtAccounter.unlimitedHeap()).getInt("DataVersion")
            );
        }

        CacheSnapshot warm = builder.build(manager(original), TARGET_DATA_VERSION);
        assertEquals(firstBlob, warm.convertedResources().get(LOCATION));
        assertEquals(1, warm.stats().cacheHits());
        assertEquals(0, warm.stats().converted());

        CacheSnapshot changed = builder.build(manager(compressedStructure("changed")), TARGET_DATA_VERSION);
        Path changedBlob = changed.convertedResources().get(LOCATION);
        assertNotEquals(firstBlob, changedBlob);
        assertTrue(Files.isRegularFile(changedBlob));
        assertFalse(Files.exists(firstBlob));
        assertEquals(1, changed.stats().converted());
    }

    @Test
    void duplicateContentIsConvertedOnceAndSharedAcrossWorkers() {
        byte[] duplicate = compressedStructure("shared");
        StructureCacheBuilder builder = builder(new CachePolicy(Duration.ofMinutes(5)));

        CacheSnapshot snapshot = builder.build(
            manager(Map.of(LOCATION, duplicate, DUPLICATE_LOCATION, duplicate)),
            TARGET_DATA_VERSION
        );

        assertEquals(1, snapshot.stats().converted());
        assertEquals(1, snapshot.stats().cacheHits());
        assertEquals(snapshot.convertedResources().get(LOCATION), snapshot.convertedResources().get(DUPLICATE_LOCATION));
        assertTrue(Files.isRegularFile(snapshot.convertedResources().get(LOCATION)));
    }

    @Test
    void boundedReaderRejectsNbtThatExpandsBeyondItsMemoryBudget() throws Exception {
        CompoundTag oversized = new CompoundTag();
        oversized.putByteArray("payload", new byte[2 * 1024 * 1024]);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        net.minecraft.nbt.NbtIo.writeCompressed(oversized, output);

        assertThrows(
            RuntimeException.class,
            () -> StructureCacheBuilder.readCompressed(output.toByteArray(), 1024L)
        );
    }

    @Test
    void timeoutDoesNotCommitPartialIndex() {
        AtomicInteger opens = new AtomicInteger();
        ResourceManager manager = manager(() -> {
            opens.incrementAndGet();
            try {
                Thread.sleep(200L);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
            return new ByteArrayInputStream(compressedStructure("slow"));
        });
        StructureCacheBuilder builder = builder(new CachePolicy(Duration.ofMillis(20)));

        assertThrows(CacheBuildException.class, () -> builder.build(manager, TARGET_DATA_VERSION));
        assertFalse(Files.exists(temporaryDirectory.resolve("index.json")));
        assertEquals(1, opens.get());
    }

    private StructureCacheBuilder builder(CachePolicy policy) {
        return new StructureCacheBuilder(DataFixers.getDataFixer(), temporaryDirectory, policy, 2);
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
        return manager(() -> new ByteArrayInputStream(bytes));
    }

    private static ResourceManager manager(net.minecraft.server.packs.resources.IoSupplier<InputStream> supplier) {
        Resource resource = new Resource(packResources(), supplier);
        return new ResourceManager() {
            @Override
            public Set<String> getNamespaces() {
                return Set.of(LOCATION.getNamespace());
            }

            @Override
            public Optional<Resource> getResource(ResourceLocation location) {
                return LOCATION.equals(location) ? Optional.of(resource) : Optional.empty();
            }

            @Override
            public List<Resource> getResourceStack(ResourceLocation location) {
                return LOCATION.equals(location) ? List.of(resource) : List.of();
            }

            @Override
            public Map<ResourceLocation, Resource> listResources(String path, java.util.function.Predicate<ResourceLocation> filter) {
                return path.equals("structure") && filter.test(LOCATION) ? Map.of(LOCATION, resource) : Map.of();
            }

            @Override
            public Map<ResourceLocation, List<Resource>> listResourceStacks(String path, java.util.function.Predicate<ResourceLocation> filter) {
                return path.equals("structure") && filter.test(LOCATION) ? Map.of(LOCATION, List.of(resource)) : Map.of();
            }

            @Override
            public Stream<PackResources> listPacks() {
                return Stream.of(packResources());
            }
        };
    }

    private static ResourceManager manager(Map<ResourceLocation, byte[]> resources) {
        Map<ResourceLocation, Resource> resolved = resources.entrySet().stream().collect(
            java.util.stream.Collectors.toUnmodifiableMap(
                Map.Entry::getKey,
                entry -> new Resource(packResources(), () -> new ByteArrayInputStream(entry.getValue()))
            )
        );
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
                Resource resource = resolved.get(location);
                return resource == null ? List.of() : List.of(resource);
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
            public Map<ResourceLocation, List<Resource>> listResourceStacks(String path, java.util.function.Predicate<ResourceLocation> filter) {
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

    private static PackResources packResources() {
        return (PackResources) Proxy.newProxyInstance(
            StructureCacheBuilderTest.class.getClassLoader(),
            new Class<?>[] { PackResources.class },
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
