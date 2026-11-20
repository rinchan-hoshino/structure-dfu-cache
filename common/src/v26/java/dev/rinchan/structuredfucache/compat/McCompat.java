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
import net.minecraft.commands.Commands;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

/**
 * Minecraft 26.x compatibility bridge (Identifier, WorldVersion.dataVersion().version(), PermissionCheck).
 */
public final class McCompat {
    public static final String STRUCTURE_DIR = "structure";

    private McCompat() {
    }

    public static int currentDataVersion() {
        return SharedConstants.getCurrentVersion().dataVersion().version();
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
        Map<Identifier, Resource> raw = manager.listResources(
            STRUCTURE_DIR,
            loc -> loc.getPath().endsWith(".nbt")
        );
        Map<StructureKey, Resource> sorted = new TreeMap<>();
        for (Map.Entry<Identifier, Resource> entry : raw.entrySet()) {
            Identifier loc = entry.getKey();
            sorted.put(new StructureKey(loc.getNamespace(), loc.getPath()), entry.getValue());
        }
        return sorted;
    }

    public static CompoundTag readCompressedNbt(byte[] bytes, long maxDecodedBytes) throws IOException {
        return NbtIo.readCompressed(new ByteArrayInputStream(bytes), NbtAccounter.create(maxDecodedBytes));
    }

    public static boolean hasPermissionLevel2(CommandSourceStack source) {
        return Commands.hasPermission(Commands.LEVEL_GAMEMASTERS).test(source);
    }
}
