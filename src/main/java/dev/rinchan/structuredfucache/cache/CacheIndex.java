package dev.rinchan.structuredfucache.cache;

import java.util.Map;
import java.util.TreeMap;

public record CacheIndex(int formatVersion, int targetDataVersion, Map<String, CacheEntry> entries) {
    public static final int CURRENT_FORMAT = 1;

    public CacheIndex {
        if (formatVersion != CURRENT_FORMAT) {
            throw new IllegalArgumentException("unsupported cache index format: " + formatVersion);
        }
        if (targetDataVersion <= 0) {
            throw new IllegalArgumentException("targetDataVersion must be positive");
        }
        entries = Map.copyOf(new TreeMap<>(entries));
    }
}
