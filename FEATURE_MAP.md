# Feature Map — Structure DFU Cache 2.0.0

## Architecture Overview

| Layer | Path | Responsibility |
| --- | --- | --- |
| Shared Core | `common/src/main/java` | `StructureCacheBootstrap`, `StructureCacheBuilder`, `CacheManifest`, `PackFingerprint`, `CacheStats`, `StructureKey`, `StructureDfuCacheConfig`, `SimpleToml`, `StructureDfuCacheCommand`, `StructureTemplateManagerMixin`, `CommandsMixin` |
| MC `1.20.1` Adapter | `common/src/v120/` | `McCompat` (`structures/` plural dir, `ResourceLocation`, `NbtAccounter(long)`), `StructureOverlayResourceManager`, `StructureDfuCacheConfigScreen` |
| MC `1.21.1` Adapter | `common/src/v121/` | `McCompat` (`structure/` singular dir, `ResourceLocation`, `NbtAccounter.create`), `StructureOverlayResourceManager`, `StructureDfuCacheConfigScreen` |
| MC `26.1.2` / `26.2` Adapter | `common/src/v26/` | `McCompat` (`Identifier`, `WorldVersion.dataVersion().version()`, `Commands.LEVEL_GAMEMASTERS`), `StructureOverlayResourceManager`, `StructureDfuCacheConfigScreen` |
| Fabric Loader | `fabric/` | `StructureDfuCacheFabric` (`FabricPlatformBridge`), `StructureDfuCacheModMenu`, compile-only `modmenuStub` |
| Forge Loader (`1.20.1`) | `forge/` | `StructureDfuCacheForge` (`ForgePlatformBridge`, `ConfigScreenHandler.ConfigScreenFactory`) |
| NeoForge Loader (`1.21.1` / `26.x`) | `neoforge/` | `StructureDfuCacheNeoForge` (`NeoForgePlatformBridge`), `StructureDfuCacheNeoForgeClient` (`IConfigScreenFactory`) |

## Mixin Surface (Minimal & Safe)

| Mixin | Target | Injection Point | Purpose |
| --- | --- | --- | --- |
| `StructureTemplateManagerMixin` | `StructureTemplateManager` | `<init>`, `onResourceManagerReload` at `RETURN` | Wraps `this.resourceManager` with `StructureOverlayResourceManager` (unwrapping prior overlays first) |
| `CommandsMixin` | `Commands` | `<init>` at `RETURN` | Registers `/structuredfucache status` and `/structuredfucache clear` on all loaders with zero Fabric API dependency |

## Configuration (`config/structure_dfu_cache.toml`)

| Key | Type | Default | Range / Behavior |
| --- | --- | --- | --- |
| `enabled` | `boolean` | `true` | Master switch; `false` returns the vanilla `ResourceManager` directly |
| `maxWorkers` | `int` | `0` | `0` = adaptive `1..4` based on JVM heap; `1..8` = explicit cap |
| `verboseLogging` | `boolean` | `false` | Logs worker count and extra diagnostics at `INFO` level |

## Cache Layout (`cache/structure_dfu_cache/`)

- `manifest.v2`: Line 1 header `#v2\t<targetDataVersion>\t<mcVersion>\t<loaderId>\t<packFingerprint>`, followed by `<ns:path>\t<sourceSha256>\t<C|P>` entries.
- `overlay/data/<namespace>/<structure|structures>/<path>.nbt`: Only upgraded `C` structures written with `Deflater.BEST_SPEED` GZIP via atomic temp-file replacement.
- `prepare.lock`: Cross-process `FileChannel.tryLock()` preventing concurrent cache mutation.
- Automatic retirement of `1.x` legacy artifacts (`blobs/`, `sources/`, `generations/`, `index.json`).

## Verification Matrix

- **Unit Tests**: `StructureDfuCacheV2Test` (9 tests covering legacy upgrade & zero-write passthrough, corrupt/oversized NBT isolation, zero-read fast-path, incremental rebuild & orphan pruning, legacy 1.x cleanup & reload unwrap, worker budget & clamping, TOML config round-trip & master switch bypass, `PackFingerprint` determinism, and `CacheStats` disk inspection & cache clear) executed via NeoForge JUnit across `1.21.1`, `26.1.2`, and `26.2`.
- **Release Audit (`scripts/build-matrix.py`)**: Builds all 8 targets and verifies each JAR is `< 100 KB`, contains `icon.png`, `structure_dfu_cache.mixins.json`, and all 4 `lang/*.json` files, with zero forbidden stub or external library classes.
