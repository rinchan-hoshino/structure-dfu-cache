package dev.rinchan.structuredfucache.cache;

import java.io.FilterInputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import java.util.stream.Stream;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

final class TestResourceAdapter {
    private TestResourceAdapter() {
    }

    static Optional<Resource> getResource(ResourceManager manager, StructureKey key) {
        return manager.getResource(ResourceLocation.fromNamespaceAndPath(key.namespace(), key.path()));
    }

    static List<Resource> getResourceStack(ResourceManager manager, StructureKey key) {
        return manager.getResourceStack(ResourceLocation.fromNamespaceAndPath(key.namespace(), key.path()));
    }

    static CountingResourceManager counting(ResourceManager delegate) {
        return new CountingResourceManager(delegate);
    }

    static final class CountingResourceManager implements ResourceManager {
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
        public Optional<Resource> getResource(ResourceLocation location) {
            return delegate.getResource(location).map(this::wrapResource);
        }

        @Override
        public List<Resource> getResourceStack(ResourceLocation location) {
            return delegate.getResourceStack(location).stream().map(this::wrapResource).toList();
        }

        @Override
        public Map<ResourceLocation, Resource> listResources(String path, Predicate<ResourceLocation> filter) {
            listResourcesCalls.incrementAndGet();
            Map<ResourceLocation, Resource> raw = delegate.listResources(path, filter);
            Map<ResourceLocation, Resource> wrapped = new LinkedHashMap<>(raw.size());
            raw.forEach((loc, res) -> wrapped.put(loc, wrapResource(res)));
            return wrapped;
        }

        @Override
        public Map<ResourceLocation, List<Resource>> listResourceStacks(String path, Predicate<ResourceLocation> filter) {
            return delegate.listResourceStacks(path, filter);
        }

        @Override
        public Stream<PackResources> listPacks() {
            return delegate.listPacks();
        }

        private Resource wrapResource(Resource original) {
            return new Resource(original.source(), () -> {
                resourceOpenCalls.incrementAndGet();
                return new FilterInputStream(original.open()) {
                };
            });
        }
    }
}
