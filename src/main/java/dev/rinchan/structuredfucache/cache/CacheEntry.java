package dev.rinchan.structuredfucache.cache;

public record CacheEntry(String digest, String blobPath) {
    public CacheEntry {
        if (digest == null || digest.isBlank()) {
            throw new IllegalArgumentException("digest must not be blank");
        }
        if (blobPath == null) {
            throw new IllegalArgumentException("blobPath must not be null");
        }
    }

    public boolean isConverted() {
        return !blobPath.isEmpty();
    }
}
