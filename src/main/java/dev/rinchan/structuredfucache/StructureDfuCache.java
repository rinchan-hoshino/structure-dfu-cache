package dev.rinchan.structuredfucache;

import dev.rinchan.structuredfucache.cache.StructureCacheReloadListener;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.AddReloadListenerEvent;

@Mod(StructureDfuCache.MOD_ID)
public final class StructureDfuCache {
    public static final String MOD_ID = "structure_dfu_cache";

    public StructureDfuCache(IEventBus modBus, ModContainer modContainer) {
        modContainer.registerConfig(ModConfig.Type.COMMON, StructureDfuCacheConfig.SPEC);
        NeoForge.EVENT_BUS.addListener(this::addReloadListener);
    }

    private void addReloadListener(AddReloadListenerEvent event) {
        event.addListener(new StructureCacheReloadListener());
    }
}
