package dev.rinchan.structuredfucache.neoforge;

import dev.rinchan.structuredfucache.StructureDfuCache;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;

@Mod(StructureDfuCache.MOD_ID)
public final class StructureDfuCacheNeoForge {
    public StructureDfuCacheNeoForge() {
        String version = ModList.get().getModContainerById("neoforge").orElseThrow().getModInfo().getVersion().toString();
        StructureDfuCache.initialize(FMLPaths.GAMEDIR.get(), "neoforge:" + version);
    }
}
