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
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Stream;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PathPackResources;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

/**
 * Lightweight ResourceManager wrapper for Minecraft 1.21.1 serving pre-upgraded structure NBT
 * files directly from overlay/data/<namespace>/structure/<path>.
 */
public final class StructureOverlayResourceManager implements ResourceManager {
    private static final String STRUCTURE_PREFIX = "structure/";
    private static final PackLocationInfo OVERLAY_INFO = new PackLocationInfo(
        StructureDfuCache.MOD_ID + "_overlay",
        Component.translatable("pack.structure_dfu_cache.overlay"),
        PackSource.BUILT_IN,
        Optional.empty()
    );

    private final ResourceManager delegate;
    private final Path overlayDataDir;
    private final PackResources overlayPack;

    public StructureOverlayResourceManager(ResourceManager original, Path overlayDataDir) {
        if (original == null || overlayDataDir == null) {
            throw new IllegalArgumentException("original and overlayDataDir must not be null");
        }
        this.delegate = original instanceof StructureOverlayResourceManager existing
            ? existing.delegate()
            : original;
        this.overlayDataDir = overlayDataDir.toAbsolutePath().normalize();
        Path packRoot = this.overlayDataDir.getParent() != null ? this.overlayDataDir.getParent() : this.overlayDataDir;
        this.overlayPack = new PathPackResources(OVERLAY_INFO, packRoot);
    }

    public ResourceManager delegate() {
        return delegate;
    }

    @Override
    public Set<String> getNamespaces() {
        return delegate.getNamespaces();
    }

    @Override
    public Optional<Resource> getResource(ResourceLocation location) {
        Optional<Resource> overlaid = resolveOverlay(location);
        return overlaid.isPresent() ? overlaid : delegate.getResource(location);
    }

    @Override
    public List<Resource> getResourceStack(ResourceLocation location) {
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

    @Override
    public Map<ResourceLocation, Resource> listResources(String path, Predicate<ResourceLocation> filter) {
        Map<ResourceLocation, Resource> base = delegate.listResources(path, filter);
        if (base.isEmpty() || (!path.isEmpty() && !"structure".equals(path) && !path.startsWith(STRUCTURE_PREFIX))) {
            return base;
        }
        Map<ResourceLocation, Resource> merged = null;
        for (Map.Entry<ResourceLocation, Resource> entry : base.entrySet()) {
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

    @Override
    public Map<ResourceLocation, List<Resource>> listResourceStacks(String path, Predicate<ResourceLocation> filter) {
        return delegate.listResourceStacks(path, filter);
    }

    @Override
    public Stream<PackResources> listPacks() {
        return delegate.listPacks();
    }

    private Optional<Resource> resolveOverlay(ResourceLocation location) {
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
}
