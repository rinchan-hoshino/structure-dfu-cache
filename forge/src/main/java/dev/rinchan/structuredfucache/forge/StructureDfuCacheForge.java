package dev.rinchan.structuredfucache.forge;

import dev.rinchan.structuredfucache.StructureDfuCache;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLPaths;

@Mod(StructureDfuCache.MOD_ID)
public final class StructureDfuCacheForge {
    public StructureDfuCacheForge() {
        String version = ModList.get().getModContainerById("forge").orElseThrow().getModInfo().getVersion().toString();
        StructureDfuCache.initialize(FMLPaths.GAMEDIR.get(), "forge:" + version);
    }
}
