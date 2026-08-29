package dev.rinchan.structuredfucache.cache;

import java.nio.charset.StandardCharsets;

public record CacheIdentity(int formatVersion, int targetDataVersion, String minecraftVersion, String neoForgeVersion) {
    public static final int CURRENT_FORMAT = 3;

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
        if (neoForgeVersion == null || neoForgeVersion.isBlank()) {
            throw new IllegalArgumentException("neoForgeVersion must not be blank");
        }
    }

    public String stableKey() {
        return String.join(
            "|",
            "structure-dfu-cache=" + formatVersion,
            "data=" + targetDataVersion,
            "minecraft=" + minecraftVersion,
            "neoforge=" + neoForgeVersion
        );
    }

    public String pathSegment() {
        return CacheKey.sha256(stableKey().getBytes(StandardCharsets.UTF_8)).substring(0, 16);
    }
}
