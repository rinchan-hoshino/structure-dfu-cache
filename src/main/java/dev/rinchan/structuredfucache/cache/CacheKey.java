package dev.rinchan.structuredfucache.cache;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public record CacheKey(String digest, CacheIdentity identity) {
    public CacheKey {
        if (digest == null || !digest.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("digest must be a lowercase SHA-256 value");
        }
        if (identity == null) {
            throw new IllegalArgumentException("identity must not be null");
        }
    }

    public static CacheKey of(byte[] sourceBytes, CacheIdentity identity) {
        return new CacheKey(sha256(sourceBytes), identity);
    }

    static String sha256(byte[] bytes) {
        return HexFormat.of().formatHex(newDigest().digest(bytes));
    }

    static String sha256(Path path) throws IOException {
        MessageDigest digest = newDigest();
        try (InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public String convertedBlobRelativePath() {
        return "blobs/" + identity.pathSegment() + "/" + digest.substring(0, 2) + "/" + digest + ".nbt";
    }

    public String sourceBlobRelativePath() {
        return "sources/" + digest.substring(0, 2) + "/" + digest + ".nbt";
    }
}
