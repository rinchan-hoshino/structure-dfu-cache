package dev.rinchan.structuredfucache.cache;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.datafixers.DataFixer;
import dev.rinchan.structuredfucache.compat.McCompat;
import dev.rinchan.structuredfucache.config.StructureDfuCacheConfig;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.PathPackResources;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.resources.MultiPackResourceManager;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.datafix.DataFixers;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StructureDfuCacheV2Test {
    private static int targetDataVersion;
    private static DataFixer dataFixer;

    @TempDir
    Path tempDir;

    @BeforeAll
    static void initMinecraftConstants() {
        SharedConstants.tryDetectVersion();
        targetDataVersion = McCompat.currentDataVersion();
        dataFixer = DataFixers.getDataFixer();
    }

    @Test
    void upgradesLegacyStructureAndZeroWritesCurrentVersion() throws Exception {
        Path packDir = tempDir.resolve("pack");
        Path cacheRoot = tempDir.resolve("cache");
        StructureKey oldLoc = new StructureKey("example", "structure/old_house.nbt");
        StructureKey curLoc = new StructureKey("example", "structure/new_house.nbt");

        byte[] oldBytes = gzipStructureNbt(1500, "minecraft:stone");
        byte[] curBytes = gzipStructureNbt(targetDataVersion, "minecraft:stone");
        writePackStructure(packDir, oldLoc, oldBytes);
        writePackStructure(packDir, curLoc, curBytes);

        try (var raw = new MultiPackResourceManager(PackType.SERVER_DATA, List.of(pack("test", packDir)))) {
            var header = new CacheManifest.Header(targetDataVersion, "1.21.1", "neoforge:21.1.250", "fp-1");
            ResourceManager prepared = StructureCacheBootstrap.prepare(raw, dataFixer, cacheRoot, header);

            Path oldOverlay = cacheRoot.resolve("overlay/data/example/structure/old_house.nbt");
            Path curOverlay = cacheRoot.resolve("overlay/data/example/structure/new_house.nbt");
            assertTrue(Files.isRegularFile(oldOverlay), "Old structure must be materialized in overlay");
            assertFalse(Files.exists(curOverlay), "Current-version structure must not be written to overlay");

            CompoundTag loadedOld = readResourceNbt(TestResourceAdapter.getResource(prepared, oldLoc).orElseThrow());
            assertEquals(targetDataVersion, NbtUtils.getDataVersion(loadedOld, -1));

            try (InputStream in = TestResourceAdapter.getResource(prepared, curLoc).orElseThrow().open()) {
                assertArrayEquals(curBytes, in.readAllBytes());
            }

            Map<StructureKey, Resource> listed = McCompat.listStructureResources(prepared);
            assertEquals(Set.of(oldLoc, curLoc), listed.keySet());
            assertEquals(targetDataVersion, NbtUtils.getDataVersion(readResourceNbt(listed.get(oldLoc)), -1));
            List<Resource> stack = TestResourceAdapter.getResourceStack(prepared, oldLoc);
            assertEquals(1, stack.size());
            assertEquals(targetDataVersion, NbtUtils.getDataVersion(readResourceNbt(stack.get(0)), -1));
        }
    }

    @Test
    void corruptAndOversizedNbtAreMarkedPassthroughAndNeverCrash() throws Exception {
        Path packDir = tempDir.resolve("pack");
        Path cacheRoot = tempDir.resolve("cache");
        StructureKey corruptLoc = new StructureKey("example", "structure/corrupt.nbt");
        StructureKey bombLoc = new StructureKey("example", "structure/bomb.nbt");

        byte[] corruptBytes = new byte[] {0x1f, (byte) 0x8b, 0x08, 0x00, 0x01, 0x02};
        byte[] bombBytes = gzipOversizedNbt(33 * 1024 * 1024);
        writePackStructure(packDir, corruptLoc, corruptBytes);
        writePackStructure(packDir, bombLoc, bombBytes);

        try (var raw = new MultiPackResourceManager(PackType.SERVER_DATA, List.of(pack("test", packDir)))) {
            var header1 = new CacheManifest.Header(targetDataVersion, "1.21.1", "neoforge:21.1.250", "fp-1");
            ResourceManager prepared = StructureCacheBootstrap.prepare(raw, dataFixer, cacheRoot, header1);

            assertFalse(Files.exists(cacheRoot.resolve("overlay/data/example/structure/corrupt.nbt")));
            assertFalse(Files.exists(cacheRoot.resolve("overlay/data/example/structure/bomb.nbt")));

            Map<String, CacheManifest.Entry> entries = CacheManifest.readEntriesIfMatchingTarget(
                cacheRoot.resolve(CacheManifest.FILE_NAME), targetDataVersion);
            assertEquals(CacheManifest.Status.P, entries.get(corruptLoc.toString()).status());
            assertEquals(CacheManifest.Status.P, entries.get(bombLoc.toString()).status());

            try (InputStream in = TestResourceAdapter.getResource(prepared, corruptLoc).orElseThrow().open()) {
                assertArrayEquals(corruptBytes, in.readAllBytes());
            }
            try (InputStream in = TestResourceAdapter.getResource(prepared, bombLoc).orElseThrow().open()) {
                assertArrayEquals(bombBytes, in.readAllBytes());
            }

            // On fingerprint change, previous P status skips decompression completely.
            var header2 = new CacheManifest.Header(targetDataVersion, "1.21.1", "neoforge:21.1.250", "fp-2");
            StructureCacheBuilder.BuildSummary summary2 = new StructureCacheBuilder(dataFixer, cacheRoot, 1).build(raw, header2);
            assertEquals(2, summary2.cacheHits());
            assertEquals(0, summary2.skippedBad());
        }
    }

    @Test
    void fastPathDoesZeroResourceListingOrReadsAndIgnoresLinesAfterHeader() throws Exception {
        Path packDir = tempDir.resolve("pack");
        Path cacheRoot = tempDir.resolve("cache");
        StructureKey oldLoc = new StructureKey("example", "structure/temple.nbt");
        writePackStructure(packDir, oldLoc, gzipStructureNbt(1500, "minecraft:stone"));

        try (var raw = new MultiPackResourceManager(PackType.SERVER_DATA, List.of(pack("test", packDir)))) {
            var counting = TestResourceAdapter.counting(raw);
            var header = new CacheManifest.Header(targetDataVersion, "1.21.1", "neoforge:21.1.250", "fp-fast");

            StructureCacheBootstrap.prepare(counting, dataFixer, cacheRoot, header);
            assertEquals(1, counting.listResourcesCalls.get());
            assertEquals(1, counting.resourceOpenCalls.get());

            counting.listResourcesCalls.set(0);
            counting.resourceOpenCalls.set(0);

            // Append garbage on line 2+ to prove fast-path only reads line 1.
            Path manifest = cacheRoot.resolve(CacheManifest.FILE_NAME);
            String firstLine = Files.readAllLines(manifest, StandardCharsets.UTF_8).get(0);
            Files.writeString(manifest, firstLine + "\ncorrupted_line_2_should_not_be_read\n", StandardCharsets.UTF_8);

            ResourceManager fastMounted = StructureCacheBootstrap.prepare(counting, dataFixer, cacheRoot, header);
            assertInstanceOf(StructureOverlayResourceManager.class, fastMounted);
            assertEquals(0, counting.listResourcesCalls.get(), "Fast-path must not call listResources");
            assertEquals(0, counting.resourceOpenCalls.get(), "Fast-path must not open any resource");

            CompoundTag upgraded = readResourceNbt(TestResourceAdapter.getResource(fastMounted, oldLoc).orElseThrow());
            assertEquals(targetDataVersion, NbtUtils.getDataVersion(upgraded, -1));
        }
    }

    @Test
    void incrementalRebuildReusesUnchangedAndPrunesRemovedModOverlay() throws Exception {
        Path packDir = tempDir.resolve("pack");
        Path cacheRoot = tempDir.resolve("cache");
        StructureKey keepLoc = new StructureKey("mod_a", "structure/keep.nbt");
        StructureKey removeLoc = new StructureKey("mod_b", "structure/gone.nbt");
        StructureKey addLoc = new StructureKey("mod_c", "structure/added.nbt");

        writePackStructure(packDir, keepLoc, gzipStructureNbt(1500, "minecraft:stone"));
        writePackStructure(packDir, removeLoc, gzipStructureNbt(1500, "minecraft:oak_planks"));

        try (var raw1 = new MultiPackResourceManager(PackType.SERVER_DATA, List.of(pack("p1", packDir)))) {
            var h1 = new CacheManifest.Header(targetDataVersion, "1.21.1", "neoforge:21.1.250", "fp-v1");
            StructureCacheBootstrap.prepare(raw1, dataFixer, cacheRoot, h1);
            assertTrue(Files.isRegularFile(cacheRoot.resolve("overlay/data/mod_a/structure/keep.nbt")));
            assertTrue(Files.isRegularFile(cacheRoot.resolve("overlay/data/mod_b/structure/gone.nbt")));
        }

        // Remove mod_b, add mod_c, keep mod_a untouched.
        Files.delete(packDir.resolve("data/mod_b/structure/gone.nbt"));
        writePackStructure(packDir, addLoc, gzipStructureNbt(1500, "minecraft:dirt"));

        try (var raw2 = new MultiPackResourceManager(PackType.SERVER_DATA, List.of(pack("p2", packDir)))) {
            var h2 = new CacheManifest.Header(targetDataVersion, "1.21.1", "neoforge:21.1.250", "fp-v2");
            StructureCacheBuilder.BuildSummary summary = new StructureCacheBuilder(dataFixer, cacheRoot, 1).build(raw2, h2);
            assertEquals(1, summary.cacheHits(), "Unchanged mod_a structure must hit cache");
            assertEquals(1, summary.converted(), "New mod_c structure must be converted");
            assertTrue(Files.isRegularFile(cacheRoot.resolve("overlay/data/mod_a/structure/keep.nbt")));
            assertTrue(Files.isRegularFile(cacheRoot.resolve("overlay/data/mod_c/structure/added.nbt")));
            assertFalse(Files.exists(cacheRoot.resolve("overlay/data/mod_b/structure/gone.nbt")), "Orphan overlay file must be pruned");
            assertFalse(Files.exists(cacheRoot.resolve("overlay/data/mod_b")), "Empty orphan namespace dir must be pruned");
        }
    }

    @Test
    void cleansLegacy1xArtifactsAndUnwrapsRepeatedReloads() throws Exception {
        Path packDir = tempDir.resolve("pack");
        Path cacheRoot = tempDir.resolve("cache");
        StructureKey loc = new StructureKey("example", "structure/a.nbt");
        writePackStructure(packDir, loc, gzipStructureNbt(1500, "minecraft:stone"));

        Files.createDirectories(cacheRoot.resolve("blobs/ab"));
        Files.writeString(cacheRoot.resolve("blobs/ab/deadbeef"), "old");
        Files.createDirectories(cacheRoot.resolve("sources/cd"));
        Files.writeString(cacheRoot.resolve("sources/cd/deadbeef"), "old");
        Files.createDirectories(cacheRoot.resolve("generations/uuid-1"));
        Files.writeString(cacheRoot.resolve("generations/uuid-1/pack.mcmeta"), "{}");
        Files.writeString(cacheRoot.resolve("index.json"), "{}");

        try (var raw = new MultiPackResourceManager(PackType.SERVER_DATA, List.of(pack("test", packDir)))) {
            var header = new CacheManifest.Header(targetDataVersion, "1.21.1", "neoforge:21.1.250", "fp-clean");
            ResourceManager first = StructureCacheBootstrap.prepare(raw, dataFixer, cacheRoot, header);

            assertFalse(Files.exists(cacheRoot.resolve("blobs")));
            assertFalse(Files.exists(cacheRoot.resolve("sources")));
            assertFalse(Files.exists(cacheRoot.resolve("generations")));
            assertFalse(Files.exists(cacheRoot.resolve("index.json")));

            ResourceManager second = StructureCacheBootstrap.prepare(first, dataFixer, cacheRoot, header);
            StructureOverlayResourceManager overlay = assertInstanceOf(StructureOverlayResourceManager.class, second);
            assertSame(raw, overlay.delegate(), "Repeated /reload must unwrap instead of nesting wrappers");
        }
    }

    @Test
    void workerBudgetForcesSingleThreadAtSixGigHeapAndRespectsExplicitConfig() {
        long giB = 1024L * 1024L * 1024L;
        assertEquals(1, StructureCacheBuilder.workerCountFor(6L * giB, 4L * giB, 16));
        assertEquals(1, StructureCacheBuilder.workerCountFor(10L * giB, 2L * giB, 16));
        assertEquals(4, StructureCacheBuilder.workerCountFor(10L * giB, 6L * giB, 16));
        assertEquals(2, StructureCacheBuilder.workerCountFor(10L * giB, 6L * giB, 2));

        assertEquals(6, StructureCacheBuilder.resolveWorkers(6));
        assertEquals(8, StructureCacheBuilder.resolveWorkers(99));
        assertTrue(StructureCacheBuilder.resolveWorkers(0) >= 1);
    }

    @Test
    void configRoundTripsAndDisabledMasterSwitchBypassesOverlay() throws Exception {
        Path configFile = tempDir.resolve("config/structure_dfu_cache.toml");
        StructureDfuCacheConfig written = new StructureDfuCacheConfig(false, 5, true);
        StructureDfuCacheConfig.writeTo(configFile, written);

        StructureDfuCacheConfig readBack = StructureDfuCacheConfig.readFrom(configFile);
        assertEquals(written, readBack);
        assertEquals(8, new StructureDfuCacheConfig(true, 42, false).maxWorkers());
        assertEquals(0, new StructureDfuCacheConfig(true, -3, false).maxWorkers());

        Path packDir = tempDir.resolve("pack");
        Path cacheRoot = tempDir.resolve("cache");
        writePackStructure(packDir, new StructureKey("example", "structure/old.nbt"), gzipStructureNbt(1500, "minecraft:stone"));

        try (var raw = new MultiPackResourceManager(PackType.SERVER_DATA, List.of(pack("test", packDir)))) {
            var header = new CacheManifest.Header(targetDataVersion, "1.21.1", "fabric:0.19.3", "fp-off");
            ResourceManager result = StructureCacheBootstrap.prepare(raw, dataFixer, cacheRoot, header, written);
            assertSame(raw, result, "Disabled config must return vanilla ResourceManager directly");
            assertFalse(Files.exists(cacheRoot.resolve(CacheManifest.FILE_NAME)));
        }
    }

    @Test
    void packFingerprintIsDeterministicAndSensitiveToModFiles() throws Exception {
        Path gameDir = tempDir.resolve("game");
        Path modsDir = gameDir.resolve("mods");
        Files.createDirectories(modsDir);
        Path fakeMod = modsDir.resolve("sample-mod.jar");
        Files.writeString(fakeMod, "v1", StandardCharsets.UTF_8);

        Path packDir = tempDir.resolve("pack");
        writePackStructure(packDir, new StructureKey("example", "structure/a.nbt"), gzipStructureNbt(1500, "minecraft:stone"));

        try (var raw = new MultiPackResourceManager(PackType.SERVER_DATA, List.of(pack("test", packDir)))) {
            String fp1 = PackFingerprint.compute(raw, gameDir, List.of(fakeMod));
            String fp2 = PackFingerprint.compute(raw, gameDir, List.of(fakeMod));
            assertEquals(fp1, fp2, "Identical inputs must produce identical fingerprint");

            Files.writeString(fakeMod, "v2-larger-payload", StandardCharsets.UTF_8);
            String fp3 = PackFingerprint.compute(raw, gameDir, List.of(fakeMod));
            assertNotEquals(fp1, fp3, "Changing mod jar size must invalidate fingerprint");
        }
    }

    @Test
    void cacheStatsInspectsOnDiskAndClearsCacheCleanly() throws Exception {
        Path packDir = tempDir.resolve("pack");
        Path cacheRoot = tempDir.resolve("cache");
        writePackStructure(packDir, new StructureKey("example", "structure/old.nbt"), gzipStructureNbt(1500, "minecraft:stone"));
        writePackStructure(packDir, new StructureKey("example", "structure/cur.nbt"), gzipStructureNbt(targetDataVersion, "minecraft:stone"));

        try (var raw = new MultiPackResourceManager(PackType.SERVER_DATA, List.of(pack("test", packDir)))) {
            var header = new CacheManifest.Header(targetDataVersion, "1.21.1", "neoforge:21.1.250", "fp-stats");
            StructureCacheBootstrap.prepare(raw, dataFixer, cacheRoot, header);
        }

        // Simulate fresh launch before world load (IDLE runtime, reading stats directly from disk).
        CacheStats.resetRuntime();
        CacheStats.Snapshot diskSnap = CacheStats.inspect(cacheRoot);
        assertEquals(CacheStats.Mode.IDLE, diskSnap.mode());
        assertEquals(1, diskSnap.convertedCount());
        assertEquals(1, diskSnap.passthroughCount());
        assertEquals(1, diskSnap.overlayFiles());
        assertTrue(diskSnap.overlayBytes() > 0L);

        CacheStats.ClearResult cleared = CacheStats.clearCache(cacheRoot);
        assertTrue(cleared.cleared());
        assertFalse(cleared.locked());
        assertTrue(cleared.removedFiles() >= 2);
        assertTrue(cleared.freedBytes() > 0L);

        CacheStats.Snapshot afterClear = CacheStats.inspect(cacheRoot);
        assertEquals(0, afterClear.convertedCount());
        assertEquals(0, afterClear.passthroughCount());
        assertEquals(0, afterClear.overlayFiles());
        assertEquals(0L, afterClear.overlayBytes());
    }

    private static PackResources pack(String id, Path root) {
        return new PathPackResources(
            new PackLocationInfo(id, Component.literal(id), PackSource.BUILT_IN, Optional.empty()),
            root
        );
    }

    private static void writePackStructure(Path packDir, StructureKey loc, byte[] bytes) throws IOException {
        Path file = packDir.resolve("data").resolve(loc.namespace()).resolve(loc.path());
        Files.createDirectories(file.getParent());
        Files.write(file, bytes);
        Path mcmeta = packDir.resolve("pack.mcmeta");
        if (!Files.exists(mcmeta)) {
            Files.writeString(mcmeta, "{\"pack\":{\"pack_format\":48,\"description\":\"test\"}}\n");
        }
    }

    private static byte[] gzipStructureNbt(int dataVersion, String blockName) throws IOException {
        CompoundTag root = new CompoundTag();
        root.putInt("DataVersion", dataVersion);

        ListTag size = new ListTag();
        size.add(IntTag.valueOf(1));
        size.add(IntTag.valueOf(1));
        size.add(IntTag.valueOf(1));
        root.put("size", size);

        ListTag palette = new ListTag();
        CompoundTag state = new CompoundTag();
        state.putString("Name", blockName);
        palette.add(state);
        root.put("palette", palette);

        ListTag blocks = new ListTag();
        CompoundTag block = new CompoundTag();
        ListTag pos = new ListTag();
        pos.add(IntTag.valueOf(0));
        pos.add(IntTag.valueOf(0));
        pos.add(IntTag.valueOf(0));
        block.put("pos", pos);
        block.putInt("state", 0);
        blocks.add(block);
        root.put("blocks", blocks);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        NbtIo.writeCompressed(root, out);
        return out.toByteArray();
    }

    private static byte[] gzipOversizedNbt(int payloadBytes) throws IOException {
        CompoundTag root = new CompoundTag();
        root.putInt("DataVersion", 1500);
        ListTag bigList = new ListTag();
        String chunk = "x".repeat(65536);
        int count = (payloadBytes / 65536) + 2;
        for (int i = 0; i < count; i++) {
            bigList.add(StringTag.valueOf(chunk));
        }
        root.put("junk", bigList);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        NbtIo.writeCompressed(root, out);
        return out.toByteArray();
    }

    private static CompoundTag readResourceNbt(Resource resource) throws IOException {
        try (InputStream in = resource.open()) {
            return McCompat.readCompressedNbt(in.readAllBytes(), 128L * 1024L * 1024L);
        }
    }
}
