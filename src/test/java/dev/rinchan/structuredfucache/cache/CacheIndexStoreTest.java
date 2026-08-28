package dev.rinchan.structuredfucache.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CacheIndexStoreTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void roundTripsVersionedIndexAtomically() throws Exception {
        Path indexPath = temporaryDirectory.resolve("index.json");
        CacheIdentity identity = new CacheIdentity(CacheIdentity.CURRENT_FORMAT, 3955, "1.21.1", "21.1.248");
        CacheIndex expected = new CacheIndex(
            identity,
            Map.of("example:structure/test.nbt", new CacheEntry("a".repeat(64), "blobs/abc.nbt", true))
        );

        CacheIndexStore.writeAtomic(indexPath, expected);

        assertEquals(expected, CacheIndexStore.read(indexPath).orElseThrow());
        assertTrue(Files.isRegularFile(indexPath));
        assertTrue(Files.notExists(indexPath.resolveSibling("index.json.tmp")));
    }

    @Test
    void legacyIndexWithoutIdentityIsIgnored() throws Exception {
        Path indexPath = temporaryDirectory.resolve("index.json");
        Files.writeString(indexPath, "{\"formatVersion\":1,\"targetDataVersion\":3955,\"entries\":{}}");

        assertTrue(CacheIndexStore.read(indexPath).isEmpty());
    }
}
