package dev.rinchan.structuredfucache;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public record CacheConfig(long maxDecodedNbtBytes, int maxCompressedResourceBytes) {
    private static final long MIB = 1024L * 1024L;

    public static CacheConfig defaults() { return new CacheConfig(1024L * MIB, (int)(64L * MIB)); }

    public static CacheConfig load(Path directory) throws IOException {
        Files.createDirectories(directory);
        try (var file = CommentedFileConfig.builder(directory.resolve("structure_dfu_cache.toml")).sync().build()) {
            file.load();
            boolean changed = false;
            if (!file.contains("maxDecodedNbtMiB")) { file.set("maxDecodedNbtMiB", 1024); changed = true; }
            if (!file.contains("maxCompressedResourceMiB")) { file.set("maxCompressedResourceMiB", 64); changed = true; }
            if (changed) file.save();
            return new CacheConfig(readBytes(file, "maxDecodedNbtMiB"),
                    Math.toIntExact(readBytes(file, "maxCompressedResourceMiB")));
        }
    }

    private static long readBytes(CommentedFileConfig file, String key) {
        Object value = file.get(key);
        if (!(value instanceof Integer || value instanceof Long) || ((Number)value).longValue() <= 0) {
            throw new IllegalArgumentException("structure_dfu_cache.toml: " + key + " must be a positive integer in MiB");
        }
        return Math.multiplyExact(((Number)value).longValue(), MIB);
    }
}
