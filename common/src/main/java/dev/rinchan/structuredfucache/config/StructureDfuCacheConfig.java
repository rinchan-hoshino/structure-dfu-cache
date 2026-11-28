package dev.rinchan.structuredfucache.config;

import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;

/**
 * Lightweight zero-dependency TOML configuration (config/structure_dfu_cache.toml).
 */
public record StructureDfuCacheConfig(boolean enabled, int maxWorkers, boolean verboseLogging, boolean notifyInChat) {
    public static final String FILE_NAME = "structure_dfu_cache.toml";
    public static final int MIN_WORKERS = 0;
    public static final int MAX_WORKERS = 8;

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final AtomicLong TMP_SEQ = new AtomicLong();

    private static volatile StructureDfuCacheConfig current = defaults();
    private static volatile Path configFile;

    public StructureDfuCacheConfig {
        maxWorkers = clampWorkers(maxWorkers);
    }

    public StructureDfuCacheConfig(boolean enabled, int maxWorkers, boolean verboseLogging) {
        this(enabled, maxWorkers, verboseLogging, false);
    }

    public static StructureDfuCacheConfig defaults() {
        return new StructureDfuCacheConfig(true, 0, false, false);
    }

    public static StructureDfuCacheConfig get() {
        return current;
    }

    public static int clampWorkers(int value) {
        return Math.max(MIN_WORKERS, Math.min(MAX_WORKERS, value));
    }

    public static synchronized StructureDfuCacheConfig load(Path configDir) {
        Path file = configDir.resolve(FILE_NAME);
        configFile = file;
        StructureDfuCacheConfig loaded = readFrom(file);
        current = loaded;
        if (!Files.exists(file)) {
            writeTo(file, loaded);
        }
        return loaded;
    }

    public static synchronized void updateAndSave(StructureDfuCacheConfig updated) {
        current = updated != null ? updated : defaults();
        Path file = configFile;
        if (file != null) {
            writeTo(file, current);
        }
    }

    public static StructureDfuCacheConfig readFrom(Path file) {
        if (file == null || !Files.isRegularFile(file)) {
            return defaults();
        }
        try {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            Map<String, Object> parsed = SimpleToml.parse(content);
            boolean enabled = readBoolean(parsed, "enabled", true);
            int maxWorkers = readInt(parsed, "maxWorkers", 0);
            boolean verboseLogging = readBoolean(parsed, "verboseLogging", false);
            boolean notifyInChat = readBoolean(parsed, "notifyInChat", false);
            return new StructureDfuCacheConfig(enabled, maxWorkers, verboseLogging, notifyInChat);
        } catch (IOException | RuntimeException exception) {
            LOGGER.warn("Unable to read {}: {}; using defaults.", file, exception.getMessage());
            return defaults();
        }
    }

    public static void writeTo(Path file, StructureDfuCacheConfig cfg) {
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path tmp = file.resolveSibling(FILE_NAME + ".tmp-" + TMP_SEQ.incrementAndGet());
            try {
                Files.writeString(tmp, formatToml(cfg), StandardCharsets.UTF_8);
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(tmp);
            }
        } catch (IOException exception) {
            LOGGER.warn("Unable to save {}: {}", file, exception.getMessage());
        }
    }

    public static String formatToml(StructureDfuCacheConfig cfg) {
        return "# Structure DFU Cache 2.0 configuration\n"
            + "# Changes take effect on the next world load or /reload.\n\n"
            + "# Master switch. When false, Structure DFU Cache bypasses the overlay and uses vanilla resources directly.\n"
            + "enabled = " + cfg.enabled() + "\n\n"
            + "# Maximum worker threads used during cold/incremental cache builds.\n"
            + "# 0 = auto-adaptive (1..4 workers based on available JVM heap headroom).\n"
            + "# 1..8 = explicit worker cap.\n"
            + "maxWorkers = " + cfg.maxWorkers() + "\n\n"
            + "# When true, logs extra fingerprint and build diagnostics at INFO level.\n"
            + "verboseLogging = " + cfg.verboseLogging() + "\n\n"
            + "# When true, sends a single concise cache status notice in chat to joining ops / singleplayer host.\n"
            + "# Defaults to false so chat stays completely quiet.\n"
            + "notifyInChat = " + cfg.notifyInChat() + "\n";
    }

    private static boolean readBoolean(Map<String, Object> parsed, String key, boolean fallback) {
        Object value = parsed.get(key);
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof String str) {
            if ("true".equalsIgnoreCase(str)) {
                return true;
            }
            if ("false".equalsIgnoreCase(str)) {
                return false;
            }
        }
        return fallback;
    }

    private static int readInt(Map<String, Object> parsed, String key, int fallback) {
        Object value = parsed.get(key);
        if (value instanceof Integer num) {
            return num;
        }
        if (value instanceof String str) {
            try {
                return Integer.parseInt(str.trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }
}
