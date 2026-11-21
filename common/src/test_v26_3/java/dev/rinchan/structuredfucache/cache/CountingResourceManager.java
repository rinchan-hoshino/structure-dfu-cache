package dev.rinchan.structuredfucache.cache;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

final class CountingResourceManager implements ResourceManager {
    private final ResourceManager delegate;
    final AtomicInteger listResourcesCalls = new AtomicInteger();
    final AtomicInteger resourceOpenCalls = new AtomicInteger();

    CountingResourceManager(ResourceManager delegate) {
        this.delegate = delegate;
    }

    @Override
    public Set<String> getNamespaces() {
        return delegate.getNamespaces();
    }

    @Override
    public Optional<Resource> getResource(Identifier location) {
        return delegate.getResource(location).map(r -> TestResourceAdapter.wrapResource(r, resourceOpenCalls));
    }

    @Override
    public List<Resource> getResourceStack(Identifier location) {
        return delegate.getResourceStack(location).stream()
            .map(r -> TestResourceAdapter.wrapResource(r, resourceOpenCalls))
            .toList();
    }

    @Override
    public Map<Identifier, Resource> listResources(String path, ResourceManager.Selector filter) {
        listResourcesCalls.incrementAndGet();
        return TestResourceAdapter.wrapListedResources(delegate.listResources(path, filter), resourceOpenCalls);
    }

    @Override
    public Map<Identifier, List<Resource>> listResourceStacks(String path, ResourceManager.Selector filter) {
        return delegate.listResourceStacks(path, filter);
    }

    @Override
    public Stream<PackResources> listPacks() {
        return delegate.listPacks();
    }
}
