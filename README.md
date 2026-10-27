# Structure DFU Cache

<!-- PROJECT_PAGE_START -->
Moves repetitive structure DataFixerUpper (DFU) migration to the startup phase and caches the result to improve TPS during heavy chunk loading.

## How it works

- Uniformly parses server structure resources during game startup or reload.
- Provides and caches processed structures through a vanilla-compatible resource pack layer.

## Configuration

Starting in 1.2.0, processing limits can be configured in `config/structure_dfu_cache.toml` and are loaded on game startup (restart the game after editing):

```toml
# Maximum decoded NBT memory quota in MiB (tracks NBT decoder accounting of NBT objects and allocation overhead)
maxDecodedNbtMiB = 1024

# Maximum compressed structure resource size in MiB
maxCompressedResourceMiB = 64
```

- Both options must be positive integers in MiB.
- `maxDecodedNbtMiB` tracks the NBT decoder's accounting of in-memory NBT objects and allocation overhead during processing (does not measure total DFU memory).
- Worker concurrency is estimated based on available heap after deducting resident memory and a 2 GiB reserve, budgeting roughly 4x `maxDecodedNbtMiB` per worker (capped at 4 threads, leaving 1 CPU core free when more than 1 core is available). Higher quota values reduce parallel workers to allocate more headroom for conversion.
- When compressed resource size exceeds `maxCompressedResourceMiB`, startup pre-processing is skipped and handed back to vanilla structure reading. When decoded NBT exceeds `maxDecodedNbtMiB` or conversion fails, raw compressed bytes are cached as unoptimized resources and retried during subsequent load or reload.

## Requirements

Install the following mod as a dependency:

- RinLib
<!-- PROJECT_PAGE_END -->
