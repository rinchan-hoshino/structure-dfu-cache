package dev.rinchan.structuredfucache.forge;

import dev.rinchan.structuredfucache.StructureDfuCache;
import dev.rinchan.structuredfucache.forge.client.StructureDfuCacheForgeClient;
import dev.rinchan.structuredfucache.platform.PlatformBridge;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLLoader;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.forgespi.language.IModFileInfo;

@Mod(StructureDfuCache.MOD_ID)
public final class StructureDfuCacheForge {
    public StructureDfuCacheForge() {
        StructureDfuCache.init(new ForgePlatformBridge());
        DistExecutor.safeRunWhenOn(Dist.CLIENT, () -> StructureDfuCacheForgeClient::register);
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
