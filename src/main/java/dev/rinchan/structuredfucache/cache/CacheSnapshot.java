package dev.rinchan.structuredfucache.cache;

import java.nio.file.Path;
import java.util.Map;
import net.minecraft.resources.ResourceLocation;

public record CacheSnapshot(CacheIdentity identity, Map<ResourceLocation, Path> preparedResources, CacheBuildStats stats) {
    public CacheSnapshot {
        if (identity == null) {
            throw new IllegalArgumentException("identity must not be null");
        }
        preparedResources = Map.copyOf(preparedResources);
    }
}
