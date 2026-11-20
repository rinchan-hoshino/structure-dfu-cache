package dev.rinchan.structuredfucache.forge.client;

import dev.rinchan.structuredfucache.client.StructureDfuCacheConfigScreen;
import net.minecraftforge.client.ConfigScreenHandler;
import net.minecraftforge.fml.ModLoadingContext;

public final class StructureDfuCacheForgeClient {
    private StructureDfuCacheForgeClient() {
    }

    public static void register() {
        ModLoadingContext.get().registerExtensionPoint(
            ConfigScreenHandler.ConfigScreenFactory.class,
            () -> new ConfigScreenHandler.ConfigScreenFactory(
                (mc, parent) -> new StructureDfuCacheConfigScreen(parent)
            )
        );
    }
}
