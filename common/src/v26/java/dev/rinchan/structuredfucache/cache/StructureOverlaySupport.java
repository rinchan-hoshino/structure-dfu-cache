package dev.rinchan.structuredfucache.cache;

import dev.rinchan.structuredfucache.StructureDfuCache;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PathPackResources;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

/**
 * Shared overlay resolution and merging logic for Minecraft 26.x {@link ResourceManager} wrappers.
 */
final class StructureOverlaySupport {
    private static final String STRUCTURE_PREFIX = "structure/";
    private static final PackLocationInfo OVERLAY_INFO = new PackLocationInfo(
        StructureDfuCache.MOD_ID + "_overlay",
        Component.translatable("pack.structure_dfu_cache.overlay"),
        net.minecraft.server.packs.repository.PackSource.BUILT_IN,
        Optional.empty()
    );

    private final Path overlayDataDir;
    private final PackResources overlayPack;

    StructureOverlaySupport(Path overlayDataDir) {
        this.overlayDataDir = overlayDataDir.toAbsolutePath().normalize();
        Path packRoot = this.overlayDataDir.getParent() != null ? this.overlayDataDir.getParent() : this.overlayDataDir;
        this.overlayPack = new PathPackResources(OVERLAY_INFO, packRoot);
    }

    Optional<Resource> resolveOverlay(Identifier location) {
        String path = location.getPath();
        if (!path.startsWith(STRUCTURE_PREFIX) || !path.endsWith(".nbt")) {
            return Optional.empty();
        }
        Path candidate = overlayDataDir.resolve(location.getNamespace()).resolve(path).normalize();
        if (!candidate.startsWith(overlayDataDir) || !Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)) {
            return Optional.empty();
        }
        return Optional.of(new Resource(overlayPack, () -> Files.newInputStream(candidate)));
    }

    List<Resource> mergeResourceStack(ResourceManager delegate, Identifier location) {
        Optional<Resource> overlaid = resolveOverlay(location);
        if (overlaid.isEmpty()) {
            return delegate.getResourceStack(location);
        }
        List<Resource> base = delegate.getResourceStack(location);
        if (base.isEmpty()) {
            return List.of(overlaid.get());
        }
        List<Resource> combined = new ArrayList<>(base.size());
        combined.addAll(base.subList(0, base.size() - 1));
        combined.add(overlaid.get());
        return combined;
    }

    Map<Identifier, Resource> mergeListedResources(String path, Map<Identifier, Resource> base) {
        if (base.isEmpty() || (!path.isEmpty() && !"structure".equals(path) && !path.startsWith(STRUCTURE_PREFIX))) {
            return base;
        }
        Map<Identifier, Resource> merged = null;
        for (Map.Entry<Identifier, Resource> entry : base.entrySet()) {
            Optional<Resource> overlaid = resolveOverlay(entry.getKey());
            if (overlaid.isPresent()) {
                if (merged == null) {
                    merged = new LinkedHashMap<>(base);
                }
                merged.put(entry.getKey(), overlaid.get());
            }
        }
        return merged != null ? merged : base;
    }
}
