package dev.rinchan.structuredfucache.cache;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.datafixers.DataFixer;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import java.util.stream.Stream;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
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
        targetDataVersion = SharedConstants.getCurrentVersion().getDataVersion().getVersion();
        dataFixer = DataFixers.getDataFixer();
    }

    @Test
    void upgradesLegacyStructureAndZeroWritesCurrentVersion() throws Exception {
        Path packDir = tempDir.resolve("pack");
        Path cacheRoot = tempDir.resolve("cache");
        ResourceLocation oldLoc = ResourceLocation.fromNamespaceAndPath("example", "structure/old_house.nbt");
        ResourceLocation curLoc = ResourceLocation.fromNamespaceAndPath("example", "structure/new_house.nbt");

        byte[] oldBytes = gzipStructureNbt(1500, "minecraft:stone");
        byte[] curBytes = gzipStructureNbt(targetDataVersion, "minecraft:stone");
        writePackStructure(packDir, oldLoc, oldBytes);
        writePackStructure(packDir, curLoc, curBytes);

        try (var raw = new MultiPackResourceManager(PackType.SERVER_DATA, List.of(pack("test", packDir)))) {
            var header = new CacheManifest.Header(targetDataVersion, "1.21.1", "21.1.248", "fp-1");
            ResourceManager prepared = StructureCacheBootstrap.prepare(raw, dataFixer, cacheRoot, header);

            Path oldOverlay = cacheRoot.resolve("overlay/data/example/structure/old_house.nbt");
            Path curOverlay = cacheRoot.resolve("overlay/data/example/structure/new_house.nbt");
            assertTrue(Files.isRegularFile(oldOverlay), "Old structure must be materialized in overlay");
            assertFalse(Files.exists(curOverlay), "Current-version structure must not be written to overlay");

            CompoundTag loadedOld = readResourceNbt(prepared.getResource(oldLoc).orElseThrow());
            assertEquals(targetDataVersion, NbtUtils.getDataVersion(loadedOld, -1));

            try (InputStream in = prepared.getResource(curLoc).orElseThrow().open()) {
                assertArrayEquals(curBytes, in.readAllBytes());
            }

            Map<ResourceLocation, Resource> listed = prepared.listResources("structure", loc -> loc.getPath().endsWith(".nbt"));
            assertEquals(Set.of(oldLoc, curLoc), listed.keySet());
            assertEquals(targetDataVersion, NbtUtils.getDataVersion(readResourceNbt(listed.get(oldLoc)), -1));
            assertEquals(1, prepared.getResourceStack(oldLoc).size());
            assertEquals(targetDataVersion, NbtUtils.getDataVersion(readResourceNbt(prepared.getResourceStack(oldLoc).getFirst()), -1));
        }
    }

    @Test
    void corruptAndOversizedNbtAreMarkedPassthroughAndNeverCrash() throws Exception {
        Path packDir = tempDir.resolve("pack");
        Path cacheRoot = tempDir.resolve("cache");
        ResourceLocation corruptLoc = ResourceLocation.fromNamespaceAndPath("example", "structure/corrupt.nbt");
        ResourceLocation bombLoc = ResourceLocation.fromNamespaceAndPath("example", "structure/bomb.nbt");

        byte[] corruptBytes = new byte[] {0x1f, (byte) 0x8b, 0x08, 0x00, 0x01, 0x02};
        byte[] bombBytes = gzipOversizedNbt(33 * 1024 * 1024);
        writePackStructure(packDir, corruptLoc, corruptBytes);
        writePackStructure(packDir, bombLoc, bombBytes);

        try (var raw = new MultiPackResourceManager(PackType.SERVER_DATA, List.of(pack("test", packDir)))) {
            var header1 = new CacheManifest.Header(targetDataVersion, "1.21.1", "21.1.248", "fp-1");
            ResourceManager prepared = StructureCacheBootstrap.prepare(raw, dataFixer, cacheRoot, header1);

            assertFalse(Files.exists(cacheRoot.resolve("overlay/data/example/structure/corrupt.nbt")));
            assertFalse(Files.exists(cacheRoot.resolve("overlay/data/example/structure/bomb.nbt")));

            Map<String, CacheManifest.Entry> entries = CacheManifest.readEntriesIfMatchingTarget(
                cacheRoot.resolve(CacheManifest.FILE_NAME), targetDataVersion);
            assertEquals(CacheManifest.Status.P, entries.get(corruptLoc.toString()).status());
            assertEquals(CacheManifest.Status.P, entries.get(bombLoc.toString()).status());

            try (InputStream in = prepared.getResource(corruptLoc).orElseThrow().open()) {
                assertArrayEquals(corruptBytes, in.readAllBytes());
            }
            try (InputStream in = prepared.getResource(bombLoc).orElseThrow().open()) {
                assertArrayEquals(bombBytes, in.readAllBytes());
            }

            // On fingerprint change, previous P status skips decompression completely.
            var header2 = new CacheManifest.Header(targetDataVersion, "1.21.1", "21.1.248", "fp-2");
            StructureCacheBuilder.BuildSummary summary2 = new StructureCacheBuilder(dataFixer, cacheRoot, 1).build(raw, header2);
            assertEquals(2, summary2.cacheHits());
            assertEquals(0, summary2.skippedBad());
        }
    }

    @Test
    void fastPathDoesZeroResourceListingOrReadsAndIgnoresLinesAfterHeader() throws Exception {
        Path packDir = tempDir.resolve("pack");
        Path cacheRoot = tempDir.resolve("cache");
        ResourceLocation oldLoc = ResourceLocation.fromNamespaceAndPath("example", "structure/temple.nbt");
        writePackStructure(packDir, oldLoc, gzipStructureNbt(1500, "minecraft:stone"));

        try (var raw = new MultiPackResourceManager(PackType.SERVER_DATA, List.of(pack("test", packDir)))) {
            var counting = new CountingResourceManager(raw);
            var header = new CacheManifest.Header(targetDataVersion, "1.21.1", "21.1.248", "fp-fast");

            StructureCacheBootstrap.prepare(counting, dataFixer, cacheRoot, header);
            assertEquals(1, counting.listResourcesCalls.get());
            assertEquals(1, counting.resourceOpenCalls.get());

            counting.listResourcesCalls.set(0);
            counting.resourceOpenCalls.set(0);

            // Append garbage on line 2+ to prove fast-path only reads line 1.
            Path manifest = cacheRoot.resolve(CacheManifest.FILE_NAME);
            String firstLine = Files.readAllLines(manifest, StandardCharsets.UTF_8).getFirst();
            Files.writeString(manifest, firstLine + "\ncorrupted_line_2_should_not_be_read\n", StandardCharsets.UTF_8);

            ResourceManager fastMounted = StructureCacheBootstrap.prepare(counting, dataFixer, cacheRoot, header);
            assertInstanceOf(StructureOverlayResourceManager.class, fastMounted);
            assertEquals(0, counting.listResourcesCalls.get(), "Fast-path must not call listResources");
            assertEquals(0, counting.resourceOpenCalls.get(), "Fast-path must not open any resource");

            CompoundTag upgraded = readResourceNbt(fastMounted.getResource(oldLoc).orElseThrow());
            assertEquals(targetDataVersion, NbtUtils.getDataVersion(upgraded, -1));
        }
    }

    @Test
    void incrementalRebuildReusesUnchangedAndPrunesRemovedModOverlay() throws Exception {
        Path packDir = tempDir.resolve("pack");
        Path cacheRoot = tempDir.resolve("cache");
        ResourceLocation keepLoc = ResourceLocation.fromNamespaceAndPath("mod_a", "structure/keep.nbt");
        ResourceLocation removeLoc = ResourceLocation.fromNamespaceAndPath("mod_b", "structure/gone.nbt");
        ResourceLocation addLoc = ResourceLocation.fromNamespaceAndPath("mod_c", "structure/added.nbt");

        writePackStructure(packDir, keepLoc, gzipStructureNbt(1500, "minecraft:stone"));
        writePackStructure(packDir, removeLoc, gzipStructureNbt(1500, "minecraft:oak_planks"));

        try (var raw1 = new MultiPackResourceManager(PackType.SERVER_DATA, List.of(pack("p1", packDir)))) {
            var h1 = new CacheManifest.Header(targetDataVersion, "1.21.1", "21.1.248", "fp-v1");
            StructureCacheBootstrap.prepare(raw1, dataFixer, cacheRoot, h1);
            assertTrue(Files.isRegularFile(cacheRoot.resolve("overlay/data/mod_a/structure/keep.nbt")));
            assertTrue(Files.isRegularFile(cacheRoot.resolve("overlay/data/mod_b/structure/gone.nbt")));
        }

        // Remove mod_b, add mod_c, keep mod_a untouched.
        Files.delete(packDir.resolve("data/mod_b/structure/gone.nbt"));
        writePackStructure(packDir, addLoc, gzipStructureNbt(1500, "minecraft:dirt"));

        try (var raw2 = new MultiPackResourceManager(PackType.SERVER_DATA, List.of(pack("p2", packDir)))) {
            var h2 = new CacheManifest.Header(targetDataVersion, "1.21.1", "21.1.248", "fp-v2");
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
        ResourceLocation loc = ResourceLocation.fromNamespaceAndPath("example", "structure/a.nbt");
        writePackStructure(packDir, loc, gzipStructureNbt(1500, "minecraft:stone"));

        Files.createDirectories(cacheRoot.resolve("blobs/ab"));
        Files.writeString(cacheRoot.resolve("blobs/ab/deadbeef"), "old");
        Files.createDirectories(cacheRoot.resolve("sources/cd"));
        Files.writeString(cacheRoot.resolve("sources/cd/deadbeef"), "old");
        Files.createDirectories(cacheRoot.resolve("generations/uuid-1"));
        Files.writeString(cacheRoot.resolve("generations/uuid-1/pack.mcmeta"), "{}");
        Files.writeString(cacheRoot.resolve("index.json"), "{}");

        try (var raw = new MultiPackResourceManager(PackType.SERVER_DATA, List.of(pack("test", packDir)))) {
            var header = new CacheManifest.Header(targetDataVersion, "1.21.1", "21.1.248", "fp-clean");
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
    void workerBudgetForcesSingleThreadAtSixGigHeap() {
        long giB = 1024L * 1024L * 1024L;
        assertEquals(1, StructureCacheBuilder.workerCountFor(6L * giB, 4L * giB, 16));
        assertEquals(1, StructureCacheBuilder.workerCountFor(10L * giB, 2L * giB, 16));
        assertEquals(4, StructureCacheBuilder.workerCountFor(10L * giB, 6L * giB, 16));
        assertEquals(2, StructureCacheBuilder.workerCountFor(10L * giB, 6L * giB, 2));
    }

    private static PackResources pack(String id, Path root) {
        return new PathPackResources(
            new PackLocationInfo(id, Component.literal(id), PackSource.BUILT_IN, Optional.empty()),
            root
        );
    }

    private static void writePackStructure(Path packDir, ResourceLocation loc, byte[] bytes) throws IOException {
        Path file = packDir.resolve("data").resolve(loc.getNamespace()).resolve(loc.getPath());
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
            return NbtIo.readCompressed(new ByteArrayInputStream(in.readAllBytes()), NbtAccounter.unlimitedHeap());
        }
    }

    private static final class CountingResourceManager implements ResourceManager {
        private final ResourceManager delegate;
        final AtomicInteger listResourcesCalls = new AtomicInteger();
        final AtomicInteger resourceOpenCalls = new AtomicInteger();

        CountingResourceManager(ResourceManager delegate) {
            this.delegate = delegate;
        }

        @Override
        public Set<String> getNamespaces() {
            return delegate.getNamespaces();
        }

        @Override
        public Optional<Resource> getResource(ResourceLocation location) {
            return delegate.getResource(location).map(this::wrapResource);
        }

        @Override
        public List<Resource> getResourceStack(ResourceLocation location) {
            return delegate.getResourceStack(location).stream().map(this::wrapResource).toList();
        }

        @Override
        public Map<ResourceLocation, Resource> listResources(String path, Predicate<ResourceLocation> filter) {
            listResourcesCalls.incrementAndGet();
            Map<ResourceLocation, Resource> raw = delegate.listResources(path, filter);
            Map<ResourceLocation, Resource> wrapped = new LinkedHashMap<>(raw.size());
            raw.forEach((loc, res) -> wrapped.put(loc, wrapResource(res)));
            return wrapped;
        }

        @Override
        public Map<ResourceLocation, List<Resource>> listResourceStacks(String path, Predicate<ResourceLocation> filter) {
            return delegate.listResourceStacks(path, filter);
        }

        @Override
        public Stream<PackResources> listPacks() {
            return delegate.listPacks();
        }

        private Resource wrapResource(Resource original) {
            return new Resource(original.source(), () -> {
                resourceOpenCalls.incrementAndGet();
                return new FilterInputStream(original.open()) {};
            });
        }
    }
}
