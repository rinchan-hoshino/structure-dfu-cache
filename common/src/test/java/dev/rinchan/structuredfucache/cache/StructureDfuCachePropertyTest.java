package dev.rinchan.structuredfucache.cache;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.datafixers.DataFixer;
import dev.rinchan.structuredfucache.compat.McCompat;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.ProviderMismatchException;
import java.nio.file.spi.FileSystemProvider;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
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
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.datafix.DataFixers;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Gate 2: Fixed-seed property & fuzz invariant suite (>= 1,200 randomized iterations).
 */
class StructureDfuCachePropertyTest {
    private static int targetDataVersion;
    private static DataFixer dataFixer;
    private static byte[] legacyStructureBytes;
    private static byte[] currentStructureBytes;
    private static byte[] oversizedBombBytes;

    @TempDir
    Path tempDir;

    @BeforeAll
    static void initMinecraftConstants() throws IOException {
        SharedConstants.tryDetectVersion();
        targetDataVersion = McCompat.currentDataVersion();
        dataFixer = DataFixers.getDataFixer();
        legacyStructureBytes = gzipStructureNbt(1500, "minecraft:stone");
        currentStructureBytes = gzipStructureNbt(targetDataVersion, "minecraft:stone");
        oversizedBombBytes = gzipOversizedNbt(33 * 1024 * 1024);
    }

    @Test
    void manifestCorruptionTruncationAndVersionJumpAlwaysRecoverSafely() throws Exception {
        Random rng = new Random(0x5DFC0001L);
        Path packDir = tempDir.resolve("manifest-fuzz-pack");
        StructureKey legacyLoc = new StructureKey("fuzz", "structure/old.nbt");
        StructureKey currentLoc = new StructureKey("fuzz", "structure/cur.nbt");
        writePackStructure(packDir, legacyLoc, legacyStructureBytes);
        writePackStructure(packDir, currentLoc, currentStructureBytes);

        Path cacheRoot = tempDir.resolve("manifest-fuzz-cache");
        Path manifestFile = cacheRoot.resolve(CacheManifest.FILE_NAME);
        Files.createDirectories(cacheRoot);

        try (var raw = new MultiPackResourceManager(PackType.SERVER_DATA, List.of(pack("fuzz", packDir)))) {
            for (int i = 0; i < 400; i++) {
                byte[] mutated = randomManifestBytes(rng, i);
                Files.write(manifestFile, mutated);

                // Low-level readers must never throw uncaught exceptions even on raw invalid UTF-8 / truncated bytes.
                assertDoesNotThrow(() -> CacheManifest.readHeaderOnly(manifestFile), "iteration " + i);
                assertDoesNotThrow(() -> CacheManifest.inspectCounts(manifestFile), "iteration " + i);
                assertDoesNotThrow(() -> CacheManifest.readEntriesIfMatchingTarget(manifestFile, targetDataVersion), "iteration " + i);

                // Every 20th iteration, also assert full Bootstrap recovery rebuilds a valid overlay & manifest.
                if (i % 20 == 0) {
                    var header = new CacheManifest.Header(targetDataVersion, "1.21.1", "neoforge:21.1.250", "fp-rec-" + i);
                    ResourceManager prepared = StructureCacheBootstrap.prepare(raw, dataFixer, cacheRoot, header);
                    assertTrue(Files.isRegularFile(cacheRoot.resolve("overlay/data/fuzz/structure/old.nbt")),
                        "Bootstrap must recover and materialize legacy structure after corrupt manifest #" + i);
                    assertFalse(Files.exists(cacheRoot.resolve("overlay/data/fuzz/structure/cur.nbt")),
                        "Current structure must stay passthrough after recovery #" + i);
                    Optional<CacheManifest.Header> recoveredHeader = CacheManifest.readHeaderOnly(manifestFile);
                    assertEquals(Optional.of(header), recoveredHeader, "Manifest header must be rewritten cleanly #" + i);
                    CompoundTag loaded = readResourceNbt(TestResourceAdapter.getResource(prepared, legacyLoc).orElseThrow());
                    assertEquals(targetDataVersion, NbtUtils.getDataVersion(loaded, -1));
                }
            }
        }
    }

    @Test
    void orphanFilesVersionJumpsAndIncrementalMutationsPreserveExactOverlayInvariant() throws Exception {
        Random rng = new Random(0x5DFC0002L);
        Path cacheRoot = tempDir.resolve("orphan-fuzz-cache");
        Files.createDirectories(cacheRoot);

        for (int i = 0; i < 250; i++) {
            Path packDir = tempDir.resolve("orphan-pack-" + i);
            Map<StructureKey, Boolean> expectedConverted = new HashMap<>();

            int structCount = 1 + rng.nextInt(4);
            for (int s = 0; s < structCount; s++) {
                String ns = "ns" + rng.nextInt(3);
                StructureKey key = new StructureKey(ns, "structure/item_" + s + ".nbt");
                boolean isLegacy = rng.nextBoolean();
                writePackStructure(packDir, key, isLegacy ? legacyStructureBytes : currentStructureBytes);
                expectedConverted.put(key, isLegacy);
            }

            // Plant random orphan files & empty directories inside overlay/data before rebuild.
            Path overlayData = cacheRoot.resolve("overlay/data");
            int orphanCount = rng.nextInt(4);
            for (int o = 0; o < orphanCount; o++) {
                Path orphan = overlayData.resolve("orphan_" + i + "_" + o).resolve("structure/stale_" + o + ".nbt");
                Files.createDirectories(orphan.getParent());
                Files.write(orphan, new byte[] {1, 2, 3, (byte) o});
            }

            // Occasionally corrupt manifest version or delete an existing C overlay file to force self-healing.
            if (rng.nextInt(3) == 0) {
                int bogusVersion = rng.nextBoolean() ? targetDataVersion - 10 : targetDataVersion + 10;
                CacheManifest.writeAtomic(
                    cacheRoot.resolve(CacheManifest.FILE_NAME),
                    new CacheManifest.Header(bogusVersion, "1.20.1", "forge:47", "old-fp-" + i),
                    Map.of("ns0:structure/item_0.nbt", new CacheManifest.Entry("deadbeef", CacheManifest.Status.C))
                );
            }

            try (var raw = new MultiPackResourceManager(PackType.SERVER_DATA, List.of(pack("p" + i, packDir)))) {
                var header = new CacheManifest.Header(targetDataVersion, "1.21.1", "neoforge:21.1.250", "fp-iter-" + i);
                StructureCacheBootstrap.prepare(raw, dataFixer, cacheRoot, header);

                // Invariant 1: Every legacy structure exists in overlay; every current structure does NOT.
                long expectedOverlayFiles = 0;
                for (Map.Entry<StructureKey, Boolean> e : expectedConverted.entrySet()) {
                    Path target = overlayData.resolve(e.getKey().namespace()).resolve(e.getKey().path());
                    if (e.getValue()) {
                        assertTrue(Files.isRegularFile(target), "Expected overlay for " + e.getKey() + " at iter " + i);
                        expectedOverlayFiles++;
                    } else {
                        assertFalse(Files.exists(target), "Passthrough must not write overlay for " + e.getKey() + " at iter " + i);
                    }
                }

                // Invariant 2: Total files on disk in overlay/data MUST equal exact converted count (zero orphan leaks).
                long actualOverlayFiles;
                try (var walk = Files.walk(overlayData)) {
                    actualOverlayFiles = walk.filter(Files::isRegularFile).count();
                }
                assertEquals(expectedOverlayFiles, actualOverlayFiles, "Orphan files leaked at iter " + i);
            }
        }
    }

    @Test
    void corruptTruncatedAndOversizedStreamsNeverThrowAndAlwaysPassthrough() throws Exception {
        Random rng = new Random(0x5DFC0003L);
        Path cacheRoot = tempDir.resolve("stream-fuzz-cache");
        Path packDir = tempDir.resolve("stream-fuzz-pack");

        // Include a deterministic >32 MiB decoded NBT bomb alongside 150 randomized corrupt/truncated streams.
        StructureKey bombKey = new StructureKey("bomb", "structure/over_32mib.nbt");
        writePackStructure(packDir, bombKey, oversizedBombBytes);

        List<StructureKey> corruptKeys = new ArrayList<>();
        for (int i = 0; i < 150; i++) {
            StructureKey key = new StructureKey("bad", "structure/corrupt_" + i + ".nbt");
            byte[] payload = randomCorruptPayload(rng, i);
            writePackStructure(packDir, key, payload);
            corruptKeys.add(key);
        }

        try (var raw = new MultiPackResourceManager(PackType.SERVER_DATA, List.of(pack("badpack", packDir)))) {
            var header = new CacheManifest.Header(targetDataVersion, "1.21.1", "neoforge:21.1.250", "fp-stream-fuzz");
            ResourceManager prepared = assertDoesNotThrow(
                () -> StructureCacheBootstrap.prepare(raw, dataFixer, cacheRoot, header)
            );
            assertNotNull(prepared);

            Map<String, CacheManifest.Entry> entries = CacheManifest.readEntriesIfMatchingTarget(
                cacheRoot.resolve(CacheManifest.FILE_NAME), targetDataVersion);
            assertEquals(CacheManifest.Status.P, entries.get(bombKey.toString()).status());
            assertFalse(Files.exists(cacheRoot.resolve("overlay/data/bomb/structure/over_32mib.nbt")));

            for (StructureKey key : corruptKeys) {
                CacheManifest.Entry entry = entries.get(key.toString());
                assertNotNull(entry, "Missing manifest entry for " + key);
                assertEquals(CacheManifest.Status.P, entry.status(), "Corrupt payload must be marked P: " + key);
                assertFalse(Files.exists(cacheRoot.resolve("overlay/data").resolve(key.namespace()).resolve(key.path())));
            }
        }
    }

    @Test
    void heterogeneousFileSystemAndUnionPathFuzzingNeverThrowsAndStaysDeterministic() throws Exception {
        Random rng = new Random(0x5DFC0004L);
        Path gameDir = tempDir.resolve("fs-fuzz-game");
        Path modsDir = gameDir.resolve("mods");
        Files.createDirectories(modsDir);
        Path realMod = modsDir.resolve("real-mod.jar");
        Files.writeString(realMod, "real-mod-payload", StandardCharsets.UTF_8);

        Path packDir = tempDir.resolve("fs-fuzz-pack");
        writePackStructure(packDir, new StructureKey("example", "structure/a.nbt"), legacyStructureBytes);

        try (var raw = new MultiPackResourceManager(PackType.SERVER_DATA, List.of(pack("fs", packDir)))) {
            for (int i = 0; i < 400; i++) {
                int pathCount = 1 + rng.nextInt(6);
                List<Path> modPaths = new ArrayList<>(pathCount);
                for (int p = 0; p < pathCount; p++) {
                    int mode = rng.nextInt(7);
                    switch (mode) {
                        case 0 -> modPaths.add(null);
                        case 1 -> modPaths.add(realMod);
                        case 2 -> modPaths.add(foreignPath(rng, "union", "/mods/balm.jar!/META-INF/jarjar/dep_" + p + ".jar", true, false, false));
                        case 3 -> modPaths.add(foreignPath(rng, "jimfs", "/virtual/mod_" + i + "_" + p + ".jar", false, true, false));
                        case 4 -> modPaths.add(foreignPath(rng, "jar", "nested/archive_" + p + ".jar", true, true, true));
                        case 5 -> modPaths.add(gameDir.resolve("missing_" + p + ".jar"));
                        default -> modPaths.add(foreignPath(rng, "s3", "bucket/mod_" + p + ".jar", false, false, true));
                    }
                }

                String fp1 = assertDoesNotThrow(() -> PackFingerprint.compute(raw, gameDir, modPaths), "iter " + i);
                String fp2 = assertDoesNotThrow(() -> PackFingerprint.compute(raw, gameDir, modPaths), "iter " + i);
                assertEquals(64, fp1.length(), "SHA-256 hex length must be 64");
                assertEquals(fp1, fp2, "PackFingerprint must be deterministic at iter " + i);
            }
        }
    }

    private static byte[] randomManifestBytes(Random rng, int iteration) {
        int kind = iteration % 8;
        return switch (kind) {
            case 0 -> new byte[0];
            case 1 -> {
                // Invalid UTF-8 binary garbage (tests MalformedInputException resilience!)
                byte[] garbage = new byte[1 + rng.nextInt(64)];
                rng.nextBytes(garbage);
                garbage[0] = (byte) 0xFF;
                yield garbage;
            }
            case 2 -> "#v2\tnot_an_int\t1.21.1\tneoforge\tfp\n".getBytes(StandardCharsets.UTF_8);
            case 3 -> ("#v2\t" + (targetDataVersion - 1 - rng.nextInt(500)) + "\t1.20.1\tforge\tfp\nfoo:bar\tabc\tC\n")
                .getBytes(StandardCharsets.UTF_8);
            case 4 -> ("#v2\t" + targetDataVersion + "\t1.21.1\tneoforge\tfp\n"
                + "broken_line_no_tabs\n"
                + "a\tb\tX\n"
                + "c\td\tC\textra\n"
                + "fuzz:structure/old.nbt\tdeadbeef\tC\n").getBytes(StandardCharsets.UTF_8);
            case 5 -> "#v1\t3955\t1.21.1\tneoforge\tfp\n".getBytes(StandardCharsets.UTF_8);
            case 6 -> ("#v2\t" + targetDataVersion + "\t1.21.1").getBytes(StandardCharsets.UTF_8);
            default -> {
                // Truncated valid-looking manifest
                String full = "#v2\t" + targetDataVersion + "\t1.21.1\tneoforge:21.1.250\tfp\n"
                    + "fuzz:structure/old.nbt\t0123456789abcdef\tC\n";
                int cut = rng.nextInt(full.length());
                yield full.substring(0, cut).getBytes(StandardCharsets.UTF_8);
            }
        };
    }

    private static byte[] randomCorruptPayload(Random rng, int iteration) {
        int kind = iteration % 5;
        return switch (kind) {
            case 0 -> new byte[0];
            case 1 -> {
                // Truncated GZIP header
                byte[] b = new byte[2 + rng.nextInt(12)];
                rng.nextBytes(b);
                b[0] = 0x1f;
                b[1] = (byte) 0x8b;
                yield b;
            }
            case 2 -> {
                // Truncated real compressed NBT (cut well inside the deflate payload, not merely the 8-byte GZIP trailer)
                int maxCut = Math.max(1, legacyStructureBytes.length - 16);
                int cut = 1 + rng.nextInt(maxCut);
                byte[] truncated = new byte[cut];
                System.arraycopy(legacyStructureBytes, 0, truncated, 0, cut);
                yield truncated;
            }
            case 3 -> {
                // Valid GZIP NBT, but not a structure template (missing palette/blocks/size)
                try {
                    CompoundTag nonStruct = new CompoundTag();
                    nonStruct.putInt("DataVersion", 1500);
                    nonStruct.putString("hello", "world-" + iteration);
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    NbtIo.writeCompressed(nonStruct, out);
                    yield out.toByteArray();
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
            default -> {
                byte[] random = new byte[1 + rng.nextInt(128)];
                rng.nextBytes(random);
                yield random;
            }
        };
    }

    private static Path foreignPath(
        Random rng,
        String scheme,
        String displayPath,
        boolean nullRealPath,
        boolean throwOnNormalize,
        boolean nullFileSystem
    ) {
        // Use minimal FileSystem subclass only when non-null.
        FileSystem fs = nullFileSystem ? null : new MinimalForeignFileSystem(scheme);
        return (Path) Proxy.newProxyInstance(
            Path.class.getClassLoader(),
            new Class<?>[] {Path.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "getFileSystem" -> fs;
                case "toRealPath" -> {
                    if (nullRealPath) {
                        yield null;
                    }
                    throw new ProviderMismatchException("foreign realpath");
                }
                case "toAbsolutePath", "normalize" -> {
                    if (throwOnNormalize) {
                        throw new UnsupportedOperationException("foreign normalize");
                    }
                    yield proxy;
                }
                case "startsWith", "relativize" -> throw new ProviderMismatchException("cross-fs");
                case "getFileName" -> rng.nextBoolean() ? null : proxy;
                case "toString" -> displayPath;
                case "equals" -> args != null && args.length == 1 && proxy == args[0];
                case "hashCode" -> System.identityHashCode(proxy);
                default -> null;
            }
        );
    }

    private static final class MinimalForeignFileSystem extends FileSystem {
        private final FileSystemProvider provider;

        MinimalForeignFileSystem(String scheme) {
            this.provider = new FileSystemProvider() {
                @Override public String getScheme() { return scheme; }
                @Override public FileSystem newFileSystem(java.net.URI uri, Map<String, ?> env) { throw new UnsupportedOperationException(); }
                @Override public FileSystem getFileSystem(java.net.URI uri) { throw new UnsupportedOperationException(); }
                @Override public Path getPath(java.net.URI uri) { throw new UnsupportedOperationException(); }
                @Override public java.nio.channels.SeekableByteChannel newByteChannel(Path path, Set<? extends java.nio.file.OpenOption> options, java.nio.file.attribute.FileAttribute<?>... attrs) { throw new UnsupportedOperationException(); }
                @Override public java.nio.file.DirectoryStream<Path> newDirectoryStream(Path dir, java.nio.file.DirectoryStream.Filter<? super Path> filter) { throw new UnsupportedOperationException(); }
                @Override public void createDirectory(Path dir, java.nio.file.attribute.FileAttribute<?>... attrs) { throw new UnsupportedOperationException(); }
                @Override public void delete(Path path) { throw new UnsupportedOperationException(); }
                @Override public void copy(Path source, Path target, java.nio.file.CopyOption... options) { throw new UnsupportedOperationException(); }
                @Override public void move(Path source, Path target, java.nio.file.CopyOption... options) { throw new UnsupportedOperationException(); }
                @Override public boolean isSameFile(Path path, Path path2) { return false; }
                @Override public boolean isHidden(Path path) { return false; }
                @Override public java.nio.file.FileStore getFileStore(Path path) { throw new UnsupportedOperationException(); }
                @Override public void checkAccess(Path path, java.nio.file.AccessMode... modes) { throw new ProviderMismatchException("foreign"); }
                @Override public <V extends java.nio.file.attribute.FileAttributeView> V getFileAttributeView(Path path, Class<V> type, java.nio.file.LinkOption... options) { return null; }
                @Override public <A extends java.nio.file.attribute.BasicFileAttributes> A readAttributes(Path path, Class<A> type, java.nio.file.LinkOption... options) { throw new ProviderMismatchException("foreign"); }
                @Override public Map<String, Object> readAttributes(Path path, String attributes, java.nio.file.LinkOption... options) { throw new ProviderMismatchException("foreign"); }
                @Override public void setAttribute(Path path, String attribute, Object value, java.nio.file.LinkOption... options) { throw new UnsupportedOperationException(); }
            };
        }

        @Override public FileSystemProvider provider() { return provider; }
        @Override public void close() {}
        @Override public boolean isOpen() { return true; }
        @Override public boolean isReadOnly() { return true; }
        @Override public String getSeparator() { return "/"; }
        @Override public Iterable<Path> getRootDirectories() { return List.of(); }
        @Override public Iterable<java.nio.file.FileStore> getFileStores() { return List.of(); }
        @Override public Set<String> supportedFileAttributeViews() { return Set.of(); }
        @Override public Path getPath(String first, String... more) { throw new UnsupportedOperationException(); }
        @Override public java.nio.file.PathMatcher getPathMatcher(String syntaxAndPattern) { throw new UnsupportedOperationException(); }
        @Override public java.nio.file.attribute.UserPrincipalLookupService getUserPrincipalLookupService() { throw new UnsupportedOperationException(); }
        @Override public java.nio.file.WatchService newWatchService() { throw new UnsupportedOperationException(); }
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
            Files.writeString(mcmeta, "{\"pack\":{\"pack_format\":48,\"description\":\"fuzz\"}}\n");
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

    private static CompoundTag readResourceNbt(net.minecraft.server.packs.resources.Resource resource) throws IOException {
        try (InputStream in = resource.open()) {
            return McCompat.readCompressedNbt(in.readAllBytes(), 128L * 1024L * 1024L);
        }
    }
}
