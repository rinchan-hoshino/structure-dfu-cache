package dev.rinchan.structuredfucache.cache;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.profiling.ProfilerFiller;

public final class StructureCacheReloadListener implements PreparableReloadListener {
    @Override
    public CompletableFuture<Void> reload(
        PreparationBarrier preparationBarrier,
        ResourceManager resourceManager,
        ProfilerFiller preparationProfiler,
        ProfilerFiller reloadProfiler,
        Executor backgroundExecutor,
        Executor gameExecutor
    ) {
        return CompletableFuture.supplyAsync(() -> StructureCacheBootstrap.build(resourceManager), backgroundExecutor)
            .thenCompose(preparationBarrier::wait)
            .thenAcceptAsync(
                snapshot -> StructureCacheBootstrap.activateAndLog(snapshot, "reload apply"),
                gameExecutor
            );
    }
}
