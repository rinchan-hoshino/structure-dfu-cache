# Structure DFU Cache

Reduce repeated structure DataFixerUpper work during startup and `/reload` by caching converted structure NBT, with safe validation and vanilla fallback.

## How it works

- Validates a single-line `manifest.v2` header against the current pack stack, mod files, and datapack metadata (`PackFingerprint`) for a sub-5ms zero-read warm-boot mount.
- On cold start or pack changes, scans `structure/*.nbt` once and writes only structures with `DataVersion < targetDataVersion` into a single `overlay/data/<namespace>/<path>` layer.
- Current-version, non-template, corrupt, or oversized structures are recorded as `P` (passthrough) in `manifest.v2` with zero bytes written to disk and are not re-decoded on subsequent runs.
- Serves upgraded structures via a lightweight `StructureOverlayResourceManager` wrapper, automatically unwrapping across `/reload` and cleaning up legacy `1.x` cache directories.

## Configuration

There is no configuration file. Drop the jar into a pack or server. Removing the jar removes every runtime behaviour; the files under `cache/structure_dfu_cache/` are then inert and can be deleted with the game stopped.

| Limit | Value |
| --- | --- |
| Compressed input per structure | 64 MiB |
| Decoded NBT per structure | 32 MiB (`NbtAccounter` cap) |
| Preparation workers | 1 (when `-Xmx <= 6.8G` or headroom `< 2.5G`), otherwise 2 to 4 |

## Compatibility

- Minecraft 1.21.1
- NeoForge 21.1.248 or later in the 21.1 series
- Java 21
- No other mods or libraries are required.

## Download

- [GitHub releases](https://github.com/rinchan-hoshino/structure-dfu-cache/releases)

## License

GPL-3.0-or-later. See [LICENSE](LICENSE).
