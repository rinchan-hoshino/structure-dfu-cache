package dev.rinchan.structuredfucache.cache;

import com.mojang.logging.LogUtils;
import dev.rinchan.structuredfucache.StructureDfuCache;
import dev.rinchan.structuredfucache.StructureDfuCacheConfig;
import java.nio.file.Path;
import net.minecraft.SharedConstants;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.datafix.DataFixers;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;

public final class StructureCacheBootstrap {
    private static final Logger LOGGER = LogUtils.getLogger();

    private StructureCacheBootstrap() {
    }

    public static synchronized CacheSnapshot ensureInitialCache(ResourceManager resourceManager) {
        if (StructureCacheService.hasActiveSnapshot()) {
            return StructureCacheService.activeSnapshot();
        }
        return rebuildAndActivate(resourceManager, "initial pre-reload");
    }

    public static CacheSnapshot rebuildAndActivate(ResourceManager resourceManager, String phase) {
        CacheSnapshot snapshot = build(resourceManager);
        activateAndLog(snapshot, phase);
        return snapshot;
    }

    public static CacheSnapshot build(ResourceManager resourceManager) {
        int targetDataVersion = SharedConstants.getCurrentVersion().getDataVersion().getVersion();
        CachePolicy policy = CachePolicy.fromSeconds(StructureDfuCacheConfig.COLD_BUILD_TIMEOUT_SECONDS.get());
        Path cacheRoot = cacheRoot();
        StructureCacheBuilder builder = new StructureCacheBuilder(
            DataFixers.getDataFixer(),
            cacheRoot,
            policy,
            StructureDfuCacheConfig.WORKER_THREADS.get()
        );
        return builder.build(resourceManager, targetDataVersion);
    }

    public static void activateAndLog(CacheSnapshot snapshot, String phase) {
        StructureCacheService.activate(snapshot);
        CacheBuildStats stats = snapshot.stats();
        LOGGER.info(
            "Structure DFU cache ready during {}: resources={}, converted={}, current={}, cacheHits={}, elapsed={} ms, slowest={}, root={}",
            phase,
            stats.totalResources(),
            stats.converted(),
            stats.current(),
            stats.cacheHits(),
            stats.elapsedMillis(),
            stats.slowestResource(),
            cacheRoot()
        );
    }

    private static Path cacheRoot() {
        return FMLPaths.GAMEDIR.get().resolve("cache").resolve(StructureDfuCache.MOD_ID);
    }
}
