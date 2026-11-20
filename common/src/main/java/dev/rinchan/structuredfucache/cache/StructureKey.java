package dev.rinchan.structuredfucache.cache;

import java.util.Objects;

/**
 * Version-neutral resource key (namespace:path) decoupling cache logic from
 * Minecraft's ResourceLocation (1.20-1.21) vs Identifier (26.x) rename.
 */
public record StructureKey(String namespace, String path) implements Comparable<StructureKey> {
    public StructureKey {
        Objects.requireNonNull(namespace, "namespace must not be null");
        Objects.requireNonNull(path, "path must not be null");
    }

    @Override
    public int compareTo(StructureKey other) {
        int ns = this.namespace.compareTo(other.namespace);
        return ns != 0 ? ns : this.path.compareTo(other.path);
    }

    @Override
    public String toString() {
        return namespace + ":" + path;
    }
}
