package dev.rinchan.structuredfucache.cache;

public record CacheEntry(String digest, String blobDigest, String blobPath, boolean converted) {
    public CacheEntry {
        if (!CacheKey.isDigest(digest)) {
            throw new IllegalArgumentException("digest must be a lowercase SHA-256 value");
        }
        if (!CacheKey.isDigest(blobDigest)) {
            throw new IllegalArgumentException("blobDigest must be a lowercase SHA-256 value");
        }
        if (blobPath == null || blobPath.isBlank()) {
            throw new IllegalArgumentException("blobPath must not be blank");
        }
    }
}
