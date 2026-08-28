package dev.rinchan.structuredfucache.cache;

public record CacheBuildStats(
    int totalResources,
    int converted,
    int current,
    int cacheHits,
    int vanillaFallbacks,
    long elapsedMillis,
    String slowestResource
) {
}
