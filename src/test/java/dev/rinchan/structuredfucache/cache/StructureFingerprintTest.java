package dev.rinchan.structuredfucache.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import org.junit.jupiter.api.Test;

class StructureFingerprintTest {
    @Test
    void capturesGeometryAndPaletteCounts() {
        CompoundTag structure = structure(3, 8, 2, 4);

        assertEquals(new StructureFingerprint("[1,2,3]", 8, 2, 4), StructureFingerprint.capture(structure));
    }

    @Test
    void detectsAChangedInvariant() {
        assertNotEquals(StructureFingerprint.capture(structure(3, 8, 2, 4)), StructureFingerprint.capture(structure(3, 9, 2, 4)));
    }

    private static CompoundTag structure(int sizeCount, int blocks, int entities, int palette) {
        CompoundTag tag = new CompoundTag();
        ListTag size = new ListTag();
        for (int value = 1; value <= sizeCount; value++) {
            size.add(IntTag.valueOf(value));
        }
        tag.put("size", size);
        tag.put("blocks", compounds(blocks));
        tag.put("entities", compounds(entities));
        tag.put("palette", compounds(palette));
        return tag;
    }

    private static ListTag compounds(int count) {
        ListTag list = new ListTag();
        for (int index = 0; index < count; index++) {
            list.add(new CompoundTag());
        }
        return list;
    }
}
