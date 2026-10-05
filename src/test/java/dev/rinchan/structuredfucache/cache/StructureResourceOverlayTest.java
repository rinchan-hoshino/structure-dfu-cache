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
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.PackResources;
import dev.rinchan.rinlib.minecraft.StructureResources;
import net.minecraft.server.packs.resources.MultiPackResourceManager;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.datafix.DataFixers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StructureResourceOverlayTest {
    static { net.minecraft.SharedConstants.tryDetectVersion(); }
    @TempDir
    Path temporaryDirectory;

    @Test
    void materializedGenerationLoadsThroughOnlyVanillaResourceClasses() throws Exception {
        byte[] cachedBytes = new byte[] {1, 2, 3, 4};
        String location = "example:" + StructureResources.directory() + "/house.nbt";

        Path blob = temporaryDirectory.resolve("blobs/cached.nbt");
        Files.createDirectories(blob.getParent());
        Files.write(blob, cachedBytes);
        CacheIdentity identity = new CacheIdentity(CacheIdentity.CURRENT_FORMAT, 3955, "1.21.1", "21.1.248");
        CacheSnapshot snapshot = new CacheSnapshot(
            identity,
            Map.of(location, blob),
            new CacheBuildStats(1, 1, 0, 0, 0, 0L, location, 0, 0, CacheBuildStats.IndexState.NEW)
        );

        Path generation = StructureCacheBootstrap.materializeOverlay(temporaryDirectory, snapshot);
        PackResources preparedPack = pack("prepared", generation);
        MultiPackResourceManager manager = new MultiPackResourceManager(PackType.SERVER_DATA, List.of(preparedPack));

        try (var input = StructureResources.list(manager).get(location).open()) {
            assertArrayEquals(cachedBytes, input.readAllBytes());
        }
        assertTrue(Files.isRegularFile(generation.resolve("pack.mcmeta")));

        new StructureCacheBootstrap.PreparedStructureResources(manager, generation, null).close();
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
        assertTrue(prepared.operatorNotice().getString().contains("Cache unavailable"));
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
        String selected = "example:" + StructureResources.directory() + "/selected.nbt";
        String hidden = "example:" + StructureResources.directory() + "/hidden.nbt";
        Files.createDirectories(low.resolve("data/example/" + StructureResources.directory()));
        Files.createDirectories(high.resolve("data/example/" + StructureResources.directory()));
        Files.write(low.resolve("data/example/" + StructureResources.directory() + "/selected.nbt"), new byte[] {1});
        Files.write(low.resolve("data/example/" + StructureResources.directory() + "/hidden.nbt"), new byte[] {2});
        Files.write(high.resolve("data/example/" + StructureResources.directory() + "/selected.nbt"), new byte[] {3});
        Files.writeString(high.resolve("pack.mcmeta"), """
            {"pack":{"pack_format":48,"description":"priority fixture"},
             "filter":{"block":[{"namespace":"example","path":"structure/hidden[.]nbt"}]}}
            """.replace("structure/hidden", StructureResources.directory() + "/hidden"));
        try (var original = new MultiPackResourceManager(PackType.SERVER_DATA, List.of(pack("low", low), pack("high", high)))) {
            assertFalse(StructureResources.list(original).containsKey(hidden));
            var first = StructureCacheBootstrap.prepare(original, DataFixers.getDataFixer(), cache, identity, 2);
            assertTrue(first.generationRoot() != null);
            try (var stream = StructureResources.list(first.resourceManager()).get(selected).open()) {
                assertArrayEquals(new byte[] {3}, stream.readAllBytes());
            }
            assertEquals(StructureResources.list(original).keySet(),
                StructureResources.list(first.resourceManager()).keySet());
            Files.write(high.resolve("data/example/" + StructureResources.directory() + "/selected.nbt"), new byte[] {4});
            var second = StructureCacheBootstrap.prepare(original, DataFixers.getDataFixer(), cache, identity, 2);
            try (var oldStream = StructureResources.list(first.resourceManager()).get(selected).open(); var newStream = StructureResources.list(second.resourceManager()).get(selected).open()) {
                assertArrayEquals(new byte[] {3}, oldStream.readAllBytes());
                assertArrayEquals(new byte[] {4}, newStream.readAllBytes());
            }
            first.close();
            second.close();
        }
    }

    @Test
    void failedMaterializationDelegatesOnlyTheMissingBlob() throws Exception {
        Path healthy = temporaryDirectory.resolve("healthy.nbt");
        Files.write(healthy, new byte[] {1});
        String good = "example:" + StructureResources.directory() + "/good.nbt";
        String bad = "example:" + StructureResources.directory() + "/bad.nbt";
        var identity = new CacheIdentity(CacheIdentity.CURRENT_FORMAT, 3955, "1.21.1", "21.1.248");
        var original = new net.minecraft.server.packs.resources.Resource(pack("original", temporaryDirectory),
            () -> new java.io.ByteArrayInputStream(new byte[] {2}));
        var delegated = new java.util.HashMap<String, net.minecraft.server.packs.resources.Resource>();
        var snapshot = new CacheSnapshot(identity, Map.of(good, healthy, bad, temporaryDirectory.resolve("missing")),
            new CacheBuildStats(2, 2, 0, 0, 0, 0, "none", 0, 0, CacheBuildStats.IndexState.NEW));
        Path generation = StructureCacheBootstrap.materializeOverlay(temporaryDirectory.resolve("cache"), snapshot,
            Map.of(bad, original), delegated);
        try (var manager = StructureResources.overlay(generation, "prepared", delegated)) {
            assertEquals(java.util.Set.of(good, bad), StructureResources.list(manager).keySet());
            try (var input = StructureResources.list(manager).get(bad).open()) {
                assertArrayEquals(new byte[] {2}, input.readAllBytes());
            }
        }
        assertEquals(java.util.Set.of(bad), delegated.keySet());
    }

    @Test
    void repairedIndexNoticeClearsAfterHealthyPreparation() throws Exception {
        Path cache = temporaryDirectory.resolve("cache");
        Files.createDirectories(cache);
        Files.writeString(cache.resolve("index.json"), "damaged");
        var identity = new CacheIdentity(CacheIdentity.CURRENT_FORMAT, 3955, "1.21.1", "21.1.248");
        Path resources = temporaryDirectory.resolve("resources");
        Path file = StructureResources.resourcePath(resources, "example:" + StructureResources.directory() + "/current.nbt");
        Files.createDirectories(file.getParent());
        var tag = new net.minecraft.nbt.CompoundTag();
        net.minecraft.nbt.NbtUtils.addCurrentDataVersion(tag);
        try (var output = Files.newOutputStream(file)) { net.minecraft.nbt.NbtIo.writeCompressed(tag, output); }
        try (var original = new MultiPackResourceManager(PackType.SERVER_DATA, List.of(pack("original", resources)))) {
            var repaired = StructureCacheBootstrap.prepare(original, DataFixers.getDataFixer(), cache, identity, 1);
            assertTrue(repaired.operatorNotice().getString().contains("damaged cache index"));
            repaired.close();
            var healthy = StructureCacheBootstrap.prepare(original, DataFixers.getDataFixer(), cache, identity, 1);
            assertNull(healthy.operatorNotice());
            healthy.close();
        }
    }

    private static PackResources pack(String id, Path root) {
        return StructureResources.pack(root, id);
    }
}
