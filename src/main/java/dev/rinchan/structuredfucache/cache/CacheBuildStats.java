package dev.rinchan.structuredfucache.cache;

public record CacheBuildStats(
    int totalResources,
    int converted,
    int passthrough,
    int cacheHits,
    int vanillaFallbacks,
    long elapsedMillis,
    String slowestResource
) {
}
