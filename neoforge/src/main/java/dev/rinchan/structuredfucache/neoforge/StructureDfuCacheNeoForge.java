package dev.rinchan.structuredfucache.neoforge;

import dev.rinchan.structuredfucache.StructureDfuCache;
import dev.rinchan.structuredfucache.platform.PlatformBridge;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforgespi.language.IModFileInfo;

@Mod(StructureDfuCache.MOD_ID)
public final class StructureDfuCacheNeoForge {
    public StructureDfuCacheNeoForge() {
        StructureDfuCache.init(new NeoForgePlatformBridge());
    }

    private static final class NeoForgePlatformBridge implements PlatformBridge {
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
            return "neoforge";
        }

        @Override
        public String loaderVersion() {
            ModList modList = ModList.get();
            if (modList == null) {
                return "unknown";
            }
            return modList.getModContainerById("neoforge")
                .map(c -> c.getModInfo().getVersion().toString())
                .orElse("unknown");
        }

        @Override
        public String minecraftVersion() {
            ModList modList = ModList.get();
            if (modList == null) {
                return "unknown";
            }
            return modList.getModContainerById("minecraft")
                .map(c -> c.getModInfo().getVersion().toString())
                .orElse("unknown");
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
