# Development

## Origin and scope

The project started from structure-related TPS problems found while testing the Watermelon Field (WMF) modpack. The optimization applies to data-pack structure templates. It does not remove NBT decoding or block placement, and it does not address every cause of world-generation stalls. Warm preparation still reads source files and validates cached data. Timings are preparation times, not TPS measurements.

## Preparation lifecycle

1. Let the original resource manager resolve `data/<namespace>/structure/**/*.nbt`, including pack priority and filters.
2. Hash each resolved file's exact bytes. Reuse requires a matching cache format, target DataVersion, Minecraft version and NeoForge version, plus a verified output digest at the expected cache path.
3. Prepare each distinct content key once. Upgrade supported old NBT with the structure manager's DataFixer. Keep exact source bytes for current or future versions, unsupported or malformed NBT, and failed conversions.
4. Commit complete files through temporary-file replacement, then write a deterministic index. Cache files without a valid index are rebuilt. Digests are computed while writing.
5. Materialize the complete set through hard links, or copies where links are unavailable. Hand the structure manager a vanilla `MultiPackResourceManager` containing one `PathPackResources`.

Startup and reload share one hook, after vanilla installs the incoming manager and clears its template cache on reload. Nothing is added to gameplay ticks or ordinary structure reads. All workers terminate before the maintenance call returns, including on interruption. There is no preparation timeout.

## Failure handling

- The cache-directory lock covers preparation, pruning and materialization. If another preparation holds it, this generation uses vanilla.
- A capture or disk-write failure disables the whole generated layer. A conversion failure alone keeps that input for vanilla.
- Fatal JVM errors are never treated as a successful fallback.
- Each reload keeps the previous generation and retires the older one.

## Build

With Java 21 available:

```sh
./gradlew test build
```

The build uses pinned Minecraft and NeoForge dependencies and reproducible archive ordering. Additional loaders or versions need their own runtime validation.
