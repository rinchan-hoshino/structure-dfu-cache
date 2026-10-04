package dev.rinchan.structuredfucache.cache;

import java.nio.charset.StandardCharsets;

public record CacheIdentity(int formatVersion, int targetDataVersion, String minecraftVersion, String loaderVersion) {
    public static final int CURRENT_FORMAT = 4;

    public CacheIdentity {
        if (formatVersion != CURRENT_FORMAT) {
            throw new IllegalArgumentException("unsupported cache format: " + formatVersion);
        }
        if (targetDataVersion <= 0) {
            throw new IllegalArgumentException("targetDataVersion must be positive");
        }
        if (minecraftVersion == null || minecraftVersion.isBlank()) {
            throw new IllegalArgumentException("minecraftVersion must not be blank");
        }
        if (loaderVersion == null || loaderVersion.isBlank()) {
            throw new IllegalArgumentException("loaderVersion must not be blank");
        }
    }

    public String stableKey() {
        return String.join(
            "|",
            "structure-dfu-cache=" + formatVersion,
            "data=" + targetDataVersion,
            "minecraft=" + minecraftVersion,
            "loader=" + loaderVersion
        );
    }

    public String pathSegment() {
        return CacheKey.sha256(stableKey().getBytes(StandardCharsets.UTF_8)).substring(0, 16);
    }
}
