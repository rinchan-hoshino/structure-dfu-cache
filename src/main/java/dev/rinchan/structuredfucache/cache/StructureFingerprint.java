package dev.rinchan.structuredfucache.cache;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

public record StructureFingerprint(String size, int blocks, int entities, int paletteEntries) {
    public static StructureFingerprint capture(CompoundTag structure) {
        int paletteEntries = structure.contains("palette", Tag.TAG_LIST)
            ? structure.getList("palette", Tag.TAG_COMPOUND).size()
            : structure.getList("palettes", Tag.TAG_LIST).size();
        return new StructureFingerprint(
            structure.getList("size", Tag.TAG_INT).toString(),
            structure.getList("blocks", Tag.TAG_COMPOUND).size(),
            structure.getList("entities", Tag.TAG_COMPOUND).size(),
            paletteEntries
        );
    }
}
