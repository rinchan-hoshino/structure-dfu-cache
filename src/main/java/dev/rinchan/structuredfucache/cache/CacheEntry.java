package dev.rinchan.structuredfucache.cache;

public record CacheEntry(String digest, String blobPath, boolean converted) {
    public CacheEntry {
        if (digest == null || !digest.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("digest must be a lowercase SHA-256 value");
        }
        if (blobPath == null || blobPath.isBlank()) {
            throw new IllegalArgumentException("blobPath must not be blank");
        }
    }
}
