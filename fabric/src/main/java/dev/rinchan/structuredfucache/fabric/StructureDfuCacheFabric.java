package dev.rinchan.structuredfucache.fabric;

import dev.rinchan.structuredfucache.StructureDfuCache;
import dev.rinchan.structuredfucache.platform.PlatformBridge;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.ModOrigin;

public final class StructureDfuCacheFabric implements ModInitializer {
    @Override
    public void onInitialize() {
        StructureDfuCache.init(new FabricPlatformBridge());
    }

    private static final class FabricPlatformBridge implements PlatformBridge {
        @Override
        public Path gameDir() {
            return FabricLoader.getInstance().getGameDir();
        }

        @Override
        public Path configDir() {
            return FabricLoader.getInstance().getConfigDir();
        }

        @Override
        public String loaderName() {
            return "fabric";
        }

        @Override
        public String loaderVersion() {
            return FabricLoader.getInstance()
                .getModContainer("fabricloader")
                .map(c -> c.getMetadata().getVersion().getFriendlyString())
                .orElse("unknown");
        }

        @Override
        public String minecraftVersion() {
            return FabricLoader.getInstance()
                .getModContainer("minecraft")
                .map(c -> c.getMetadata().getVersion().getFriendlyString())
                .orElse("unknown");
        }

        @Override
        public List<Path> modFilePaths() {
            Set<Path> unique = new LinkedHashSet<>();
            for (ModContainer container : FabricLoader.getInstance().getAllMods()) {
                ModOrigin origin = container.getOrigin();
                if (origin != null && origin.getKind() == ModOrigin.Kind.PATH) {
                    unique.addAll(origin.getPaths());
                }
            }
            return new ArrayList<>(unique);
        }
    }
}
