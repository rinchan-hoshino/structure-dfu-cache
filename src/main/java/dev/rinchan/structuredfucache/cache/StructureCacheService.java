package dev.rinchan.structuredfucache.cache;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;

public final class StructureCacheService {
    private static volatile CacheSnapshot activeSnapshot = CacheSnapshot.empty();

    private StructureCacheService() {
    }

    public static void activate(CacheSnapshot snapshot) {
        activeSnapshot = snapshot;
    }

    public static boolean hasActiveSnapshot() {
        return activeSnapshot.targetDataVersion() != 0;
    }

    public static InputStream open(ResourceManager resourceManager, ResourceLocation fileLocation) throws IOException {
        Path cached = activeSnapshot.convertedResources().get(fileLocation);
        if (cached != null) {
            return Files.newInputStream(cached);
        }
        return resourceManager.open(fileLocation);
    }

    static CacheSnapshot activeSnapshot() {
        return activeSnapshot;
    }
}
