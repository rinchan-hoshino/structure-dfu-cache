package dev.rinchan.structuredfucache.cache;

public record CacheBuildStats(
    int totalResources,
    int converted,
    int passthrough,
    int cacheHits,
    int vanillaFallbacks,
    long elapsedMillis,
    String slowestResource,
    int repairedResources,
    int unoptimizedResources,
    IndexState indexState
) {
    public enum IndexState {
        NEW("new cache"), REUSED("unchanged"), VERSION_CHANGED("game or loader version changed"),
        FORMAT_CHANGED("cache format changed"), RECOVERED("damaged index rebuilt");
        private final String description;
        IndexState(String description) { this.description = description; }
        public String description() { return description; }
    }
    public boolean hasProblems() {
        return repairedResources > 0 || unoptimizedResources > 0 || vanillaFallbacks > 0 || indexState == IndexState.RECOVERED;
    }
}
