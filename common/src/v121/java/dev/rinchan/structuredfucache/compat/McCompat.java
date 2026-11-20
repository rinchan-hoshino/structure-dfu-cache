package dev.rinchan.structuredfucache.compat;

import dev.rinchan.structuredfucache.cache.StructureKey;
import dev.rinchan.structuredfucache.cache.StructureOverlayResourceManager;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import net.minecraft.SharedConstants;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

/**
 * Minecraft 1.21.1 compatibility bridge (singular "structure" directory, ResourceLocation, NbtAccounter.create).
 */
public final class McCompat {
    public static final String STRUCTURE_DIR = "structure";

    private McCompat() {
    }

    public static int currentDataVersion() {
        return SharedConstants.getCurrentVersion().getDataVersion().getVersion();
    }

    public static String structureDir() {
        return STRUCTURE_DIR;
    }

    public static ResourceManager wrapOverlay(ResourceManager base, Path overlayDataDir) {
        return new StructureOverlayResourceManager(base, overlayDataDir);
    }

    public static ResourceManager unwrapOverlay(ResourceManager manager) {
        return manager instanceof StructureOverlayResourceManager overlay ? overlay.delegate() : manager;
    }

    public static Map<StructureKey, Resource> listStructureResources(ResourceManager manager) {
        Map<ResourceLocation, Resource> raw = manager.listResources(
            STRUCTURE_DIR,
            loc -> loc.getPath().endsWith(".nbt")
        );
        Map<StructureKey, Resource> sorted = new TreeMap<>();
        for (Map.Entry<ResourceLocation, Resource> entry : raw.entrySet()) {
            ResourceLocation loc = entry.getKey();
            sorted.put(new StructureKey(loc.getNamespace(), loc.getPath()), entry.getValue());
        }
        return sorted;
    }

    public static CompoundTag readCompressedNbt(byte[] bytes, long maxDecodedBytes) throws IOException {
        return NbtIo.readCompressed(new ByteArrayInputStream(bytes), NbtAccounter.create(maxDecodedBytes));
    }

    public static boolean hasPermissionLevel2(CommandSourceStack source) {
        return source.hasPermission(2);
    }
}
