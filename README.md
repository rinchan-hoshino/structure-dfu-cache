# Structure DFU Cache

<!-- PROJECT_PAGE_START -->
Prepare and persist upgraded structure templates during startup and `/reload` to reduce structure-related server tick stalls during gameplay. The first startup may take longer.

## What it does

- Uses Minecraft's official structure DataFixer to upgrade old templates before gameplay.
- Reuses validated conversion results across restarts and reloads.
- Preserves data-pack priority and filters, serving the effective structures through one ordinary resource pack.
- Falls back to vanilla loading if a complete prepared layer is unavailable.

## Installation

Requires Minecraft **1.21.1**, NeoForge **21.1.248 or later within the 21.1 series**, and **Java 21**. No configuration or additional library is required. Install on the dedicated server, or in the client instance for single-player worlds.

Use the platform Versions page for published files and GitHub Issues for reproducible problems.
<!-- PROJECT_PAGE_END -->

## Design

The project originated from structure-related TPS problems identified by the owner while testing the Watermelon Field (WMF) modpack. The owner directed the caching approach and reports long-term validation in WMF. The design deliberately accepts longer initial preparation and retains a single resource-pack layer. Implementation and documentation use AI assistance.

The optimization applies to data-pack structure templates. It does not remove NBT decoding or block placement, and does not optimize every cause of world-generation stalls or low FPS. Warm preparation still reads source files and validates cached data. The measurements in the validation record are preparation timings, not quantified TPS gains.

## Preparation lifecycle

1. Let the original resource manager resolve `data/<namespace>/structure/**/*.nbt`, including pack priority and filters.
2. Hash each resolved file's exact bytes. Reuse requires matching cache format, target DataVersion, Minecraft version, and NeoForge version, plus a verified output digest at the expected cache path.
3. Prepare each distinct content key once. Upgrade old supported NBT using the structure manager's own DataFixer. Preserve exact source bytes for current/future versions, unsupported or malformed NBT, and failed conversions.
4. Commit complete files through temporary-file replacement, then write a deterministic index. Cache files without a valid index are rebuilt. Write-time hashing avoids rereading newly prepared blobs.
5. Materialize the complete set through hard links, or copies where linking is unavailable. Hand the structure manager a vanilla `MultiPackResourceManager` containing one `PathPackResources`.

Startup and reload share one hook after vanilla installs the incoming manager and clears its template cache on reload. No cache callback is added to gameplay ticks or ordinary structure reads. All preparation workers terminate before the maintenance call returns, including when interrupted. There is no preparation timeout; an unresponsive input or conversion can delay that return.

The cache-directory lock covers preparation, pruning and materialization. If another preparation holds it, this resource generation uses vanilla. A capture or disk-write failure also disables the whole generated layer; a conversion failure alone preserves the input for vanilla handling. Fatal JVM errors are not hidden as successful fallback.

## Storage and limits

The cache lives at `<game directory>/cache/structure_dfu_cache/`. Removing the mod leaves inert files. With the game stopped, the whole directory can be deleted; it will be rebuilt when needed.

- Input limit: 64 MiB of compressed data per structure.
- Decode limit: 256 MiB of NBT accounting per structure; this is not a total heap cap.
- Workers: one to four, based on CPU count and a heap estimate that subtracts 2 GiB for Minecraft and budgets 1 GiB per worker. This does not reserve memory.
- File replacement uses atomic moves where supported, with completed-file replacement otherwise. Existing hard links remain unchanged.
- Each reload retains the immediately preceding generation and retires the older one. Files left by prior sessions remain inert until removed; there is no gameplay cleanup worker.

## Development and validation

With Java 21 available:

```sh
./gradlew test build
```

The build uses pinned Minecraft/NeoForge dependencies and reproducible archive ordering. The supported loader range remains narrow; additional platforms require their own runtime validation.

[Current review and validation](docs/validation-cache-integrity-2026-09-14.md) covers cache recovery, concurrent deduplication, interruption, atomic replacement, data-pack filters, and a local server smoke test. [Runtime reproduction steps](runtime-tests/README.md) include old-template placement, reload, fallback, and recovery. [Historical 1.0.0 validation](docs/validation-1.0.0.md) is retained separately.

## License

GPL-3.0-or-later. See [LICENSE](LICENSE).
