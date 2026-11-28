# Changelog

## 2.0.5

- **Chat Ready Notice Enabled by Default (`notifyInChat = true`)**: Changed the default value of `notifyInChat` from `false` to `true` across `structure_dfu_cache.toml`, missing-key fallback, and the in-game Config & Health Dashboard screen (`1.20.1`, `1.21.1`, `26.x`). Joining operators and singleplayer hosts now immediately see a single concise cache summary in chat once per prepare cycle; modpack authors who prefer silent chat can set `notifyInChat = false`.

## 2.0.4

- **Optional Chat Ready Notice (`notifyInChat`)**: Added `notifyInChat = false` (off by default so chat stays completely quiet) across `structure_dfu_cache.toml`, the in-game Config & Health Dashboard screen (`1.20.1`, `1.21.1`, `26.x`), and all 4 languages (`en_us`, `zh_cn`, `zh_tw`, `ja_jp`). When enabled, sends a single concise cache summary in chat once per prepare cycle to joining operators or the singleplayer host.

## 2.0.3

- **1.20.1 Forge `pack.mcmeta` Fix**: Added `pack.mcmeta` (`pack_format: 15`) exclusively to the `1.20.1` Forge JAR to eliminate the `ResourcePackInfo` startup warning, with strict per-target JAR assertions in `scripts/build-matrix.py`.
- **Minecraft 26.3 Support**: Added `26.3` (`Fabric` & `NeoForge`, Java 25) to `profiles.json`, expanding the release matrix to 10 targets.

## 2.0.2

- **Corrupt Manifest Self-Healing**: Hardened `CacheManifest` against non-UTF-8 or binary-corrupted `manifest.v2` files by safely catching `MalformedInputException` / `IOException` and automatically rebuilding the cache instead of crashing startup.
- **Verification & Build Tooling**: Added local 3-gate verification (`./scripts/verify.sh` with optional `--container` Docker runner) covering all 8 matrix JARs, property tests, `ArchitectureContractTest`, and packaged-JAR NeoForge 1.21.1 `GameTestServer` E2E tests.

## 2.0.1

- Fixed a startup/world-load crash on NeoForge `1.21.1+` when computing `PackFingerprint` for Jar-in-Jar nested mods whose `IModFile.getFilePath()` returns a virtual `UnionPath` (`toRealPath()` returning `null` and cross-filesystem `ProviderMismatchException` / attribute stat errors).
- Hardened `PackFingerprint` path normalization, relativization, and file metadata stat collection against non-default `FileSystem` providers.

## 2.0.0

### Core v2 Engine
- Single `overlay/data/<ns>/<path>` layer and streaming `manifest.v2` line format.
- Added sub-5ms warm-boot fast-path mounting by validating line 1 of `manifest.v2` against `PackFingerprint` (zero structure file reads or NBT decoding).
- Only materializes structures with `DataVersion < targetDataVersion`; records current-version, non-template, corrupt, and oversized (`>32 MiB` decoded / `>64 MiB` compressed) NBT as `P` (passthrough) with zero disk writes.
- Added adaptive `1–4` worker concurrency (single-threaded streaming on `<=6.8G` heaps), `prepare.lock` process locking, and orphan file pruning.

### Cross-Platform 8-Target Matrix
- Decoupled `PackFingerprint` and runtime metadata from NeoForge via `PlatformBridge` (`FabricLoader` / Forge `ModList` / NeoForge `ModList`) and `McCompat` (`v120` plural `structures/`, `v121` singular `structure/`, `v26` `Identifier` + `WorldVersion` record).
- Expanded to 8 zero-dependency single-JAR targets:
  - `1.20.1`: Fabric, Forge
  - `1.21.1`: Fabric, NeoForge
  - `26.1.2`: Fabric, NeoForge
  - `26.2`: Fabric, NeoForge

### Configuration, Health Dashboard, Commands & Polish
- Added zero-dependency commented `config/structure_dfu_cache.toml` (`enabled`, `maxWorkers`, `verboseLogging`).
- Added native Config & Live Health Dashboard screen (Mod Menu compile-only stub on Fabric + native Forge/NeoForge extension points) displaying live/on-disk stats and a one-click **Clear Cache** action.
- Added Brigadier OP diagnostic commands `/structuredfucache status` and `/structuredfucache clear` (permission level 2, zero Fabric API dependency).
- Added 4-language localization (`en_us`, `zh_cn`, `zh_tw`, `ja_jp`) and removed hardcoded overlay pack names.
- Replaced artwork with an original `64x64` pixel-art rounded stone badge icon (`512x512` nearest-neighbor).

## 1.2.0 / 1.1.0

- Earlier multi-loader content-addressed cache releases.

## 1.0.1

- Verified persisted cache blobs against SHA-256 before reuse and added failure isolation coverage.

## 1.0.0

- Initial persistent structure DFU cache release.
