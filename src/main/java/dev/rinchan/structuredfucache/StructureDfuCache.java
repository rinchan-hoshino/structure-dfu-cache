package dev.rinchan.structuredfucache;

import java.nio.file.Path;
import dev.rinchan.rinlib.minecraft.StructureLifecycle;
import dev.rinchan.structuredfucache.cache.StructureCacheBootstrap;

public final class StructureDfuCache {
    public static final String MOD_ID = "structure_dfu_cache";

    private StructureDfuCache() { }

    public static void initialize(Path gameDirectory, String loaderVersion) {
        Path cache = gameDirectory.resolve("cache").resolve(MOD_ID);
        StructureLifecycle.register(() -> new StructureCacheBootstrap(cache, loaderVersion));
    }
}
