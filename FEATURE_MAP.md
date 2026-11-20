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

All three hard verification gates are wired directly into local `./gradlew check`, `scripts/build-matrix.py`, `.githooks/pre-push`, and `scripts/verify.sh [--container]`:

- **Developer Setup & Full Verification**:
  - Enable local `pre-push` hook: `./scripts/setup-hooks.sh`
  - Run full verification natively or in reproducible container (`Dockerfile.verify` + `xvfb-run`): `./scripts/verify.sh [--container]`

- **Gate 1 — Packaged-JAR Real Runtime Black-Box E2E (`:neoforge:runGameTestServer` + `StructureDfuCacheConfigScreenTest`)**:
  - Boots a headless Minecraft `1.21.1` NeoForge `GameTestServer` with `loadedMods = []`, loading only the final packaged `structure_dfu_cache-neoforge-*.jar`, a real Jar-in-Jar nested fixture (`jij_e2e_host` -> `jij_e2e_nested`), and the black-box `structure_dfu_cache_e2e` harness (`StructureDfuCacheE2eMod`).
  - Verifies old `DataVersion` structures are DFU-upgraded into `overlay/`, current-version structures take zero-write `P` passthrough, warm `onResourceManagerReload` hits `manifest.v2` fast-path in `< 50ms`, and Brigadier `/structuredfucache status` / `/structuredfucache clear` enforce permission level `2`.
  - `StructureDfuCacheConfigScreenTest` validates headless `StructureDfuCacheConfigScreen` construction, widget layout (`init()`), live on-disk `CacheStats` inspection, and `onClose()` with zero exceptions.
  - Command: `./gradlew :neoforge:runGameTestServer -Pminecraft_version=1.21.1 -Ploader=neoforge --no-configuration-cache`
- **Gate 2 — Property Invariant Fuzzing (`StructureDfuCachePropertyTest`, >= 1,200 fixed-seed iterations)**:
  - Fixed-seed randomized bombardment covering `manifest.v2` corruption/truncation/invalid UTF-8/version jumps (400 iterations + full Bootstrap recovery), orphan overlay pruning & incremental rebuilds (250 iterations), corrupt/truncated GZIP & `>32 MiB` decoded NBT bombs (150+ iterations), and heterogeneous `FileSystem` / `UnionPath` inputs (400 iterations).
  - Command: `./gradlew :neoforge:test --tests "*StructureDfuCachePropertyTest*" -Pminecraft_version=1.21.1 -Ploader=neoforge --no-configuration-cache`
- **Gate 3 — Bytecode & Packaged Artifact Guardrails (`ArchitectureContractTest`)**:
  - Scans the freshly built packaged `.jar` constant pool to enforce Dedicated Server isolation (zero `net/minecraft/client/` or client-only references in non-client classes), Mixin bidirectional closure (`structure_dfu_cache.mixins.json` <-> `@Mixin` classes), and clean artifact hygiene (zero `rinlib`, zero `modmenu` stubs, `< 100 KB`, required `icon.png` + 4 `lang/*.json`).
  - Command: `./gradlew :neoforge:test --tests "*ArchitectureContractTest*" -Pminecraft_version=1.21.1 -Ploader=neoforge --no-configuration-cache`
- **Full Local Gate Entry Points**:
  - Single-target full verification: `./gradlew :neoforge:check -Pminecraft_version=1.21.1 -Ploader=neoforge --no-configuration-cache`
  - Full 8-target release matrix & audit: `python3 scripts/build-matrix.py`
