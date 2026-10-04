package dev.rinchan.structuredfucache.fabric;

import dev.rinchan.structuredfucache.StructureDfuCache;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;

public final class StructureDfuCacheFabric implements ModInitializer {
    @Override public void onInitialize() {
        var loader = FabricLoader.getInstance();
        String version = loader.getModContainer("fabricloader").orElseThrow().getMetadata().getVersion().getFriendlyString();
        StructureDfuCache.initialize(loader.getGameDir(), "fabric:" + version);
    }
}
