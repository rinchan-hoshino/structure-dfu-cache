package dev.rinchan.structuredfucache.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ConversionMemoryBudgetTest {
    @Test
    void largeCompressedTemplatesBecomeExclusiveConversions() {
        assertEquals(1, StructureCacheBuilder.permitWeight(64 * 1024, 8));
        assertEquals(2, StructureCacheBuilder.permitWeight(65 * 1024, 8));
        assertEquals(4, StructureCacheBuilder.permitWeight(257 * 1024, 8));
        assertEquals(8, StructureCacheBuilder.permitWeight(1025 * 1024, 8));
        assertEquals(3, StructureCacheBuilder.permitWeight(1025 * 1024, 3));
    }
}
