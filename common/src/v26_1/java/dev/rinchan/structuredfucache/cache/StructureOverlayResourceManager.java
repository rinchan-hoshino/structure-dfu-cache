package dev.rinchan.structuredfucache.cache;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Stream;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

/**
 * Lightweight ResourceManager wrapper for Minecraft 26.1.x / 26.2 serving pre-upgraded structure NBT
 * files directly from overlay/data/<namespace>/structure/<path>.
 */
public final class StructureOverlayResourceManager implements ResourceManager {
    private final ResourceManager delegate;
    private final StructureOverlaySupport support;

    public StructureOverlayResourceManager(ResourceManager original, Path overlayDataDir) {
        if (original == null || overlayDataDir == null) {
            throw new IllegalArgumentException("original and overlayDataDir must not be null");
        }
        this.delegate = original instanceof StructureOverlayResourceManager existing
            ? existing.delegate()
            : original;
        this.support = new StructureOverlaySupport(overlayDataDir);
    }

    public ResourceManager delegate() {
        return delegate;
    }

    @Override
    public Set<String> getNamespaces() {
        return delegate.getNamespaces();
    }

    @Override
    public Optional<Resource> getResource(Identifier location) {
        Optional<Resource> overlaid = support.resolveOverlay(location);
        return overlaid.isPresent() ? overlaid : delegate.getResource(location);
    }

    @Override
    public List<Resource> getResourceStack(Identifier location) {
        return support.mergeResourceStack(delegate, location);
    }

    @Override
    public Map<Identifier, Resource> listResources(String path, Predicate<Identifier> filter) {
        return support.mergeListedResources(path, delegate.listResources(path, filter));
    }

    @Override
    public Map<Identifier, List<Resource>> listResourceStacks(String path, Predicate<Identifier> filter) {
        return delegate.listResourceStacks(path, filter);
    }

    @Override
    public Stream<PackResources> listPacks() {
        return delegate.listPacks();
    }
}
