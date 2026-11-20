package dev.rinchan.structuredfucache;

import dev.rinchan.structuredfucache.config.StructureDfuCacheConfig;
import dev.rinchan.structuredfucache.platform.PlatformBridge;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Cross-platform entry point and shared runtime state for Structure DFU Cache.
 */
public final class StructureDfuCache {
    public static final String MOD_ID = "structure_dfu_cache";

    private static volatile PlatformBridge platform = FallbackPlatformBridge.INSTANCE;

    private StructureDfuCache() {
    }

    public static void init(PlatformBridge bridge) {
        platform = Objects.requireNonNull(bridge, "bridge must not be null");
        StructureDfuCacheConfig.load(bridge.configDir());
    }

    public static PlatformBridge platform() {
        return platform;
    }

    public static Path cacheRoot() {
        return platform.gameDir().resolve("cache").resolve(MOD_ID);
    }

    private enum FallbackPlatformBridge implements PlatformBridge {
        INSTANCE;

        @Override
        public Path gameDir() {
            return Path.of(".").toAbsolutePath().normalize();
        }

        @Override
        public Path configDir() {
            return gameDir().resolve("config");
        }

        @Override
        public String loaderName() {
            return "unknown";
        }

        @Override
        public String loaderVersion() {
            return "unknown";
        }

        @Override
        public String minecraftVersion() {
            return "unknown";
        }

        @Override
        public List<Path> modFilePaths() {
            return List.of();
        }
    }
}
