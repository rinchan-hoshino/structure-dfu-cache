package dev.rinchan.structuredfucache.cache;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CacheFilesTest {
    @TempDir
    Path directory;

    @Test
    void failedWritePreservesPublishedFileAndRemovesTemporaryFile() throws Exception {
        Path target = directory.resolve("index.json");
        Files.writeString(target, "previous");
        assertThrows(IOException.class, () -> CacheFiles.write(target, output -> {
            output.write("partial".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            throw new IOException("negative fixture");
        }));
        assertEquals("previous", Files.readString(target));
        try (var paths = Files.list(directory)) {
            assertEquals(1, paths.count());
        }
    }

    @Test
    void replacementPreservesAnActiveGenerationsHardLinkAndReturnsActualDigest() throws Exception {
        Path target = directory.resolve("blob.nbt");
        Files.writeString(target, "previous");
        Path active = Files.createLink(directory.resolve("active.nbt"), target);
        String digest = CacheFiles.write(target, output -> output.write(new byte[] {1, 2, 3}));
        assertEquals("previous", Files.readString(active));
        assertArrayEquals(new byte[] {1, 2, 3}, Files.readAllBytes(target));
        assertEquals(CacheKey.sha256(target), digest);
    }
}
