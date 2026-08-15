package dev.rinchan.structuredfucache.cache;

import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public record CacheKey(String digestHex, int targetDataVersion) {
    public CacheKey {
        if (digestHex == null || digestHex.length() != 64) {
            throw new IllegalArgumentException("SHA-256 digest must contain 64 hexadecimal characters");
        }
        if (targetDataVersion <= 0) {
            throw new IllegalArgumentException("targetDataVersion must be positive");
        }
    }

    public static CacheKey of(byte[] resourceBytes, int targetDataVersion) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(resourceBytes);
            return new CacheKey(HexFormat.of().formatHex(digest), targetDataVersion);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public Path blobRelativePath() {
        return Path.of(Integer.toString(targetDataVersion), digestHex.substring(0, 2), digestHex + ".nbt");
    }
}
