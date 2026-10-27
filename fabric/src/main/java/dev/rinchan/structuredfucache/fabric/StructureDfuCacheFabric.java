package dev.rinchan.structuredfucache.fabric;

import dev.rinchan.structuredfucache.StructureDfuCache;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;

public final class StructureDfuCacheFabric implements ModInitializer {
    @Override public void onInitialize() {
        var loader = FabricLoader.getInstance();
        String version = loader.getModContainer("fabricloader").orElseThrow().getMetadata().getVersion().getFriendlyString();
        try {
            StructureDfuCache.initialize(loader.getGameDir(), loader.getConfigDir(), "fabric:" + version);
        } catch (java.io.IOException failure) {
            throw new java.io.UncheckedIOException(failure);
        }
    }
}
