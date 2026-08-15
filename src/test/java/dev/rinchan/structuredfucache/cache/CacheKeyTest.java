package dev.rinchan.structuredfucache.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class CacheKeyTest {
    @Test
    void keyIncludesExactResourceBytesAndTargetDataVersion() {
        byte[] bytes = "structure".getBytes(StandardCharsets.UTF_8);

        CacheKey first = CacheKey.of(bytes, 3955);
        CacheKey same = CacheKey.of(bytes, 3955);
        CacheKey otherVersion = CacheKey.of(bytes, 3956);

        assertEquals(first, same);
        assertNotEquals(first, otherVersion);
        assertEquals(
            "3955/52/520cdb563bf80b193aab6aad62781a9647c75dbf76748117299c7dac0ae63a87.nbt",
            first.blobRelativePath().toString().replace('\\', '/')
        );
    }
}
