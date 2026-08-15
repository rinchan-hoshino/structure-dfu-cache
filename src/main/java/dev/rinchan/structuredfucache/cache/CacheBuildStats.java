package dev.rinchan.structuredfucache.cache;

public record CacheBuildStats(
    int totalResources,
    int converted,
    int current,
    int cacheHits,
    long elapsedMillis,
    String slowestResource
) {
}
