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
        assertEquals(1, changed.stats().converted());
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
