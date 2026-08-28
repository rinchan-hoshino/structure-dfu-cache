<p align="center"><img src="docs/icon.png" width="160" alt="Structure DFU Cache icon"></p>

# Structure DFU Cache

A drop-in startup-time persistent DFU cache for structure-heavy NeoForge packs.

## Contract

Drop the JAR into a pack. No configuration or integration API is required. Removing the JAR removes every runtime behavior; files left under `cache/structure_dfu_cache/` are inert.

The mod may spend CPU, memory, I/O, and time while the server resources are being prepared at startup or during an explicit `/reload`. Once that maintenance window closes, it owns no tick hook, background worker, scanner, hashing job, DFU job, or cache writer.

## Mechanism

For each effective `data/<namespace>/structure/*.nbt` resource after ordinary data-pack priority and filters have been resolved:

1. hash the exact resolved bytes;
2. preserve the exact bytes of current, future-version, malformed, unsupported, or unconvertible resources;
3. upgrade older structure NBT with Minecraft's `DataFixTypes.STRUCTURE` and the exact `DataFixer` owned by `StructureTemplateManager`;
4. persist both passthrough and converted results in a content-addressed cache;
5. materialize the complete resolved structure set as one ordinary vanilla `PathPackResources`;
6. give `StructureTemplateManager` a vanilla single-pack `MultiPackResourceManager`.

The generated pack contains exactly the structure IDs already selected by ordinary data-pack priority and filters. It does not add IDs, and non-converted structures remain byte-for-byte identical. Normal gameplay resolves structures through one vanilla pack; Structure DFU Cache code and the original multi-pack search are both absent from the structure-loading path.

If even one effective resource cannot be captured, or if the cache/index/generated pack cannot be committed, the complete optimization is disabled for that resource generation and the original resource manager remains active. Compatibility therefore never depends on a partial generated view.

## Bounds

- compressed input: at most 64 MiB per structure before vanilla fallback;
- decoded NBT: at most 256 MiB per structure before vanilla fallback;
- conversion workers: selected automatically from available processors and maximum heap, reserving 2 GiB for Minecraft and then budgeting 1 GiB per worker, capped at four;
- no wall-clock startup timeout;
- cache identity includes cache format, target DataVersion, Minecraft version, and NeoForge version;
- overlays are generation-scoped and atomically published; stale generations are removed during startup/reload maintenance.

## Supported runtime

- Minecraft `1.21.1`
- NeoForge `[21.1.248, 21.2)`
- Java 21

The support range is intentionally narrow. Expansion requires a separate static Mixin contract and structure-corpus parity evidence.

## Validation

The focused test set covers content addressing, runtime identity invalidation, bounded input, stream closure, fallback behavior, atomic index replacement, and vanilla `PathPackResources` materialization. The 1.0.0 release was also started on a minimal NeoForge 21.1.248 dedicated server: the cache prepared 1,180 effective structure resources with zero vanilla fallbacks before the server reached `Done`.

## License

Structure DFU Cache is licensed under `GPL-3.0-or-later`. See [LICENSE](LICENSE).
