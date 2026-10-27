## Structure DFU Cache 1.2.0

### Features & Improvements
- **TOML configuration**: Added `config/structure_dfu_cache.toml` (loaded on game startup; restart required after editing).
- **Configurable processing limits**:
  - `maxDecodedNbtMiB` (default `1024`): Positive integer in MiB tracking the NBT decoder's accounting of in-memory NBT objects and allocation overhead during processing (this does not measure total DFU memory).
  - `maxCompressedResourceMiB` (default `64`): Positive integer in MiB setting the limit for compressed structure resource size.
- **Concurrency & memory estimation**: Worker concurrency is budgeted based on available heap after deducting resident memory and a 2 GiB reserve, estimating total DFU memory at roughly 4x `maxDecodedNbtMiB` per worker (capped at 4 threads, leaving 1 CPU core free when more than 1 core is available). Higher quota values reduce worker concurrency to allocate more memory headroom.
- **Fallback behavior**: Structures exceeding `maxCompressedResourceMiB` bypass startup pre-processing and are handed back to vanilla reading. Structures exceeding `maxDecodedNbtMiB` or failing conversion cache the raw compressed bytes as unoptimized resources to retry during future load or reload.

# Changelog

## 1.1.0 — 2026-10-05

- Share one cache implementation across Minecraft 1.20.1, 1.21.1, 26.1.2 and 26.2 using RinLib 1.2.0.
- Add Fabric and Forge/NeoForge builds with a required RinLib dependency.
- Cache only the structures selected by vanilla resource-pack priority and filters.
- Isolate individual structure read, conversion, storage and materialization failures; healthy structures remain cached.
- Summarize cache updates, repaired data and vanilla fallbacks in the server console.
- Notify operators once when they log in if the current preparation encountered damage or fallback; clear the notice after healthy preparation.
- Release generated resource layers on reload and shutdown while retaining verified persistent blobs.

## 1.0.1 — 2026-08-29

- Verify every prepared cache blob against its persisted SHA-256 before reuse.
- Rebuild tampered or truncated blobs instead of exposing them through the generated resource layer.
- Advance the cache format so pre-integrity indexes are ignored and rebuilt automatically.
- Add bounded failure coverage for changed runtime identity, corrupt indexes, and unusable cache roots.

## 1.0.0 — 2026-08-28

- Build a persistent, content-addressed cache of structure NBT upgraded by Minecraft's official structure DataFixer.
- Preserve vanilla resource-pack resolution and fall back to the original resource manager if a complete prepared layer cannot be built.
- Limit cache preparation to startup and explicit resource reload maintenance windows.
