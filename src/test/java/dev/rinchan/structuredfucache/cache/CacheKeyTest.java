package dev.rinchan.structuredfucache.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class CacheKeyTest {
    @Test
    void keyIncludesExactBytesAndRuntimeConversionIdentity() {
        byte[] bytes = "structure".getBytes(StandardCharsets.UTF_8);
        CacheIdentity firstIdentity = new CacheIdentity(CacheIdentity.CURRENT_FORMAT, 3955, "1.21.1", "21.1.248");
        CacheIdentity changedRuntime = new CacheIdentity(CacheIdentity.CURRENT_FORMAT, 3955, "1.21.1", "21.1.249");

        CacheKey first = CacheKey.of(bytes, firstIdentity);
        CacheKey same = CacheKey.of(bytes, firstIdentity);
        CacheKey otherRuntime = CacheKey.of(bytes, changedRuntime);

        assertEquals(first, same);
        assertNotEquals(first, otherRuntime);
        assertTrue(first.convertedBlobRelativePath().startsWith("blobs/" + firstIdentity.pathSegment() + "/52/"));
        assertTrue(first.convertedBlobRelativePath().endsWith(first.digest() + ".nbt"));
        assertEquals("sources/52/" + first.digest() + ".nbt", first.sourceBlobRelativePath());
    }
}
