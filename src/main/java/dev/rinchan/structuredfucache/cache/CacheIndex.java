package dev.rinchan.structuredfucache.cache;

import java.util.Map;
import java.util.TreeMap;

public record CacheIndex(CacheIdentity identity, Map<String, CacheEntry> entries) {
    public CacheIndex {
        if (identity == null) {
            throw new IllegalArgumentException("identity must not be null");
        }
        if (entries == null) {
            throw new IllegalArgumentException("entries must not be null");
        }
        entries = Map.copyOf(new TreeMap<>(entries));
    }
}
