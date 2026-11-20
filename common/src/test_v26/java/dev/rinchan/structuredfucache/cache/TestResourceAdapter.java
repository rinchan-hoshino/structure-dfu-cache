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
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

final class TestResourceAdapter {
    private TestResourceAdapter() {
    }

    static Optional<Resource> getResource(ResourceManager manager, StructureKey key) {
        return manager.getResource(Identifier.fromNamespaceAndPath(key.namespace(), key.path()));
    }

    static List<Resource> getResourceStack(ResourceManager manager, StructureKey key) {
        return manager.getResourceStack(Identifier.fromNamespaceAndPath(key.namespace(), key.path()));
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
        public Optional<Resource> getResource(Identifier location) {
            return delegate.getResource(location).map(this::wrapResource);
        }

        @Override
        public List<Resource> getResourceStack(Identifier location) {
            return delegate.getResourceStack(location).stream().map(this::wrapResource).toList();
        }

        @Override
        public Map<Identifier, Resource> listResources(String path, Predicate<Identifier> filter) {
            listResourcesCalls.incrementAndGet();
            Map<Identifier, Resource> raw = delegate.listResources(path, filter);
            Map<Identifier, Resource> wrapped = new LinkedHashMap<>(raw.size());
            raw.forEach((loc, res) -> wrapped.put(loc, wrapResource(res)));
            return wrapped;
        }

        @Override
        public Map<Identifier, List<Resource>> listResourceStacks(String path, Predicate<Identifier> filter) {
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
