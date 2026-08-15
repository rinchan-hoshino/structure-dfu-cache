# Structure DFU Cache (private prototype)

Private Minecraft 1.21.1 / NeoForge 21.1.248 incubation project. It does not bundle or publish structure assets from other mods.

## Behavior

- Resolves the effective `data/<namespace>/structure/*.nbt` resources after pack priority is applied.
- Hashes the compressed source bytes with SHA-256 and keys cache blobs by source hash plus target Minecraft `DataVersion`.
- Converts stale templates with Mojang's `DataFixTypes.STRUCTURE` and writes the current `DataVersion`.
- Rejects a conversion if template size, block count, entity count, or palette count changes.
- Builds the first cache before other server reload listeners to keep transient DFU memory separate from large mod reloads.
- Atomically activates an index only after a complete build. Completed content-addressed blobs survive a failed attempt and are safe to reuse.
- Prunes unreferenced historical blobs only after a replacement index commits successfully.
- Redirects `StructureTemplateManager` resource reads to matching cached blobs. Current or unreadable non-template resources remain untouched.

Cache files are instance-local under `cache/structure_dfu_cache/`.

## Configuration

`config/structure_dfu_cache-common.toml`:

```toml
coldBuildTimeoutSeconds = 300
workerThreads = 4
```

A finite timeout must be 60–1800 seconds; `0` is explicitly unlimited. A timeout fails the reload and never silently falls back to stale structure data. The Watermelon Field torture pack needs a private override of 420 seconds after a reproducible clean build reached 355.812 seconds; ordinary packs retain the 300-second default.

## Current private evidence

- Unit tests: content-addressed keying, official DFU conversion, structural invariants, warm hits, source-byte invalidation, atomic index writes, timeout fail-closed behavior, and weighted conversion memory limits.
- Clean NeoForge headless startup: classloading, both mixins, cache listener, and server startup reached `Done` without bundled third-party assets.
- Watermelon Field dev.52 clone with Paxi and the 72.94 MB generated datapack removed:
  - fresh cache: 15,510 effective resources; 10,654 converted, 4,782 current, 72 duplicate-content hits, one unreadable Botany Pots non-template skipped; 315.728 seconds; 227.8 MB local cache;
  - fresh fixed-seed server: `Done` in 112.091 seconds after cache creation; Cloudy Temple locate 3.689 seconds;
  - warm cache scan: 0.494 seconds before reload plus 2.170 seconds reload verification; existing-world `Done` in 5.296 seconds; Cloudy Temple locate 225 ms;
  - one-resource override: exactly one new conversion and one stale blob pruned; WMF's ordinary `/reload` still exceeded its 60-second watchdog in unrelated pack reload work, so large packs must restart after mod/datapack changes.

## Boundaries

- Private incubation only: WMF dev.53 consumption is authorized, but no public repository visibility, Modrinth project, or standalone release is authorized.
- Initial cold construction is server-startup work. Subsequent resource reloads prepare a replacement snapshot asynchronously and activate it only at the reload barrier, but this does not make a large pack's other reload listeners watchdog-safe.
- Compatibility is currently proven only for Minecraft 1.21.1 / NeoForge 21.1.248.
