package dev.rinchan.structuredfucache.neoforge;

import dev.rinchan.structuredfucache.StructureDfuCache;
import dev.rinchan.structuredfucache.client.StructureDfuCacheConfigScreen;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

@Mod(value = StructureDfuCache.MOD_ID, dist = Dist.CLIENT)
public final class StructureDfuCacheNeoForgeClient {
    public StructureDfuCacheNeoForgeClient(ModContainer container) {
        container.registerExtensionPoint(
            IConfigScreenFactory.class,
            (modContainer, parent) -> new StructureDfuCacheConfigScreen(parent)
        );
    }
}
