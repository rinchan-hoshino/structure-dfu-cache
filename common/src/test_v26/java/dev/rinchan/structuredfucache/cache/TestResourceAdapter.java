package dev.rinchan.structuredfucache.cache;

import java.io.FilterInputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.resources.Identifier;
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

    static Resource wrapResource(Resource original, AtomicInteger resourceOpenCalls) {
        return new Resource(original.source(), () -> {
            resourceOpenCalls.incrementAndGet();
            return new FilterInputStream(original.open()) {
            };
        });
    }

    static Map<Identifier, Resource> wrapListedResources(
        Map<Identifier, Resource> raw,
        AtomicInteger resourceOpenCalls
    ) {
        Map<Identifier, Resource> wrapped = new LinkedHashMap<>(raw.size());
        raw.forEach((loc, res) -> wrapped.put(loc, wrapResource(res, resourceOpenCalls)));
        return wrapped;
    }
}
