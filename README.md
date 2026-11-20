# Structure DFU Cache

![Structure DFU Cache Icon](docs/icon.png)

Eliminates repeated Minecraft structure DataFixerUpper (DFU) conversions during world loading and `/reload` by pre-upgrading outdated structure NBT templates once into a single-layer overlay cache — with sub-5ms zero-read warm startups, a live native health dashboard, and zero external dependencies.

## Highlights

- **Sub-5ms warm-boot fast path**: Validates line 1 of `cache/structure_dfu_cache/manifest.v2` against `PackFingerprint` (active pack stack + mod files + datapack metadata) without reading or decoding a single NBT file.
- **Single-layer overlay (`overlay/data/<ns>/<path>`)**: Only materializes structure templates with `DataVersion < targetDataVersion`. Current-version, non-template, corrupt, and oversized (`>32 MiB` decoded / `>64 MiB` compressed) files are recorded as `P` (passthrough) with zero disk writes.
- **Heap-safe & process-safe**: Adaptive `1–4` worker concurrency (automatically throttled to `1` worker when `-Xmx <= 6.8G` or heap headroom `< 2.5G`), `prepare.lock` cross-process lock, and orphan file pruning.
- **Native Config & Health Dashboard**: Built-in zero-dependency screen accessible via Mod Menu (Fabric), Forge, and NeoForge. Displays live/on-disk status, last prepare time (`ms`), accelerated/passthrough counts, `overlay/` disk usage, hot-save toggles, and a one-click **Clear Cache** button.
- **Server OP Diagnostics**: Native Brigadier `/structuredfucache status` and `/structuredfucache clear` (permission level 2), completely invisible to non-OP players.
- **Zero External Dependencies**: Pure single JAR (`< 100 KB`), no Fabric API, no Cloth Config, no library mods required.
- **4-Language Localization**: English (`en_us`), Simplified Chinese (`zh_cn`), Traditional Chinese (`zh_tw`), Japanese (`ja_jp`).

## Supported Matrix (8 Targets)

| Minecraft | Fabric | Forge | NeoForge | Java |
| --- | --- | --- | --- | --- |
| `1.20.1` | Yes | Yes (`47.4+`) | — | Java 17 |
| `1.21.1` | Yes | — | Yes (`21.1+`) | Java 21 |
| `26.1.2` | Yes | — | Yes (`26.1.2+`) | Java 25 |
| `26.2` | Yes | — | Yes (`26.2+`) | Java 25 |

## Configuration

Generated automatically at `config/structure_dfu_cache.toml` (also editable in-game via the native Config Screen):

```toml
# Master switch. When false, bypasses the overlay and uses vanilla resources directly.
enabled = true

# Maximum worker threads during cold/incremental cache builds.
# 0 = auto-adaptive (1..4 based on JVM heap headroom); 1..8 = explicit cap.
maxWorkers = 0

# When true, logs extra build diagnostics at INFO level.
verboseLogging = false
```

## OP Commands

Requires permission level 2 (gamemaster / operator):

- `/structuredfucache status` — Shows current mount mode, last prepare duration, accelerated/passthrough counts, `overlay/` disk size, and worker setting.
- `/structuredfucache clear` — Clears `cache/structure_dfu_cache/` so the overlay rebuilds on the next world load or `/reload`.

## Safety Limits

| Limit | Value |
| --- | --- |
| Max compressed structure size | `64 MiB` |
| Max decoded NBT size | `32 MiB` (`NbtAccounter` cap) |
| Default worker budget | `1` when `-Xmx <= 6.8G` or headroom `< 2.5G`, else `2–4` |

## Compatibility

- Works alongside **Lithium**, **ModernFix**, **C2ME**, **FerriteCore**, and **LazyDFU** without overlap.
- Removing the JAR leaves vanilla worlds untouched; `cache/structure_dfu_cache/` can be deleted safely at any time while the game is stopped.

## License

GPL-3.0-or-later. See [LICENSE](LICENSE).
