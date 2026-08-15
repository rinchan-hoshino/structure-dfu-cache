package dev.rinchan.structuredfucache.cache;

import java.nio.file.Path;
import java.util.Map;
import net.minecraft.resources.ResourceLocation;

public record CacheSnapshot(int targetDataVersion, Map<ResourceLocation, Path> convertedResources, CacheBuildStats stats) {
    public CacheSnapshot {
        convertedResources = Map.copyOf(convertedResources);
    }

    public static CacheSnapshot empty() {
        return new CacheSnapshot(0, Map.of(), new CacheBuildStats(0, 0, 0, 0, 0L, ""));
    }
}
