package dev.rinchan.structuredfucache.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CacheIndexStoreTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void atomicallyRoundTripsOnlyCompleteGeneration() throws Exception {
        CacheIndex expected = new CacheIndex(
            CacheIndex.CURRENT_FORMAT,
            3955,
            Map.of(
                "example:structure/old.nbt", new CacheEntry("abc", "3955/ab/abc.nbt"),
                "example:structure/current.nbt", new CacheEntry("def", "")
            )
        );
        Path index = temporaryDirectory.resolve("index.json");

        CacheIndexStore.writeAtomic(index, expected);

        assertEquals(expected, CacheIndexStore.read(index).orElseThrow());
        assertFalse(Files.exists(temporaryDirectory.resolve("index.json.tmp")));
    }
}
