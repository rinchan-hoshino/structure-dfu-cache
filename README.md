# Structure DFU Cache

Reduce repeated structure DataFixerUpper work during startup and `/reload` by caching converted structure NBT, with safe validation and vanilla fallback.

## How it works

- Resolves the server's structure resources once during startup or reload.
- Hashes the source bytes and the relevant DataFixer context of each structure.
- Reuses validated converted NBT from a persistent cache.
- Serves the result through one vanilla-compatible resource pack layer.
- If any entry is missing, ambiguous or invalid, the cache refuses to publish and vanilla behaviour is used. There is no gameplay-tick worker.

## Configuration

There is no configuration file. Drop the jar into a pack or server. Removing the jar removes every runtime behaviour; the files under `cache/structure_dfu_cache/` are then inert and can be deleted with the game stopped.

| Limit | Value |
| --- | --- |
| Compressed input per structure | 64 MiB |
| Decoded NBT per structure | 256 MiB (not a total heap cap) |
| Preparation workers | 1 to 4, based on CPU count and heap |

## Compatibility

- Minecraft 1.21.1
- NeoForge 21.1.248 or later in the 21.1 series
- Java 21
- No other mods or libraries are required.

## Download

- [GitHub releases](https://github.com/rinchan-hoshino/structure-dfu-cache/releases)

## License

GPL-3.0-or-later. See [LICENSE](LICENSE).
