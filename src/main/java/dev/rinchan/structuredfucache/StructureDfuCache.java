package dev.rinchan.structuredfucache;

import java.nio.file.Path;
import dev.rinchan.rinlib.minecraft.StructureLifecycle;
import dev.rinchan.structuredfucache.cache.StructureCacheBootstrap;

public final class StructureDfuCache {
    public static final String MOD_ID = "structure_dfu_cache";

    private StructureDfuCache() { }

    public static void initialize(Path gameDirectory, Path configDirectory, String loaderVersion) throws java.io.IOException {
        Path cache = gameDirectory.resolve("cache").resolve(MOD_ID);
        var config = CacheConfig.load(configDirectory);
        StructureLifecycle.register(() -> new StructureCacheBootstrap(cache, loaderVersion, config));
    }
}
