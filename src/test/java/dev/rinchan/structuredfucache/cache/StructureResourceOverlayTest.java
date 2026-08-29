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
    }

    private static PathPackResources pack(String id, Path root) {
        return new PathPackResources(
            new PackLocationInfo(id, Component.literal(id), PackSource.BUILT_IN, Optional.empty()),
            root
        );
    }
}
