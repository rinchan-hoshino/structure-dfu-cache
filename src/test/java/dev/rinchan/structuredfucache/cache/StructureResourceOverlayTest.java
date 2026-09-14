package dev.rinchan.structuredfucache.cache;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.PathPackResources;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.resources.MultiPackResourceManager;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.datafix.DataFixers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StructureResourceOverlayTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void materializedGenerationLoadsThroughOnlyVanillaResourceClasses() throws Exception {
        byte[] cachedBytes = new byte[] {1, 2, 3, 4};
        ResourceLocation location = ResourceLocation.fromNamespaceAndPath("example", "structure/house.nbt");

        Path blob = temporaryDirectory.resolve("blobs/cached.nbt");
        Files.createDirectories(blob.getParent());
        Files.write(blob, cachedBytes);
        CacheIdentity identity = new CacheIdentity(CacheIdentity.CURRENT_FORMAT, 3955, "1.21.1", "21.1.248");
        CacheSnapshot snapshot = new CacheSnapshot(
            identity,
            Map.of(location, blob),
            new CacheBuildStats(1, 1, 0, 0, 0, 0L, location.toString())
        );

        Path generation = StructureCacheBootstrap.materializeOverlay(temporaryDirectory, snapshot);
        PathPackResources preparedPack = pack("prepared", generation);
        MultiPackResourceManager manager = new MultiPackResourceManager(PackType.SERVER_DATA, List.of(preparedPack));

        try (var input = manager.open(location)) {
            assertArrayEquals(cachedBytes, input.readAllBytes());
        }
        assertTrue(Files.isRegularFile(generation.resolve("pack.mcmeta")));

        StructureCacheBootstrap.retireGeneration(generation);
        assertFalse(Files.exists(generation));
    }

    @Test
    void unusableCacheRootFallsBackToTheOriginalResourceManager() throws Exception {
        Path blockedRoot = temporaryDirectory.resolve("blocked-cache-root");
        Files.writeString(blockedRoot, "not a directory");
        ResourceManager original = new MultiPackResourceManager(PackType.SERVER_DATA, List.of());
        CacheIdentity identity = new CacheIdentity(CacheIdentity.CURRENT_FORMAT, 3955, "1.21.1", "21.1.248");

        StructureCacheBootstrap.PreparedStructureResources prepared = StructureCacheBootstrap.prepare(
            original,
            DataFixers.getDataFixer(),
            blockedRoot,
            identity,
            1
        );

        assertSame(original, prepared.resourceManager());
        assertNull(prepared.generationRoot());
    }

    @Test
    void workerCountReservesHeapAndLeavesOneProcessorFree() {
        long gibibyte = 1024L * 1024L * 1024L;
        assertEquals(1, StructureCacheBootstrap.workerThreadsFor(3L * gibibyte, 8));
        assertEquals(4, StructureCacheBootstrap.workerThreadsFor(9L * gibibyte, 8));
        assertEquals(1, StructureCacheBootstrap.workerThreadsFor(9L * gibibyte, 2));
        assertEquals(4, StructureCacheBootstrap.workerThreadsFor(Long.MAX_VALUE, 8));
        assertEquals(1, StructureCacheBootstrap.workerThreadsFor(1, 1));
    }

    @Test
    void lockedCacheFallsBackWithoutPublishingAnIndex() throws Exception {
        try (var channel = java.nio.channels.FileChannel.open(temporaryDirectory.resolve("prepare.lock"),
                 java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE);
             var lock = channel.lock();
             var original = new MultiPackResourceManager(PackType.SERVER_DATA, List.of())) {
            assertTrue(lock.isValid());
            var identity = new CacheIdentity(CacheIdentity.CURRENT_FORMAT, 3955, "1.21.1", "21.1.248");
            var prepared = StructureCacheBootstrap.prepare(original, DataFixers.getDataFixer(), temporaryDirectory, identity, 1);
            assertSame(original, prepared.resourceManager());
            assertNull(prepared.generationRoot());
            assertFalse(Files.exists(temporaryDirectory.resolve("index.json")));
        }
    }

    @Test
    void vanillaPriorityFiltersAndOldGenerationSurvivePreparation() throws Exception {
        Path low = temporaryDirectory.resolve("low");
        Path high = temporaryDirectory.resolve("high");
        Path cache = temporaryDirectory.resolve("cache");
        var identity = new CacheIdentity(CacheIdentity.CURRENT_FORMAT, 3955, "1.21.1", "21.1.248");
        var selected = ResourceLocation.fromNamespaceAndPath("example", "structure/selected.nbt");
        var hidden = ResourceLocation.fromNamespaceAndPath("example", "structure/hidden.nbt");
        Files.createDirectories(low.resolve("data/example/structure"));
        Files.createDirectories(high.resolve("data/example/structure"));
        Files.write(low.resolve("data/example/structure/selected.nbt"), new byte[] {1});
        Files.write(low.resolve("data/example/structure/hidden.nbt"), new byte[] {2});
        Files.write(high.resolve("data/example/structure/selected.nbt"), new byte[] {3});
        Files.writeString(high.resolve("pack.mcmeta"), """
            {"pack":{"pack_format":48,"description":"priority fixture"},
             "filter":{"block":[{"namespace":"example","path":"structure/hidden[.]nbt"}]}}
            """);
        try (var original = new MultiPackResourceManager(PackType.SERVER_DATA, List.of(pack("low", low), pack("high", high)))) {
            assertFalse(original.getResource(hidden).isPresent());
            var first = StructureCacheBootstrap.prepare(original, DataFixers.getDataFixer(), cache, identity, 2);
            assertTrue(first.generationRoot() != null);
            try (var stream = first.resourceManager().open(selected)) {
                assertArrayEquals(new byte[] {3}, stream.readAllBytes());
            }
            assertEquals(original.listResources("structure", id -> true).keySet(),
                first.resourceManager().listResources("structure", id -> true).keySet());
            Files.write(high.resolve("data/example/structure/selected.nbt"), new byte[] {4});
            var second = StructureCacheBootstrap.prepare(original, DataFixers.getDataFixer(), cache, identity, 2);
            try (var oldStream = first.resourceManager().open(selected); var newStream = second.resourceManager().open(selected)) {
                assertArrayEquals(new byte[] {3}, oldStream.readAllBytes());
                assertArrayEquals(new byte[] {4}, newStream.readAllBytes());
            }
            StructureCacheBootstrap.retireGeneration(first.generationRoot());
            StructureCacheBootstrap.retireGeneration(second.generationRoot());
        }
    }

    private static PathPackResources pack(String id, Path root) {
        return new PathPackResources(
            new PackLocationInfo(id, Component.literal(id), PackSource.BUILT_IN, Optional.empty()),
            root
        );
    }
}
