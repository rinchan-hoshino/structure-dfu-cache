package dev.rinchan.structuredfucache.forge;

import dev.rinchan.structuredfucache.StructureDfuCache;
import dev.rinchan.structuredfucache.client.StructureDfuCacheConfigScreen;
import dev.rinchan.structuredfucache.platform.PlatformBridge;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.ConfigScreenHandler;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLLoader;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.forgespi.language.IModFileInfo;

@Mod(StructureDfuCache.MOD_ID)
public final class StructureDfuCacheForge {
    public StructureDfuCacheForge() {
        StructureDfuCache.init(new ForgePlatformBridge());
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> StructureDfuCacheForge::registerConfigScreen);
    }

    private static void registerConfigScreen() {
        ModLoadingContext.get().registerExtensionPoint(
            ConfigScreenHandler.ConfigScreenFactory.class,
            () -> new ConfigScreenHandler.ConfigScreenFactory(
                (mc, parent) -> new StructureDfuCacheConfigScreen(parent)
            )
        );
    }

    private static final class ForgePlatformBridge implements PlatformBridge {
        @Override
        public Path gameDir() {
            return FMLPaths.GAMEDIR.get();
        }

        @Override
        public Path configDir() {
            return FMLPaths.CONFIGDIR.get();
        }

        @Override
        public String loaderName() {
            return "forge";
        }

        @Override
        public String loaderVersion() {
            return FMLLoader.versionInfo().forgeVersion();
        }

        @Override
        public String minecraftVersion() {
            return FMLLoader.versionInfo().mcVersion();
        }

        @Override
        public List<Path> modFilePaths() {
            ModList modList = ModList.get();
            if (modList == null) {
                return List.of();
            }
            List<Path> paths = new ArrayList<>();
            for (IModFileInfo info : modList.getModFiles()) {
                if (info != null && info.getFile() != null && info.getFile().getFilePath() != null) {
                    paths.add(info.getFile().getFilePath());
                }
            }
            return paths;
        }
    }
}
