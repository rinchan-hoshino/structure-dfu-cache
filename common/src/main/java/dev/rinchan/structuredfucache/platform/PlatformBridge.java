package dev.rinchan.structuredfucache.platform;

import java.nio.file.Path;
import java.util.List;

/**
 * Loader abstraction decoupling the v2 cache core from Fabric, Forge, and NeoForge internals.
 */
public interface PlatformBridge {
    Path gameDir();

    Path configDir();

    String loaderName();

    String loaderVersion();

    String minecraftVersion();

    List<Path> modFilePaths();

    default String loaderId() {
        return loaderName() + ":" + loaderVersion();
    }
}
